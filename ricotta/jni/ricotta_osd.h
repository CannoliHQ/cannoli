#ifndef RICOTTA_OSD_H
#define RICOTTA_OSD_H

/* Force-included into every RetroArch translation unit by Android.mk, which includes the C++ ones
 * such as griffin_cpp.cpp. Without this the declaration would get C++ linkage there and fail to
 * link against the C definition in ricotta_bridge.c. */
#ifdef __cplusplus
extern "C" {
#endif

/*
 * Cannoli owns the on-screen notifications RetroArch would otherwise draw itself.
 * The patched RetroArch sites report what happened through ricotta_osd_event and
 * Cannoli decides how to show it, so these values cross the JNI boundary and are
 * mirrored by RicottaOsdEvent on the Kotlin side. Never renumber them.
 */
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
   RICOTTA_OSD_CHEEVOS_LOGIN_FAILED  = 12
};

/* slot carries RetroArch's state_slot for the state events (< 0 is the auto slot),
 * the disk index for RICOTTA_OSD_DISK_CHANGED, the port for
 * RICOTTA_OSD_CONTROLLER_PORT, and 1 when the stored login must be re-entered
 * (0 otherwise) on RICOTTA_OSD_CHEEVOS_LOGIN_FAILED. It is unused elsewhere. */
void ricotta_osd_event(int type, int slot);

/* The save notification is deferred until the thumbnail it names exists on disk. task_save latches
 * it before queueing the screenshot; the screenshot task raises it once the PNG is written. That
 * ordering used to be bought by encoding the PNG on the runloop thread, which stalled the frame. */
void ricotta_osd_defer_save(int type, int slot);
void ricotta_osd_flush_save(void);

/* Open across a savestate. Video drivers skip black frame insertion while it is set: a save stalls
 * the runloop, and whichever frame was swapped last stays on screen for the duration, so a BFI dark
 * frame reads as the screen blanking. */
void ricotta_save_begin(void);
void ricotta_save_end(void);
int  ricotta_save_is_active(void);

/* True when the launcher marked this 0-based port's pad as built in. RetroArch announces every
 * configured pad; a handheld's own controls are not news. Ports come from the launch intent, so
 * a pad that connects after launch is never suppressed. */
int  ricotta_port_is_builtin(int port);

/* The input hooks RetroArch's android_input.c calls. Declared here because this header is
 * force-included everywhere, which keeps the patch down to the two call sites: an extern in the
 * patch itself would be a third hunk to rebase, anchored on whatever happened to sit above it. */
int  ricotta_bridge_intercept_key(int keycode, int action);
void ricotta_bridge_poll_commands(void);

#ifdef __cplusplus
}
#endif

#endif
