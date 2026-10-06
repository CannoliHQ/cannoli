#include <jni.h>
#include "ricotta_osd.h"
#include <pthread.h>
#include <unistd.h>
#include <time.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <streams/file_stream.h>
#include <memory/mem_stats.h>

static long long get_time_ms(void)
{
   struct timespec ts;
   clock_gettime(CLOCK_MONOTONIC, &ts);
   return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

#define RTAG "RicottaBridge"
#define RLOG(...) __android_log_print(ANDROID_LOG_DEBUG, RTAG, __VA_ARGS__)

#include "../../../../retroarch.h"
#include "../../../../gfx/video_shader_parse.h"
#include "../../../../audio/audio_driver.h"
#include "../../../../command.h"
#include "../../../../configuration.h"
#include <file/file_path.h>
#include <string/stdstring.h>
#include "../../../../menu/menu_driver.h"
#include "../../../../menu/menu_shader.h"
#include "../../../../menu/menu_defines.h"
#include "../../../../runloop.h"
#include "../../../../paths.h"
#include "../../../../setting_list.h"
#include "../../../../menu/menu_setting.h"
#include "../../../../menu/menu_displaylist.h"
#include "../../../../menu/menu_entries.h"
#include "ricotta_menu_screens.h"
#include "ricotta_key_aliases.h"
#include "../../../../cheevos/cheevos_locals.h"
#include "../../../../deps/rcheevos/include/rc_client.h"
#include "../../../../disk_control_interface.h"
#include "../../../../cheat_manager.h"
#include "../../../../cheevos/cheevos.h"
#include "../../../../core.h"
#include "../../../../dynamic.h"
#include "../../../../input/input_driver.h"
#include "../../../../menu/menu_cbs.h"
#include "../../../../content.h"
#include "../../../../verbosity.h"
#include <queues/task_queue.h>

static JavaVM *g_jvm           = NULL;
static jobject g_bridge_obj    = NULL;
static jmethodID g_on_menu_closed_mid = NULL;
static jmethodID g_on_debug_key_mid = NULL;

static volatile int g_igm_visible = 0;

#define RICOTTA_MAX_TRIGGER_KEYS 8
static volatile int g_igm_trigger_keycodes[RICOTTA_MAX_TRIGGER_KEYS];
static volatile int g_igm_trigger_keycount = 0;

#define RICOTTA_MAX_SHORTCUT_KEYS 32
#define RICOTTA_SHORTCUT_QUEUE    64
static volatile int g_shortcut_keycodes[RICOTTA_MAX_SHORTCUT_KEYS];
static volatile int g_shortcut_keycount = 0;

static volatile int g_shortcut_queue[RICOTTA_SHORTCUT_QUEUE];
static volatile int g_shortcut_head = 0;
static volatile int g_shortcut_tail = 0;

#define RICOTTA_MAX_CHORDS     16
#define RICOTTA_MAX_CHORD_KEYS 8
typedef struct
{
   int action;
   int hold_ms;
   int pass_through;
   int count;
   int keys[RICOTTA_MAX_CHORD_KEYS];
} ricotta_chord;
static ricotta_chord g_chords[RICOTTA_MAX_CHORDS];
static volatile int g_chord_count = 0;

static volatile int g_hold_chord = -1;
static volatile long long g_hold_deadline_us = 0;

#define RICOTTA_ACT_FIRED          0
#define RICOTTA_ACT_RELEASED       1
#define RICOTTA_ACT_HOLD_ARMED     2
#define RICOTTA_ACT_HOLD_CANCELLED 3

static void ricotta_queue_action(int action, int kind);
static long long ricotta_now_us(void);


static volatile int g_held[RICOTTA_MAX_SHORTCUT_KEYS];
static volatile int g_held_count = 0;

static volatile int g_firing_chord = -1;

static volatile int g_action_queue[RICOTTA_SHORTCUT_QUEUE];
static volatile int g_action_head = 0;
static volatile int g_action_tail = 0;

static volatile int g_retract[RICOTTA_MAX_SHORTCUT_KEYS];
static volatile int g_retract_count = 0;

static volatile int g_menu_modifier_held = 0;

static volatile int g_swallowed[RICOTTA_MAX_SHORTCUT_KEYS];
static volatile int g_swallowed_count = 0;

static volatile int g_passthrough_up[RICOTTA_MAX_SHORTCUT_KEYS];
static volatile int g_passthrough_up_count = 0;

static jmethodID g_on_igm_trigger_mid = NULL;
static jmethodID g_on_shortcut_key_mid = NULL;
static jmethodID g_on_shortcut_action_mid = NULL;
static jmethodID g_on_runloop_ready_mid = NULL;
static jmethodID g_on_osd_event_mid = NULL;
static jmethodID g_on_osd_achievement_mid = NULL;
static jmethodID g_on_cheevos_load_mid = NULL;

static volatile int g_cheevos_outcome = -1;
static jmethodID g_on_ra_applied_mid = NULL;
static jmethodID g_on_ra_apply_done_mid = NULL;
static jmethodID g_on_cheats_loaded_mid = NULL;
static jmethodID g_on_cheevos_request_mid = NULL;
static jmethodID g_on_cheevos_response_mid = NULL;
static jmethodID g_on_cheevos_failed_mid = NULL;

static JNIEnv *g_native_env = NULL;

static char g_cannoli_root[PATH_MAX_LENGTH];
static char g_platform_tag[PATH_MAX_LENGTH];
static char g_rom_base_name[PATH_MAX_LENGTH];
static char g_core_id[PATH_MAX_LENGTH];

static volatile long long g_igm_open_time = 0;

static volatile int g_runloop_ready = 0;
static volatile int g_runloop_ready_pending = 0;

static volatile int g_igm_trigger_pending = 0;

#define MENU_POLL_INTERVAL_MS 50
#define MENU_OPEN_TIMEOUT_MS  2000
static pthread_t g_menu_poll_thread;
static volatile int g_menu_poll_active = 0;

/* Pending command queue. command_event() is NOT safe to call from the JNI/main
 * thread while retro_run executes on the runloop thread, so the JNI methods enqueue
 * commands here and ricotta_bridge_poll_commands() runs them on the runloop thread. */
#define RICOTTA_CMD_QUEUE_SIZE 32
#define RICOTTA_CMD_RESERVE 8
#define RICOTTA_MAX_OPTS 48

typedef struct
{
   const char *name;
   const char *value;
} ricotta_field;

static jobjectArray ricotta_fields_to_array(JNIEnv *env, const ricotta_field *f, size_t n)
{
   jclass str_cls   = (*env)->FindClass(env, "java/lang/String");
   jobjectArray out = (*env)->NewObjectArray(env, (jsize)(n * 2), str_cls, NULL);
   size_t i;

   if (!out)
      return NULL;

   for (i = 0; i < n; i++)
   {
      jstring name  = (*env)->NewStringUTF(env, f[i].name);
      jstring value = (*env)->NewStringUTF(env, f[i].value ? f[i].value : "");
      (*env)->SetObjectArrayElement(env, out, (jsize)(i * 2), name);
      (*env)->SetObjectArrayElement(env, out, (jsize)(i * 2 + 1), value);
      (*env)->DeleteLocalRef(env, name);
      (*env)->DeleteLocalRef(env, value);
   }
   return out;
}

#define RICOTTA_QCMD_RA_SET           -1
#define RICOTTA_QCMD_RA_SAVE_OVERRIDE -2
#define RICOTTA_QCMD_DISK_SET         -3
#define RICOTTA_QCMD_CHEAT_LOAD       -4
#define RICOTTA_QCMD_CHEAT_TOGGLE     -5
#define RICOTTA_QCMD_CHEAT_APPLY      -6
#define RICOTTA_QCMD_OSD_EVENT        -7
#define RICOTTA_QCMD_OSD_ACHIEVEMENT  -8
#define RICOTTA_QCMD_VIEWPORT_SET     -9
#define RICOTTA_QCMD_SHADER_SET       -10
#define RICOTTA_QCMD_REWIND_RESET     -11
#define RICOTTA_QCMD_CHEEVOS_LOAD     -12
#define RICOTTA_QCMD_PORT_DEVICE_SET  -13
#define RICOTTA_QCMD_PLAYERS_SWAP     -14
#define RICOTTA_QCMD_REMAP_SET        -15
#define RICOTTA_QCMD_HELD_FLUSH       -16
#define RICOTTA_QCMD_HELD_DROP        -17
#define RICOTTA_PLAYER_ROWS           4
#define RICOTTA_REMAP_BUTTONS         16
typedef struct
{
   int   cmd;
   int   slot;
   int   has_slot;
   char *ra_key;
   char *ra_value;
   int   ra_scope;
   int   osd_type;
   int   vp_x;
   int   vp_y;
   int   vp_w;
   int   vp_h;
   int   vp_integer_scale;
   int   port_a;
   int   port_b;
   int   remap_value;
   int   ra_wait;
   char *ra_watch;
} ricotta_cmd_entry;
static ricotta_cmd_entry g_cmd_queue[RICOTTA_CMD_QUEUE_SIZE];
static int g_cmd_head = 0;
static int g_cmd_tail = 0;
static pthread_mutex_t g_cmd_mutex = PTHREAD_MUTEX_INITIALIZER;

static int ricotta_enqueue_entry(ricotta_cmd_entry entry)
{
   int queued = 0;
   pthread_mutex_lock(&g_cmd_mutex);
   {
      int next  = (g_cmd_tail + 1) % RICOTTA_CMD_QUEUE_SIZE;
      int depth = (g_cmd_tail - g_cmd_head + RICOTTA_CMD_QUEUE_SIZE) % RICOTTA_CMD_QUEUE_SIZE;
      int free_slots  = RICOTTA_CMD_QUEUE_SIZE - 1 - depth;
      int menu_write  = entry.cmd == RICOTTA_QCMD_RA_SET && entry.ra_wait != 0;
      if (next != g_cmd_head && !(menu_write && free_slots < RICOTTA_CMD_RESERVE))
      {
         g_cmd_queue[g_cmd_tail] = entry;
         g_cmd_tail = next;
         queued = 1;
      }
   }
   pthread_mutex_unlock(&g_cmd_mutex);
   if (!queued)
   {
      free(entry.ra_key);
      free(entry.ra_value);
      free(entry.ra_watch);
   }
   return queued;
}

static void ricotta_enqueue_command(int cmd, int slot, int has_slot)
{
   ricotta_cmd_entry entry = {0};
   entry.cmd      = cmd;
   entry.slot     = slot;
   entry.has_slot = has_slot;
   ricotta_enqueue_entry(entry);
}

#define RICOTTA_HELD_MAX 32

static __thread int g_hold_active = 0;
static int g_held_cmds[RICOTTA_HELD_MAX];
static int g_held_cmd_count = 0;

static int ricotta_deferrable(int cmd)
{
   switch (cmd)
   {
      case CMD_EVENT_REINIT:
      case CMD_EVENT_REINIT_FROM_TOGGLE:
      case CMD_EVENT_AUDIO_REINIT:
      case CMD_EVENT_AUDIO_START:
      case CMD_EVENT_AUDIO_STOP:
#ifdef HAVE_MICROPHONE
      case CMD_EVENT_MICROPHONE_REINIT:
#endif
      case CMD_EVENT_VIDEO_APPLY_STATE_CHANGES:
      case CMD_EVENT_VIDEO_SET_ASPECT_RATIO:
      case CMD_EVENT_VIDEO_SET_BLOCKING_STATE:
      case CMD_EVENT_OVERLAY_INIT:
      case CMD_EVENT_OVERLAY_UNLOAD:
      case CMD_EVENT_OVERLAY_SET_SCALE_FACTOR:
      case CMD_EVENT_OVERLAY_SET_ALPHA_MOD:
      case CMD_EVENT_OVERLAY_SET_EIGHTWAY_DIAGONAL_SENSITIVITY:
      case CMD_EVENT_CONTROLLER_INIT:
      case CMD_EVENT_PREEMPT_UPDATE:
#if defined(HAVE_RUNAHEAD) && (defined(HAVE_DYNAMIC) || defined(HAVE_DYLIB))
      case CMD_EVENT_LOAD_SECOND_CORE:
#endif
      case CMD_EVENT_CHEATS_APPLY:
      case CMD_EVENT_OSD_NOTIFICATION_TOGGLE:
      case CMD_EVENT_HISTORY_INIT:
      case CMD_EVENT_CORE_INFO_INIT:
         return 1;
      default:
         return 0;
   }
}

static int ricotta_opposite(int cmd)
{
   switch (cmd)
   {
      case CMD_EVENT_AUDIO_START: return CMD_EVENT_AUDIO_STOP;
      case CMD_EVENT_AUDIO_STOP:  return CMD_EVENT_AUDIO_START;
      default:                    return CMD_EVENT_NONE;
   }
}

int ricotta_hold_command(int cmd, void *data)
{
   int i, opposite;
   if (!g_hold_active || data || !ricotta_deferrable(cmd))
      return 0;
   for (i = 0; i < g_held_cmd_count; i++)
      if (g_held_cmds[i] == cmd)
         return 1;
   opposite = ricotta_opposite(cmd);
   if (opposite != CMD_EVENT_NONE)
      for (i = 0; i < g_held_cmd_count; i++)
         if (g_held_cmds[i] == opposite)
         {
            memmove(&g_held_cmds[i], &g_held_cmds[i + 1],
                  sizeof(g_held_cmds[0]) * (size_t)(g_held_cmd_count - i - 1));
            g_held_cmd_count--;
            break;
         }
   if (g_held_cmd_count == RICOTTA_HELD_MAX)
      return 0;
   g_held_cmds[g_held_cmd_count++] = cmd;
   return 1;
}

static void ricotta_held_flush(void)
{
   int cmds[RICOTTA_HELD_MAX];
   int i, n = g_held_cmd_count;
   memcpy(cmds, g_held_cmds, sizeof(cmds[0]) * (size_t)n);
   g_held_cmd_count = 0;
   for (i = 0; i < n; i++)
      command_event((enum event_command)cmds[i], NULL);
}

static const char *ricotta_port_device_label(rarch_system_info_t *sys_info,
      unsigned port, unsigned device)
{
   const struct retro_controller_description *desc = NULL;

   if (sys_info && port < sys_info->ports.size)
      desc = libretro_find_controller_description(&sys_info->ports.data[port], device);
   if (desc && desc->desc && *desc->desc)
      return desc->desc;
   switch (device)
   {
      case RETRO_DEVICE_NONE:
         return msg_hash_to_str(MENU_ENUM_LABEL_VALUE_NONE);
      case RETRO_DEVICE_JOYPAD:
         return msg_hash_to_str(MENU_ENUM_LABEL_VALUE_RETROPAD);
      case RETRO_DEVICE_ANALOG:
         return msg_hash_to_str(MENU_ENUM_LABEL_VALUE_RETROPAD_WITH_ANALOG);
      default:
         return msg_hash_to_str(MENU_ENUM_LABEL_VALUE_UNKNOWN);
   }
}

static void ricotta_swap_players(unsigned a, unsigned b)
{
   settings_t *settings = config_get_ptr();
   unsigned pad_a, pad_b;

   if (!settings || a == b || a >= RICOTTA_PLAYER_ROWS || b >= RICOTTA_PLAYER_ROWS)
      return;
   pad_a = settings->uints.input_joypad_index[a];
   pad_b = settings->uints.input_joypad_index[b];
   if (pad_a >= MAX_INPUT_DEVICES || pad_b >= MAX_INPUT_DEVICES)
      return;
   if (a == 0 && !input_config_get_device_name(pad_b))
      return;
   if (b == 0 && !input_config_get_device_name(pad_a))
      return;
   settings->uints.input_joypad_index[a] = pad_b;
   settings->uints.input_joypad_index[b] = pad_a;
}

typedef struct
{
   char  *buf;
   size_t len;
   size_t cap;
} ricotta_strbuf;

static int ricotta_sb_reserve(ricotta_strbuf *sb, size_t extra)
{
   size_t need = sb->len + extra + 1;
   size_t cap;
   char  *grown;
   if (need <= sb->cap)
      return 1;
   cap = sb->cap ? sb->cap : 4096;
   while (cap < need)
      cap *= 2;
   grown = (char *)realloc(sb->buf, cap);
   if (!grown)
      return 0;
   sb->buf = grown;
   sb->cap = cap;
   return 1;
}

static void ricotta_sb_putc(ricotta_strbuf *sb, char c)
{
   if (!ricotta_sb_reserve(sb, 1))
      return;
   sb->buf[sb->len++] = c;
   sb->buf[sb->len]   = '\0';
}

static void ricotta_sb_puts(ricotta_strbuf *sb, const char *s)
{
   size_t n;

   if (!s || !*s)
      return;
   n = strlen(s);
   if (!ricotta_sb_reserve(sb, n))
      return;
   memcpy(sb->buf + sb->len, s, n);
   sb->len += n;
   sb->buf[sb->len] = '\0';
}

static void ricotta_sb_escaped(ricotta_strbuf *sb, const char *s)
{
   const unsigned char *p = (const unsigned char *)s;

   if (!s)
      return;
   while (*p)
   {
      int seq = 0;
      int k;

      if (*p < 0x80)
         seq = 1;
      else if ((*p & 0xE0) == 0xC0)
         seq = 2;
      else if ((*p & 0xF0) == 0xE0)
         seq = 3;

      for (k = 1; k < seq; k++)
      {
         if ((p[k] & 0xC0) != 0x80)
         {
            seq = 0;
            break;
         }
      }

      if (seq == 0)
      {
         ricotta_sb_putc(sb, '?');
         p++;
      }
      else if (seq == 1)
      {
         if (*p == '\\' || *p == '|' || *p == '\n')
         {
            ricotta_sb_putc(sb, '\\');
            ricotta_sb_putc(sb, *p == '\n' ? 'n' : (char)*p);
         }
         else
            ricotta_sb_putc(sb, (char)*p);
         p++;
      }
      else
      {
         for (k = 0; k < seq; k++)
            ricotta_sb_putc(sb, (char)p[k]);
         p += seq;
      }
   }
}

static void ricotta_ra_apply(const char *key, const char *value, int token, const char *watch);
static void ricotta_ra_save_override(int scope, const char *keys);
static JNIEnv *ricotta_runloop_env(void);

static int ricotta_jni_check(JNIEnv *env, const char *where)
{
   if (!env || !(*env)->ExceptionCheck(env))
      return 0;
   RLOG("JNI exception pending at %s", where);
   (*env)->ExceptionDescribe(env);
   (*env)->ExceptionClear(env);
   return 1;
}

static int ricotta_cheat_has_system_ram(void)
{
   retro_ctx_memory_info_t meminfo;
   rarch_system_info_t *sys_info = &runloop_state_get_ptr()->system;
   unsigned i;

   if (sys_info)
   {
      for (i = 0; i < sys_info->mmaps.num_descriptors; i++)
      {
         if ((sys_info->mmaps.descriptors[i].core.flags & RETRO_MEMDESC_SYSTEM_RAM)
               && sys_info->mmaps.descriptors[i].core.ptr
               && sys_info->mmaps.descriptors[i].core.len > 0)
            return 1;
      }
   }

   meminfo.id = RETRO_MEMORY_SYSTEM_RAM;
   if (core_get_memory(&meminfo) && meminfo.data && meminfo.size > 0)
      return 1;
   return 0;
}

static void ricotta_cheat_emit_snapshot(void)
{
   JNIEnv        *env = ricotta_runloop_env();
   ricotta_strbuf sb  = {0};
   unsigned       i;
   unsigned       size    = cheat_manager_get_size();
   int            has_ram = ricotta_cheat_has_system_ram();
   jstring        payload;

   if (!env || !g_bridge_obj || !g_on_cheats_loaded_mid)
      return;

   for (i = 0; i < size; i++)
   {
      int is_emu = cheat_manager_state.cheats
         && cheat_manager_state.cheats[i].handler == CHEAT_HANDLER_TYPE_EMU;
      ricotta_sb_escaped(&sb, cheat_manager_get_desc(i));
      ricotta_sb_putc(&sb, '|');
      ricotta_sb_escaped(&sb, cheat_manager_get_code(i));
      ricotta_sb_putc(&sb, '|');
      ricotta_sb_putc(&sb, cheat_manager_get_code_state(i) ? '1' : '0');
      ricotta_sb_putc(&sb, '|');
      ricotta_sb_putc(&sb, (is_emu || has_ram) ? '1' : '0');
      ricotta_sb_putc(&sb, '\n');
   }

   payload = (*env)->NewStringUTF(env, sb.buf ? sb.buf : "");
   free(sb.buf);
   if (!payload)
      return;
   (*env)->CallVoidMethod(env, g_bridge_obj, g_on_cheats_loaded_mid, payload);
   ricotta_jni_check(env, "onCheatsLoaded");
   (*env)->DeleteLocalRef(env, payload);
}

static int   g_vp_bias_saved = 0;
static float g_vp_bias_x = 0.0f;
static float g_vp_bias_y = 0.0f;
#if defined(RARCH_MOBILE)
static float g_vp_bias_portrait_x = 0.0f;
static float g_vp_bias_portrait_y = 0.0f;
#endif

/* Called from runloop_iterate on the runloop thread, once per iteration, and deliberately not from
 * the input driver's poll: a core enters that from within retro_run, and one running its own
 * coroutine stack makes every JNI call from there throw a spurious StackOverflowError. */
#define RICOTTA_FPS_WINDOW_US 500000
static volatile float g_fps = 0.0f;
static int g_fps_frames = 0;
static long long g_fps_window_start_us = 0;


static long long ricotta_now_us(void)
{
   struct timespec ts;
   clock_gettime(CLOCK_MONOTONIC, &ts);
   return (long long)ts.tv_sec * 1000000LL + ts.tv_nsec / 1000LL;
}

static void ricotta_fps_tick(void)
{
   long long now = ricotta_now_us();
   long long elapsed;

   if (g_fps_window_start_us == 0)
   {
      g_fps_window_start_us = now;
      g_fps_frames = 0;
      return;
   }

   g_fps_frames++;
   elapsed = now - g_fps_window_start_us;
   if (elapsed < RICOTTA_FPS_WINDOW_US)
      return;

   g_fps = (float)((double)g_fps_frames * 1000000.0 / (double)elapsed);
   g_fps_frames = 0;
   g_fps_window_start_us = now;
}

float ricotta_fps(void)
{
   return g_fps;
}

#define RICOTTA_RESUME_RETRY_US     500000LL
#define RICOTTA_RESUME_WINDOW_US    5000000LL
static char          g_resume_path[PATH_MAX_LENGTH];
static int           g_resume_active    = 0;
static int           g_resume_attempts  = 0;
static int           g_resume_scheduled = 0;
static volatile int  g_resume_in_flight = 0;
static int           g_resume_spent     = 0;
static long long     g_resume_first_us  = 0;
static long long     g_resume_next_us   = 0;

static void ricotta_resume_clear(void)
{
   g_resume_active    = 0;
   g_resume_attempts  = 0;
   g_resume_scheduled = 0;
   g_resume_in_flight = 0;
}

static void ricotta_resume_abandon(void)
{
   ricotta_resume_clear();
   g_resume_spent = 1;
}

static void ricotta_resume_give_up(void)
{
   RARCH_LOG("[State] Resume gave up after %d attempts.\n", g_resume_attempts);
   ricotta_resume_abandon();
}

void ricotta_state_loaded(const char *path, int autoload, int ok)
{
   long long now = ricotta_now_us();

   g_resume_in_flight = 0;

   if (!autoload)
   {
      ricotta_resume_clear();
      return;
   }

   if (ok)
   {
      if (g_resume_active)
      {
         RARCH_LOG("[State] Resume loaded on attempt %d.\n", g_resume_attempts + 1);
         ricotta_enqueue_command(RICOTTA_QCMD_REWIND_RESET, 0, 0);
      }
      ricotta_resume_clear();
      return;
   }

   if (!g_resume_active)
   {
      if (!path || g_resume_spent)
         return;
      strlcpy(g_resume_path, path, sizeof(g_resume_path));
      g_resume_active   = 1;
      g_resume_first_us = now;
   }
   g_resume_attempts++;

   if (now - g_resume_first_us >= RICOTTA_RESUME_WINDOW_US)
   {
      ricotta_resume_give_up();
      return;
   }

   g_resume_next_us   = now + RICOTTA_RESUME_RETRY_US;
   g_resume_scheduled = 1;
}

static bool ricotta_task_is_blocking(retro_task_t *task, void *userdata)
{
   (void)userdata;
   return task->type == TASK_TYPE_BLOCKING;
}

static void ricotta_resume_tick(void)
{
   long long now;
   task_finder_data_t blocking;

   if (!g_resume_active)
      return;

   now = ricotta_now_us();

   if (g_resume_in_flight)
   {
      if (now - g_resume_first_us >= RICOTTA_RESUME_WINDOW_US + RICOTTA_RESUME_RETRY_US)
         ricotta_resume_give_up();
      return;
   }

   if (!g_resume_scheduled || now < g_resume_next_us)
      return;

   if (now - g_resume_first_us >= RICOTTA_RESUME_WINDOW_US)
   {
      ricotta_resume_give_up();
      return;
   }

   if (rcheevos_hardcore_active())
   {
      ricotta_resume_abandon();
      return;
   }

   blocking.func     = ricotta_task_is_blocking;
   blocking.userdata = NULL;
   if (task_queue_find(&blocking))
   {
      g_resume_next_us = now + RICOTTA_RESUME_RETRY_US;
      return;
   }

   g_resume_scheduled = 0;
   g_resume_in_flight = 1;
   if (!content_load_state(g_resume_path, false, true))
      ricotta_resume_clear();
}

void ricotta_bridge_poll_commands(void)
{
   if (!g_runloop_ready)
   {
      g_runloop_ready = 1;
      g_runloop_ready_pending = 1;
   }

   if (g_runloop_ready_pending)
   {
      JNIEnv *env = ricotta_runloop_env();
      g_runloop_ready_pending = 0;
      if (env && g_bridge_obj && g_on_runloop_ready_mid)
      {
         (*env)->CallVoidMethod(env, g_bridge_obj, g_on_runloop_ready_mid);
         ricotta_jni_check(env, "onRunloopReady");
      }
   }

   ricotta_fps_tick();
   ricotta_resume_tick();

   if (g_hold_chord >= 0 && ricotta_now_us() >= g_hold_deadline_us)
   {
      const ricotta_chord *c = &g_chords[g_hold_chord];
      if (c->pass_through)
      {
         int i;
         for (i = 0; i < c->count && g_passthrough_up_count < RICOTTA_MAX_SHORTCUT_KEYS; i++)
            g_passthrough_up[g_passthrough_up_count++] = c->keys[i];
      }
      ricotta_queue_action(c->action, RICOTTA_ACT_FIRED);
      g_hold_chord = -1;
   }

   if (g_igm_trigger_pending)
   {
      JNIEnv *env = ricotta_runloop_env();
      g_igm_trigger_pending = 0;
      if (env && g_bridge_obj && g_on_igm_trigger_mid)
      {
         (*env)->CallVoidMethod(env, g_bridge_obj, g_on_igm_trigger_mid);
         ricotta_jni_check(env, "onIgmTrigger");
      }
   }

   while (g_shortcut_head != g_shortcut_tail)
   {
      JNIEnv *env = ricotta_runloop_env();
      int packed  = g_shortcut_queue[g_shortcut_head];
      g_shortcut_head = (g_shortcut_head + 1) % RICOTTA_SHORTCUT_QUEUE;
      if (env && g_bridge_obj && g_on_shortcut_key_mid)
      {
         (*env)->CallVoidMethod(env, g_bridge_obj, g_on_shortcut_key_mid,
               (jint)(packed & 0x7fffffff), (jboolean)((packed < 0) ? JNI_TRUE : JNI_FALSE));
         ricotta_jni_check(env, "onShortcutKey");
      }
   }

   while (g_action_head != g_action_tail)
   {
      JNIEnv *env = ricotta_runloop_env();
      int packed  = g_action_queue[g_action_head];
      g_action_head = (g_action_head + 1) % RICOTTA_SHORTCUT_QUEUE;
      if (env && g_bridge_obj && g_on_shortcut_action_mid)
      {
         (*env)->CallVoidMethod(env, g_bridge_obj, g_on_shortcut_action_mid,
               (jint)(packed & 0xffff), (jint)((packed >> 16) & 0xff));
         ricotta_jni_check(env, "onShortcutAction");
      }
   }

   for (;;)
   {
      ricotta_cmd_entry entry;

      pthread_mutex_lock(&g_cmd_mutex);
      if (g_cmd_head == g_cmd_tail)
      {
         pthread_mutex_unlock(&g_cmd_mutex);
         break;
      }
      entry     = g_cmd_queue[g_cmd_head];
      g_cmd_head = (g_cmd_head + 1) % RICOTTA_CMD_QUEUE_SIZE;
      pthread_mutex_unlock(&g_cmd_mutex);

      if (entry.cmd == RICOTTA_QCMD_RA_SET)
      {
         if (entry.ra_key && entry.ra_value)
            ricotta_ra_apply(entry.ra_key, entry.ra_value, entry.ra_wait, entry.ra_watch);
         free(entry.ra_key);
         free(entry.ra_value);
         free(entry.ra_watch);
         continue;
      }
      if (entry.cmd == RICOTTA_QCMD_HELD_FLUSH)
      {
         ricotta_held_flush();
         continue;
      }
      if (entry.cmd == RICOTTA_QCMD_HELD_DROP)
      {
         g_held_cmd_count = 0;
         continue;
      }
      if (entry.cmd == RICOTTA_QCMD_PORT_DEVICE_SET)
      {
         retro_ctx_controller_info_t pad;
         input_config_set_device((unsigned)entry.port_a, (unsigned)entry.port_b);
         pad.port   = (unsigned)entry.port_a;
         pad.device = (unsigned)entry.port_b;
         core_set_controller_port_device(&pad);
         continue;
      }
      if (entry.cmd == RICOTTA_QCMD_PLAYERS_SWAP)
      {
         ricotta_swap_players((unsigned)entry.port_a, (unsigned)entry.port_b);
         continue;
      }
      if (entry.cmd == RICOTTA_QCMD_REMAP_SET)
      {
         settings_t *settings = config_get_ptr();
         if (settings)
         {
            unsigned target = entry.remap_value < 0
               ? RARCH_UNMAPPED
               : (unsigned)entry.remap_value;
            if (entry.port_a < 0)
            {
               unsigned port;
               for (port = 0; port < MAX_USERS; port++)
                  settings->uints.input_remap_ids[port][entry.port_b] = target;
            }
            else if (entry.port_a < MAX_USERS)
               settings->uints.input_remap_ids[entry.port_a][entry.port_b] = target;
         }
         continue;
      }
      if (entry.cmd == RICOTTA_QCMD_SHADER_SET)
      {
         const char *path = entry.ra_key ? entry.ra_key : "";
         enum rarch_shader_type type = video_shader_parse_type(path);
         if (*path && !video_driver_test_all_flags(video_shader_type_to_flag(type)))
         {
            free(entry.ra_key);
            continue;
         }
         menu_shader_manager_set_preset(menu_shader_get(), type, path, true);
         free(entry.ra_key);
         continue;
      }
      if (entry.cmd == RICOTTA_QCMD_VIEWPORT_SET)
      {
         settings_t *settings = config_get_ptr();
         if (settings)
         {
            if (entry.vp_w > 0 && entry.vp_h > 0)
            {
               /* aspect_ratio_index, video_scale_integer and the four bias floats forced below are
                * runtime-only: LaunchManager.kt writes config_save_on_exit = "false" for every
                * launch that reaches here, which is what keeps a normal exit from writing these
                * takeover values back into the user's config or per-game override. If that line
                * ever changes, these forces need their own persistence story. */
               settings->video_vp_custom.x      = entry.vp_x;
               settings->video_vp_custom.y      = entry.vp_y;
               settings->video_vp_custom.width  = entry.vp_w;
               settings->video_vp_custom.height = entry.vp_h;
               configuration_set_uint(settings,
                     settings->uints.video_aspect_ratio_idx, ASPECT_RATIO_CUSTOM);
               configuration_set_bool(settings, settings->bools.video_scale_integer, false);
               if (!g_vp_bias_saved)
               {
                  g_vp_bias_x = settings->floats.video_vp_bias_x;
                  g_vp_bias_y = settings->floats.video_vp_bias_y;
#if defined(RARCH_MOBILE)
                  g_vp_bias_portrait_x = settings->floats.video_vp_bias_portrait_x;
                  g_vp_bias_portrait_y = settings->floats.video_vp_bias_portrait_y;
#endif
                  g_vp_bias_saved = 1;
               }
               configuration_set_float(settings, settings->floats.video_vp_bias_x, 0.0f);
               configuration_set_float(settings, settings->floats.video_vp_bias_y, 0.0f);
#if defined(RARCH_MOBILE)
               configuration_set_float(settings, settings->floats.video_vp_bias_portrait_x, 0.0f);
               configuration_set_float(settings, settings->floats.video_vp_bias_portrait_y, 0.0f);
#endif
            }
            else
            {
               configuration_set_uint(settings,
                     settings->uints.video_aspect_ratio_idx, (unsigned)entry.vp_x);
               configuration_set_bool(settings, settings->bools.video_scale_integer,
                     entry.vp_integer_scale != 0);
               if (g_vp_bias_saved)
               {
                  configuration_set_float(settings, settings->floats.video_vp_bias_x, g_vp_bias_x);
                  configuration_set_float(settings, settings->floats.video_vp_bias_y, g_vp_bias_y);
#if defined(RARCH_MOBILE)
                  configuration_set_float(settings,
                        settings->floats.video_vp_bias_portrait_x, g_vp_bias_portrait_x);
                  configuration_set_float(settings,
                        settings->floats.video_vp_bias_portrait_y, g_vp_bias_portrait_y);
#endif
                  g_vp_bias_saved = 0;
               }
            }
            command_event(CMD_EVENT_VIDEO_SET_ASPECT_RATIO, NULL);
         }
         continue;
      }
      if (entry.cmd == RICOTTA_QCMD_DISK_SET)
      {
         runloop_state_t *runloop_st = runloop_state_get_ptr();
         if (runloop_st)
            disk_control_set_index(
                  &runloop_st->system.disk_control, (unsigned)entry.slot, true);
         continue;
      }
      if (entry.cmd == RICOTTA_QCMD_RA_SAVE_OVERRIDE)
      {
         ricotta_ra_save_override(entry.ra_scope, entry.ra_key);
         free(entry.ra_key);
         continue;
      }
      if (entry.cmd == RICOTTA_QCMD_CHEAT_LOAD)
      {
         unsigned i, size;
         cheat_manager_state_free();
         if (entry.ra_key && cheat_manager_load(entry.ra_key, false))
         {
            size = cheat_manager_get_size();
            for (i = 0; i < size; i++)
               cheat_manager_state.cheats[i].state = false;
         }
         /* Applies an empty set, which is what drops the previous file's cheats out of the core.
          * A failed load leaves no list, and apply returns before core_reset_cheat when there is
          * none, so allocate an empty one first or the old codes stay live. */
         cheat_manager_alloc_if_empty();
         cheat_manager_apply_cheats(false);
         free(entry.ra_key);
         ricotta_cheat_emit_snapshot();
         continue;
      }
      if (entry.cmd == RICOTTA_QCMD_CHEAT_TOGGLE)
      {
         settings_t *settings = config_get_ptr();
         if ((unsigned)entry.slot < cheat_manager_get_size())
            cheat_manager_toggle_index(true,
                  settings ? settings->bools.notification_show_cheats_applied : false,
                  (unsigned)entry.slot);
         continue;
      }
      if (entry.cmd == RICOTTA_QCMD_OSD_EVENT)
      {
         JNIEnv *env = ricotta_runloop_env();
         if (env && g_bridge_obj && g_on_osd_event_mid)
         {
            (*env)->CallVoidMethod(env, g_bridge_obj, g_on_osd_event_mid,
                  (jint)entry.osd_type, (jint)entry.slot);
            ricotta_jni_check(env, "onOsdEvent");
         }
         continue;
      }
      if (entry.cmd == RICOTTA_QCMD_OSD_ACHIEVEMENT)
      {
         JNIEnv *env = ricotta_runloop_env();
         if (env && g_bridge_obj && g_on_osd_achievement_mid && entry.ra_key)
         {
            jstring jtitle = (*env)->NewStringUTF(env, entry.ra_key);
            (*env)->CallVoidMethod(env, g_bridge_obj, g_on_osd_achievement_mid, jtitle);
            ricotta_jni_check(env, "onOsdAchievement");
            (*env)->DeleteLocalRef(env, jtitle);
         }
         free(entry.ra_key);
         continue;
      }
      if (entry.cmd == RICOTTA_QCMD_CHEEVOS_LOAD)
      {
         JNIEnv *env = ricotta_runloop_env();
         if (env && g_bridge_obj && g_on_cheevos_load_mid && entry.ra_key)
         {
            jstring jtext = (*env)->NewStringUTF(env, entry.ra_key);
            (*env)->CallVoidMethod(env, g_bridge_obj, g_on_cheevos_load_mid, jtext);
            ricotta_jni_check(env, "onCheevosLoad");
            (*env)->DeleteLocalRef(env, jtext);
         }
         free(entry.ra_key);
         continue;
      }
      if (entry.cmd == RICOTTA_QCMD_REWIND_RESET)
      {
         runloop_state_t *rl = runloop_state_get_ptr();
         if (rl && rl->rewind_st.state)
         {
            command_event(CMD_EVENT_REWIND_DEINIT, NULL);
            command_event(CMD_EVENT_REWIND_INIT, NULL);
         }
         continue;
      }
      if (entry.cmd == RICOTTA_QCMD_CHEAT_APPLY)
      {
         settings_t *settings = config_get_ptr();
         cheat_manager_apply_cheats(
               settings ? settings->bools.notification_show_cheats_applied : false);
         continue;
      }
      if (entry.has_slot)
      {
         settings_t *settings = config_get_ptr();
         if (settings)
            settings->ints.state_slot = entry.slot;
      }
      if (entry.cmd == CMD_EVENT_RESET || entry.cmd == CMD_EVENT_QUIT)
         ricotta_resume_abandon();
      command_event(entry.cmd, NULL);
   }
}

/* RetroArch no longer keeps the settings list resident: menu_setting_new() builds one, learns a
 * lazy name index from it, frees it, and hands back a terminator-only token, so menu_setting_find
 * is the only lookup and there is no list left to walk. The menu driver builds the index when it
 * initialises; with the Cannoli IGM the RA menu may never open, so build it once here if a lookup
 * finds no index. The token is kept because freeing it tears the index down, and priming is
 * one-shot because learning starts by freeing the cache, which invalidates every setting pointer
 * handed out so far. */
static rarch_setting_t *g_ra_index_token = NULL;

/* menu_setting_find reaches settings_lazy_get, which is check-then-act on a static cache with no
 * locking of its own: two threads that both miss build the same list twice, one pointer is
 * orphaned, and a later settings_lazy_free can free a list the other thread still holds a setting
 * from. The IGM reads on the JNI thread while applies and override saves run on the runloop, so
 * both reach it. Every other caller in RetroArch is menu code, which is dormant here, so
 * serialising our own entry points covers it. Recursive because the screen builder reaches the
 * same lookup through RetroArch's displaylist code rather than through ricotta_ra_find. */
static pthread_mutex_t g_ra_settings_lock;

static void ricotta_ra_settings_lock_init(void)
{
   pthread_mutexattr_t attr;
   pthread_mutexattr_init(&attr);
   pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_RECURSIVE);
   pthread_mutex_init(&g_ra_settings_lock, &attr);
   pthread_mutexattr_destroy(&attr);
}

