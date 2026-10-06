#ifndef RICOTTA_OSD_H
#define RICOTTA_OSD_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

enum ricotta_osd_type
{
   RICOTTA_OSD_SAVE_STATE            = 0,
   RICOTTA_OSD_LOAD_STATE            = 1,
   RICOTTA_OSD_RESET                 = 2,
   RICOTTA_OSD_UNDO_SAVE_STATE       = 4,
   RICOTTA_OSD_DISK_CHANGED          = 7,
   RICOTTA_OSD_SCREENSHOT            = 8,
   RICOTTA_OSD_CONTROLLER_PORT       = 9,
   RICOTTA_OSD_LOAD_REFUSED          = 10,
   RICOTTA_OSD_HARDCORE_PAUSED       = 11,
   RICOTTA_OSD_CHEEVOS_LOGIN_FAILED  = 12,
   RICOTTA_OSD_FASTFORWARD           = 13,
   RICOTTA_OSD_REWIND_END            = 14
};

void ricotta_osd_event(int type, int slot);

void ricotta_osd_cheevos_load(int outcome, int unlocked, int total);

void ricotta_osd_achievement(const char *title);

void ricotta_cheevos_stash_progress(const void *buffer, size_t size);
void ricotta_cheevos_apply_pending_progress(void);
void ricotta_cheevos_forget_progress(void);

void ricotta_state_loaded(const char *path, int autoload, int ok);

void ricotta_osd_defer_save(int type, int slot);
void ricotta_osd_flush_save(void);

void ricotta_save_begin(void);
void ricotta_save_end(void);
int  ricotta_save_is_active(void);

int  ricotta_bridge_intercept_key(int keycode, int action);
void ricotta_bridge_poll_commands(void);

#define RICOTTA_MAX_RETRACT 32
int  ricotta_bridge_take_retract(int *out, int max);

int  ricotta_ff_take_toggle(void);
int  ricotta_ff_held(void);

int  ricotta_rewind_held(void);

int  ricotta_hold_command(int cmd, void *data);

#ifdef __cplusplus
}
#endif

#endif
