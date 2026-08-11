package dev.cannoli.scorza.ui.viewmodel

import dev.cannoli.scorza.input.ConnectedDevice
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.autoconfig.AutoconfigRepository
import dev.cannoli.scorza.input.autoconfig.AutoconfigSeeder
import dev.cannoli.scorza.input.autoconfig.BundledAutoconfigEntries
import dev.cannoli.scorza.input.autoconfig.MapCfgSource
import dev.cannoli.scorza.input.resolver.MappingResolver
import dev.cannoli.scorza.input.runtime.ActiveMappingHolder
import dev.cannoli.scorza.input.runtime.PortRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ControllersViewModelTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After fun tearDown() = Dispatchers.resetMain()

    private val repository by lazy { AutoconfigRepository { tmp.root } }
    private val portRouter = PortRouter()
    private val activeMappingHolder = ActiveMappingHolder()
    private val resolver by lazy {
        MappingResolver(repository, BundledAutoconfigEntries.forTest(emptyList()))
    }

    private val bundledAssets = MapCfgSource(
        mapOf(
            "autoconfig/android/stadia.cfg" to """
                input_device = "Stadia Controller"
                input_vendor_id = "6353"
                input_product_id = "37888"
                input_b_btn = "96"
            """.trimIndent()
        )
    )

    private val seeder by lazy {
        AutoconfigSeeder(bundledAssets, { tmp.root }, { File(tmp.root, "Mappings") }, versionCode = 1)
    }

    private fun vm() =
        ControllersViewModel(repository, portRouter, activeMappingHolder, resolver, seeder)

    private val stadia = ConnectedDevice(
        androidDeviceId = 7,
        descriptor = "stadia-1",
        name = "Stadia Controller",
        vendorId = 6353,
        productId = 37888,
        androidBuildModel = "Pixel",
        sourceMask = 0,
        connectedAtMillis = 1_000L,
    )

    private fun writeCfg(fileName: String, body: String) {
        tmp.newFile(fileName).writeText(body.trimIndent())
        repository.invalidate()
    }

    private fun writeStadiaBundled() = writeCfg(
        "stadia.cfg",
        """
        input_device = "Stadia Controller"
        input_vendor_id = "6353"
        input_product_id = "37888"
        input_b_btn = "96"
        """
    )

    private fun writeStadiaUser() = writeCfg(
        "stadia_user.cfg",
        """
        input_device = "Stadia Controller"
        input_device_display_name = "Couch Pad"
        input_vendor_id = "6353"
        input_product_id = "37888"
        input_b_btn = "190"
        cannoli_user = "true"
        cannoli_descriptor = "stadia-1"
        """
    )

    private fun connectStadia(): DeviceMapping {
        val mapping = resolver.resolve(stadia)
        portRouter.onConnect(stadia, mapping)
        return mapping
    }

    @Test
    fun `saved list holds user cfgs only and skips the bundled ones`() {
        writeStadiaBundled()
        writeStadiaUser()

        val state = vm().state.value

        assertEquals(listOf("Couch Pad"), state.savedMappings.map { it.displayName })
        assertEquals(listOf("stadia_user"), state.savedMappings.map { it.id })
    }

    @Test
    fun `a connected user cfg is not repeated in the saved list`() {
        writeStadiaUser()
        connectStadia()

        val state = vm().state.value

        assertEquals(listOf("stadia_user"), state.connected.map { it.mapping.id })
        assertTrue(state.savedMappings.isEmpty())
    }

    @Test
    fun `rename writes a user cfg to the autoconfig database`() {
        writeStadiaBundled()
        val mapping = connectStadia()
        val model = vm()

        model.renameMapping(mapping, "Couch Pad")

        val entry = repository.findById(mapping.id) ?: error("expected a cfg on disk")
        assertTrue(entry.cannoliUser)
        assertEquals("Couch Pad", entry.displayName)
        assertEquals("Couch Pad", model.state.value.connected.single().mapping.displayName)
    }

    @Test
    fun `reset drops the user cfg and re-resolves the connected pad to the bundled one`() {
        writeStadiaBundled()
        writeStadiaUser()
        val mapping = connectStadia()
        assertEquals("stadia_user", mapping.id)
        activeMappingHolder.set(mapping)
        val model = vm()

        model.resetMapping(mapping)

        assertNull(repository.findById("stadia_user"))
        assertEquals("stadia", portRouter.mappingFor(7)?.id)
        assertEquals("stadia", activeMappingHolder.active.value?.id)
        assertTrue(portRouter.evaluatorFor(7)?.keyCodeIsBound(96) == true)
        assertTrue(model.state.value.savedMappings.isEmpty())
    }

    // An edit overwrites the bundled cfg it came from, so deleting it would leave RetroArch a hole
    // where its own database entry used to be.
    @Test
    fun `reset puts the pristine bundled cfg back on disk`() {
        writeStadiaBundled()
        val mapping = connectStadia()
        val model = vm()
        val renamed = model.renameMapping(mapping, "Couch Pad")
        assertTrue(repository.findById("stadia")?.cannoliUser == true)

        model.resetMapping(renamed)

        val restored = repository.findById("stadia") ?: error("expected the bundled cfg back on disk")
        assertFalse(restored.cannoliUser)
        assertEquals("Stadia Controller", portRouter.mappingFor(7)?.displayName)
    }

    @Test
    fun `reset of a cfg with no bundled counterpart leaves no file`() {
        writeStadiaUser()
        val model = vm()

        model.resetMapping(model.state.value.savedMappings.single())

        assertFalse(File(tmp.root, "stadia_user.cfg").exists())
    }

    @Test
    fun `reset on a disconnected user cfg drops it from the saved list`() {
        writeStadiaUser()
        val model = vm()
        val saved = model.state.value.savedMappings.single()

        model.resetMapping(saved)

        assertNull(repository.findById("stadia_user"))
        assertTrue(model.state.value.savedMappings.isEmpty())
    }
}
