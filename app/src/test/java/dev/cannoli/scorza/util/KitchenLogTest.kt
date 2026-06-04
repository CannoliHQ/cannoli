package dev.cannoli.scorza.util

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class KitchenLogTest {
    private lateinit var root: File

    @Before fun setUp() {
        root = File.createTempFile("cannoli", "").also { it.delete(); it.mkdirs() }
        KitchenLog.init(root.absolutePath)
        LoggingPrefs.kitchen = false
    }

    @After fun tearDown() {
        LoggingPrefs.kitchen = false
        root.deleteRecursively()
    }

    private fun logFile() = File(File(root, "Logs"), "kitchen.log")

    @Test fun doesNotWriteWhenDisabled() {
        LoggingPrefs.kitchen = false
        KitchenLog.log("hello")
        assertFalse(logFile().exists())
    }

    @Test fun writesWhenEnabled() {
        LoggingPrefs.kitchen = true
        KitchenLog.log("hello")
        assertTrue(logFile().exists())
        assertTrue(logFile().readText().contains("hello"))
    }

    @Test fun logErrorIncludesThrowableDetail() {
        LoggingPrefs.kitchen = true
        KitchenLog.logError("boom", IllegalStateException("bad value"))
        val text = logFile().readText()
        assertTrue(text.contains("boom"))
        assertTrue(text.contains("IllegalStateException"))
        assertTrue(text.contains("bad value"))
    }
}