static rarch_setting_t *ricotta_ra_find(const char *key)
{
   rarch_setting_t *s;
   if (!g_runloop_ready)
      return NULL;
   pthread_mutex_lock(&g_ra_settings_lock);
   s = menu_setting_find(key);
   if (!s && !g_ra_index_token)
   {
      g_ra_index_token = menu_setting_new();
      s = menu_setting_find(key);
   }
   pthread_mutex_unlock(&g_ra_settings_lock);
   return s;
}

static int ricotta_screen_dl(const char *label)
{
   size_t i;
   if (!label || !*label)
      return (int)DISPLAYLIST_SETTINGS_ALL;
   for (i = 0; i < sizeof(ricotta_menu_screens) / sizeof(ricotta_menu_screens[0]); i++)
      if (!strcmp(ricotta_menu_screens[i].label, label))
         return ricotta_menu_screens[i].dl;
   return -1;
}

static file_list_t *ricotta_build_screen(const char *label)
{
   menu_displaylist_info_t info;
   file_list_t *list;
   int dl = ricotta_screen_dl(label);

   if (dl < 0)
      return NULL;
   if (!(list = (file_list_t*)calloc(1, sizeof(*list))))
      return NULL;
   list->actiondata_free = menu_entries_cbs_free;

   menu_displaylist_info_init(&info);
   info.list  = list;
   info.label = strdup(label ? label : "");

   pthread_mutex_lock(&g_ra_settings_lock);
   {
      bool ok = menu_displaylist_ctl((enum menu_displaylist_ctl_state)dl, &info, config_get_ptr());
      pthread_mutex_unlock(&g_ra_settings_lock);
      if (!ok)
      {
         menu_displaylist_info_free(&info);
         file_list_free(list);
         return NULL;
      }
   }
   menu_displaylist_info_free(&info);
   return list;
}

