/*
 * ricotta_bridge.c - JNI bridge for RicottaArch IGM (In-Game Menu)
 *
 * Provides native methods for dev.cannoli.ricotta.EmbeddedRetroArchBridge
 * that dispatch RetroArch command events and query emulator state.
 */

#include <jni.h>
#include "ricotta_osd.h"
#include <pthread.h>
#include <unistd.h>
#include <time.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <streams/file_stream.h>

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

/* Cached JVM and bridge object refs for callbacks */
static JavaVM *g_jvm           = NULL;
static jobject g_bridge_obj    = NULL;
static jmethodID g_on_menu_closed_mid = NULL;
static jmethodID g_on_debug_key_mid = NULL;

/* Flag set by Java side when IGM overlay is visible */
static volatile int g_igm_visible = 0;

/* Cannoli IGM trigger keycodes, set from Kotlin via nativeSetIgmTriggerKeycodes. */
#define RICOTTA_MAX_TRIGGER_KEYS 8
static volatile int g_igm_trigger_keycodes[RICOTTA_MAX_TRIGGER_KEYS];
static volatile int g_igm_trigger_keycount = 0;

/* Ports whose pad the launcher's input DB marks built in, from the launch intent. RetroArch has
 * no notion of built in, so it announces the handheld's own controls on every launch. */
#define RICOTTA_MAX_PORTS 16
static volatile int g_builtin_ports[RICOTTA_MAX_PORTS];
static volatile int g_builtin_port_count = 0;
static jmethodID g_on_igm_trigger_mid = NULL;
static jmethodID g_on_osd_event_mid = NULL;
static jmethodID g_on_osd_achievement_mid = NULL;
static jmethodID g_on_ra_applied_mid = NULL;
static jmethodID g_on_cheats_loaded_mid = NULL;

/* Cached JNIEnv for the native runloop thread (attached once, never detached) */
static JNIEnv *g_native_env = NULL;

/* Cannoli launch context, set from Kotlin via nativeSetCannoliContext before any override save.
 * The IGM save-scope rows target Cannoli's own override tiers, which are keyed by these. */
static char g_cannoli_root[PATH_MAX_LENGTH];
static char g_platform_tag[PATH_MAX_LENGTH];
static char g_rom_base_name[PATH_MAX_LENGTH];
static char g_core_id[PATH_MAX_LENGTH];

/* Timestamp of when the IGM was opened, to debounce the menu button */
static volatile long long g_igm_open_time = 0;

/* Set by the input poll, raised by the pump. See ricotta_bridge_intercept_key. */
static volatile int g_igm_trigger_pending = 0;

/* Menu close polling */
#define MENU_POLL_INTERVAL_MS 50
#define MENU_OPEN_TIMEOUT_MS  2000
static pthread_t g_menu_poll_thread;
static volatile int g_menu_poll_active = 0;

/* Pending command queue. command_event() is NOT safe to call from the JNI/main
 * thread while retro_run executes on the runloop thread, so the JNI methods enqueue
 * commands here and ricotta_bridge_poll_commands() runs them on the runloop thread. */
#define RICOTTA_CMD_QUEUE_SIZE 32
/* A setting crosses as a flat name/value array rather than a fixed positional one. Adding a field
 * cannot shift another, and the two describers cannot drift apart by allocating different counts,
 * which is how core options ended up with no machine value. */
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
} ricotta_cmd_entry;
static ricotta_cmd_entry g_cmd_queue[RICOTTA_CMD_QUEUE_SIZE];
static int g_cmd_head = 0;
static int g_cmd_tail = 0;
static pthread_mutex_t g_cmd_mutex = PTHREAD_MUTEX_INITIALIZER;

