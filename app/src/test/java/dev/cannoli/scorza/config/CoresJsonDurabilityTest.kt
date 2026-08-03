package dev.cannoli.scorza.config

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoresJsonDurabilityTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun rootWith(name: String, json: String): File {
        val root = File(tmp.root, name).apply { mkdirs() }
        File(root, "Config").apply { mkdirs() }.also { File(it, "cores.json").writeText(json) }
        return root
    }

    private fun config(root: File) = PlatformConfig(
        root, ApplicationProvider.getApplicationContext<android.content.Context>().assets,
    ).also { it.load() }

    @Test fun `a torn file is flagged and not overwritten by the next save`() {
        val torn = """{"cores":{"NES":"fceumm"""
        val root = rootWith("dur-torn", torn)
        val pc = config(root)
        assertTrue("an unparseable file must be flagged", pc.loadFailed)

        pc.saveCoreMappings()
        assertEquals(
            "a save after a failed load must not clobber the file",
            torn,
            File(root, "Config/cores.json").readText(),
        )
    }

    @Test fun `a good file loads clean and saves atomically leaving no temp behind`() {
        val root = rootWith("dur-ok", """{"cores":{"NES":"fceumm_libretro"}}""")
        val pc = config(root)
        assertFalse(pc.loadFailed)

        pc.saveCoreMappings()
        val configDir = File(root, "Config")
        assertTrue(File(configDir, "cores.json").exists())
        assertEquals(
            "no temp files may survive a successful save",
            emptyList<String>(),
            configDir.listFiles().orEmpty().map { it.name }
                .filter { it != "cores.json" && it != "platforms.ini" },
        )
    }

    @Test fun `a missing file is not a failure`() {
        val root = File(tmp.root, "dur-absent").apply { mkdirs() }
        assertFalse(config(root).loadFailed)
    }
}