static int ricotta_ra_is_combobox(rarch_setting_t *s)
{
   float step, span;
   if (!s->actions->repr)
      return 0;
   if (!(s->type == ST_UINT || s->type == ST_INT || s->type == ST_SIZE))
      return 0;
   if (!(s->flags & SD_FLAG_HAS_RANGE))
      return 0;
   step = s->step > 0.0f ? s->step : 1.0f;
   span = (s->max - s->min) / step;
   return (span >= 0.0f && span <= 64.0f) ? 1 : 0;
}

static long ricotta_ra_get_int(rarch_setting_t *s)
{
   switch (s->type)
   {
      case ST_UINT: return (long)*s->value.target.unsigned_integer;
      case ST_INT:  return (long)*s->value.target.integer;
      case ST_SIZE: return (long)*s->value.target.sizet;
      default:      return 0;
   }
}

static void ricotta_ra_set_int(rarch_setting_t *s, long v)
{
   switch (s->type)
   {
      case ST_UINT: *s->value.target.unsigned_integer = (unsigned)v; break;
      case ST_INT:  *s->value.target.integer          = (int)v;      break;
      case ST_SIZE: *s->value.target.sizet            = (size_t)v;   break;
      default: break;
   }
}

static int ricotta_ra_format_raw_value(rarch_setting_t *s, char *buf, size_t len)
{
   buf[0] = '\0';
   switch (s->type)
   {
      case ST_BOOL:
         strlcpy(buf, *s->value.target.boolean ? "true" : "false", len);
         return 1;
      case ST_INT:
         snprintf(buf, len, "%d", *s->value.target.integer);
         return 1;
      case ST_UINT:
         snprintf(buf, len, "%u", *s->value.target.unsigned_integer);
         return 1;
      case ST_SIZE:
         snprintf(buf, len, "%zu", *s->value.target.sizet);
         return 1;
      case ST_FLOAT:
         snprintf(buf, len, "%g", *s->value.target.fraction);
         return 1;
      case ST_STRING:
      case ST_STRING_OPTIONS:
      case ST_PATH:
      case ST_DIR:
         if (s->value.target.string)
            strlcpy(buf, s->value.target.string, len);
         return 1;
      default:
         return 0;
   }
}

static int ricotta_ra_format_value(rarch_setting_t *s, char *buf, size_t len)
{
   buf[0] = '\0';
   if (ricotta_ra_is_combobox(s))
   {
      s->actions->repr(s, buf, len);
      return 1;
   }
   switch (s->type)
   {
      case ST_BOOL:
         strlcpy(buf, *s->value.target.boolean ? "true" : "false", len);
         return 1;
      case ST_INT:
         snprintf(buf, len, "%d", *s->value.target.integer);
         return 1;
      case ST_UINT:
         snprintf(buf, len, "%u", *s->value.target.unsigned_integer);
         return 1;
      case ST_SIZE:
         snprintf(buf, len, "%zu", *s->value.target.sizet);
         return 1;
      case ST_FLOAT:
         snprintf(buf, len, "%g", *s->value.target.fraction);
         return 1;
      case ST_STRING:
      case ST_STRING_OPTIONS:
      case ST_PATH:
      case ST_DIR:
         strlcpy(buf, s->value.target.string, len);
         return 1;
      default:
         return 0;
   }
}

#define RICOTTA_CORE_OPT_PREFIX "core::"

static core_option_manager_t *ricotta_core_options(void)
{
   runloop_state_t *runloop_st = runloop_state_get_ptr();
   return runloop_st ? runloop_st->core_options : NULL;
}

static long ricotta_core_opt_index(const char *key)
{
   size_t i;
   core_option_manager_t *opt = ricotta_core_options();
   if (!opt || !key)
      return -1;
   for (i = 0; i < opt->size; i++)
      if (opt->opts[i].key && !strcmp(opt->opts[i].key, key))
         return (long)i;
   return -1;
}