static void ricotta_enqueue_entry(ricotta_cmd_entry entry)
{
   pthread_mutex_lock(&g_cmd_mutex);
   {
      int next = (g_cmd_tail + 1) % RICOTTA_CMD_QUEUE_SIZE;
      if (next != g_cmd_head) /* drop if full rather than overwrite */
      {
         g_cmd_queue[g_cmd_tail] = entry;
         g_cmd_tail = next;
      }
      else
      {
         /* A dropped cheat load emits no snapshot: the upcall only runs on the runloop thread, so
          * nothing can report the drop from here. The screen keeps its old list and reloads on the
          * next entry. */
         free(entry.ra_key);
         free(entry.ra_value);
      }
   }
   pthread_mutex_unlock(&g_cmd_mutex);
}

static void ricotta_enqueue_command(int cmd, int slot, int has_slot)
{
   ricotta_cmd_entry entry = {0};
   entry.cmd      = cmd;
   entry.slot     = slot;
   entry.has_slot = has_slot;
   ricotta_enqueue_entry(entry);
}

/* Cheat descriptions and codes have no fixed bound (CHEAT_CODE_SCRATCH_SIZE is 16 KB), so the
 * snapshot is built into a growable buffer rather than the fixed line buffer the achievement
 * snapshot uses. */
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

/* Backslash, pipe and newline are escaped so a desc or code containing them cannot forge a field
 * or a row boundary. The Kotlin decoder reverses exactly these three and splits rows on \n alone,
 * so a lone \r is deliberately left as a literal byte inside its field.
 *
 * These bytes come from a user-supplied .cht and community packs are not always UTF-8, so anything
 * that is not valid modified UTF-8 is replaced with '?' as it is copied: NewStringUTF aborts the
 * process under CheckJNI on a bad sequence. Four-byte sequences are invalid here too, modified
 * UTF-8 spells those as a surrogate pair of three-byte ones. */
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

      /* A terminator fails the continuation test and breaks here, so a string that ends
       * mid-sequence is never read past. */
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
         /* Resync one byte at a time so the ASCII after a bad sequence still survives. */
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

static void ricotta_ra_apply(const char *key, const char *value);
static void ricotta_ra_save_override(int scope, const char *keys);
static JNIEnv *ricotta_runloop_env(void);

/* A pending exception is sticky: once a callback into Kotlin throws, every later JNI call on that
 * thread aborts the process under CheckJNI, so the crash lands in whatever unrelated code called
 * into Java next rather than at the throw. Clear it at our own boundary and name the site. */
static int ricotta_jni_check(JNIEnv *env, const char *where)
{
   if (!env || !(*env)->ExceptionCheck(env))
      return 0;
   RLOG("JNI exception pending at %s", where);
   (*env)->ExceptionDescribe(env);
   (*env)->ExceptionClear(env);
   return 1;
}

/* RETRO-handler cheats need a system RAM mapping. Same two checks
 * cheat_manager_initialize_memory makes, without its allocations or its failure toast. */
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

/* One line per loaded cheat, "desc|code|state|supported", the nativeGetAchievementData shape.
 * Sent as an upcall rather than returned from a getter because the load that produces it is
 * queued: a synchronous read from the JNI thread would race the runloop and see the old list. */
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

