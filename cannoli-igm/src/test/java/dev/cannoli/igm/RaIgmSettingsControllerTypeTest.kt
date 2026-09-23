package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class PortHost : RaSettingsHost {
    val devices = mutableMapOf<Int, PortDevices>()
    var slots: List<PlayerSlot> = emptyList()
    val portWrites = mutableListOf<Pair<Int, Int>>()
    val rawWrites = mutableListOf<String>()
    val savedKeys = mutableListOf<Set<String>>()

    override fun portDeviceTypes(port: Int): PortDevices? = devices[port]
    override fun setPortDevice(port: Int, id: Int) {
        portWrites += port to id
        devices[port]?.let { devices[port] = it.copy(current = id) }
    }
    override fun players(): List<PlayerSlot> = slots
    override fun raGetSetting(key: String): RaSetting? = null
    fun applyNow(key: String, value: MachineValue, watch: Collection<String>): RaApplyResult {
        rawWrites += key
        return RaApplyResult(value)
    }

    override fun raApply(
        key: String,
        value: MachineValue,
        watch: Collection<String>,
        onDone: (RaApplyResult?) -> Unit,
    ): Boolean = answerNow(onDone) { applyNow(key, value, watch) }

    override fun raSaveOverride(scope: RaOverrideScope, keys: Set<String>) { savedKeys += keys }
}

class RaIgmSettingsControllerTypeTest {

    private val none = PortDeviceType(0, "None")
    private val retroPad = PortDeviceType(1, "RetroPad")
    private val dualShock = PortDeviceType(261, "DualShock")

    private fun offering(vararg types: PortDeviceType, current: Int = 1) = PortDevices(current, listOf(none) + types)

    private fun pad(player: Int) = PlayerSlot(player, player, "Pad", 0)

    private fun provider(host: PortHost) = RaIgmSettingsProvider(host = host, strings = RaOptionStrings(), curated = true)

    private fun inputRows(p: RaIgmSettingsProvider) =
        p.screen(listOf(CuratedCatalog.CATEGORY_INPUT)).items.filterIsInstance<GenericIgmSettingsItem.Choice>()

    @Test fun `one pad gets a single Controller Type row showing the current type`() {
        val host = PortHost().apply { slots = listOf(pad(0)); devices[0] = offering(retroPad, dualShock) }

        val rows = inputRows(provider(host))

        assertEquals(listOf("Controller Type"), rows.map { it.label })
        assertEquals("RetroPad", rows.single().value)
    }

    @Test fun `two pads get a row per player`() {
        val host = PortHost().apply {
            slots = listOf(pad(0), pad(1))
            devices[0] = offering(retroPad, dualShock)
            devices[1] = offering(retroPad, dualShock)
        }

        assertEquals(listOf("Player 1 Controller", "Player 2 Controller"), inputRows(provider(host)).map { it.label })
    }

    @Test fun `five padded players still get only Player 1 through Player 4`() {
        val host = PortHost().apply {
            slots = (0..4).map { pad(it) }
            for (i in 0..4) devices[i] = offering(retroPad, dualShock)
        }

        assertEquals(
            listOf("Player 1 Controller", "Player 2 Controller", "Player 3 Controller", "Player 4 Controller"),
            inputRows(provider(host)).map { it.label },
        )
    }

    @Test fun `a port offering nothing beyond RetroPad has no row`() {
        val host = PortHost().apply { slots = listOf(pad(0)); devices[0] = offering(retroPad) }

        assertTrue(inputRows(provider(host)).isEmpty())
    }

    @Test fun `shortcuts stay below the controller rows`() {
        val host = PortHost().apply { slots = listOf(pad(0)); devices[0] = offering(retroPad, dualShock) }

        val items = provider(host).screen(listOf(CuratedCatalog.CATEGORY_INPUT)).items

        assertEquals(CuratedCatalog.INPUT_SHORTCUTS, items.last().key)
    }

    @Test fun `cycling applies the next type through the port and stages the key`() {
        val host = PortHost().apply { slots = listOf(pad(0)); devices[0] = offering(retroPad, dualShock) }
        val p = provider(host)
        inputRows(p)

        p.cycle("input_libretro_device_p1", 1)

        assertEquals(listOf(0 to 261), host.portWrites)
        assertTrue(host.rawWrites.isEmpty())
        (p.exitPrompt() as IgmSettingsExit.Prompt).choose(SaveAnswer.game)
        assertEquals(setOf("input_libretro_device_p1"), host.savedKeys.single())
    }

    @Test fun `cycling never lands on None and wraps`() {
        val host = PortHost().apply { slots = listOf(pad(0)); devices[0] = offering(retroPad, dualShock, current = 261) }
        val p = provider(host)
        inputRows(p)

        p.cycle("input_libretro_device_p1", 1)

        assertEquals(listOf(0 to 1), host.portWrites)
    }

    @Test fun `discard puts the old type back through the port, never a raw write`() {
        val host = PortHost().apply { slots = listOf(pad(0)); devices[0] = offering(retroPad, dualShock) }
        val p = provider(host)
        inputRows(p)
        p.cycle("input_libretro_device_p1", 1)

        (p.exitPrompt() as IgmSettingsExit.Prompt).choose(SaveAnswer.discard)

        assertEquals(listOf(0 to 261, 0 to 1), host.portWrites)
        assertTrue(host.rawWrites.isEmpty())
    }
}
