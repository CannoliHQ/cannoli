package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private const val DPAD_UP = 19
private const val DPAD_DOWN = 20
private const val DPAD_LEFT = 21
private const val DPAD_RIGHT = 22
private const val CONFIRM = 96
private const val BACK = 97
private const val WEST = 99
private const val NORTH = 100

class IGMControllerCheatsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun writeCht(name: String, vararg descs: String) {
        val dir = File(tmp.root, "Cheats/nes/Game").apply { mkdirs() }
        val body = StringBuilder("cheats = ${descs.size}\n")
        descs.forEachIndexed { i, d ->
            body.append("cheat${i}_desc = \"$d\"\n")
            body.append("cheat${i}_code = \"CODE$i\"\n")
        }
        File(dir, name).writeText(body.toString())
    }

    /** Writes land before the call returns, so every assertion below stays synchronous. */
    private fun manager() =
        CheatManager(tmp.root.absolutePath, "nes", "Game", writer = { it.run() })

    private fun bridgeFor(vararg files: Pair<String, List<String>>) =
        FakeRetroArchBridge().apply {
            files.forEach { (name, descs) ->
                cheatRowsByPath[File(tmp.root, "Cheats/nes/Game/$name").absolutePath] =
                    descs.mapIndexed { i, d ->
                        RetroArchBridge.CheatRow(i, d, "CODE$i", enabled = false, supported = true)
                    }
            }
        }

    private fun bridgeWithSupport(vararg files: Pair<String, List<Pair<String, Boolean>>>) =
        FakeRetroArchBridge().apply {
            files.forEach { (name, rows) ->
                cheatRowsByPath[File(tmp.root, "Cheats/nes/Game/$name").absolutePath] =
                    rows.mapIndexed { i, (desc, supported) ->
                        RetroArchBridge.CheatRow(i, desc, "CODE$i", enabled = false, supported = supported)
                    }
            }
        }

    private fun onCheatsRow(c: IGMController) {
        val idx = c.buildMenuOptions().cheatsIndex
        assertTrue("the cheats row must exist", idx >= 0)
        c.replaceTop((c.currentScreen as IGMScreen.Menu).copy(selectedIndex = idx))
    }

    private fun selection(c: IGMController) = (c.currentScreen as IGMScreen.Cheats).selectedIndex

    @Test fun `no cheat files means no menu row`() {
        val c = testController(FakeRetroArchBridge())
        c.attachCheats(manager())
        c.openMenu()
        assertFalse(c.buildMenuOptions().hasCheats)
    }

    @Test fun `a cheat file adds the menu row`() {
        writeCht("a.cht", "One")
        val c = testController(bridgeFor("a.cht" to listOf("One")))
        c.attachCheats(manager())
        c.openMenu()
        assertTrue(c.buildMenuOptions().hasCheats)
    }

    @Test fun `a file with no parsable cheats does not add the row`() {
        File(tmp.root, "Cheats/nes/Game").mkdirs()
        File(tmp.root, "Cheats/nes/Game/broken.cht").writeText("not a cht file")
        val c = testController(FakeRetroArchBridge())
        c.attachCheats(manager())
        c.openMenu()
        assertFalse(c.buildMenuOptions().hasCheats)
    }

    @Test fun `confirming the row opens the screen and loads exactly one file`() {
        writeCht("a.cht", "One", "Two")
        writeCht("b.cht", "Three")
        val bridge = bridgeFor("a.cht" to listOf("One", "Two"), "b.cht" to listOf("Three"))
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu()
        onCheatsRow(c)

        c.handleKeyDown(CONFIRM)

        assertTrue(c.currentScreen is IGMScreen.Cheats)
        assertEquals(1, bridge.loadedCheatPaths.size)
        assertTrue(bridge.loadedCheatPaths[0].endsWith("a.cht"))
        assertEquals(listOf("One", "Two"), c.cheatItems.value.map { it.label })
    }

    @Test fun `every cheat starts disabled`() {
        writeCht("a.cht", "One")
        val c = testController(bridgeFor("a.cht" to listOf("One")))
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)
        assertFalse(c.cheatItems.value[0].enabled)
    }

    @Test fun `confirm toggles the selected cheat through the bridge`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeFor("a.cht" to listOf("One", "Two"))
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)

        c.handleKeyDown(DPAD_DOWN)
        c.handleKeyDown(CONFIRM)

        assertEquals(listOf(1), bridge.toggledCheatIndexes)
        assertTrue(c.cheatItems.value[1].enabled)
    }

    @Test fun `the restore row reapplies the last used set and reports the count`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeFor("a.cht" to listOf("One", "Two"))
        manager().saveLastUsed("a.cht", setOf(CheatIdentity.hash("Two", "CODE1")))
        val c = testController(bridge)
        var restored = -1
        c.onCheatsRestored = { restored = it }
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)
        assertTrue(c.cheatHasRemembered.value)
        assertEquals(0, selection(c))

        c.handleKeyDown(CONFIRM)

        assertEquals(1, restored)
        assertTrue(c.cheatItems.value[1].enabled)
        assertEquals(listOf(1), bridge.toggledCheatIndexes)
        assertEquals(1, bridge.cheatApplies)
        assertFalse("the row goes once its work is done", c.cheatHasRemembered.value)
    }

    @Test fun `back leaves the screen without reloading on the next entry`() {
        writeCht("a.cht", "One")
        val bridge = bridgeFor("a.cht" to listOf("One"))
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)
        c.handleKeyDown(BACK)
        assertTrue(c.currentScreen is IGMScreen.Menu)

        c.handleKeyDown(CONFIRM)

        assertEquals(1, bridge.loadedCheatPaths.size)
    }

    @Test fun `a disc switch forces a reload that restores the session's set`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeFor("a.cht" to listOf("One", "Two")).apply { discs = 2 }
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)
        c.handleKeyDown(DPAD_DOWN)
        c.handleKeyDown(CONFIRM)
        c.handleKeyDown(BACK)

        val discIndex = c.buildMenuOptions().switchDiscIndex
        c.replaceTop((c.currentScreen as IGMScreen.Menu).copy(selectedIndex = discIndex))
        c.handleKeyDown(DPAD_RIGHT)

        onCheatsRow(c)
        c.handleKeyDown(CONFIRM)

        assertEquals(2, bridge.loadedCheatPaths.size)
        assertTrue(c.cheatItems.value[1].enabled)
    }

    @Test fun `a set remembered under another file name is never offered`() {
        writeCht("a.cht", "One")
        val bridge = bridgeFor("a.cht" to listOf("One"))
        manager().saveLastUsed("b.cht", setOf(CheatIdentity.hash("One", "CODE0")))
        val c = testController(bridge)
        var restored = -1
        c.onCheatsRestored = { restored = it }
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)

        assertFalse(c.cheatHasRemembered.value)
        assertEquals(-1, restored)
        assertTrue(c.cheatItems.value.none { it.enabled })
        assertEquals(1, bridge.loadedCheatPaths.size)
    }

    @Test fun `a load whose snapshot never arrives is retried on the next menu open`() {
        writeCht("a.cht", "One")
        val bridge = bridgeFor("a.cht" to listOf("One")).apply { deferCheatLoads = true }
        val c = testController(bridge)
        c.attachCheats(manager())
        assertEquals(1, bridge.loadedCheatPaths.size)
        bridge.dropPendingCheatLoads()

        c.openMenu()

        assertFalse(c.buildMenuOptions().hasCheats)
        assertEquals(2, bridge.loadedCheatPaths.size)

        bridge.deliverCheats()
        c.openMenu()

        assertTrue(c.buildMenuOptions().hasCheats)
        onCheatsRow(c); c.handleKeyDown(CONFIRM)
        assertEquals(listOf("One"), c.cheatItems.value.map { it.label })
    }

    @Test fun `a late duplicate snapshot does not wipe what the user turned on`() {
        writeCht("a.cht", "One")
        val bridge = bridgeFor("a.cht" to listOf("One")).apply { deferCheatLoads = true }
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu()
        assertEquals(2, bridge.loadedCheatPaths.size)

        bridge.deliverCheats()
        onCheatsRow(c); c.handleKeyDown(CONFIRM)
        c.handleKeyDown(CONFIRM)
        assertTrue(c.cheatItems.value[0].enabled)

        bridge.deliverCheats()

        assertTrue(c.cheatItems.value[0].enabled)
        assertEquals(listOf(0), bridge.toggledCheatIndexes)
    }

    @Test fun `a disc switch across a load in flight discards it and reloads`() {
        writeCht("a.cht", "One")
        val bridge = bridgeFor("a.cht" to listOf("One")).apply { discs = 2; deferCheatLoads = true }
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu()
        assertEquals(2, bridge.loadedCheatPaths.size)

        val discIndex = c.buildMenuOptions().switchDiscIndex
        c.replaceTop((c.currentScreen as IGMScreen.Menu).copy(selectedIndex = discIndex))
        c.handleKeyDown(DPAD_RIGHT)

        assertEquals(3, bridge.loadedCheatPaths.size)

        bridge.deliverCheats()
        bridge.deliverCheats()

        assertFalse("both snapshots describe the disc that left", c.buildMenuOptions().hasCheats)

        bridge.deliverCheats()

        assertTrue(c.buildMenuOptions().hasCheats)
        assertEquals(listOf("One"), c.cheatItems.value.map { it.label })
    }

    @Test fun `a file RetroArch supports nothing from starts with no selection`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeWithSupport("a.cht" to listOf("One" to false, "Two" to false))
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)

        assertEquals(-1, selection(c))

        c.handleKeyDown(CONFIRM)

        assertTrue(bridge.toggledCheatIndexes.isEmpty())
        assertTrue(c.cheatItems.value.none { it.enabled })

        c.handleKeyDown(DPAD_DOWN)

        assertEquals(0, selection(c))
    }

    @Test fun `selection starts on the first row RetroArch took`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeWithSupport("a.cht" to listOf("One" to false, "Two" to true))
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)

        assertEquals(1, selection(c))

        c.handleKeyDown(CONFIRM)

        assertEquals(listOf(1), bridge.toggledCheatIndexes)
        assertTrue(c.cheatItems.value[1].enabled)
    }

    @Test fun `confirm on a row RetroArch did not take dispatches nothing`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeWithSupport("a.cht" to listOf("One" to false, "Two" to true))
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)

        c.handleKeyDown(DPAD_UP)
        assertEquals(0, selection(c))

        c.handleKeyDown(CONFIRM)

        assertTrue(bridge.toggledCheatIndexes.isEmpty())
        assertEquals(0, bridge.cheatApplies)
        assertTrue(c.cheatItems.value.none { it.enabled })
    }

    @Test fun `reopening a file with nothing toggleable still starts with no selection`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeWithSupport("a.cht" to listOf("One" to false, "Two" to false))
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)
        assertEquals(-1, selection(c))

        c.handleKeyDown(BACK)
        c.handleKeyDown(CONFIRM)

        assertEquals(1, bridge.loadedCheatPaths.size)
        assertEquals(-1, selection(c))
    }

    @Test fun `reopening starts past a dead first row`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeWithSupport("a.cht" to listOf("One" to false, "Two" to true))
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)
        assertEquals(1, selection(c))

        c.handleKeyDown(BACK)
        c.handleKeyDown(CONFIRM)

        assertEquals(1, bridge.loadedCheatPaths.size)
        assertEquals(1, selection(c))
    }

    @Test fun `a snapshot that lands with no screen open still places the selection`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeWithSupport("a.cht" to listOf("One" to false, "Two" to true))
            .apply { deferCheatLoads = true }
        val c = testController(bridge)
        c.attachCheats(manager())

        bridge.deliverCheats()

        assertNull(c.currentScreen)

        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)

        assertEquals(1, bridge.loadedCheatPaths.size)
        assertEquals(1, selection(c))
    }

    @Test fun `two disc switches in a row keep the set to restore`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeFor("a.cht" to listOf("One", "Two")).apply { discs = 3 }
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)
        c.handleKeyDown(DPAD_DOWN)
        c.handleKeyDown(CONFIRM)
        c.handleKeyDown(BACK)

        val discIndex = c.buildMenuOptions().switchDiscIndex
        c.replaceTop((c.currentScreen as IGMScreen.Menu).copy(selectedIndex = discIndex))
        c.handleKeyDown(DPAD_RIGHT)
        c.handleKeyDown(DPAD_RIGHT)

        onCheatsRow(c)
        c.handleKeyDown(CONFIRM)

        assertEquals(3, bridge.loadedCheatPaths.size)
        assertTrue(c.cheatItems.value[1].enabled)
    }

    @Test fun `a snapshot from before a disc switch never becomes the new disc's session`() {
        writeCht("a.cht", "One", "Two")
        val path = File(tmp.root, "Cheats/nes/Game/a.cht").absolutePath
        val bridge = bridgeWithSupport("a.cht" to listOf("One" to true, "Two" to true))
            .apply { discs = 2; deferCheatLoads = true }
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu()

        val discIndex = c.buildMenuOptions().switchDiscIndex
        c.replaceTop((c.currentScreen as IGMScreen.Menu).copy(selectedIndex = discIndex))
        c.handleKeyDown(DPAD_RIGHT)
        assertEquals(3, bridge.loadedCheatPaths.size)

        // The loads in flight when the disc changed describe the disc that left. The new disc
        // reinitializes content state, so RetroArch takes a different set of rows from the file.
        bridge.deliverCheats()
        bridge.deliverCheats()
        bridge.cheatRowsByPath[path] = listOf(
            RetroArchBridge.CheatRow(0, "One", "CODE0", enabled = false, supported = false),
            RetroArchBridge.CheatRow(1, "Two", "CODE1", enabled = false, supported = true),
        )
        bridge.deliverCheats()

        onCheatsRow(c)
        c.handleKeyDown(CONFIRM)

        assertEquals(listOf(false, true), c.cheatItems.value.map { it.supported })
        assertEquals(1, selection(c))
    }

    @Test fun `hardcore warns before the first enable and proceeds on confirm`() {
        writeCht("a.cht", "One")
        val bridge = bridgeFor("a.cht" to listOf("One")).apply { hardcoreActive = true }
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)

        c.handleKeyDown(CONFIRM)

        assertTrue(c.currentScreen is IGMScreen.CheatsHardcoreWarning)
        assertEquals(0, bridge.toggledCheatIndexes.size)

        c.handleKeyDown(CONFIRM)

        assertTrue(c.currentScreen is IGMScreen.Cheats)
        assertEquals(listOf(0), bridge.toggledCheatIndexes)
        assertTrue(c.cheatItems.value[0].enabled)
    }

    @Test fun `backing out of the warning enables nothing`() {
        writeCht("a.cht", "One")
        val bridge = bridgeFor("a.cht" to listOf("One")).apply { hardcoreActive = true }
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)
        c.handleKeyDown(CONFIRM)

        c.handleKeyDown(BACK)

        assertTrue(c.currentScreen is IGMScreen.Cheats)
        assertEquals(0, bridge.toggledCheatIndexes.size)
        assertFalse(c.cheatItems.value[0].enabled)
    }

    @Test fun `the warning shows once per session`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeFor("a.cht" to listOf("One", "Two")).apply { hardcoreActive = true }
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)
        c.handleKeyDown(CONFIRM)
        c.handleKeyDown(CONFIRM)

        c.handleKeyDown(DPAD_DOWN)
        c.handleKeyDown(CONFIRM)

        assertTrue(c.currentScreen is IGMScreen.Cheats)
        assertEquals(listOf(0, 1), bridge.toggledCheatIndexes)
    }

    @Test fun `turning a cheat off never warns`() {
        writeCht("a.cht", "One")
        val bridge = bridgeFor("a.cht" to listOf("One")).apply { hardcoreActive = true }
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)
        c.handleKeyDown(CONFIRM)
        c.handleKeyDown(CONFIRM)

        c.handleKeyDown(CONFIRM)

        assertTrue(c.currentScreen is IGMScreen.Cheats)
        assertFalse(c.cheatItems.value[0].enabled)
    }

    @Test fun `no hardcore means no warning`() {
        writeCht("a.cht", "One")
        val bridge = bridgeFor("a.cht" to listOf("One"))
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)

        c.handleKeyDown(CONFIRM)

        assertTrue(c.currentScreen is IGMScreen.Cheats)
        assertEquals(listOf(0), bridge.toggledCheatIndexes)
    }

    @Test fun `backing out preserves the once-per-session opportunity`() {
        writeCht("a.cht", "One")
        val bridge = bridgeFor("a.cht" to listOf("One")).apply { hardcoreActive = true }
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)

        c.handleKeyDown(CONFIRM)
        assertTrue(c.currentScreen is IGMScreen.CheatsHardcoreWarning)

        c.handleKeyDown(BACK)
        assertTrue(c.currentScreen is IGMScreen.Cheats)

        c.handleKeyDown(CONFIRM)

        assertTrue("the warning must show again after backing out", c.currentScreen is IGMScreen.CheatsHardcoreWarning)
        assertTrue(bridge.toggledCheatIndexes.isEmpty())

        c.handleKeyDown(CONFIRM)

        assertTrue(c.currentScreen is IGMScreen.Cheats)
        assertEquals(listOf(0), bridge.toggledCheatIndexes)
        assertTrue(c.cheatItems.value[0].enabled)
    }

    @Test fun `the restore offer goes away once the remembered set is already on`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeFor("a.cht" to listOf("One", "Two"))
        manager().saveLastUsed("a.cht", setOf(CheatIdentity.hash("Two", "CODE1")))
        val c = testController(bridge)
        var restored = -1
        c.onCheatsRestored = { restored = it }
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)
        assertTrue(c.cheatHasRemembered.value)

        c.handleKeyDown(DPAD_DOWN)
        c.handleKeyDown(DPAD_DOWN)
        c.handleKeyDown(CONFIRM)
        assertTrue(c.cheatItems.value[1].enabled)

        assertFalse("the offer goes with its work", c.cheatHasRemembered.value)
        assertEquals(listOf(1), bridge.toggledCheatIndexes)
        assertEquals(0, bridge.cheatApplies)
        assertEquals(-1, restored)
    }

    @Test fun `attaching asks for the file before any menu opens`() {
        writeCht("a.cht", "One")
        val bridge = bridgeFor("a.cht" to listOf("One")).apply { deferCheatLoads = true }
        val c = testController(bridge)

        c.attachCheats(manager())

        assertEquals(1, bridge.loadedCheatPaths.size)
        assertTrue(bridge.loadedCheatPaths[0].endsWith("a.cht"))
        assertNull(c.currentScreen)
    }

    @Test fun `the row stays away until a snapshot lands`() {
        writeCht("a.cht", "One")
        val bridge = bridgeFor("a.cht" to listOf("One")).apply { deferCheatLoads = true }
        val c = testController(bridge)
        c.attachCheats(manager())

        c.openMenu()
        assertFalse(c.buildMenuOptions().hasCheats)

        bridge.deliverCheats()
        c.openMenu()

        assertTrue(c.buildMenuOptions().hasCheats)
        onCheatsRow(c); c.handleKeyDown(CONFIRM)
        assertEquals(listOf("One"), c.cheatItems.value.map { it.label })
    }

    @Test fun `a snapshot with no rows is not the file's data and is asked for again`() {
        writeCht("a.cht", "One")
        val path = File(tmp.root, "Cheats/nes/Game/a.cht").absolutePath
        val bridge = FakeRetroArchBridge().apply {
            cheatRowsByPath[path] = emptyList()
            deferCheatLoads = true
        }
        val c = testController(bridge)
        c.attachCheats(manager())

        bridge.deliverCheats()

        assertFalse(c.buildMenuOptions().hasCheats)

        c.openMenu()

        assertFalse(c.buildMenuOptions().hasCheats)
        assertEquals(2, bridge.loadedCheatPaths.size)

        bridge.cheatRowsByPath[path] =
            listOf(RetroArchBridge.CheatRow(0, "One", "CODE0", enabled = false, supported = true))
        bridge.deliverCheats()

        assertTrue(c.buildMenuOptions().hasCheats)
    }

    @Test fun `an empty answer does not turn away the snapshot queued behind it`() {
        writeCht("a.cht", "One")
        val path = File(tmp.root, "Cheats/nes/Game/a.cht").absolutePath
        val bridge = FakeRetroArchBridge().apply {
            cheatRowsByPath[path] = emptyList()
            deferCheatLoads = true
        }
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu()
        assertEquals(2, bridge.loadedCheatPaths.size)

        bridge.deliverCheats()
        assertFalse(c.buildMenuOptions().hasCheats)

        bridge.cheatRowsByPath[path] =
            listOf(RetroArchBridge.CheatRow(0, "One", "CODE0", enabled = false, supported = true))
        bridge.deliverCheats()

        assertTrue(c.buildMenuOptions().hasCheats)
    }

    @Test fun `a file RetroArch refuses draws nothing and offers no row`() {
        writeCht("a.cht", "One", "Two")
        val path = File(tmp.root, "Cheats/nes/Game/a.cht").absolutePath
        val bridge = FakeRetroArchBridge().apply { cheatRowsByPath[path] = emptyList() }
        val c = testController(bridge)
        c.attachCheats(manager())

        c.openMenu()

        assertFalse(c.buildMenuOptions().hasCheats)
        assertTrue(c.cheatItems.value.isEmpty())
        assertTrue("the refused file draws no rows at all", c.cheatVisibleItems.value.isEmpty())

        bridge.cheatRowsByPath[path] = listOf(
            RetroArchBridge.CheatRow(0, "One", "CODE0", enabled = false, supported = true),
            RetroArchBridge.CheatRow(1, "Two", "CODE1", enabled = false, supported = true),
        )
        c.openMenu()

        assertTrue(c.buildMenuOptions().hasCheats)
        assertEquals(listOf("One", "Two"), c.cheatItems.value.map { it.label })
    }

    @Test fun `a file RetroArch takes but supports nothing from still shows the row`() {
        writeCht("a.cht", "One")
        val c = testController(bridgeWithSupport("a.cht" to listOf("One" to false)))
        c.attachCheats(manager())

        c.openMenu()

        assertTrue(c.buildMenuOptions().hasCheats)
    }

    @Test fun `a disc switch reloads at once and the row follows the data`() {
        writeCht("a.cht", "One")
        val bridge = bridgeFor("a.cht" to listOf("One")).apply { discs = 2; deferCheatLoads = true }
        val c = testController(bridge)
        c.attachCheats(manager())
        bridge.deliverCheats()
        c.openMenu()
        assertTrue(c.buildMenuOptions().hasCheats)

        val discIndex = c.buildMenuOptions().switchDiscIndex
        c.replaceTop((c.currentScreen as IGMScreen.Menu).copy(selectedIndex = discIndex))
        c.handleKeyDown(DPAD_RIGHT)

        assertEquals(2, bridge.loadedCheatPaths.size)
        assertFalse("the new disc has no data yet", c.buildMenuOptions().hasCheats)

        bridge.deliverCheats()

        assertTrue(c.buildMenuOptions().hasCheats)
    }

    @Test fun `the remembered menu row survives the cheats row coming back`() {
        writeCht("a.cht", "One")
        val bridge = bridgeFor("a.cht" to listOf("One")).apply { discs = 2; deferCheatLoads = true }
        val c = testController(bridge)
        c.attachCheats(manager())
        bridge.deliverCheats()
        c.openMenu()

        val discIndex = c.buildMenuOptions().switchDiscIndex
        c.replaceTop((c.currentScreen as IGMScreen.Menu).copy(selectedIndex = discIndex))
        c.handleKeyDown(DPAD_RIGHT)
        assertFalse(c.buildMenuOptions().hasCheats)

        val quitIndex = c.buildMenuOptions().quitIndex
        c.replaceTop((c.currentScreen as IGMScreen.Menu).copy(selectedIndex = quitIndex))
        c.closeMenu()

        bridge.deliverCheats()
        c.openMenu()

        val opts = c.buildMenuOptions()
        assertTrue(opts.hasCheats)
        val selected = (c.currentScreen as IGMScreen.Menu).selectedIndex
        assertEquals(IgmMenuAction.QUIT, opts.actionAt(selected))
    }

    @Test fun `two disc switches while loads are in flight discard exactly what is in flight`() {
        writeCht("a.cht", "One")
        val bridge = bridgeFor("a.cht" to listOf("One")).apply { discs = 3; deferCheatLoads = true }
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu()
        assertEquals(2, bridge.loadedCheatPaths.size)

        val discIndex = c.buildMenuOptions().switchDiscIndex
        c.replaceTop((c.currentScreen as IGMScreen.Menu).copy(selectedIndex = discIndex))
        c.handleKeyDown(DPAD_RIGHT)
        c.handleKeyDown(DPAD_RIGHT)
        assertEquals(4, bridge.loadedCheatPaths.size)

        repeat(3) { bridge.deliverCheats() }

        assertFalse(c.buildMenuOptions().hasCheats)

        bridge.deliverCheats()

        assertTrue("the last disc's own snapshot must still be taken", c.buildMenuOptions().hasCheats)
    }

    @Test fun `the selection stays on the disc row when the cheats row goes away`() {
        writeCht("a.cht", "One")
        val bridge = bridgeFor("a.cht" to listOf("One")).apply { discs = 2; deferCheatLoads = true }
        val c = testController(bridge)
        c.attachCheats(manager())
        bridge.deliverCheats()
        c.openMenu()
        val discIndex = c.buildMenuOptions().switchDiscIndex
        c.replaceTop((c.currentScreen as IGMScreen.Menu).copy(selectedIndex = discIndex))

        c.handleKeyDown(DPAD_RIGHT)

        val opts = c.buildMenuOptions()
        assertFalse(opts.hasCheats)
        val selected = (c.currentScreen as IGMScreen.Menu).selectedIndex
        assertEquals(IgmMenuAction.SWITCH_DISC, opts.actionAt(selected))

        c.handleKeyDown(DPAD_RIGHT)

        assertEquals(0, bridge.disc)
    }

    @Test fun `a disc switch stops drawing the old rows while the file reloads`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeFor("a.cht" to listOf("One", "Two"))
            .apply { discs = 2; deferCheatLoads = true }
        val c = testController(bridge)
        c.attachCheats(manager())
        bridge.deliverCheats()
        c.openMenu()
        assertEquals(listOf("One", "Two"), c.cheatVisibleItems.value.map { it.label })

        val discIndex = c.buildMenuOptions().switchDiscIndex
        c.replaceTop((c.currentScreen as IGMScreen.Menu).copy(selectedIndex = discIndex))
        c.handleKeyDown(DPAD_RIGHT)

        assertTrue(c.cheatItems.value.isEmpty())
        assertTrue(c.cheatVisibleItems.value.isEmpty())
        assertFalse(c.buildMenuOptions().hasCheats)

        bridge.deliverCheats()

        assertEquals(listOf("One", "Two"), c.cheatVisibleItems.value.map { it.label })
    }

    @Test fun `a restore under the off filter leaves the list usable`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeFor("a.cht" to listOf("One", "Two"))
        manager().saveLastUsed("a.cht", setOf(CheatIdentity.hash("One", "CODE0")))
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)

        c.handleKeyDown(WEST)
        c.handleKeyDown(WEST)
        assertEquals(listOf("One", "Two"), c.cheatVisibleItems.value.map { it.label })
        assertEquals(0, selection(c))

        c.handleKeyDown(CONFIRM)

        assertEquals(listOf("Two"), c.cheatVisibleItems.value.map { it.label })
        assertFalse(c.cheatHasRemembered.value)
        assertEquals(0, selection(c))

        c.handleKeyDown(DPAD_DOWN)
        c.handleKeyDown(CONFIRM)

        assertEquals(listOf(0, 1), bridge.toggledCheatIndexes)
    }

    @Test fun `the restore row is absent when there is nothing to put back`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeFor("a.cht" to listOf("One", "Two"))
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)

        assertFalse(c.cheatHasRemembered.value)
        assertEquals("with no offer the first cheat is row 0", 0, selection(c))

        c.handleKeyDown(CONFIRM)

        assertEquals("row 0 is a cheat now, not an action", listOf(0), bridge.toggledCheatIndexes)
    }

    @Test fun `the walk runs restore, then the cheats`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeFor("a.cht" to listOf("One", "Two"))
        manager().saveLastUsed("a.cht", setOf(CheatIdentity.hash("Two", "CODE1")))
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)

        assertEquals(0, selection(c))

        c.handleKeyDown(DPAD_DOWN)
        assertEquals(1, selection(c))

        c.handleKeyDown(DPAD_DOWN)
        assertEquals(2, selection(c))

        c.handleKeyDown(DPAD_DOWN)
        assertEquals("the walk wraps at the last cheat", 0, selection(c))

        c.handleKeyDown(DPAD_UP)
        assertEquals(2, selection(c))
    }

    @Test fun `restoring in hardcore does not warn, and leaves the warning unspent`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeFor("a.cht" to listOf("One", "Two")).apply { hardcoreActive = true }
        manager().saveLastUsed("a.cht", setOf(CheatIdentity.hash("One", "CODE0")))
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)

        c.handleKeyDown(CONFIRM)

        assertTrue("restore never warned before, and must not start", c.currentScreen is IGMScreen.Cheats)
        assertEquals(listOf(0), bridge.toggledCheatIndexes)
        assertTrue(c.cheatItems.value[0].enabled)

        c.handleKeyDown(DPAD_DOWN)
        c.handleKeyDown(CONFIRM)

        assertTrue("the once-per-session warning is still unspent", c.currentScreen is IGMScreen.CheatsHardcoreWarning)
    }

    @Test fun `the north button no longer restores`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeFor("a.cht" to listOf("One", "Two"))
        manager().saveLastUsed("a.cht", setOf(CheatIdentity.hash("Two", "CODE1")))
        val c = testController(bridge)
        var restored = -1
        c.onCheatsRestored = { restored = it }
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)

        c.handleKeyDown(NORTH)

        assertEquals(-1, restored)
        assertTrue(bridge.toggledCheatIndexes.isEmpty())
        assertTrue(c.cheatHasRemembered.value)
        assertEquals(0, selection(c))
    }

    @Test fun `the filter cycles through all, on and off`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeFor("a.cht" to listOf("One", "Two"))
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)
        c.handleKeyDown(CONFIRM)
        assertTrue(c.cheatItems.value[0].enabled)

        c.handleKeyDown(WEST)

        assertEquals(CheatFilter.ON, c.cheatFilter.value)
        assertEquals(listOf("One"), c.cheatVisibleItems.value.map { it.label })

        c.handleKeyDown(WEST)

        assertEquals(CheatFilter.OFF, c.cheatFilter.value)
        assertEquals(listOf("Two"), c.cheatVisibleItems.value.map { it.label })

        c.handleKeyDown(WEST)

        assertEquals(CheatFilter.ALL, c.cheatFilter.value)
        assertEquals(listOf("One", "Two"), c.cheatVisibleItems.value.map { it.label })
        assertEquals(listOf("One", "Two"), c.cheatItems.value.map { it.label })
    }

    @Test fun `a toggle under a filter hits the selected cheat, not its position`() {
        writeCht("a.cht", "One", "Two", "Three")
        val bridge = bridgeFor("a.cht" to listOf("One", "Two", "Three"))
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)
        c.handleKeyDown(CONFIRM)

        c.handleKeyDown(WEST)
        c.handleKeyDown(WEST)

        assertEquals(listOf("Two", "Three"), c.cheatVisibleItems.value.map { it.label })

        c.handleKeyDown(DPAD_DOWN)
        c.handleKeyDown(CONFIRM)

        assertEquals(listOf(0, 2), bridge.toggledCheatIndexes)
        assertTrue(c.cheatItems.value[2].enabled)
        assertFalse(c.cheatItems.value[1].enabled)
    }

    @Test fun `the selection follows its cheat across a filter flip`() {
        writeCht("a.cht", "One", "Two", "Three")
        val bridge = bridgeFor("a.cht" to listOf("One", "Two", "Three"))
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)
        c.handleKeyDown(DPAD_DOWN)
        c.handleKeyDown(DPAD_DOWN)
        c.handleKeyDown(CONFIRM)
        assertTrue(c.cheatItems.value[2].enabled)
        assertEquals(2, selection(c))

        c.handleKeyDown(WEST)

        assertEquals(listOf("Three"), c.cheatVisibleItems.value.map { it.label })
        assertEquals(0, selection(c))

        c.handleKeyDown(CONFIRM)

        assertEquals(listOf(2, 2), bridge.toggledCheatIndexes)
    }

    @Test fun `entry lands on the restore row even when the first cheat is dead`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeWithSupport("a.cht" to listOf("One" to false, "Two" to true))
        manager().saveLastUsed("a.cht", setOf(CheatIdentity.hash("Two", "CODE1")))
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)

        assertTrue(c.cheatHasRemembered.value)
        assertEquals(0, selection(c))

        c.handleKeyDown(CONFIRM)

        assertEquals("row 0 was the offer, not a cheat", listOf(1), bridge.toggledCheatIndexes)
        assertEquals(1, bridge.cheatApplies)
    }

    @Test fun `a filter that hides everything parks on the restore row when there is one`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeFor("a.cht" to listOf("One", "Two"))
        manager().saveLastUsed("a.cht", setOf(CheatIdentity.hash("Two", "CODE1")))
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)

        c.handleKeyDown(WEST)

        assertTrue(c.cheatVisibleItems.value.isEmpty())
        assertEquals(0, selection(c))

        c.handleKeyDown(CONFIRM)

        assertEquals(listOf(1), bridge.toggledCheatIndexes)
    }

    @Test fun `a filter that hides everything leaves nothing selected`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeFor("a.cht" to listOf("One", "Two"))
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)

        c.handleKeyDown(WEST)

        assertTrue(c.cheatVisibleItems.value.isEmpty())
        assertEquals(-1, selection(c))

        c.handleKeyDown(DPAD_DOWN)
        c.handleKeyDown(DPAD_DOWN)
        c.handleKeyDown(DPAD_UP)

        assertEquals("there is nothing to walk to", -1, selection(c))

        c.handleKeyDown(CONFIRM)

        assertTrue(bridge.toggledCheatIndexes.isEmpty())

        c.handleKeyDown(WEST)

        assertEquals(listOf("One", "Two"), c.cheatVisibleItems.value.map { it.label })
        assertEquals("the rows coming back take the selection", 0, selection(c))
    }

    @Test fun `a toggle that hides its own row keeps the list usable`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeFor("a.cht" to listOf("One", "Two"))
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)

        c.handleKeyDown(WEST)
        c.handleKeyDown(WEST)
        assertEquals(listOf("One", "Two"), c.cheatVisibleItems.value.map { it.label })

        c.handleKeyDown(DPAD_DOWN)
        c.handleKeyDown(CONFIRM)

        assertEquals(listOf("One"), c.cheatVisibleItems.value.map { it.label })
        assertEquals(0, selection(c))

        c.handleKeyDown(CONFIRM)

        assertEquals(listOf(1, 0), bridge.toggledCheatIndexes)
    }

    @Test fun `the hardcore warning arms the cheat under the selection, not its position`() {
        writeCht("a.cht", "One", "Two", "Three")
        val path = File(tmp.root, "Cheats/nes/Game/a.cht").absolutePath
        val bridge = FakeRetroArchBridge().apply {
            hardcoreActive = true
            cheatRowsByPath[path] = listOf(
                RetroArchBridge.CheatRow(0, "One", "CODE0", enabled = true, supported = true),
                RetroArchBridge.CheatRow(1, "Two", "CODE1", enabled = false, supported = true),
                RetroArchBridge.CheatRow(2, "Three", "CODE2", enabled = false, supported = true),
            )
        }
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)

        c.handleKeyDown(WEST)
        c.handleKeyDown(WEST)
        assertEquals(listOf("Two", "Three"), c.cheatVisibleItems.value.map { it.label })

        c.handleKeyDown(DPAD_DOWN)
        c.handleKeyDown(CONFIRM)

        assertTrue(c.currentScreen is IGMScreen.CheatsHardcoreWarning)
        assertEquals(2, (c.currentScreen as IGMScreen.CheatsHardcoreWarning).pendingRowIndex)

        c.handleKeyDown(CONFIRM)

        assertEquals(listOf(2), bridge.toggledCheatIndexes)
        assertTrue(c.cheatItems.value[2].enabled)
    }

    @Test fun `the restore offer survives a filter that hides the remembered row`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeFor("a.cht" to listOf("One", "Two"))
        manager().saveLastUsed("a.cht", setOf(CheatIdentity.hash("Two", "CODE1")))
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)
        assertTrue(c.cheatHasRemembered.value)

        c.handleKeyDown(WEST)

        assertTrue(c.cheatVisibleItems.value.isEmpty())
        assertTrue(c.cheatHasRemembered.value)
        assertEquals(0, selection(c))

        c.handleKeyDown(CONFIRM)

        assertEquals(listOf(1), bridge.toggledCheatIndexes)
        assertEquals(listOf("Two"), c.cheatVisibleItems.value.map { it.label })
    }

    @Test fun `a remembered row RetroArch did not take is never offered`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeWithSupport("a.cht" to listOf("One" to true, "Two" to false))
        manager().saveLastUsed("a.cht", setOf(CheatIdentity.hash("Two", "CODE1")))
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)

        assertFalse(c.cheatHasRemembered.value)
    }
}