/* The four viewport bias floats the RICOTTA_QCMD_VIEWPORT_SET apply branch forces to zero below
 * are not menu settings under RaSettingsHost, so nothing on the Kotlin side can shadow and restore
 * them the way it does aspect_ratio_index and video_scale_integer. Stash them here across the
 * takeover instead and hand them back verbatim when the viewport clears. */
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
void ricotta_bridge_poll_commands(void)
{
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
            ricotta_ra_apply(entry.ra_key, entry.ra_value);
         free(entry.ra_key);
         free(entry.ra_value);
         continue;
      }
      if (entry.cmd == RICOTTA_QCMD_SHADER_SET)
      {
         /* Through the menu's own loader rather than straight to the render chain. Both apply the
          * preset, but this one also reads it into the shader the chain screen edits, and those
          * are different objects: applying directly leaves All Settings looking at an empty chain,
          * so appending combines with nothing and leaving recompiles the stale one over the top.
          * An empty path clears the passes, which is how the chain is emptied. */
         const char *path = entry.ra_key ? entry.ra_key : "";
         menu_shader_manager_set_preset(menu_shader_get(),
               video_shader_parse_type(path), path, true);
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
               /* Cannoli's own integer mode already yields an integer scaled rect, so
                * RetroArch's would re-quantise one that is already correct. */
               configuration_set_bool(settings, settings->bools.video_scale_integer, false);
               /* Stash the pre-takeover bias values the first time so the clear branch can hand
                * them back; a later apply while still active must not re-stash the zeros this
                * already wrote. */
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
               /* With bias zero the padding term RetroArch adds to custom_vp vanishes, so
                * the rect below resolves to an absolute top-left rect under both drivers' y conventions. */
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
            /* A user's file may carry enable = "true"; the disabled-start rule wins. */
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
         /* A toggle queued against a longer list drains after the load that replaced it, and
          * RetroArch range-checks nothing here: its own caller is the synchronous menu. */
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

/* All Settings defers to RetroArch for structure: which rows exist right now, in what order, under
 * what name, and what a row leads to. RetroArch decides all of that in its displaylist builders,
 * including the conditions that hide a row (video_aspect_ratio only appears when the aspect index
 * is Config, the integer-scaling rows only when integer scaling is on). Rebuilding one here and
 * reading it back means those conditions are RetroArch's rather than a copy of them that rots.
 *
 * Values are deliberately NOT read through this. Both menu modes read and write through
 * ricotta_ra_find, so there is one write path, one changed-key set, and one save tier. */
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

/* Builds `label`'s screen into a throwaway list. Caller frees with file_list_free. */
static file_list_t *ricotta_build_screen(const char *label)
{
   menu_displaylist_info_t info;
   file_list_t *list;
   int dl = ricotta_screen_dl(label);

   if (dl < 0)
      return NULL;
   if (!(list = (file_list_t*)calloc(1, sizeof(*list))))
      return NULL;
   /* Entries carry a menu_file_list_cbs_t that owns further allocations, so the list needs the
    * same destructor the menu's own lists use or freeing it leaks every row. */
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

/* A small-range numeric setting that carries a label representation (aspect
 * ratio, rotation, swap interval). Surfaced as an ENUM of labels rather than a
 * raw integer; matches how RetroArch's own dropdown builder enumerates options. */
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

/* The machine value, never the display text. A combobox renders through
 * actions->repr, which for aspect_ratio_index returns a translated label out of
 * aspectratio_lut, so anything comparing values has to read this instead: labels differ by
 * locale, can repeat, and enumerating them writes the live setting once per option. */
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

/* Core options live in a separate namespace from RetroArch's own settings and are keyed by the
 * core, so they carry this prefix to keep the two apart on one get/set path. */
#define RICOTTA_CORE_OPT_PREFIX "core::"

static core_option_manager_t *ricotta_core_options(void)
{
   runloop_state_t *runloop_st = runloop_state_get_ptr();
   return runloop_st ? runloop_st->core_options : NULL;
}

/* Index of the core option with this key, or -1. */
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

/* The machine value of a core option, which is what an .opt file stores. val_labels carries the
 * translated display text and must never reach disk. */
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
      /* Machine values only. Labels are translated and can repeat, and every writer sends the
       * machine value, so matching one would only ever hide a caller that did not. */
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

/* A core option is always a labelled value list, which is the ENUM case. Its machine values and its
 * display labels are separate lists and must stay that way: the labels are translated, and only the
 * machine value is what the core and the .opt file understand. */
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

   /* "optionKey|categoryKey|categoryLabel", the key already carrying the prefix that marks it a
    * core option rather than a RetroArch setting. Emitting it here rather than having Kotlin
    * prepend it keeps the prefix defined once, next to the five sites that strip it back off.
    * Cores that declare no categories leave the last two empty and the caller shows one flat list. */
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

static void ricotta_ra_apply(const char *key, const char *value)
{
   settings_t *settings;
   rarch_setting_t *s;

   if (!strncmp(key, RICOTTA_CORE_OPT_PREFIX, strlen(RICOTTA_CORE_OPT_PREFIX)))
   {
      ricotta_core_opt_apply(key + strlen(RICOTTA_CORE_OPT_PREFIX), value);
      return;
   }

   s = ricotta_ra_find(key);
   if (!s)
      return;
   if (ricotta_ra_is_combobox(s))
   {
      /* A combobox is a numeric setting rendered with labels, so a write is the number. Labels are
       * translated and can repeat, and matching one meant setting the live value once per candidate
       * while searching. */
      char *end = NULL;
      long  n   = strtol(value, &end, 10);
      if (!end || end == value || *end != '\0'
            || n < (long)s->min || n > (long)s->max)
         return;
      ricotta_ra_set_int(s, n);
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
         /* A path is a string target like the two above. Falling to default dropped the write and
          * returned before the change handler, so setting input_overlay did nothing at all and the
          * overlay that handler then reloaded was whatever was there before. */
         case ST_PATH:
         case ST_DIR:
            strlcpy(s->value.target.string, value, s->size);
            break;
         default:
            return;
      }
   }
   settings         = config_get_ptr();
   settings->flags |= SETTINGS_FLG_MODIFIED;
   if (s->actions->change)
      s->actions->change(s);
   if (s->cmd_trigger_idx
         && !(s->flags & SD_FLAG_CMD_TRIGGER_EVENT_TRIGGERED))
      command_event(s->cmd_trigger_idx, NULL);

   /* Confirm with the authoritative value; handlers may clamp or rewrite it. */
   {
      char buf[512];
      if (s->actions->read)
         s->actions->read(s);
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
   }
}

/* A setting is looked up by its menu name, but config_file reads and writes its config key, and for
 * 68 settings the two differ. Writing an override under the menu name produces a key RetroArch
 * never reads back, so the setting silently reverts on the next launch: audio_output_rate is stored
 * as audio_out_rate, gpu_index as vulkan_gpu_index. Only the file write needs this; a live change
 * still goes through menu_setting_find, which matches the menu name. */
static const char *ricotta_config_key(const char *menu_key)
{
   size_t i;
   for (i = 0; i < sizeof(ricotta_key_aliases) / sizeof(ricotta_key_aliases[0]); i++)
      if (!strcmp(ricotta_key_aliases[i].menu, menu_key))
         return ricotta_key_aliases[i].config;
   return menu_key;
}

/* Writes one live setting into conf with the same typed setters and raw values
 * config_save_overrides uses, so a combobox saves its index rather than its label. */
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

/* An empty tier file is worse than none: the launch composer would read it, and a stale one left
 * behind after the last key was cleared would keep applying. */
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

/* Saves an override .cfg holding only the newline-delimited keys the IGM changed this session,
 * at their live values, merged over any existing override so untouched keys survive. Replaces
 * config_save_overrides, which diffed the whole live config against Cannoli's minimal launch
 * config and wrote hundreds of unrelated keys. Runs on the runloop thread. scope: 1 = game,
 * else system. Keys with no live RA setting (e.g. core options) are silently skipped.
 *
 * The target is Cannoli's own override tier, not RetroArch's library-keyed
 * Config/RetroArch/<library>/ location, so the launch composer reads these back. Both tiers are
 * keyed by core, because what is worth overriding is mostly what differs between cores:
 *   game   -> <root>/Config/Overrides/Games/<tag>/<base>/<core>.cfg
 *   system -> <root>/Config/Overrides/Systems/<tag>/<core>.cfg */
static void ricotta_ra_save_override(int scope, const char *keys)
{
   char override_path[PATH_MAX_LENGTH];
   char opt_path[PATH_MAX_LENGTH];
   char base_dir[PATH_MAX_LENGTH];
   config_file_t *conf;
   config_file_t *opt_conf;
   const char *p;

   /* Non-zero selects the game tier over the system one. Values Cannoli owns rather than
    * RetroArch are written by the Kotlin side straight into its own tier, not through here. */
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

   /* config_save_overrides made this directory; config_file_write's fopen will not. */
   fill_pathname_basedir(base_dir, override_path, sizeof(base_dir));
   if (*base_dir && !path_is_directory(base_dir))
      path_mkdir(base_dir);

   /* Core options are not RetroArch settings and never resolve through menu_setting_find, so they
    * go to a sibling .opt that the launch composer feeds to RetroArch through core_options_path.
    * Same directory, same core key, same two scopes as the .cfg beside it. */
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
         /* A setting whose enum has no _STR define falls back to its US display label, so its name
          * arrives here as something like "HDR Mode". No RetroArch config key contains whitespace,
          * so writing one produces a line RetroArch cannot read and that merging then preserves
          * forever. Drop it, and clear any an older build already wrote. */
         if (strpbrk(key, " \t"))
         {
            config_unset(conf, key);
            s = NULL;
         }
         if (s)
         {
            const char *ck = ricotta_config_key(key);
            /* An override written before the alias fix holds the menu name, which RetroArch
             * ignores. Merging would keep it forever, so it goes when the right key is written. */
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

   /* Attach this thread to the JVM */
   if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK)
   {
      if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != JNI_OK)
         return NULL;
      attached = 1;
   }

   /* The toggle was enqueued, not run, so wait for the menu to come up before watching for it to
    * close. */
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
         /* Menu has closed - notify Kotlin */
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
   /* Pending here and none of our callbacks ran: it came from RetroArch's own JNI on this thread. */
   ricotta_jni_check(g_native_env, "runloop env, before our call");
   return g_native_env;
}

