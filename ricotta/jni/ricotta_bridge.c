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

static long long get_time_ms(void)
{
   struct timespec ts;
   clock_gettime(CLOCK_MONOTONIC, &ts);
   return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

#define RTAG "RicottaBridge"
#define RLOG(...) __android_log_print(ANDROID_LOG_DEBUG, RTAG, __VA_ARGS__)

#include "../../../../retroarch.h"
#include "../../../../command.h"
#include "../../../../configuration.h"
#include "../../../../menu/menu_driver.h"
#include "../../../../menu/menu_defines.h"
#include "../../../../runloop.h"
#include "../../../../setting_list.h"
#include "../../../../menu/menu_setting.h"
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
static jmethodID g_on_igm_trigger_mid = NULL;
static jmethodID g_on_osd_event_mid = NULL;
static jmethodID g_on_osd_achievement_mid = NULL;
static jmethodID g_on_ra_applied_mid = NULL;
static jmethodID g_on_cheats_loaded_mid = NULL;

/* Cached JNIEnv for the native runloop thread (attached once, never detached) */
static JNIEnv *g_native_env = NULL;

/* Timestamp of when the IGM was opened, to debounce the menu button */
static volatile long long g_igm_open_time = 0;

/* Menu close polling */
#define MENU_POLL_INTERVAL_MS 50
#define MENU_OPEN_TIMEOUT_MS  2000
static pthread_t g_menu_poll_thread;
static volatile int g_menu_poll_active = 0;

/* Pending command queue. command_event() is NOT safe to call from the JNI/main
 * thread while retro_run executes on the runloop thread, so the JNI methods enqueue
 * commands here and ricotta_bridge_poll_commands() runs them on the runloop thread. */
#define RICOTTA_CMD_QUEUE_SIZE 32
#define RICOTTA_QCMD_RA_SET           -1
#define RICOTTA_QCMD_RA_SAVE_OVERRIDE -2
#define RICOTTA_QCMD_DISK_SET         -3
#define RICOTTA_QCMD_CHEAT_LOAD       -4
#define RICOTTA_QCMD_CHEAT_TOGGLE     -5
#define RICOTTA_QCMD_CHEAT_APPLY      -6
typedef struct
{
   int   cmd;
   int   slot;
   int   has_slot;
   char *ra_key;
   char *ra_value;
   int   ra_scope;
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
 * or a row boundary. The Kotlin decoder reverses exactly these three. */
static void ricotta_sb_escaped(ricotta_strbuf *sb, const char *s)
{
   if (!s)
      return;
   for (; *s; s++)
   {
      if (*s == '\\' || *s == '|' || *s == '\n')
      {
         ricotta_sb_putc(sb, '\\');
         ricotta_sb_putc(sb, *s == '\n' ? 'n' : *s);
      }
      else
         ricotta_sb_putc(sb, *s);
   }
}

static void ricotta_ra_apply(const char *key, const char *value);
static JNIEnv *ricotta_runloop_env(void);

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
   (*env)->DeleteLocalRef(env, payload);
}

/* Called from runloop.c on the runloop thread, once per iteration. */
void ricotta_bridge_poll_commands(void)
{
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
         runloop_state_t *runloop_st = runloop_state_get_ptr();
         config_save_overrides(
               entry.ra_scope == 1 ? OVERRIDE_GAME : OVERRIDE_CONTENT_DIR,
               &runloop_st->system, false, NULL);
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
         cheat_manager_toggle_index(true,
               settings ? settings->bools.notification_show_cheats_applied : false,
               (unsigned)entry.slot);
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

/* The menu driver creates menu_st->entries.list_settings lazily; with the
 * Cannoli IGM the RA menu may never open, so keep a private fallback list. */
static rarch_setting_t *g_ra_fallback_list = NULL;

static rarch_setting_t *ricotta_ra_find(const char *key)
{
   rarch_setting_t *s = menu_setting_find(key);
   if (s)
      return s;
   if (!g_ra_fallback_list)
      g_ra_fallback_list = menu_setting_new();
   for (s = g_ra_fallback_list; s && s->type != ST_NONE; s++)
   {
      if (s->type <= ST_GROUP && s->name && !strcmp(s->name, key))
      {
         if (!s->short_description || !*s->short_description)
            return NULL;
         if (s->read_handler)
            s->read_handler(s);
         return s;
      }
   }
   return NULL;
}

/* A small-range numeric setting that carries a label representation (aspect
 * ratio, rotation, swap interval). Surfaced as an ENUM of labels rather than a
 * raw integer; matches how RetroArch's own dropdown builder enumerates options. */
static int ricotta_ra_is_combobox(rarch_setting_t *s)
{
   float step, span;
   if (!s->get_string_representation)
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

static int ricotta_ra_format_value(rarch_setting_t *s, char *buf, size_t len)
{
   buf[0] = '\0';
   if (ricotta_ra_is_combobox(s))
   {
      s->get_string_representation(s, buf, len);
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

static void ricotta_core_opt_apply(const char *key, const char *value)
{
   size_t v;
   core_option_manager_t *opt = ricotta_core_options();
   long idx = ricotta_core_opt_index(key);
   if (!opt || idx < 0)
      return;
   {
      struct core_option *o = &opt->opts[idx];
      if (!o->val_labels)
         return;
      for (v = 0; v < o->val_labels->size; v++)
      {
         if (!strcmp(o->val_labels->elems[v].data, value))
         {
            core_option_manager_set_val(opt, (size_t)idx, v, true);
            return;
         }
      }
   }
}

/* Same eight-element layout nativeRaGetSetting returns, so one decoder serves both. A core option
 * is always a labelled value list, which is the ENUM case. */
static jobjectArray ricotta_core_opt_describe(JNIEnv *env, const char *key)
{
   size_t v;
   char opts_buf[1024];
   jobjectArray out;
   jclass str_cls;
   core_option_manager_t *opt = ricotta_core_options();
   long idx = ricotta_core_opt_index(key);
   struct core_option *o;

   if (!opt || idx < 0)
      return NULL;

   o           = &opt->opts[idx];
   opts_buf[0] = '\0';
   if (o->val_labels)
   {
      for (v = 0; v < o->val_labels->size; v++)
      {
         if (v)
            strlcat(opts_buf, "|", sizeof(opts_buf));
         strlcat(opts_buf, o->val_labels->elems[v].data, sizeof(opts_buf));
      }
   }

   str_cls = (*env)->FindClass(env, "java/lang/String");
   out     = (*env)->NewObjectArray(env, 8, str_cls, NULL);
   if (!out)
      return NULL;

   (*env)->SetObjectArrayElement(env, out, 0, (*env)->NewStringUTF(env,
         core_option_manager_get_desc(opt, (size_t)idx, true)));
   (*env)->SetObjectArrayElement(env, out, 1, (*env)->NewStringUTF(env, "ENUM"));
   (*env)->SetObjectArrayElement(env, out, 2, (*env)->NewStringUTF(env,
         core_option_manager_get_val_label(opt, (size_t)idx)));
   (*env)->SetObjectArrayElement(env, out, 3, (*env)->NewStringUTF(env, ""));
   (*env)->SetObjectArrayElement(env, out, 4, (*env)->NewStringUTF(env, ""));
   (*env)->SetObjectArrayElement(env, out, 5, (*env)->NewStringUTF(env, ""));
   (*env)->SetObjectArrayElement(env, out, 6, (*env)->NewStringUTF(env, opts_buf));
   (*env)->SetObjectArrayElement(env, out, 7, (*env)->NewStringUTF(env, "0"));
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

   /* "optionKey|categoryKey|categoryLabel". Cores that declare no categories leave the last two
    * empty and the caller shows one flat list. */
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
      snprintf(entry, sizeof(entry), "%s|%s|%s", opt->opts[i].key, cat_key, cat_desc);
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
      float step    = s->step > 0.0f ? s->step : 1.0f;
      long  orig    = ricotta_ra_get_int(s);
      int   matched = 0;
      char  lbl[256];
      float i;
      for (i = s->min; i <= s->max; i += step)
      {
         ricotta_ra_set_int(s, (long)i);
         s->get_string_representation(s, lbl, sizeof(lbl));
         if (!strcmp(lbl, value))
         {
            matched = 1;
            break;
         }
      }
      if (!matched)
      {
         ricotta_ra_set_int(s, orig);
         return;
      }
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
            strlcpy(s->value.target.string, value, s->size);
            break;
         default:
            return;
      }
   }
   settings         = config_get_ptr();
   settings->flags |= SETTINGS_FLG_MODIFIED;
   if (s->change_handler)
      s->change_handler(s);
   if (s->cmd_trigger_idx
         && !(s->flags & SD_FLAG_CMD_TRIGGER_EVENT_TRIGGERED))
      command_event(s->cmd_trigger_idx, NULL);

   /* Confirm with the authoritative value; handlers may clamp or rewrite it. */
   {
      char buf[512];
      if (s->read_handler)
         s->read_handler(s);
      if (ricotta_ra_format_value(s, buf, sizeof(buf)))
      {
         JNIEnv *env = ricotta_runloop_env();
         if (env && g_bridge_obj && g_on_ra_applied_mid)
         {
            jstring jk = (*env)->NewStringUTF(env, key);
            jstring jv = (*env)->NewStringUTF(env, buf);
            (*env)->CallVoidMethod(env, g_bridge_obj, g_on_ra_applied_mid, jk, jv);
            (*env)->DeleteLocalRef(env, jk);
            (*env)->DeleteLocalRef(env, jv);
         }
      }
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
         if (action == 0) /* AKEY_EVENT_ACTION_DOWN */
         {
            JNIEnv *env = ricotta_runloop_env();
            if (env && g_bridge_obj && g_on_igm_trigger_mid)
               (*env)->CallVoidMethod(env, g_bridge_obj, g_on_igm_trigger_mid);
         }
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
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeInit(
      JNIEnv *env, jobject obj)
{
   jclass cls;

   (*env)->GetJavaVM(env, &g_jvm);

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
   rarch_setting_t *s;
   const char *type_str;
   char value_buf[512];
   char min_buf[32], max_buf[32], step_buf[32];
   char opts_buf[1024];
   jobjectArray out;
   jclass str_cls;

   (void)obj;

   key = (*env)->GetStringUTFChars(env, jkey, NULL);

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

   opts_buf[0] = '\0';
   if (ricotta_ra_is_combobox(s))
   {
      float step  = s->step > 0.0f ? s->step : 1.0f;
      long  orig  = ricotta_ra_get_int(s);
      int   first = 1;
      char  lbl[256];
      float i;
      for (i = s->min; i <= s->max; i += step)
      {
         ricotta_ra_set_int(s, (long)i);
         s->get_string_representation(s, lbl, sizeof(lbl));
         if (!first)
            strlcat(opts_buf, "|", sizeof(opts_buf));
         strlcat(opts_buf, lbl, sizeof(opts_buf));
         first = 0;
      }
      ricotta_ra_set_int(s, orig);
   }
   else if (s->type == ST_STRING_OPTIONS && s->values)
      strlcpy(opts_buf, s->values, sizeof(opts_buf));

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

   str_cls = (*env)->FindClass(env, "java/lang/String");
   out     = (*env)->NewObjectArray(env, 8, str_cls, NULL);
   if (!out)
      return NULL;

   (*env)->SetObjectArrayElement(env, out, 0,
         (*env)->NewStringUTF(env, s->short_description ? s->short_description : s->name));
   (*env)->SetObjectArrayElement(env, out, 1, (*env)->NewStringUTF(env, type_str));
   (*env)->SetObjectArrayElement(env, out, 2, (*env)->NewStringUTF(env, value_buf));
   (*env)->SetObjectArrayElement(env, out, 3, (*env)->NewStringUTF(env, min_buf));
   (*env)->SetObjectArrayElement(env, out, 4, (*env)->NewStringUTF(env, max_buf));
   (*env)->SetObjectArrayElement(env, out, 5, (*env)->NewStringUTF(env, step_buf));
   (*env)->SetObjectArrayElement(env, out, 6, (*env)->NewStringUTF(env, opts_buf));
   (*env)->SetObjectArrayElement(env, out, 7,
         (*env)->NewStringUTF(env,
               ((s->flags & SD_FLAG_IS_DRIVER)
                || s->cmd_trigger_idx == CMD_EVENT_REINIT
                || s->cmd_trigger_idx == CMD_EVENT_REINIT_FROM_TOGGLE) ? "1" : "0"));
   return out;
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeRaSetSetting(
      JNIEnv *env, jobject obj, jstring jkey, jstring jvalue)
{
   ricotta_cmd_entry entry = {0};
   const char *key   = (*env)->GetStringUTFChars(env, jkey, NULL);
   const char *value = (*env)->GetStringUTFChars(env, jvalue, NULL);

   (void)obj;

   entry.cmd      = RICOTTA_QCMD_RA_SET;
   entry.ra_key   = key ? strdup(key) : NULL;
   entry.ra_value = value ? strdup(value) : NULL;

   if (key)
      (*env)->ReleaseStringUTFChars(env, jkey, key);
   if (value)
      (*env)->ReleaseStringUTFChars(env, jvalue, value);

   ricotta_enqueue_entry(entry);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeRaSaveOverride(
      JNIEnv *env, jobject obj, jint scope)
{
   ricotta_cmd_entry entry = {0};

   (void)env;
   (void)obj;

   entry.cmd      = RICOTTA_QCMD_RA_SAVE_OVERRIDE;
   entry.ra_scope = (int)scope;

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
   const char *path        = (*env)->GetStringUTFChars(env, jpath, NULL);

   (void)obj;

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
void ricotta_osd_event(int type, int slot)
{
   JNIEnv *env  = NULL;
   int attached = 0;
   /* This may run on RetroArch's task thread (threaded savestates), not the
    * runloop thread, so we must use THIS thread's JNIEnv, not the cached one. */
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
   if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK)
   {
      if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != JNI_OK)
         return;
      attached = 1;
   }
   (*env)->CallVoidMethod(env, g_bridge_obj, g_on_osd_event_mid, (jint)type, (jint)slot);
   if (attached)
      (*g_jvm)->DetachCurrentThread(g_jvm);
}

/* Called from cheevos.c (HAVE_RICOTTA_OSD) on an achievement unlock. */
void ricotta_osd_achievement(const char *title)
{
   JNIEnv *env;
   jstring jtitle;
   if (!title || !title[0])
      return;
   env = ricotta_runloop_env();
   if (!env || !g_bridge_obj || !g_on_osd_achievement_mid)
      return;
   jtitle = (*env)->NewStringUTF(env, title);
   (*env)->CallVoidMethod(env, g_bridge_obj, g_on_osd_achievement_mid, jtitle);
   (*env)->DeleteLocalRef(env, jtitle);
}