static const char *ricotta_core_opt_value(const char *key)
{
   core_option_manager_t *opt = ricotta_core_options();
   long idx = ricotta_core_opt_index(key);
   if (!opt || idx < 0)
      return NULL;
   {
      struct core_option *o = &opt->opts[idx];
      if (!o->vals || o->index >= o->vals->size)
         return NULL;
      return o->vals->elems[o->index].data;
   }
}

static void ricotta_core_opt_apply(const char *key, const char *value)
{
   size_t v;
   core_option_manager_t *opt = ricotta_core_options();
   long idx = ricotta_core_opt_index(key);
   if (!opt || idx < 0)
      return;
   {
      struct core_option *o = &opt->opts[idx];
      if (!o->vals)
         return;
      for (v = 0; v < o->vals->size; v++)
      {
         if (!strcmp(o->vals->elems[v].data, value))
         {
            core_option_manager_set_val(opt, (size_t)idx, v, true);
            return;
         }
      }
   }
}

static jobjectArray ricotta_core_opt_describe(JNIEnv *env, const char *key)
{
   size_t v;
   size_t n = 0;
   ricotta_field fields[8 + RICOTTA_MAX_OPTS * 2];
   char opt_names[RICOTTA_MAX_OPTS * 2][20];
   jobjectArray out;
   core_option_manager_t *opt = ricotta_core_options();
   long idx = ricotta_core_opt_index(key);
   struct core_option *o;
   const char *raw;

   if (!opt || idx < 0)
      return NULL;

   o   = &opt->opts[idx];
   raw = ricotta_core_opt_value(key);

   fields[n].name = "key";     fields[n++].value = key;
   fields[n].name = "label";   fields[n++].value = core_option_manager_get_desc(opt, (size_t)idx, true);
   fields[n].name = "type";    fields[n++].value = "ENUM";
   fields[n].name = "machine"; fields[n++].value = raw ? raw : "";
   fields[n].name = "display"; fields[n++].value = core_option_manager_get_val_label(opt, (size_t)idx);

   if (o->vals)
   {
      for (v = 0; v < o->vals->size && v < RICOTTA_MAX_OPTS; v++)
      {
         const char *label = (o->val_labels && v < o->val_labels->size)
            ? o->val_labels->elems[v].data
            : o->vals->elems[v].data;
         snprintf(opt_names[v * 2],     sizeof(opt_names[0]), "opt%zu.machine", v);
         snprintf(opt_names[v * 2 + 1], sizeof(opt_names[0]), "opt%zu.display", v);
         fields[n].name = opt_names[v * 2];     fields[n++].value = o->vals->elems[v].data;
         fields[n].name = opt_names[v * 2 + 1]; fields[n++].value = label;
      }
   }

   out = ricotta_fields_to_array(env, fields, n);
   return out;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeCoreOptionKeys(
      JNIEnv *env, jobject obj)
{
   size_t i, n = 0;
   jobjectArray out;
   jclass str_cls;
   core_option_manager_t *opt = ricotta_core_options();
   (void)obj;

   if (!opt || opt->size == 0)
      return NULL;

   for (i = 0; i < opt->size; i++)
      if (opt->opts[i].key && core_option_manager_get_visible(opt, i))
         n++;
   if (n == 0)
      return NULL;

   str_cls = (*env)->FindClass(env, "java/lang/String");
   out     = (*env)->NewObjectArray(env, (jsize)n, str_cls, NULL);
   if (!out)
      return NULL;

   for (i = 0, n = 0; i < opt->size; i++)
   {
      char entry[512];
      const char *cat_key;
      const char *cat_desc = "";
      size_t c;

      if (!opt->opts[i].key || !core_option_manager_get_visible(opt, i))
         continue;

      cat_key = opt->opts[i].category_key ? opt->opts[i].category_key : "";
      for (c = 0; c < opt->cats_size; c++)
      {
         if (opt->cats[c].key && !strcmp(opt->cats[c].key, cat_key))
         {
            cat_desc = opt->cats[c].desc ? opt->cats[c].desc : "";
            break;
         }
      }
      snprintf(entry, sizeof(entry), "%s%s|%s|%s",
            RICOTTA_CORE_OPT_PREFIX, opt->opts[i].key, cat_key, cat_desc);
      (*env)->SetObjectArrayElement(env, out, (jsize)n++,
            (*env)->NewStringUTF(env, entry));
   }
   return out;
}

static int ricotta_raw_value_of(const char *key, char *buf, size_t len)
{
   size_t plen = strlen(RICOTTA_CORE_OPT_PREFIX);
   rarch_setting_t *s;
   if (!strncmp(key, RICOTTA_CORE_OPT_PREFIX, plen))
   {
      const char *v = ricotta_core_opt_value(key + plen);
      if (!v)
         return 0;
      strlcpy(buf, v, len);
      return 1;
   }
   if (!(s = ricotta_ra_find(key)))
      return 0;
   if (s->actions->read)
      s->actions->read(s);
   return ricotta_ra_format_raw_value(s, buf, len);
}

typedef struct
{
   char *key;
   char  before[512];
   int   known;
} ricotta_watched;

static ricotta_watched *ricotta_watch_read(const char *watch, int *count)
{
   ricotta_watched *out;
   char *copy, *tok, *save = NULL;
   int n = 1, i = 0;
   const char *p;
   *count = 0;
   if (!watch || !*watch)
      return NULL;
   for (p = watch; *p; p++)
      if (*p == '\x1f')
         n++;
   out  = (ricotta_watched *)calloc((size_t)n, sizeof(*out));
   copy = strdup(watch);
   if (!out || !copy)
   {
      free(out);
      free(copy);
      return NULL;
   }
   for (tok = strtok_r(copy, "\x1f", &save); tok && i < n; tok = strtok_r(NULL, "\x1f", &save))
   {
      out[i].key   = strdup(tok);
      out[i].known = ricotta_raw_value_of(tok, out[i].before, sizeof(out[i].before));
      i++;
   }
   free(copy);
   *count = i;
   return out;
}

static void ricotta_apply_done(int token, const char *raw, ricotta_watched *w, int n)
{
   JNIEnv *env = ricotta_runloop_env();
   jclass str_cls;
   jobjectArray moved;
   jstring jraw = NULL;
   char now[512];
   int i, pairs = 0;
   if (!token || !env || !g_bridge_obj || !g_on_ra_apply_done_mid)
      return;
   for (i = 0; i < n; i++)
      if (w[i].known && ricotta_raw_value_of(w[i].key, now, sizeof(now)) && strcmp(now, w[i].before))
         pairs++;
   str_cls = (*env)->FindClass(env, "java/lang/String");
   moved   = (*env)->NewObjectArray(env, (jsize)(pairs * 2), str_cls, NULL);
   pairs   = 0;
   for (i = 0; i < n; i++)
   {
      jstring jk, jb;
      if (!w[i].known || !ricotta_raw_value_of(w[i].key, now, sizeof(now)) || !strcmp(now, w[i].before))
         continue;
      jk = (*env)->NewStringUTF(env, w[i].key);
      jb = (*env)->NewStringUTF(env, w[i].before);
      (*env)->SetObjectArrayElement(env, moved, (jsize)(pairs * 2), jk);
      (*env)->SetObjectArrayElement(env, moved, (jsize)(pairs * 2 + 1), jb);
      (*env)->DeleteLocalRef(env, jk);
      (*env)->DeleteLocalRef(env, jb);
      pairs++;
   }
   if (raw)
      jraw = (*env)->NewStringUTF(env, raw);
   (*env)->CallVoidMethod(env, g_bridge_obj, g_on_ra_apply_done_mid, (jint)token, jraw, moved);
   ricotta_jni_check(env, "onRaApplyDone");
   if (jraw)
      (*env)->DeleteLocalRef(env, jraw);
   (*env)->DeleteLocalRef(env, moved);
   (*env)->DeleteLocalRef(env, str_cls);
}

static void ricotta_watch_free(ricotta_watched *w, int n)
{
   int i;
   for (i = 0; i < n; i++)
      free(w[i].key);
   free(w);
}

static int ricotta_ra_write(const char *key, const char *value, char *raw, size_t raw_len)
{
   settings_t *settings;
   rarch_setting_t *s;

   if (!strncmp(key, RICOTTA_CORE_OPT_PREFIX, strlen(RICOTTA_CORE_OPT_PREFIX)))
   {
      const char *bare = key + strlen(RICOTTA_CORE_OPT_PREFIX);
      ricotta_core_opt_apply(bare, value);
      return ricotta_raw_value_of(key, raw, raw_len);
   }

   s = ricotta_ra_find(key);
   if (!s)
      return 0;
   if (ricotta_ra_is_combobox(s))
   {
      char *end = NULL;
      long  n   = strtol(value, &end, 10);
      if (end && end != value && *end == '\0'
            && n >= (long)s->min && n <= (long)s->max)
         ricotta_ra_set_int(s, n);
      else
         goto echo;
   }
   else
   {
      switch (s->type)
      {
         case ST_BOOL:
            *s->value.target.boolean = !strcmp(value, "true");
            break;
         case ST_INT:
            *s->value.target.integer = (int)strtol(value, NULL, 10);
            break;
         case ST_UINT:
            *s->value.target.unsigned_integer = (unsigned)strtoul(value, NULL, 10);
            break;
         case ST_SIZE:
            *s->value.target.sizet = (size_t)strtoul(value, NULL, 10);
            break;
         case ST_FLOAT:
            *s->value.target.fraction = (float)strtod(value, NULL);
            break;
         case ST_STRING:
         case ST_STRING_OPTIONS:
         case ST_PATH:
         case ST_DIR:
            strlcpy(s->value.target.string, value, s->size);
            break;
         default:
            goto echo;
      }
   }
   settings         = config_get_ptr();
   settings->flags |= SETTINGS_FLG_MODIFIED;
   if (s->actions->change)
      s->actions->change(s);
   if (s->cmd_trigger_idx
         && !(s->flags & SD_FLAG_CMD_TRIGGER_EVENT_TRIGGERED))
      command_event(s->cmd_trigger_idx, NULL);

echo:
   {
      char buf[512];
      int found;
      if (s->actions->read)
         s->actions->read(s);
      found = ricotta_ra_format_raw_value(s, raw, raw_len);
      if (ricotta_ra_format_value(s, buf, sizeof(buf)))
      {
         JNIEnv *env = ricotta_runloop_env();
         if (env && g_bridge_obj && g_on_ra_applied_mid)
         {
            jstring jk = (*env)->NewStringUTF(env, key);
            jstring jv = (*env)->NewStringUTF(env, buf);
            (*env)->CallVoidMethod(env, g_bridge_obj, g_on_ra_applied_mid, jk, jv);
            ricotta_jni_check(env, "onRaSettingApplied");
            (*env)->DeleteLocalRef(env, jk);
            (*env)->DeleteLocalRef(env, jv);
         }
      }
      return found;
   }
}

static void ricotta_ra_apply(const char *key, const char *value, int token, const char *watch)
{
   char raw[512];
   int n = 0, found;
   ricotta_watched *w = token ? ricotta_watch_read(watch, &n) : NULL;
   g_hold_active = token != 0;
   found = ricotta_ra_write(key, value, raw, sizeof(raw));
   g_hold_active = 0;
   ricotta_apply_done(token, found ? raw : NULL, w, n);
   ricotta_watch_free(w, n);
}

static const char *ricotta_config_key(const char *menu_key)
{
   size_t i;
   for (i = 0; i < sizeof(ricotta_key_aliases) / sizeof(ricotta_key_aliases[0]); i++)
      if (!strcmp(ricotta_key_aliases[i].menu, menu_key))
         return ricotta_key_aliases[i].config;
   return menu_key;
}

static void ricotta_ra_config_set(config_file_t *conf, const char *key, rarch_setting_t *s)
{
   switch (s->type)
   {
      case ST_BOOL:
         config_set_string(conf, key, *s->value.target.boolean ? "true" : "false");
         break;
      case ST_INT:
      case ST_UINT:
      case ST_SIZE:
         config_set_int(conf, key, (int)ricotta_ra_get_int(s));
         break;
      case ST_FLOAT:
         config_set_float(conf, key, *s->value.target.fraction);
         break;
      case ST_STRING:
      case ST_STRING_OPTIONS:
         config_set_string(conf, key, s->value.target.string);
         break;
      case ST_PATH:
      case ST_DIR:
         config_set_path(conf, key, s->value.target.string);
         break;
      default:
         break;
   }
}

static void ricotta_ra_write_tier(const char *path, config_file_t *conf)
{
   FILE *fp;
   if (!conf->entries)
   {
      filestream_delete(path);
      return;
   }
   if ((fp = fopen(path, "w")))
   {
      fputs("# DO NOT EDIT - Cannoli writes this from your menu choices. Your own keys go in custom.cfg\n", fp);
      config_file_dump(conf, fp, true);
      fclose(fp);
   }
}

static void ricotta_ra_save_override(int scope, const char *keys)
{
   char override_path[PATH_MAX_LENGTH];
   char opt_path[PATH_MAX_LENGTH];
   char base_dir[PATH_MAX_LENGTH];
   config_file_t *conf;
   config_file_t *opt_conf;
   const char *p;

   const int is_game = scope != 0;

   if (!keys || !*keys)
      return;
   if (!*g_cannoli_root || !*g_platform_tag || !*g_core_id)
      return;
   if (is_game && !*g_rom_base_name)
      return;

   if (is_game)
      snprintf(override_path, sizeof(override_path),
            "%s/Config/Overrides/Games/%s/%s/%s.cfg",
            g_cannoli_root, g_platform_tag, g_rom_base_name, g_core_id);
   else
      snprintf(override_path, sizeof(override_path),
            "%s/Config/Overrides/Systems/%s/%s.cfg",
            g_cannoli_root, g_platform_tag, g_core_id);

   fill_pathname_basedir(base_dir, override_path, sizeof(base_dir));
   if (*base_dir && !path_is_directory(base_dir))
      path_mkdir(base_dir);

   strlcpy(opt_path, override_path, sizeof(opt_path));
   {
      char *ext = strrchr(opt_path, '.');
      if (ext)
         strlcpy(ext, ".opt", sizeof(opt_path) - (size_t)(ext - opt_path));
   }

   conf = config_file_new_from_path_to_string(override_path);
   if (!conf)
      conf = config_file_new_alloc();
   if (!conf)
      return;
   opt_conf = config_file_new_from_path_to_string(opt_path);
   if (!opt_conf)
      opt_conf = config_file_new_alloc();

   for (p = keys; *p; )
   {
      const char *nl = strchr(p, '\n');
      size_t klen    = nl ? (size_t)(nl - p) : strlen(p);
      char key[256];

      if (klen > 0 && klen < sizeof(key))
      {
         rarch_setting_t *s;
         memcpy(key, p, klen);
         key[klen] = '\0';
         if (!strncmp(key, RICOTTA_CORE_OPT_PREFIX, strlen(RICOTTA_CORE_OPT_PREFIX)))
         {
            const char *bare = key + strlen(RICOTTA_CORE_OPT_PREFIX);
            const char *val  = ricotta_core_opt_value(bare);
            if (opt_conf && val)
               config_set_string(opt_conf, bare, val);
            if (!nl)
               break;
            p = nl + 1;
            continue;
         }
         s = ricotta_ra_find(key);
         if (strpbrk(key, " \t"))
         {
            config_unset(conf, key);
            s = NULL;
         }
         if (s)
         {
            const char *ck = ricotta_config_key(key);
            if (ck != key)
               config_unset(conf, key);
            ricotta_ra_config_set(conf, ck, s);
         }
      }

      if (!nl)
         break;
      p = nl + 1;
   }

   ricotta_ra_write_tier(override_path, conf);
   config_file_free(conf);
   if (opt_conf)
   {
      ricotta_ra_write_tier(opt_path, opt_conf);
      config_file_free(opt_conf);
   }
}

static void *menu_close_poll_func(void *arg)
{
   JNIEnv *env = NULL;
   int attached = 0;
   int menu_seen_alive = 0;
   int waited_ms = 0;

   (void)arg;

   if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK)
   {
      if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != JNI_OK)
         return NULL;
      attached = 1;
   }

   while (g_menu_poll_active)
   {
      struct menu_state *menu_st = menu_state_get_ptr();
      int alive = menu_st && (menu_st->flags & MENU_ST_FLAG_ALIVE);

      if (!menu_seen_alive)
      {
         if (alive)
            menu_seen_alive = 1;
         else if (waited_ms >= MENU_OPEN_TIMEOUT_MS)
            break;
         else
            waited_ms += MENU_POLL_INTERVAL_MS;
      }
      else if (!alive)
      {
         if (g_bridge_obj && g_on_menu_closed_mid)
            (*env)->CallVoidMethod(env, g_bridge_obj, g_on_menu_closed_mid);
         ricotta_jni_check(env, "onNativeMenuClosed");
         break;
      }

      usleep(MENU_POLL_INTERVAL_MS * 1000);
   }

   g_menu_poll_active = 0;

   if (attached)
      (*g_jvm)->DetachCurrentThread(g_jvm);

   return NULL;
}

