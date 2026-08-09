#ifndef RICOTTA_OSD_H
#define RICOTTA_OSD_H

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

#endif
