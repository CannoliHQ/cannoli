package dev.cannoli.ricotta

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShaderDriverMatchParityTest {
    private val bridge = File("jni/ricotta_bridge.c").readText()

    @Test fun `a preset the running driver cannot load is not applied`() {
        val start = bridge.indexOf("if (entry.cmd == RICOTTA_QCMD_SHADER_SET)")
        assertTrue(start >= 0)
        val block = bridge.substring(start, bridge.indexOf("continue;", bridge.indexOf("menu_shader_manager_set_preset", start)))
        val check = block.indexOf("video_driver_test_all_flags(video_shader_type_to_flag(")
        assertTrue(check >= 0)
        assertTrue(check < block.indexOf("menu_shader_manager_set_preset"))
    }
}