/*
 * Called from android_input.c when a key event arrives.
 * Returns 1 if the event should be consumed (IGM wants it).
 */
int ricotta_bridge_intercept_key(int keycode, int action)
{
   /* While the IGM is visible, consume all gamepad input (handled by the Dialog). */
   if (g_igm_visible)
      return 1;

   /* Open the Cannoli IGM on any configured trigger key's down event. */
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
         /* Record only. A core that runs on its own coroutine stack enters this poll from inside
          * retro_run, where a JNI call throws a spurious StackOverflowError; the pump raises it
          * from runloop_iterate instead, which is always on this thread's own stack. */
         if (action == 0) /* AKEY_EVENT_ACTION_DOWN */
            g_igm_trigger_pending = 1;
         return 1; /* consume down and up so the game never sees the trigger key */
      }
   }

   return 0;
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeSetIGMVisible(
      JNIEnv *env, jobject obj, jboolean visible)
{
   (void)env;
   (void)obj;
   g_igm_visible = visible ? 1 : 0;
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
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeSetBuiltinPorts(
      JNIEnv *env, jobject obj, jintArray ports)
{
   jsize n;
   jint *elems;
   jsize i;

   (void)obj;

   g_builtin_port_count = 0;
   if (!ports)
      return;

   n = (*env)->GetArrayLength(env, ports);
   if (n > RICOTTA_MAX_PORTS)
      n = RICOTTA_MAX_PORTS;

   elems = (*env)->GetIntArrayElements(env, ports, NULL);
   if (!elems)
      return;

   for (i = 0; i < n; i++)
      g_builtin_ports[i] = (int)elems[i];

   (*env)->ReleaseIntArrayElements(env, ports, elems, JNI_ABORT);
   g_builtin_port_count = (int)n; /* set count last so the reader never sees partial state */
}

int ricotta_port_is_builtin(int port)
{
   int i;
   int n = g_builtin_port_count;

   for (i = 0; i < n; i++)
      if (g_builtin_ports[i] == port)
         return 1;
   return 0;
}

/* One RetroArch settings screen, as "key\x1fname\x1fisMenu" per row. An empty label is the root.
 * A row is a submenu when its key names another screen in the generated table. */
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

   /* Clean up any previous global ref */
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
   g_on_ra_applied_mid = (*env)->GetMethodID(env, cls, "onRaSettingApplied",
         "(Ljava/lang/String;Ljava/lang/String;)V");
   g_on_osd_event_mid = (*env)->GetMethodID(env, cls, "onOsdEvent", "(II)V");
   g_on_osd_achievement_mid = (*env)->GetMethodID(env, cls, "onOsdAchievement", "(Ljava/lang/String;)V");
   g_on_cheats_loaded_mid = (*env)->GetMethodID(env, cls, "onCheatsLoaded", "(Ljava/lang/String;)V");
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
   ricotta_enqueue_command(CMD_EVENT_RESET, 0, 0);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeQuit(
      JNIEnv *env, jobject obj)
{
   (void)env;
   (void)obj;
   ricotta_enqueue_command(CMD_EVENT_QUIT, 0, 0);
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
   ricotta_enqueue_command(CMD_EVENT_UNPAUSE, 0, 0);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeMenuToggle(
      JNIEnv *env, jobject obj)
{
   (void)env;
   (void)obj;

   ricotta_enqueue_command(CMD_EVENT_MENU_TOGGLE, 0, 0);

   /* Start polling for menu close */
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
   /* Copied because the fields below reference it and the JNI string is released before they are
    * built. */
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
      /* A pipe separated list of machine values, with no separate labels to show. */
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

   /* RetroArch's own description. MENU_LABEL() declares LABEL, SUBLABEL and LABEL_VALUE
    * consecutively and enum_idx is the LABEL, so the sublabel sits at enum_idx + 1. Two guards:
    * a setting added with dont_use_enum_idx has MSG_UNKNOWN and reading past it would return an
    * unrelated string, and msg_hash_to_str yields the literal "null" for an enum with no entry in
    * any language, including the English fallback. */
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

/* Values only, in a fixed order: core name then core version. The labels are localized on the
 * Kotlin side, so nothing user-visible is spelled out here. */
JNIEXPORT jobjectArray JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeSystemInfo(
      JNIEnv *env, jobject obj)
{
   runloop_state_t *runloop_st = runloop_state_get_ptr();
   jobjectArray out;
   jclass str_cls;

   (void)obj;

   if (!runloop_st)
      return NULL;

   str_cls = (*env)->FindClass(env, "java/lang/String");
   out     = (*env)->NewObjectArray(env, 2, str_cls, NULL);
   if (!out)
      return NULL;

   (*env)->SetObjectArrayElement(env, out, 0,
         (*env)->NewStringUTF(env, runloop_st->current_library_name));
   (*env)->SetObjectArrayElement(env, out, 1,
         (*env)->NewStringUTF(env, runloop_st->current_library_version));
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

   /* The apply runs on the runloop, so whether it succeeds is not knowable here. Whether the key
    * exists at all is, and that is the half worth reporting: a key that resolves to nothing was
    * still recorded as a change and then silently dropped at save time, which is how a write that
    * never landed stayed invisible until an override came out empty. */
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
   /* aspect_ratio is a float; send it as a rational so the Kotlin side does one divide. */
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

/* Edits the menu shader in place, on the thread that asked.
 *
 * These touch nothing but the struct, and the struct belongs to the menu rather than to the render
 * chain: nothing is visible until the chain is compiled on the way out. Queueing them meant the
 * write landed on the runloop while the read that redrew the list happened immediately, so removing
 * a pass redrew the chain it had before, and a second press acted on a list that was already wrong.
 * Anything that reaches the video driver stays queued.
 */
/* Loads a preset into the chain without compiling it.
 *
 * menu_shader_manager_set_preset applies as well as loads, and applying reaches the video driver,
 * which belongs to the runloop. Queueing it meant the chain root redrew from the shader RetroArch
 * had before the queue ran, so a loaded preset only appeared on the next visit. With apply left
 * off, the load is pure struct work and can happen here, and the chain is compiled on the way out
 * of the tree like every other edit.
 */
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

/* Snapshot the live rc_client achievement list as a delimited string:
 * one line per achievement, "id|title|description|points|unlocked|state|unlock_time".
 * The IGM pauses emulation while shown, so reading the client here is safe. */
JNIEXPORT jstring JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeGetAchievementData(
      JNIEnv *env, jobject obj)
{
   rcheevos_locals_t *locals;
   rc_client_t *client;
   rc_client_achievement_list_t *list;
   char *out      = NULL;
   size_t out_len = 0;
   size_t out_cap = 0;
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
         char line[768];
         int n = snprintf(line, sizeof(line),
               "%u|%s|%s|%u|%d|%u|%lld\n",
               ach->id,
               ach->title ? ach->title : "",
               ach->description ? ach->description : "",
               ach->points,
               ach->unlocked ? 1 : 0,
               (unsigned)ach->state,
               (long long)ach->unlock_time);
         if (n < 0)
            continue;
         if (out_len + (size_t)n + 1 > out_cap)
         {
            size_t new_cap = (out_cap ? out_cap * 2 : 4096);
            char  *grown;
            while (new_cap < out_len + (size_t)n + 1)
               new_cap *= 2;
            grown = (char *)realloc(out, new_cap);
            if (!grown)
               break;
            out     = grown;
            out_cap = new_cap;
         }
         memcpy(out + out_len, line, (size_t)n);
         out_len += (size_t)n;
         out[out_len] = '\0';
      }
   }

   rc_client_destroy_achievement_list(list);

   result = (*env)->NewStringUTF(env, out ? out : "");
   free(out);
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

/* Called from RetroArch source sites (HAVE_RICOTTA_OSD) when Cannoli owns an
 * event, with structured data. type: 0 save, 1 load, 4 undo-save. slot: RetroArch
 * state_slot (< 0 = auto). */
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
   /* RA-key-backed OSD toggles gate here against the live setting the IGM writes,
    * so a muted event costs no JNI call. Reset and the save events gate on the
    * Kotlin side instead. */
   {
      settings_t *settings = config_get_ptr();
      if (settings)
      {
         switch (type)
         {
            /* Deliberately not gated here. The save events tell Cannoli the slot on
             * disk changed, and the menu reads the new thumbnail when it hears; a
             * notification preference must not decide whether that read happens.
             * Kotlin gates the message instead. */
            case RICOTTA_OSD_DISK_CHANGED:
               if (!settings->bools.notification_show_disk_control)
                  return;
               break;
            case RICOTTA_OSD_SCREENSHOT:
               if (!settings->bools.notification_show_screenshot)
                  return;
               break;
            case RICOTTA_OSD_CONTROLLER_PORT:
               if (!settings->bools.notification_show_autoconfig)
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

/* Called from cheevos.c (HAVE_RICOTTA_OSD) on an achievement unlock. */
void ricotta_osd_achievement(const char *title)
{
   ricotta_cmd_entry entry = {0};
   /* Unlocks are checked inside retro_run, so this runs on the core's stack. Queue it. */
   if (!title || !title[0] || !g_bridge_obj || !g_on_osd_achievement_mid)
      return;
   entry.cmd    = RICOTTA_QCMD_OSD_ACHIEVEMENT;
   entry.ra_key = strdup(title);
   if (!entry.ra_key)
      return;
   ricotta_enqueue_entry(entry);
}
