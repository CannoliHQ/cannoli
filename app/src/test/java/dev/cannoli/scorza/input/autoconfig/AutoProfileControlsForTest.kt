package dev.cannoli.scorza.input.autoconfig

import dev.cannoli.scorza.input.ControllerIdentity
import dev.cannoli.scorza.input.ProfileManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AutoProfileControlsForTest {

    private lateinit var tempRoot: File
    private lateinit var profileManager: ProfileManager
    private lateinit var loader: AutoconfigLoader

    private val xboxCfg = """
        input_device = "Xbox Wireless Controller"
        input_vendor_id = "1118"
        input_product_id = "2835"
        input_b_btn = "96"
        input_a_btn = "97"
        input_up_btn = "19"
    """.trimIndent()

    @Before
    fun setup() {
        tempRoot = Files.createTempDirectory("cannoli-test").toFile()
        profileManager = ProfileManager(tempRoot.absolutePath)
        profileManager.ensureDefaults()
        loader = AutoconfigLoader(
            MapCfgSource(mapOf("autoconfig/android/Xbox Wireless Controller.cfg" to xboxCfg))
        )
    }

    @After
    fun cleanup() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun returnsControlsAndDeviceNameForMatch() {
        val id = ControllerIdentity("desc", "Xbox Wireless Controller", 1118, 2835)
        val matcher = AutoconfigMatcher(loader.entries())
        val match = profileManager.autoProfileControlsFor(id, matcher)
        assertNotNull(match)
        assertEquals("Xbox Wireless Controller", match!!.deviceName)
        assertEquals(96, match.controls["btn_south"])
        assertEquals(97, match.controls["btn_east"])
        assertEquals(19, match.controls["btn_up"])
    }

    @Test
    fun returnsNullWhenNoMatch() {
        val id = ControllerIdentity("desc", "Unknown", 1, 2)
        val matcher = AutoconfigMatcher(loader.entries())
        assertNull(profileManager.autoProfileControlsFor(id, matcher))
    }

    @Test
    fun returnsNullWhenVerifierRejects() {
        val id = ControllerIdentity("desc", "Xbox Wireless Controller", 1118, 2835)
        val matcher = AutoconfigMatcher(loader.entries())
        val match = profileManager.autoProfileControlsFor(id, matcher) { codes -> BooleanArray(codes.size) { false } }
        assertNull(match)
    }

    @Test
    fun returnsNullWhenControlsMapEmpty() {
        val unmappedCfg = """
            input_device = "Unmappable"
            input_vendor_id = "1"
            input_product_id = "2"
            input_unknown_btn = "99"
        """.trimIndent()
        val emptyLoader = AutoconfigLoader(MapCfgSource(mapOf("autoconfig/android/Unmappable.cfg" to unmappedCfg)))
        val id = ControllerIdentity("desc", "Unmappable", 1, 2)
        val matcher = AutoconfigMatcher(emptyLoader.entries())
        assertNull(profileManager.autoProfileControlsFor(id, matcher))
    }

    @Test
    fun deviceNameFallsBackToIdentityWhenCfgBlank() {
        val blankCfg = """
            input_vendor_id = "1"
            input_product_id = "2"
            input_b_btn = "96"
        """.trimIndent()
        val blankLoader = AutoconfigLoader(MapCfgSource(mapOf("autoconfig/android/Blank.cfg" to blankCfg)))
        val id = ControllerIdentity("desc", "Fallback Name", 1, 2)
        val matcher = AutoconfigMatcher(blankLoader.entries())
        val match = profileManager.autoProfileControlsFor(id, matcher)
        assertNotNull(match)
        assertEquals("Fallback Name", match!!.deviceName)
    }
}