/* Cached JNIEnv for the RUNLOOP thread, attached once and never detached.
 * ONLY safe from the runloop/input thread (key interception, cheevos test).
 * Any upcall that may run on a DIFFERENT thread (RetroArch task threads, the
 * menu-close poll, etc.) MUST get its own env per call and detach after - see
 * ricotta_osd_event. Reusing this cached env on the wrong thread deadlocks the
 * JVM (manifests as a 5s input-dispatch ANR). */
static JNIEnv *ricotta_runloop_env(void)
{
   if (!g_jvm)
      return NULL;
   if (!g_native_env)
   {
      if ((*g_jvm)->GetEnv(g_jvm, (void **)&g_native_env, JNI_VERSION_1_6) != JNI_OK)
      {
         if ((*g_jvm)->AttachCurrentThread(g_jvm, &g_native_env, NULL) != JNI_OK)
            return NULL;
      }
   }
   ricotta_jni_check(g_native_env, "runloop env, before our call");
   return g_native_env;
}

static volatile int g_ff_toggle_pending = 0;
static volatile int g_ff_held = 0;

int ricotta_ff_take_toggle(void)
{
   if (!g_ff_toggle_pending)
      return 0;
   g_ff_toggle_pending = 0;
   return 1;
}

int ricotta_ff_held(void)
{
   return g_ff_held;
}

static volatile int g_rewind_held = 0;

