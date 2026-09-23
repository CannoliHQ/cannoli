package dev.cannoli.ricotta

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ResumeRetryParityTest {
    private val bridge = File("jni/ricotta_bridge.c").readText()
    private val header = File("jni/ricotta_osd.h").readText()
    private val patch = File("../patches/task_save.patch").readText()
    private val taskSave = File("../retroarch/tasks/task_save.c").readText()

    private fun body(signature: String): String {
        val start = bridge.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        return bridge.substring(start, bridge.indexOf("\n}\n", start))
    }

    @Test fun `task_save reports every deserialize before its failure branch`() {
        val hook = patch.indexOf("+   ricotta_state_loaded(load_data->path,")
        assertTrue("task_save.patch lost the report", hook >= 0)
        assertTrue(patch.contains("+         (load_data->flags & SAVE_TASK_FLAG_AUTOLOAD) ? 1 : 0, ret ? 1 : 0);"))
        assertTrue("the report must come before the failure branch",
            hook < patch.indexOf("    if (!ret)\n       goto error;", hook))
    }

    @Test fun `a hardcore refusal leaves content_load_state_cb before the report`() {
        val cb = taskSave.indexOf("static void content_load_state_cb(")
        assertTrue(cb >= 0)
        val hardcore = taskSave.indexOf("if (rcheevos_hardcore_active())\n      goto error;", cb)
        val hook = taskSave.indexOf("ricotta_state_loaded(load_data->path,", cb)
        assertTrue("hardcore check missing", hardcore >= 0)
        assertTrue("report missing", hook >= 0)
        assertTrue("the report must come after the hardcore exit", hardcore < hook)
    }

    @Test fun `a retry waits out a running blocking task instead of being dropped`() {
        val b = body("static void ricotta_resume_tick(void)")
        val wait = b.indexOf("if (task_queue_find(&blocking))")
        assertTrue(wait >= 0)
        assertTrue(wait < b.indexOf("g_resume_in_flight = 1;"))
        assertTrue(body("static bool ricotta_task_is_blocking(retro_task_t *task, void *userdata)")
            .contains("task->type == TASK_TYPE_BLOCKING"))
    }

    @Test fun `reset and quit abandon the retry`() {
        val b = body("void ricotta_bridge_poll_commands(void)")
        val abandon = b.indexOf("if (entry.cmd == CMD_EVENT_RESET || entry.cmd == CMD_EVENT_QUIT)")
        assertTrue(abandon >= 0)
        assertTrue(b.indexOf("ricotta_resume_abandon();", abandon) < b.indexOf("command_event(entry.cmd, NULL);", abandon))
    }

    @Test fun `the report is declared where the patch can see it`() {
        assertTrue(header.contains("void ricotta_state_loaded(const char *path, int autoload, int ok);"))
    }

    @Test fun `the pump drives the retry`() {
        assertTrue(body("void ricotta_bridge_poll_commands(void)").contains("ricotta_resume_tick();"))
    }

    @Test fun `a retry is the same auto-load and never runs under hardcore`() {
        val b = body("static void ricotta_resume_tick(void)")
        val hardcore = b.indexOf("rcheevos_hardcore_active()")
        assertTrue(hardcore >= 0)
        assertTrue(hardcore < b.indexOf("content_load_state(g_resume_path, false, true)"))
    }

    @Test fun `a load the user asked for is never retried and cancels a pending retry`() {
        val b = body("void ricotta_state_loaded(const char *path, int autoload, int ok)")
        val manual = b.indexOf("if (!autoload)")
        assertTrue(manual >= 0)
        assertTrue(b.indexOf("ricotta_resume_clear();", manual) < b.indexOf("if (ok)"))
    }

    @Test fun `retries give up after the window and say so`() {
        val b = body("void ricotta_state_loaded(const char *path, int autoload, int ok)")
        assertTrue(b.contains("RICOTTA_RESUME_WINDOW_US"))
        assertTrue(b.contains("ricotta_resume_give_up();"))
        val tick = body("static void ricotta_resume_tick(void)")
        assertTrue("a rescheduled retry still counts against the window",
            tick.indexOf("RICOTTA_RESUME_WINDOW_US)") in 0 until tick.indexOf("task_queue_find(&blocking)"))
        assertTrue(body("static void ricotta_resume_give_up(void)").contains("Resume gave up after %d attempts."))
    }

    @Test fun `a retry that lands resets rewind without a second notification`() {
        assertTrue(body("void ricotta_state_loaded(const char *path, int autoload, int ok)")
            .contains("RICOTTA_QCMD_REWIND_RESET"))
        assertTrue(body("void ricotta_osd_event(int type, int slot)")
            .contains("if (type == RICOTTA_OSD_LOAD_STATE && g_resume_in_flight)"))
    }
}