int ricotta_rewind_held(void)
{
   return g_rewind_held;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeGetDebugStats(
      JNIEnv *env, jobject obj)
{
   ricotta_field fields[6];
   char fps_buf[32], frames_buf[32], mem_buf[64], geom_buf[48];
   size_t n = 0;
   video_driver_state_t *video_st = video_state_get_ptr();
   runloop_state_t *runloop_st    = runloop_state_get_ptr();
   uint64_t total = mem_stats_total();
   uint64_t freem = mem_stats_free();
   uint64_t used  = (total > freem) ? (total - freem) : 0;

   (void)obj;

   snprintf(fps_buf, sizeof(fps_buf), "%.2f", ricotta_fps());
   fields[n].name = "fps"; fields[n].value = fps_buf; n++;

   snprintf(frames_buf, sizeof(frames_buf), "%llu",
         (unsigned long long)(video_st ? video_st->frame_count : 0));
   fields[n].name = "frames"; fields[n].value = frames_buf; n++;

   snprintf(mem_buf, sizeof(mem_buf), "%.0f/%.0f MB",
         used / (1024.0 * 1024.0), total / (1024.0 * 1024.0));
   fields[n].name = "memory"; fields[n].value = mem_buf; n++;

   if (video_st && video_st->av_info.geometry.base_width)
      snprintf(geom_buf, sizeof(geom_buf), "%ux%u",
            video_st->av_info.geometry.base_width,
            video_st->av_info.geometry.base_height);
   else
      snprintf(geom_buf, sizeof(geom_buf), "-");
   fields[n].name = "geometry"; fields[n].value = geom_buf; n++;

   fields[n].name = "core";
   fields[n].value = (runloop_st && runloop_st->system.info.library_name)
         ? runloop_st->system.info.library_name : "-";
   n++;

   fields[n].name = "driver";
   fields[n].value = (video_st && video_st->current_video && video_st->current_video->ident)
         ? video_st->current_video->ident : "-";
   n++;

   return ricotta_fields_to_array(env, fields, n);
}

JNIEXPORT jfloat JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeGetFps(
      JNIEnv *env, jobject obj)
{
   (void)env; (void)obj;
   return (jfloat)ricotta_fps();
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeToggleFastForward(
      JNIEnv *env, jobject obj)
{
   (void)env; (void)obj;
   g_ff_toggle_pending = 1;
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeSetFastForwardHeld(
      JNIEnv *env, jobject obj, jboolean held)
{
   (void)env; (void)obj;
   g_ff_held = held ? 1 : 0;
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeResetRewindBuffer(
      JNIEnv *env, jobject obj)
{
   (void)env; (void)obj;
   ricotta_enqueue_command(RICOTTA_QCMD_REWIND_RESET, 0, 0);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeSetRewindHeld(
      JNIEnv *env, jobject obj, jboolean held)
{
   (void)env; (void)obj;
   g_rewind_held = held ? 1 : 0;
}

static int ricotta_is_shortcut_key(int keycode)
{
   int i;
   for (i = 0; i < g_shortcut_keycount; i++)
      if (keycode == g_shortcut_keycodes[i])
         return 1;
   return 0;
}

static void ricotta_queue_shortcut(int keycode, int action)
{
   int next = (g_shortcut_tail + 1) % RICOTTA_SHORTCUT_QUEUE;
   if (next == g_shortcut_head)
      return;
   g_shortcut_queue[g_shortcut_tail] =
         (action == 0) ? (int)(keycode | (int)0x80000000) : keycode;
   g_shortcut_tail = next;
}

static int ricotta_held_has(int keycode)
{
   int i;
   for (i = 0; i < g_held_count; i++)
      if (g_held[i] == keycode)
         return 1;
   return 0;
}

static void ricotta_held_add(int keycode)
{
   if (ricotta_held_has(keycode))
      return;
   if (g_held_count < RICOTTA_MAX_SHORTCUT_KEYS)
      g_held[g_held_count++] = keycode;
}

static void ricotta_held_remove(int keycode)
{
   int i;
   for (i = 0; i < g_held_count; i++)
   {
      if (g_held[i] != keycode)
         continue;
      g_held[i] = g_held[g_held_count - 1];
      g_held_count--;
      return;
   }
}

static int ricotta_chord_satisfied(const ricotta_chord *c)
{
   int i;
   if (c->count <= 0)
      return 0;
   for (i = 0; i < c->count; i++)
      if (!ricotta_held_has(c->keys[i]))
         return 0;
   return 1;
}

static int ricotta_best_chord(void)
{
   int i;
   int best = -1;
   for (i = 0; i < g_chord_count; i++)
   {
      if (!ricotta_chord_satisfied(&g_chords[i]))
         continue;
      if (best < 0 || g_chords[i].count > g_chords[best].count)
         best = i;
   }
   return best;
}

static void ricotta_queue_action(int action, int kind)
{
   int next = (g_action_tail + 1) % RICOTTA_SHORTCUT_QUEUE;
   if (next == g_action_head)
      return;
   g_action_queue[g_action_tail] = (action & 0xffff) | (kind << 16);
   g_action_tail = next;
}

static void ricotta_request_retract(int keycode)
{
   int i;
   for (i = 0; i < g_retract_count; i++)
      if (g_retract[i] == keycode)
         return;
   if (g_retract_count < RICOTTA_MAX_SHORTCUT_KEYS)
      g_retract[g_retract_count++] = keycode;
}

static void ricotta_mark_swallowed(int keycode)
{
   int i;
   for (i = 0; i < g_swallowed_count; i++)
      if (g_swallowed[i] == keycode)
         return;
   if (g_swallowed_count < RICOTTA_MAX_SHORTCUT_KEYS)
      g_swallowed[g_swallowed_count++] = keycode;
}

static int ricotta_is_swallowed(int keycode)
{
   int i;
   for (i = 0; i < g_swallowed_count; i++)
      if (g_swallowed[i] == keycode)
         return 1;
   return 0;
}

static int ricotta_take_passthrough_up(int keycode)
{
   int i;
   for (i = 0; i < g_passthrough_up_count; i++)
   {
      if (g_passthrough_up[i] != keycode)
         continue;
      g_passthrough_up[i] = g_passthrough_up[g_passthrough_up_count - 1];
      g_passthrough_up_count--;
      return 1;
   }
   return 0;
}

static int ricotta_take_swallowed(int keycode)
{
   int i;
   for (i = 0; i < g_swallowed_count; i++)
   {
      if (g_swallowed[i] != keycode)
         continue;
      g_swallowed[i] = g_swallowed[g_swallowed_count - 1];
      g_swallowed_count--;
      return 1;
   }
   return 0;
}

int ricotta_bridge_intercept_key(int keycode, int action)
{
   if (g_igm_visible)
   {
      if (action == 1 && ricotta_take_passthrough_up(keycode))
         return 0;
      return 1;
   }

   {
      int i;
      int is_trigger = 0;
      for (i = 0; i < g_igm_trigger_keycount; i++)
      {
         if (keycode == g_igm_trigger_keycodes[i])
         {
            is_trigger = 1;
            break;
         }
      }
      if (is_trigger)
      {
         if (ricotta_is_shortcut_key(keycode))
         {
            g_menu_modifier_held = (action == 0);
            ricotta_queue_shortcut(keycode, action);
         }
         /* Record only. A core that runs on its own coroutine stack enters this poll from inside
          * retro_run, where a JNI call throws a spurious StackOverflowError; the pump raises it
          * from runloop_iterate instead, which is always on this thread's own stack. */
         else if (action == 0)
            g_igm_trigger_pending = 1;
         return 1;
      }
   }

   if (ricotta_is_shortcut_key(keycode))
   {
      ricotta_queue_shortcut(keycode, action);

      if (action == 0)
      {
         int best;
         ricotta_held_add(keycode);
         best = ricotta_best_chord();
         if (best >= 0 && g_hold_chord >= 0 && g_hold_chord != best
               && g_chords[g_hold_chord].pass_through)
         {
            ricotta_queue_action(g_chords[g_hold_chord].action, RICOTTA_ACT_HOLD_CANCELLED);
            g_hold_chord = -1;
         }
         if (best >= 0 && best != g_firing_chord
               && g_chords[best].pass_through && g_chords[best].hold_ms > 0)
         {
            g_firing_chord = best;
            g_hold_chord = best;
            g_hold_deadline_us = ricotta_now_us()
                  + (long long)g_chords[best].hold_ms * 1000LL;
            ricotta_queue_action(g_chords[best].action, RICOTTA_ACT_HOLD_ARMED);
            return 0;
         }
         if (best >= 0 && best != g_firing_chord)
         {
            int i;
            g_firing_chord = best;
            for (i = 0; i < g_chords[best].count; i++)
            {
               ricotta_request_retract(g_chords[best].keys[i]);
               ricotta_mark_swallowed(g_chords[best].keys[i]);
            }
            if (g_chords[best].hold_ms > 0)
            {
               g_hold_chord = best;
               g_hold_deadline_us = ricotta_now_us()
                     + (long long)g_chords[best].hold_ms * 1000LL;
               ricotta_queue_action(g_chords[best].action, RICOTTA_ACT_HOLD_ARMED);
            }
            else
               ricotta_queue_action(g_chords[best].action, RICOTTA_ACT_FIRED);
            return 1;
         }
         if (ricotta_is_swallowed(keycode))
            return 1;
         if (g_menu_modifier_held)
         {
            ricotta_mark_swallowed(keycode);
            return 1;
         }
         return 0;
      }

      ricotta_held_remove(keycode);
      if (g_firing_chord >= 0 && !ricotta_chord_satisfied(&g_chords[g_firing_chord]))
      {
         if (g_hold_chord == g_firing_chord)
         {
            ricotta_queue_action(g_chords[g_firing_chord].action, RICOTTA_ACT_HOLD_CANCELLED);
            g_hold_chord = -1;
         }
         else
            ricotta_queue_action(g_chords[g_firing_chord].action, RICOTTA_ACT_RELEASED);
         g_firing_chord = -1;
      }
      ricotta_take_passthrough_up(keycode);
      if (ricotta_take_swallowed(keycode))
         return 1;
   }

   return 0;
}

int ricotta_bridge_take_retract(int *out, int max)
{
   int n = 0;
   while (n < max && g_retract_count > 0)
      out[n++] = g_retract[--g_retract_count];
   return n;
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeSetIGMVisible(
      JNIEnv *env, jobject obj, jboolean visible)
{
   (void)env;
   (void)obj;
   g_igm_visible = visible ? 1 : 0;
   g_menu_modifier_held = 0;
   g_swallowed_count = 0;
   g_held_count = 0;
   g_firing_chord = -1;
   g_retract_count = 0;
   g_hold_chord = -1;
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeSetIgmTriggerKeycodes(
      JNIEnv *env, jobject obj, jintArray keycodes)
{
   jsize n;
   jint *elems;
   jsize i;

   (void)obj;

   g_igm_trigger_keycount = 0;
   if (!keycodes)
      return;

   n = (*env)->GetArrayLength(env, keycodes);
   if (n > RICOTTA_MAX_TRIGGER_KEYS)
      n = RICOTTA_MAX_TRIGGER_KEYS;

   elems = (*env)->GetIntArrayElements(env, keycodes, NULL);
   if (!elems)
      return;

   for (i = 0; i < n; i++)
      g_igm_trigger_keycodes[i] = (int)elems[i];

   (*env)->ReleaseIntArrayElements(env, keycodes, elems, JNI_ABORT);
   g_igm_trigger_keycount = (int)n; /* set count last so the reader never sees partial state */
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeSetShortcutChords(
      JNIEnv *env, jobject obj, jintArray table)
{
   jsize n;
   jint *elems;
   jsize at = 0;
   int chords = 0;
   int keys   = 0;

   (void)obj;

   g_shortcut_keycount   = 0;
   g_chord_count         = 0;
   g_held_count          = 0;
   g_firing_chord        = -1;
   g_menu_modifier_held  = 0;
   g_swallowed_count     = 0;
   g_retract_count       = 0;
   g_hold_chord          = -1;
   g_passthrough_up_count = 0;

   if (!table)
      return;

   n = (*env)->GetArrayLength(env, table);
   elems = (*env)->GetIntArrayElements(env, table, NULL);
   if (!elems)
      return;

   while (at + 3 < n && chords < RICOTTA_MAX_CHORDS)
   {
      int action       = (int)elems[at++];
      int hold_ms      = (int)elems[at++];
      int pass_through = (int)elems[at++];
      int count        = (int)elems[at++];
      int i;

      if (count <= 0 || count > RICOTTA_MAX_CHORD_KEYS || at + count > n)
         break;

      g_chords[chords].action  = action;
      g_chords[chords].hold_ms = hold_ms;
      g_chords[chords].pass_through = pass_through != 0;
      g_chords[chords].count   = count;
      for (i = 0; i < count; i++)
      {
         int key = (int)elems[at++];
         int j;
         g_chords[chords].keys[i] = key;
         for (j = 0; j < keys; j++)
            if (g_shortcut_keycodes[j] == key)
               break;
         if (j == keys && keys < RICOTTA_MAX_SHORTCUT_KEYS)
            g_shortcut_keycodes[keys++] = key;
      }
      chords++;
   }

   (*env)->ReleaseIntArrayElements(env, table, elems, JNI_ABORT);
   /* Counts last, so a reader never sees a partly built table. */
   g_shortcut_keycount = keys;
   g_chord_count       = chords;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeRaScreenRows(
      JNIEnv *env, jobject obj, jstring jlabel)
{
   const char *label  = jlabel ? (*env)->GetStringUTFChars(env, jlabel, NULL) : NULL;
   file_list_t *list  = ricotta_build_screen(label ? label : "");
   jobjectArray out   = NULL;
   size_t i;

   (void)obj;

   if (list)
   {
      jclass str_cls = (*env)->FindClass(env, "java/lang/String");
      out = (*env)->NewObjectArray(env, (jsize)list->size, str_cls, NULL);
      for (i = 0; out && i < list->size; i++)
      {
         char buf[768];
         const char *key  = list->list[i].label ? list->list[i].label : "";
         const char *name = list->list[i].path  ? list->list[i].path  : "";
         jstring js;
         snprintf(buf, sizeof(buf), "%s\x1f%s\x1f%d",
               key, name, ricotta_screen_dl(key) >= 0 ? 1 : 0);
         js = (*env)->NewStringUTF(env, buf);
         (*env)->SetObjectArrayElement(env, out, (jsize)i, js);
         (*env)->DeleteLocalRef(env, js);
      }
      file_list_free(list);
   }

   if (label)
      (*env)->ReleaseStringUTFChars(env, jlabel, label);
   return out;
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeSetCannoliContext(
      JNIEnv *env, jobject obj, jstring root, jstring tag, jstring base, jstring core)
{
   const char *c_root = root ? (*env)->GetStringUTFChars(env, root, NULL) : NULL;
   const char *c_tag  = tag  ? (*env)->GetStringUTFChars(env, tag,  NULL) : NULL;
   const char *c_base = base ? (*env)->GetStringUTFChars(env, base, NULL) : NULL;
   const char *c_core = core ? (*env)->GetStringUTFChars(env, core, NULL) : NULL;

   (void)obj;

   strlcpy(g_cannoli_root,  c_root ? c_root : "", sizeof(g_cannoli_root));
   strlcpy(g_platform_tag,  c_tag  ? c_tag  : "", sizeof(g_platform_tag));
   strlcpy(g_rom_base_name, c_base ? c_base : "", sizeof(g_rom_base_name));
   strlcpy(g_core_id,       c_core ? c_core : "", sizeof(g_core_id));

   if (c_root)
      (*env)->ReleaseStringUTFChars(env, root, c_root);
   if (c_tag)
      (*env)->ReleaseStringUTFChars(env, tag, c_tag);
   if (c_base)
      (*env)->ReleaseStringUTFChars(env, base, c_base);
   if (c_core)
      (*env)->ReleaseStringUTFChars(env, core, c_core);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeInit(
      JNIEnv *env, jobject obj)
{
   jclass cls;

   (*env)->GetJavaVM(env, &g_jvm);

   ricotta_ra_settings_lock_init();

   if (g_bridge_obj)
   {
      (*env)->DeleteGlobalRef(env, g_bridge_obj);
      g_bridge_obj = NULL;
   }

   g_bridge_obj = (*env)->NewGlobalRef(env, obj);

   cls = (*env)->GetObjectClass(env, obj);
   g_on_menu_closed_mid = (*env)->GetMethodID(env, cls, "onNativeMenuClosed", "()V");
   g_on_debug_key_mid = (*env)->GetMethodID(env, cls, "onDebugKey", "(I)V");
   g_on_igm_trigger_mid = (*env)->GetMethodID(env, cls, "onIgmTrigger", "()V");
   g_on_shortcut_key_mid = (*env)->GetMethodID(env, cls, "onShortcutKey", "(IZ)V");
   g_on_shortcut_action_mid = (*env)->GetMethodID(env, cls, "onShortcutAction", "(II)V");
   g_on_runloop_ready_mid = (*env)->GetMethodID(env, cls, "onRunloopReady", "()V");
   g_on_ra_applied_mid = (*env)->GetMethodID(env, cls, "onRaSettingApplied",
         "(Ljava/lang/String;Ljava/lang/String;)V");
   g_on_ra_apply_done_mid = (*env)->GetMethodID(env, cls, "onRaApplyDone",
         "(ILjava/lang/String;[Ljava/lang/String;)V");
   g_on_osd_event_mid = (*env)->GetMethodID(env, cls, "onOsdEvent", "(II)V");
   g_on_osd_achievement_mid = (*env)->GetMethodID(env, cls, "onOsdAchievement", "(Ljava/lang/String;)V");
   g_on_cheevos_load_mid = (*env)->GetMethodID(env, cls, "onCheevosLoad", "(Ljava/lang/String;)V");
   g_on_cheats_loaded_mid = (*env)->GetMethodID(env, cls, "onCheatsLoaded", "(Ljava/lang/String;)V");
   g_on_cheevos_request_mid = (*env)->GetMethodID(env, cls, "onCheevosRequest",
         "(Ljava/lang/String;)Ljava/lang/String;");
   g_on_cheevos_response_mid = (*env)->GetMethodID(env, cls, "onCheevosResponse",
         "(Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;");
   g_on_cheevos_failed_mid = (*env)->GetMethodID(env, cls, "onCheevosFailed",
         "(Ljava/lang/String;)V");
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeDestroy(
      JNIEnv *env, jobject obj)
{
   (void)obj;

   g_menu_poll_active = 0;

   if (g_bridge_obj)
   {
      (*env)->DeleteGlobalRef(env, g_bridge_obj);
      g_bridge_obj = NULL;
   }

   g_on_menu_closed_mid = NULL;
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeSaveState(
      JNIEnv *env, jobject obj, jint slot)
{
   (void)env;
   (void)obj;
   ricotta_enqueue_command(CMD_EVENT_SAVE_STATE, (int)slot, 1);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeLoadState(
      JNIEnv *env, jobject obj, jint slot)
{
   (void)env;
   (void)obj;
   ricotta_enqueue_command(CMD_EVENT_LOAD_STATE, (int)slot, 1);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeUndoSaveState(
      JNIEnv *env, jobject obj)
{
   (void)env;
   (void)obj;
   ricotta_enqueue_command(CMD_EVENT_UNDO_SAVE_STATE, 0, 0);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeUndoLoadState(
      JNIEnv *env, jobject obj)
{
   (void)env;
   (void)obj;
   ricotta_enqueue_command(CMD_EVENT_UNDO_LOAD_STATE, 0, 0);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeReset(
      JNIEnv *env, jobject obj)
{
   (void)env;
   (void)obj;
   ricotta_enqueue_command(RICOTTA_QCMD_HELD_FLUSH, 0, 0);
   ricotta_enqueue_command(CMD_EVENT_RESET, 0, 0);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeQuit(
      JNIEnv *env, jobject obj)
{
   (void)env;
   (void)obj;
   ricotta_enqueue_command(RICOTTA_QCMD_HELD_DROP, 0, 0);
   ricotta_enqueue_command(CMD_EVENT_QUIT, 0, 0);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeExitProcess(
      JNIEnv *env, jclass clazz)
{
   (void)env;
   (void)clazz;
   _exit(0);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativePause(
      JNIEnv *env, jobject obj)
{
   (void)env;
   (void)obj;
   ricotta_enqueue_command(CMD_EVENT_PAUSE, 0, 0);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeUnpause(
      JNIEnv *env, jobject obj)
{
   (void)env;
   (void)obj;
   ricotta_enqueue_command(RICOTTA_QCMD_HELD_FLUSH, 0, 0);
   ricotta_enqueue_command(CMD_EVENT_UNPAUSE, 0, 0);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeFlushHeld(
      JNIEnv *env, jobject obj)
{
   (void)env;
   (void)obj;
   ricotta_enqueue_command(RICOTTA_QCMD_HELD_FLUSH, 0, 0);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeDropHeld(
      JNIEnv *env, jobject obj)
{
   (void)env;
   (void)obj;
   ricotta_enqueue_command(RICOTTA_QCMD_HELD_DROP, 0, 0);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeMenuToggle(
      JNIEnv *env, jobject obj)
{
   (void)env;
   (void)obj;

   ricotta_enqueue_command(RICOTTA_QCMD_HELD_FLUSH, 0, 0);
   ricotta_enqueue_command(CMD_EVENT_MENU_TOGGLE, 0, 0);

   if (!g_menu_poll_active)
   {
      g_menu_poll_active = 1;
      pthread_create(&g_menu_poll_thread, NULL, menu_close_poll_func, NULL);
      pthread_detach(g_menu_poll_thread);
   }
}

static disk_control_interface_t *ricotta_disk_control(void)
{
   runloop_state_t *runloop_st = runloop_state_get_ptr();
   if (!runloop_st)
      return NULL;
   return &runloop_st->system.disk_control;
}

JNIEXPORT jint JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeDiskCount(
      JNIEnv *env, jobject obj)
{
   disk_control_interface_t *dc = ricotta_disk_control();
   (void)env;
   (void)obj;
   if (!dc || !disk_control_enabled(dc))
      return 0;
   return (jint)disk_control_get_num_images(dc);
}

JNIEXPORT jint JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeDiskIndex(
      JNIEnv *env, jobject obj)
{
   disk_control_interface_t *dc = ricotta_disk_control();
   (void)env;
   (void)obj;
   if (!dc || !disk_control_enabled(dc))
      return 0;
   return (jint)disk_control_get_image_index(dc);
}

JNIEXPORT jstring JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeDiskLabel(
      JNIEnv *env, jobject obj, jint index)
{
   char label[256];
   disk_control_interface_t *dc = ricotta_disk_control();
   (void)obj;

   label[0] = '\0';
   if (!dc || !disk_control_enabled(dc))
      return NULL;
   disk_control_get_image_label(dc, (unsigned)index, label, sizeof(label));
   if (!label[0])
      return NULL;
   return (*env)->NewStringUTF(env, label);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeSetDiskIndex(
      JNIEnv *env, jobject obj, jint index)
{
   (void)env;
   (void)obj;
   ricotta_enqueue_command(RICOTTA_QCMD_DISK_SET, (int)index, 0);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativePortDeviceTypes(
      JNIEnv *env, jobject obj, jint port)
{
   unsigned devices[128];
   char ids[129][16];
   ricotta_field fields[129];
   unsigned count, i;
   rarch_system_info_t *sys_info;
   (void)obj;

   if (!g_runloop_ready || port < 0 || port >= RICOTTA_PLAYER_ROWS)
      return NULL;
   sys_info = &runloop_state_get_ptr()->system;
   count    = libretro_device_get_size(devices, sizeof(devices) / sizeof(devices[0]), (unsigned)port);

   snprintf(ids[0], sizeof(ids[0]), "%u", input_config_get_device((unsigned)port));
   fields[0].name  = "current";
   fields[0].value = ids[0];
   for (i = 0; i < count; i++)
   {
      snprintf(ids[i + 1], sizeof(ids[i + 1]), "%u", devices[i]);
      fields[i + 1].name  = ids[i + 1];
      fields[i + 1].value = ricotta_port_device_label(sys_info, (unsigned)port, devices[i]);
   }
   return ricotta_fields_to_array(env, fields, count + 1);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeButtonDescriptors(
      JNIEnv *env, jobject obj)
{
   char ids[16][4];
   ricotta_field fields[16];
   unsigned i, count = 0;
   rarch_system_info_t *sys_info;
   (void)obj;

   if (!g_runloop_ready)
      return NULL;
   sys_info = &runloop_state_get_ptr()->system;
   for (i = 0; i < 16; i++)
   {
      const char *desc = sys_info->input_desc_btn[0][i];
      if (!desc || !*desc)
         continue;
      snprintf(ids[count], sizeof(ids[count]), "%u", i);
      fields[count].name  = ids[count];
      fields[count].value = desc;
      count++;
   }
   return ricotta_fields_to_array(env, fields, count);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeSetPortDevice(
      JNIEnv *env, jobject obj, jint port, jint id)
{
   ricotta_cmd_entry entry = {0};
   (void)env;
   (void)obj;
   if (port < 0 || port >= RICOTTA_PLAYER_ROWS || id < 0)
      return;
   entry.cmd    = RICOTTA_QCMD_PORT_DEVICE_SET;
   entry.port_a = (int)port;
   entry.port_b = (int)id;
   ricotta_enqueue_entry(entry);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeSetButtonRemap(
      JNIEnv *env, jobject obj, jint port, jint source, jint target)
{
   ricotta_cmd_entry entry = {0};
   (void)env;
   (void)obj;
   if (source < 0 || source >= RICOTTA_REMAP_BUTTONS)
      return;
   if (target < -1 || target >= RICOTTA_REMAP_BUTTONS)
      return;
   entry.cmd         = RICOTTA_QCMD_REMAP_SET;
   entry.port_a      = (int)port;
   entry.port_b      = (int)source;
   entry.remap_value = (int)target;
   ricotta_enqueue_entry(entry);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativePlayers(
      JNIEnv *env, jobject obj)
{
   settings_t *settings = config_get_ptr();
   char pads[RICOTTA_PLAYER_ROWS][16];
   char sets[RICOTTA_PLAYER_ROWS][16];
   ricotta_field fields[RICOTTA_PLAYER_ROWS * 3];
   unsigned p;
   (void)obj;

   if (!g_runloop_ready || !settings)
      return NULL;
   for (p = 0; p < RICOTTA_PLAYER_ROWS; p++)
   {
      unsigned pad     = settings->uints.input_joypad_index[p];
      const char *name = NULL;
      unsigned set     = 0;
      if (pad < MAX_INPUT_DEVICES)
      {
         const char *display = input_config_get_device_display_name(pad);
         name = display ? display : input_config_get_device_name(pad);
         if (name)
            set = input_config_get_device_name_index(pad);
      }
      snprintf(pads[p], sizeof(pads[p]), "%u", pad);
      snprintf(sets[p], sizeof(sets[p]), "%u", set);
      fields[p * 3].name      = "pad";
      fields[p * 3].value     = pads[p];
      fields[p * 3 + 1].name  = "set";
      fields[p * 3 + 1].value = sets[p];
      fields[p * 3 + 2].name  = "name";
      fields[p * 3 + 2].value = name;
   }
   return ricotta_fields_to_array(env, fields, RICOTTA_PLAYER_ROWS * 3);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeSwapPlayers(
      JNIEnv *env, jobject obj, jint a, jint b)
{
   ricotta_cmd_entry entry = {0};
   (void)env;
   (void)obj;
   entry.cmd    = RICOTTA_QCMD_PLAYERS_SWAP;
   entry.port_a = (int)a;
   entry.port_b = (int)b;
   ricotta_enqueue_entry(entry);
}

JNIEXPORT jboolean JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeIsPaused(
      JNIEnv *env, jobject obj)
{
   uint32_t flags;
   (void)env;
   (void)obj;

   flags = runloop_get_flags();
   return (flags & RUNLOOP_FLAG_PAUSED) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeRaValue(
      JNIEnv *env, jobject obj, jstring jkey)
{
   const char *key;
   rarch_setting_t *s;
   char raw[512];
   char shown[512];
   char joined[1040];

   (void)obj;

   key = (*env)->GetStringUTFChars(env, jkey, NULL);
   if (!key)
      return NULL;

   if (!strncmp(key, RICOTTA_CORE_OPT_PREFIX, strlen(RICOTTA_CORE_OPT_PREFIX)))
   {
      const char *v = ricotta_core_opt_value(key + strlen(RICOTTA_CORE_OPT_PREFIX));
      (*env)->ReleaseStringUTFChars(env, jkey, key);
      if (!v)
         return NULL;
      snprintf(joined, sizeof(joined), "%s\x1f%s", v, v);
      return (*env)->NewStringUTF(env, joined);
   }

   s = ricotta_ra_find(key);
   (*env)->ReleaseStringUTFChars(env, jkey, key);
   if (!s)
      return NULL;
   if (s->actions->read)
      s->actions->read(s);
   if (!ricotta_ra_format_raw_value(s, raw, sizeof(raw)))
      return NULL;
   if (!ricotta_ra_format_value(s, shown, sizeof(shown)))
      strlcpy(shown, raw, sizeof(shown));
   snprintf(joined, sizeof(joined), "%s\x1f%s", raw, shown);
   return (*env)->NewStringUTF(env, joined);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeRaGetSetting(
      JNIEnv *env, jobject obj, jstring jkey)
{
   const char *key;
   char key_buf[256];
   rarch_setting_t *s;
   const char *type_str;
   char value_buf[512];
   char raw_buf[512];
   char min_buf[32], max_buf[32], step_buf[32];
   char opt_machine[RICOTTA_MAX_OPTS][32];
   char opt_display[RICOTTA_MAX_OPTS][128];
   char opt_names[RICOTTA_MAX_OPTS * 2][20];
   ricotta_field fields[10 + RICOTTA_MAX_OPTS * 2];
   unsigned opt_count;
   unsigned opt_i;
   size_t n = 0;
   jobjectArray out;

   (void)obj;

   key = (*env)->GetStringUTFChars(env, jkey, NULL);
   strlcpy(key_buf, key ? key : "", sizeof(key_buf));

   if (key && !strncmp(key, RICOTTA_CORE_OPT_PREFIX, strlen(RICOTTA_CORE_OPT_PREFIX)))
   {
      jobjectArray co = ricotta_core_opt_describe(
            env, key + strlen(RICOTTA_CORE_OPT_PREFIX));
      (*env)->ReleaseStringUTFChars(env, jkey, key);
      return co;
   }

   s = key ? ricotta_ra_find(key) : NULL;
   if (key)
      (*env)->ReleaseStringUTFChars(env, jkey, key);
   if (!s)
      return NULL;

   if (ricotta_ra_is_combobox(s))
      type_str = "ENUM";
   else
   {
      switch (s->type)
      {
         case ST_BOOL:
            type_str = "BOOL";
            break;
         case ST_INT:
         case ST_UINT:
         case ST_SIZE:
            type_str = "INT";
            break;
         case ST_FLOAT:
            type_str = "FLOAT";
            break;
         case ST_STRING_OPTIONS:
            type_str = "ENUM";
            break;
         case ST_STRING:
         case ST_PATH:
         case ST_DIR:
            type_str = "STRING_RO";
            break;
         default:
            return NULL;
      }
   }

   opt_count = 0;
   if (ricotta_ra_is_combobox(s))
   {
      float step = s->step > 0.0f ? s->step : 1.0f;
      long  orig = ricotta_ra_get_int(s);
      float i;
      for (i = s->min; i <= s->max && opt_count < RICOTTA_MAX_OPTS; i += step)
      {
         ricotta_ra_set_int(s, (long)i);
         snprintf(opt_machine[opt_count], sizeof(opt_machine[0]), "%ld", (long)i);
         s->actions->repr(s, opt_display[opt_count], sizeof(opt_display[0]));
         opt_count++;
      }
      ricotta_ra_set_int(s, orig);
   }
   else if (s->type == ST_STRING_OPTIONS && s->values)
   {
      const char *p = s->values;
      while (*p && opt_count < RICOTTA_MAX_OPTS)
      {
         const char *end = strchr(p, '|');
         size_t len = end ? (size_t)(end - p) : strlen(p);
         if (len >= sizeof(opt_machine[0]))
            len = sizeof(opt_machine[0]) - 1;
         memcpy(opt_machine[opt_count], p, len);
         opt_machine[opt_count][len] = '\0';
         strlcpy(opt_display[opt_count], opt_machine[opt_count], sizeof(opt_display[0]));
         opt_count++;
         if (!end)
            break;
         p = end + 1;
      }
   }

   if (!ricotta_ra_format_value(s, value_buf, sizeof(value_buf)))
      return NULL;

   min_buf[0] = max_buf[0] = step_buf[0] = '\0';
   if (s->flags & SD_FLAG_HAS_RANGE)
   {
      snprintf(min_buf, sizeof(min_buf), "%g", s->min);
      snprintf(max_buf, sizeof(max_buf), "%g", s->max);
   }
   if (s->step > 0.0f)
      snprintf(step_buf, sizeof(step_buf), "%g", s->step);

   if (!ricotta_ra_format_raw_value(s, raw_buf, sizeof(raw_buf)))
      raw_buf[0] = '\0';

   fields[n].name = "key";     fields[n++].value = key_buf;
   fields[n].name = "label";   fields[n++].value = s->short_description ? s->short_description : s->name;
   fields[n].name = "type";    fields[n++].value = type_str;
   fields[n].name = "machine"; fields[n++].value = raw_buf;
   fields[n].name = "display"; fields[n++].value = value_buf;
   fields[n].name = "min";     fields[n++].value = min_buf;
   fields[n].name = "max";     fields[n++].value = max_buf;
   fields[n].name = "step";    fields[n++].value = step_buf;
   fields[n].name = "restart"; fields[n++].value =
         ((s->flags & SD_FLAG_IS_DRIVER)
          || s->cmd_trigger_idx == CMD_EVENT_REINIT
          || s->cmd_trigger_idx == CMD_EVENT_REINIT_FROM_TOGGLE) ? "1" : "0";
   for (opt_i = 0; opt_i < opt_count; opt_i++)
   {
      snprintf(opt_names[opt_i * 2],     sizeof(opt_names[0]), "opt%u.machine", opt_i);
      snprintf(opt_names[opt_i * 2 + 1], sizeof(opt_names[0]), "opt%u.display", opt_i);
      fields[n].name = opt_names[opt_i * 2];     fields[n++].value = opt_machine[opt_i];
      fields[n].name = opt_names[opt_i * 2 + 1]; fields[n++].value = opt_display[opt_i];
   }

   {
      const char *sub = NULL;
      if (s->enum_idx != MSG_UNKNOWN)
         sub = msg_hash_to_str((enum msg_hash_enums)(s->enum_idx + 1));
      if (sub && !strcmp(sub, "null"))
         sub = NULL;
      fields[n].name = "desc"; fields[n++].value = sub ? sub : "";
   }

   out = ricotta_fields_to_array(env, fields, n);
   return out;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeSystemInfo(
      JNIEnv *env, jobject obj)
{
   runloop_state_t *runloop_st = runloop_state_get_ptr();
   rcheevos_locals_t *locals;
   rc_client_t *client;
   const rc_client_game_t *game = NULL;
   char game_id[16];
   jobjectArray out;
   jclass str_cls;
   size_t i;
   const char *values[7];
   char outcome_s[8];

   (void)obj;

   if (!runloop_st)
      return NULL;

   locals = get_rcheevos_locals();
   client = locals ? locals->client : NULL;
   if (client)
      game = rc_client_get_game_info(client);

   game_id[0] = '\0';
   if (game && game->id)
      snprintf(game_id, sizeof(game_id), "%u", game->id);

   values[0] = runloop_st->current_library_name;
   values[1] = runloop_st->current_library_version;
   values[2] = path_get(RARCH_PATH_CONTENT);
   values[3] = runloop_st->name.savefile;
   values[4] = game_id;
   values[5] = (game && game->hash) ? game->hash : "";
   snprintf(outcome_s, sizeof(outcome_s), "%d", g_cheevos_outcome);
   values[6] = outcome_s;

   str_cls = (*env)->FindClass(env, "java/lang/String");
   out     = (*env)->NewObjectArray(env, 7, str_cls, NULL);
   if (!out)
      return NULL;

   for (i = 0; i < 7; i++)
   {
      jstring v = (*env)->NewStringUTF(env, values[i] ? values[i] : "");
      (*env)->SetObjectArrayElement(env, out, (jsize)i, v);
      (*env)->DeleteLocalRef(env, v);
   }
   return out;
}

JNIEXPORT jboolean JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeRaSetSetting(
      JNIEnv *env, jobject obj, jstring jkey, jstring jvalue)
{
   ricotta_cmd_entry entry = {0};
   const char *key   = (*env)->GetStringUTFChars(env, jkey, NULL);
   const char *value = (*env)->GetStringUTFChars(env, jvalue, NULL);
   size_t plen       = strlen(RICOTTA_CORE_OPT_PREFIX);
   int known         = 0;

   (void)obj;

   if (key)
      known = !strncmp(key, RICOTTA_CORE_OPT_PREFIX, plen)
            ? ricotta_core_opt_index(key + plen) >= 0
            : ricotta_ra_find(key) != NULL;

   if (!known)
   {
      if (key)
         (*env)->ReleaseStringUTFChars(env, jkey, key);
      if (value)
         (*env)->ReleaseStringUTFChars(env, jvalue, value);
      return JNI_FALSE;
   }

   entry.cmd      = RICOTTA_QCMD_RA_SET;
   entry.ra_key   = key ? strdup(key) : NULL;
   entry.ra_value = value ? strdup(value) : NULL;

   if (key)
      (*env)->ReleaseStringUTFChars(env, jkey, key);
   if (value)
      (*env)->ReleaseStringUTFChars(env, jvalue, value);

   ricotta_enqueue_entry(entry);
   return JNI_TRUE;
}

static char *ricotta_join_watch(JNIEnv *env, jobjectArray jwatch)
{
   jsize i, n = jwatch ? (*env)->GetArrayLength(env, jwatch) : 0;
   size_t len = 1;
   char *out;
   for (i = 0; i < n; i++)
   {
      jstring s = (jstring)(*env)->GetObjectArrayElement(env, jwatch, i);
      const char *c = s ? (*env)->GetStringUTFChars(env, s, NULL) : NULL;
      if (c)
      {
         len += strlen(c) + 1;
         (*env)->ReleaseStringUTFChars(env, s, c);
      }
      if (s)
         (*env)->DeleteLocalRef(env, s);
   }
   if (!(out = (char *)calloc(len, 1)))
      return NULL;
   for (i = 0; i < n; i++)
   {
      jstring s = (jstring)(*env)->GetObjectArrayElement(env, jwatch, i);
      const char *c = s ? (*env)->GetStringUTFChars(env, s, NULL) : NULL;
      if (c)
      {
         if (*out)
            strlcat(out, "\x1f", len);
         strlcat(out, c, len);
         (*env)->ReleaseStringUTFChars(env, s, c);
      }
      if (s)
         (*env)->DeleteLocalRef(env, s);
   }
   return out;
}

JNIEXPORT jboolean JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeRaApply(
      JNIEnv *env, jobject obj, jint token, jstring jkey, jstring jvalue, jobjectArray jwatch)
{
   ricotta_cmd_entry entry = {0};
   const char *key   = (*env)->GetStringUTFChars(env, jkey, NULL);
   const char *value = (*env)->GetStringUTFChars(env, jvalue, NULL);
   size_t plen       = strlen(RICOTTA_CORE_OPT_PREFIX);
   int known         = 0;

   (void)obj;

   if (key)
      known = !strncmp(key, RICOTTA_CORE_OPT_PREFIX, plen)
            ? ricotta_core_opt_index(key + plen) >= 0
            : ricotta_ra_find(key) != NULL;

   if (known)
   {
      entry.cmd      = RICOTTA_QCMD_RA_SET;
      entry.ra_key   = strdup(key);
      entry.ra_value = value ? strdup(value) : NULL;
      entry.ra_wait  = token;
      entry.ra_watch = ricotta_join_watch(env, jwatch);
   }
   if (key)
      (*env)->ReleaseStringUTFChars(env, jkey, key);
   if (value)
      (*env)->ReleaseStringUTFChars(env, jvalue, value);
   if (!known)
      return JNI_FALSE;
   return ricotta_enqueue_entry(entry) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeApplyViewport(
      JNIEnv *env, jobject obj, jint x, jint y, jint w, jint h)
{
   ricotta_cmd_entry entry = {0};
   (void)env; (void)obj;
   entry.cmd  = RICOTTA_QCMD_VIEWPORT_SET;
   entry.vp_x = x;
   entry.vp_y = y;
   entry.vp_w = w;
   entry.vp_h = h;
   ricotta_enqueue_entry(entry);
   return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeClearViewport(
      JNIEnv *env, jobject obj, jint restoreAspectIdx, jboolean restoreIntegerScale)
{
   ricotta_cmd_entry entry = {0};
   (void)env; (void)obj;
   entry.cmd              = RICOTTA_QCMD_VIEWPORT_SET;
   entry.vp_x             = restoreAspectIdx;
   entry.vp_w             = 0;
   entry.vp_h             = 0;
   entry.vp_integer_scale = restoreIntegerScale == JNI_TRUE ? 1 : 0;
   ricotta_enqueue_entry(entry);
   return JNI_TRUE;
}

JNIEXPORT jintArray JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeCoreGeometry(
      JNIEnv *env, jobject obj)
{
   video_driver_state_t *video_st = video_state_get_ptr();
   jintArray out;
   jint vals[4];
   (void)obj;

   if (!video_st || video_st->av_info.geometry.base_width == 0)
      return NULL;

   vals[0] = (jint)video_st->av_info.geometry.base_width;
   vals[1] = (jint)video_st->av_info.geometry.base_height;
   vals[2] = (jint)(video_st->av_info.geometry.aspect_ratio * 10000.0f);
   vals[3] = 10000;

   out = (*env)->NewIntArray(env, 4);
   if (!out)
      return NULL;
   (*env)->SetIntArrayRegion(env, out, 0, 4, vals);
   return out;
}

JNIEXPORT jint JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeRaAspectIndex(
      JNIEnv *env, jobject obj)
{
   settings_t *settings = config_get_ptr();
   (void)env; (void)obj;
   return settings ? (jint)settings->uints.video_aspect_ratio_idx : 22;
}

JNIEXPORT jfloat JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeRaAspectValue(
      JNIEnv *env, jobject obj)
{
   settings_t *settings = config_get_ptr();
   unsigned idx;
   (void)env; (void)obj;

   if (!settings)
      return 0.0f;
   idx = settings->uints.video_aspect_ratio_idx;
   if (idx >= ASPECT_RATIO_END)
      return 0.0f;
   return aspectratio_lut[idx].value;
}

JNIEXPORT jboolean JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeRaIntegerScale(
      JNIEnv *env, jobject obj)
{
   settings_t *settings = config_get_ptr();
   (void)env; (void)obj;
   return (settings && settings->bools.video_scale_integer) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeSetShaderPreset(
      JNIEnv *env, jobject obj, jstring jpath)
{
   ricotta_cmd_entry entry = {0};
   const char *path = jpath ? (*env)->GetStringUTFChars(env, jpath, NULL) : NULL;
   (void)obj;
   entry.cmd    = RICOTTA_QCMD_SHADER_SET;
   entry.ra_key = path ? strdup(path) : strdup("");
   if (path)
      (*env)->ReleaseStringUTFChars(env, jpath, path);
   ricotta_enqueue_entry(entry);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeRaSaveOverride(
      JNIEnv *env, jobject obj, jint scope, jstring jkeys)
{
   ricotta_cmd_entry entry = {0};
   const char *keys        = jkeys ? (*env)->GetStringUTFChars(env, jkeys, NULL) : NULL;

   (void)obj;

   entry.cmd      = RICOTTA_QCMD_RA_SAVE_OVERRIDE;
   entry.ra_scope = (int)scope;
   entry.ra_key   = keys ? strdup(keys) : NULL;

   if (keys)
      (*env)->ReleaseStringUTFChars(env, jkeys, keys);

   ricotta_enqueue_entry(entry);
}

JNIEXPORT jstring JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeGetAchievementData(
      JNIEnv *env, jobject obj)
{
   rcheevos_locals_t *locals;
   rc_client_t *client;
   rc_client_achievement_list_t *list;
   ricotta_strbuf sb = {0};
   uint32_t b, a;
   jstring result;

   (void)obj;

   locals = get_rcheevos_locals();
   client = locals ? locals->client : NULL;
   if (!client || !rc_client_has_achievements(client))
      return (*env)->NewStringUTF(env, "");

   list = rc_client_create_achievement_list(client,
         RC_CLIENT_ACHIEVEMENT_CATEGORY_CORE,
         RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_LOCK_STATE);
   if (!list)
      return (*env)->NewStringUTF(env, "");

   for (b = 0; b < list->num_buckets; b++)
   {
      const rc_client_achievement_bucket_t *bucket = &list->buckets[b];
      for (a = 0; a < bucket->num_achievements; a++)
      {
         const rc_client_achievement_t *ach = bucket->achievements[a];
         char nums[96];

         snprintf(nums, sizeof(nums), "%u|", ach->id);
         ricotta_sb_puts(&sb, nums);
         ricotta_sb_escaped(&sb, ach->title);
         ricotta_sb_putc(&sb, '|');
         ricotta_sb_escaped(&sb, ach->description);
         snprintf(nums, sizeof(nums), "|%u|%d|%u|%lld\n",
               ach->points,
               ach->unlocked ? 1 : 0,
               (unsigned)ach->state,
               (long long)ach->unlock_time);
         ricotta_sb_puts(&sb, nums);
      }
   }

   rc_client_destroy_achievement_list(list);

   result = (*env)->NewStringUTF(env, sb.buf ? sb.buf : "");
   free(sb.buf);
   return result;
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeCheatLoadFile(
      JNIEnv *env, jobject obj, jstring jpath)
{
   ricotta_cmd_entry entry = {0};
   const char *path;

   (void)obj;

   if (!jpath)
      return;

   path         = (*env)->GetStringUTFChars(env, jpath, NULL);
   entry.cmd    = RICOTTA_QCMD_CHEAT_LOAD;
   entry.ra_key = path ? strdup(path) : NULL;

   if (path)
      (*env)->ReleaseStringUTFChars(env, jpath, path);

   ricotta_enqueue_entry(entry);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeCheatToggle(
      JNIEnv *env, jobject obj, jint index)
{
   (void)env;
   (void)obj;
   ricotta_enqueue_command(RICOTTA_QCMD_CHEAT_TOGGLE, (int)index, 0);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeCheatApply(
      JNIEnv *env, jobject obj)
{
   (void)env;
   (void)obj;
   ricotta_enqueue_command(RICOTTA_QCMD_CHEAT_APPLY, 0, 0);
}

JNIEXPORT jboolean JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeCheatHardcoreActive(
      JNIEnv *env, jobject obj)
{
   (void)env;
   (void)obj;
#ifdef HAVE_CHEEVOS
   return rcheevos_hardcore_active() ? JNI_TRUE : JNI_FALSE;
#else
   return JNI_FALSE;
#endif
}

static uint8_t        *g_pending_progress      = NULL;
static size_t          g_pending_progress_size = 0;
static pthread_mutex_t g_progress_mutex        = PTHREAD_MUTEX_INITIALIZER;

static void ricotta_drop_progress_locked(void)
{
   free(g_pending_progress);
   g_pending_progress      = NULL;
   g_pending_progress_size = 0;
}

void ricotta_cheevos_forget_progress(void)
{
   pthread_mutex_lock(&g_progress_mutex);
   ricotta_drop_progress_locked();
   pthread_mutex_unlock(&g_progress_mutex);
}

void ricotta_cheevos_stash_progress(const void *buffer, size_t size)
{
   rcheevos_locals_t *locals = get_rcheevos_locals();
   const int game_loaded     = locals && rc_client_is_game_loaded(locals->client);
   uint8_t *copy             = NULL;

   if (!game_loaded && buffer && size > 0)
   {
      if ((copy = (uint8_t*)malloc(size)))
         memcpy(copy, buffer, size);
   }

   pthread_mutex_lock(&g_progress_mutex);
   ricotta_drop_progress_locked();
   g_pending_progress      = copy;
   g_pending_progress_size = copy ? size : 0;
   pthread_mutex_unlock(&g_progress_mutex);
}

void ricotta_cheevos_apply_pending_progress(void)
{
   rcheevos_locals_t *locals;
   uint8_t           *progress;
   size_t             size;

   pthread_mutex_lock(&g_progress_mutex);
   progress                = g_pending_progress;
   size                    = g_pending_progress_size;
   g_pending_progress      = NULL;
   g_pending_progress_size = 0;
   pthread_mutex_unlock(&g_progress_mutex);

   if (!progress)
      return;

   locals = get_rcheevos_locals();
   if (locals && locals->client && !rcheevos_hardcore_active())
      rc_client_deserialize_progress_sized(locals->client, progress, size);

   free(progress);
}

static volatile int g_pending_save_type = -1;
static volatile int g_pending_save_slot;
static volatile int g_save_active;

void ricotta_osd_defer_save(int type, int slot)
{
   g_pending_save_slot = slot;
   g_pending_save_type = type;
}

void ricotta_osd_flush_save(void)
{
   int type = g_pending_save_type;

   if (type < 0)
      return;
   g_pending_save_type = -1;
   ricotta_osd_event(type, g_pending_save_slot);
}

void ricotta_save_begin(void)
{
   g_save_active = 1;
}

void ricotta_save_end(void)
{
   g_save_active = 0;
}

int ricotta_save_is_active(void)
{
   return g_save_active;
}

void ricotta_osd_event(int type, int slot)
{
   /* Queued, not called: this may run on RetroArch's task thread (threaded savestates) or inside
    * retro_run on a core's own coroutine stack, and a JNI call is unsafe from either. The pump
    * raises it from the runloop thread. */
   if (!g_jvm || !g_bridge_obj || !g_on_osd_event_mid)
      return;
   if (type == RICOTTA_OSD_LOAD_STATE && g_resume_in_flight)
      return;
   {
      settings_t *settings = config_get_ptr();
      if (settings)
      {
         switch (type)
         {
            case RICOTTA_OSD_DISK_CHANGED:
               if (!settings->bools.notification_show_disk_control)
                  return;
               break;
            case RICOTTA_OSD_SCREENSHOT:
               if (!settings->bools.notification_show_screenshot)
                  return;
               break;
            default:
               break;
         }
      }
   }
   {
      ricotta_cmd_entry entry = {0};
      entry.cmd      = RICOTTA_QCMD_OSD_EVENT;
      entry.osd_type = type;
      entry.slot     = slot;
      ricotta_enqueue_entry(entry);
   }
}

void ricotta_osd_cheevos_load(int outcome, int unlocked, int total)
{
   ricotta_cmd_entry entry = {0};
   rcheevos_locals_t *locals = get_rcheevos_locals();
   rc_client_t *client       = locals ? locals->client : NULL;
   const rc_client_user_t *user = client ? rc_client_get_user_info(client) : NULL;
   const char *who = NULL;
   char payload[192];

   if (!g_bridge_obj || !g_on_cheevos_load_mid)
      return;

   if (g_cheevos_outcome == 3 && outcome == 1)
      return;

   g_cheevos_outcome = outcome;

   if (user)
      who = (user->display_name && user->display_name[0]) ? user->display_name : user->username;

   snprintf(payload, sizeof(payload), "%d|%d|%d|%d|%s",
         outcome, unlocked, total,
         rcheevos_hardcore_active() ? 1 : 0,
         who ? who : "");

   entry.cmd    = RICOTTA_QCMD_CHEEVOS_LOAD;
   entry.ra_key = strdup(payload);
   if (!entry.ra_key)
      return;
   ricotta_enqueue_entry(entry);
}

void ricotta_osd_achievement(const char *title)
{
   ricotta_cmd_entry entry = {0};
   if (!title || !title[0] || !g_bridge_obj || !g_on_osd_achievement_mid)
      return;
   entry.cmd    = RICOTTA_QCMD_OSD_ACHIEVEMENT;
   entry.ra_key = strdup(title);
   if (!entry.ra_key)
      return;
   ricotta_enqueue_entry(entry);
}

/* The achievement client calls these from whichever thread issued the request: the runloop thread
 * for an unlock, a task thread during content load. Neither may use the cached runloop JNIEnv, so
 * each call attaches and detaches its own, the way menu_close_poll_func does. */
static JNIEnv *ricotta_cheevos_attach(int *attached)
{
   JNIEnv *env = NULL;
   *attached = 0;
   if (!g_jvm)
      return NULL;
   if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK)
   {
      if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != JNI_OK)
         return NULL;
      *attached = 1;
   }
   return env;
}

int ricotta_cheevos_intercept(const char *post_data, char **out_body)
{
   JNIEnv *env;
   int attached = 0;
   jstring jpost;
   jstring jbody;
   int answered = 0;

   if (!post_data || !out_body || !g_bridge_obj || !g_on_cheevos_request_mid)
      return 0;

   env = ricotta_cheevos_attach(&attached);
   if (!env)
      return 0;

   jpost = (*env)->NewStringUTF(env, post_data);
   jbody = (jstring)(*env)->CallObjectMethod(env, g_bridge_obj, g_on_cheevos_request_mid, jpost);
   ricotta_jni_check(env, "onCheevosRequest");

   if (jbody)
   {
      const char *utf = (*env)->GetStringUTFChars(env, jbody, NULL);
      if (utf)
      {
         *out_body = strdup(utf);
         answered = (*out_body != NULL);
         (*env)->ReleaseStringUTFChars(env, jbody, utf);
      }
      (*env)->DeleteLocalRef(env, jbody);
   }
   if (jpost)
      (*env)->DeleteLocalRef(env, jpost);

   if (attached)
      (*g_jvm)->DetachCurrentThread(g_jvm);
   return answered;
}

void ricotta_cheevos_failed(const char *post_data)
{
   JNIEnv *env;
   int attached = 0;
   jstring jpost;

   if (!post_data || !g_bridge_obj || !g_on_cheevos_failed_mid)
      return;

   env = ricotta_cheevos_attach(&attached);
   if (!env)
      return;

   jpost = (*env)->NewStringUTF(env, post_data);
   (*env)->CallVoidMethod(env, g_bridge_obj, g_on_cheevos_failed_mid, jpost);
   ricotta_jni_check(env, "onCheevosFailed");

   if (jpost)
      (*env)->DeleteLocalRef(env, jpost);

   if (attached)
      (*g_jvm)->DetachCurrentThread(g_jvm);
}

/* Shows Cannoli what the server said and lets it answer instead. Returns NULL to keep the server's
 * body, or a heap buffer the caller owns and must free.
 *
 * The body is taken with its length because RetroArch's HTTP task hands back exactly the bytes that
 * arrived: net_http shrinks the receive buffer to the body length, so there is no terminator to
 * read and the copy made here is what NewStringUTF can safely be given. */
char *ricotta_cheevos_filter(const char *post_data, const char *body, size_t body_length,
      int http_status)
{
   JNIEnv *env;
   int attached = 0;
   jstring jpost;
   jstring jbody;
   jstring jreplacement;
   char *terminated;
   char *replacement = NULL;

   if (!post_data || !body || !g_bridge_obj || !g_on_cheevos_response_mid)
      return NULL;

   terminated = (char *)malloc(body_length + 1);
   if (!terminated)
      return NULL;
   memcpy(terminated, body, body_length);
   terminated[body_length] = '\0';

   env = ricotta_cheevos_attach(&attached);
   if (!env)
   {
      free(terminated);
      return NULL;
   }

   jpost = (*env)->NewStringUTF(env, post_data);
   jbody = (*env)->NewStringUTF(env, terminated);
   jreplacement = (jstring)(*env)->CallObjectMethod(env, g_bridge_obj, g_on_cheevos_response_mid,
         jpost, jbody, (jint)http_status);
   ricotta_jni_check(env, "onCheevosResponse");

   if (jreplacement)
   {
      const char *utf = (*env)->GetStringUTFChars(env, jreplacement, NULL);
      if (utf)
      {
         replacement = strdup(utf);
         (*env)->ReleaseStringUTFChars(env, jreplacement, utf);
      }
      (*env)->DeleteLocalRef(env, jreplacement);
   }
   if (jpost)
      (*env)->DeleteLocalRef(env, jpost);
   if (jbody)
      (*env)->DeleteLocalRef(env, jbody);
   free(terminated);

   if (attached)
      (*g_jvm)->DetachCurrentThread(g_jvm);
   return replacement;
}
