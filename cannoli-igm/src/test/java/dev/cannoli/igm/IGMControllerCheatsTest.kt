package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun `left and right cycle the file only when there is more than one`() {
        writeCht("a.cht", "One")
        writeCht("b.cht", "Three")
        val bridge = bridgeFor("a.cht" to listOf("One"), "b.cht" to listOf("Three"))
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)
        assertEquals("b.cht", run { c.handleKeyDown(DPAD_RIGHT); c.cheatFileName.value })
        assertEquals(2, bridge.loadedCheatPaths.size)
        assertEquals(listOf("Three"), c.cheatItems.value.map { it.label })

        c.handleKeyDown(DPAD_LEFT)

        assertEquals("a.cht", c.cheatFileName.value)
        assertEquals(3, bridge.loadedCheatPaths.size)
    }

    @Test fun `a single file has no selector row so left and right do nothing`() {
        writeCht("a.cht", "One")
        val bridge = bridgeFor("a.cht" to listOf("One"))
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)

        c.handleKeyDown(DPAD_RIGHT)

        assertEquals(1, bridge.loadedCheatPaths.size)
        assertEquals(0, selection(c))
    }

    @Test fun `switching files turns the previous file's cheats off`() {
        writeCht("a.cht", "One")
        writeCht("b.cht", "Three")
        val bridge = bridgeFor("a.cht" to listOf("One"), "b.cht" to listOf("Three"))
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)
        c.handleKeyDown(DPAD_DOWN)
        c.handleKeyDown(CONFIRM)
        assertTrue(c.cheatItems.value[0].enabled)

        c.handleKeyDown(DPAD_UP)
        c.handleKeyDown(DPAD_RIGHT)

        assertTrue(c.cheatItems.value.none { it.enabled })
    }

    @Test fun `north reapplies the last used set and reports the count`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeFor("a.cht" to listOf("One", "Two"))
        manager().saveLastUsed("a.cht", setOf(CheatIdentity.hash("Two", "CODE1")))
        val c = testController(bridge)
        var restored = -1
        c.onCheatsRestored = { restored = it }
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)
        assertTrue(c.cheatHasRemembered.value)

        c.handleKeyDown(NORTH)

        assertEquals(1, restored)
        assertTrue(c.cheatItems.value[1].enabled)
        assertEquals(listOf(1), bridge.toggledCheatIndexes)
        assertEquals(1, bridge.cheatApplies)
    }

    @Test fun `the remembered file is selected first when it still exists`() {
        writeCht("a.cht", "One")
        writeCht("b.cht", "Three")
        val bridge = bridgeFor("a.cht" to listOf("One"), "b.cht" to listOf("Three"))
        manager().saveLastUsed("b.cht", setOf(CheatIdentity.hash("Three", "CODE0")))
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)

        assertEquals("b.cht", c.cheatFileName.value)
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

    @Test fun `a set remembered for another file is never offered against this one`() {
        writeCht("a.cht", "One")
        writeCht("b.cht", "One")
        val bridge = bridgeFor("a.cht" to listOf("One"), "b.cht" to listOf("One"))
        manager().saveLastUsed("b.cht", setOf(CheatIdentity.hash("One", "CODE0")))
        val c = testController(bridge)
        var restored = -1
        c.onCheatsRestored = { restored = it }
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)
        assertEquals("b.cht", c.cheatFileName.value)
        assertTrue(c.cheatHasRemembered.value)

        c.handleKeyDown(DPAD_RIGHT)
        assertEquals("a.cht", c.cheatFileName.value)

        assertFalse(c.cheatHasRemembered.value)

        c.handleKeyDown(NORTH)

        assertEquals(-1, restored)
        assertTrue(bridge.toggledCheatIndexes.isEmpty())
        assertEquals(0, bridge.cheatApplies)
        assertTrue(c.cheatItems.value.none { it.enabled })
        assertEquals(2, bridge.loadedCheatPaths.size)
    }

    @Test fun `a toggle across a load in flight changes nothing`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeFor("a.cht" to listOf("One", "Two")).apply { deferCheatLoads = true }
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)
        assertTrue(c.cheatItems.value.isEmpty())

        c.handleKeyDown(DPAD_DOWN)
        c.handleKeyDown(CONFIRM)

        assertTrue(bridge.toggledCheatIndexes.isEmpty())
        assertTrue(c.cheatItems.value.isEmpty())

        bridge.deliverCheats()

        assertEquals(listOf("One", "Two"), c.cheatItems.value.map { it.label })
        assertTrue(c.cheatItems.value.none { it.enabled })
    }

    @Test fun `a file cycle across a load in flight is ignored`() {
        writeCht("a.cht", "One")
        writeCht("b.cht", "Three")
        val bridge = bridgeFor("a.cht" to listOf("One"), "b.cht" to listOf("Three"))
            .apply { deferCheatLoads = true }
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)

        c.handleKeyDown(DPAD_RIGHT)

        assertEquals(1, bridge.loadedCheatPaths.size)
        assertEquals("a.cht", c.cheatFileName.value)

        bridge.deliverCheats()
        c.handleKeyDown(DPAD_RIGHT)

        assertEquals(2, bridge.loadedCheatPaths.size)
        assertEquals("b.cht", c.cheatFileName.value)
    }

    @Test fun `a reapply across a load in flight is ignored`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeFor("a.cht" to listOf("One", "Two")).apply { deferCheatLoads = true }
        manager().saveLastUsed("a.cht", setOf(CheatIdentity.hash("Two", "CODE1")))
        val c = testController(bridge)
        var restored = -1
        c.onCheatsRestored = { restored = it }
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)

        c.handleKeyDown(NORTH)

        assertEquals(-1, restored)
        assertTrue(bridge.toggledCheatIndexes.isEmpty())
        assertEquals(0, bridge.cheatApplies)

        bridge.deliverCheats()
        c.handleKeyDown(NORTH)

        assertEquals(1, restored)
        assertEquals(listOf(1), bridge.toggledCheatIndexes)
    }

    @Test fun `a load whose snapshot never arrives is retried on the next entry`() {
        writeCht("a.cht", "One")
        val bridge = bridgeFor("a.cht" to listOf("One")).apply { deferCheatLoads = true }
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)
        assertEquals(1, bridge.loadedCheatPaths.size)
        assertTrue(c.cheatItems.value.isEmpty())
        bridge.dropPendingCheatLoads()

        c.handleKeyDown(BACK)
        c.handleKeyDown(CONFIRM)

        assertEquals(2, bridge.loadedCheatPaths.size)

        bridge.deliverCheats()

        assertEquals(listOf("One"), c.cheatItems.value.map { it.label })
    }

    @Test fun `a late duplicate snapshot does not wipe what the user turned on`() {
        writeCht("a.cht", "One")
        val bridge = bridgeFor("a.cht" to listOf("One")).apply { deferCheatLoads = true }
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)
        c.handleKeyDown(BACK)
        c.handleKeyDown(CONFIRM)
        assertEquals(2, bridge.loadedCheatPaths.size)

        bridge.deliverCheats()
        c.handleKeyDown(CONFIRM)
        assertTrue(c.cheatItems.value[0].enabled)

        bridge.deliverCheats()

        assertTrue(c.cheatItems.value[0].enabled)
        assertEquals(listOf(0), bridge.toggledCheatIndexes)
    }

    @Test fun `a disc switch across a load in flight still reloads on the next entry`() {
        writeCht("a.cht", "One")
        val bridge = bridgeFor("a.cht" to listOf("One")).apply { discs = 2; deferCheatLoads = true }
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)
        c.handleKeyDown(BACK)

        val discIndex = c.buildMenuOptions().switchDiscIndex
        c.replaceTop((c.currentScreen as IGMScreen.Menu).copy(selectedIndex = discIndex))
        c.handleKeyDown(DPAD_RIGHT)
        bridge.deliverCheats()

        assertTrue(c.cheatItems.value.isEmpty())

        onCheatsRow(c)
        c.handleKeyDown(CONFIRM)

        assertEquals(2, bridge.loadedCheatPaths.size)
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

    @Test fun `an unsupported file with siblings starts on the selector row`() {
        writeCht("a.cht", "One")
        writeCht("b.cht", "Three")
        val bridge = bridgeWithSupport(
            "a.cht" to listOf("One" to false),
            "b.cht" to listOf("Three" to true),
        )
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)

        assertEquals(0, selection(c))

        c.handleKeyDown(DPAD_RIGHT)

        assertEquals("b.cht", c.cheatFileName.value)
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

    @Test fun `a snapshot that lands while the screen is closed still places the selection`() {
        writeCht("a.cht", "One", "Two")
        val bridge = bridgeWithSupport("a.cht" to listOf("One" to false, "Two" to true))
            .apply { deferCheatLoads = true }
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)
        c.handleKeyDown(BACK)

        bridge.deliverCheats()

        assertTrue(c.currentScreen is IGMScreen.Menu)

        c.handleKeyDown(CONFIRM)

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

        assertEquals(2, bridge.loadedCheatPaths.size)
        assertTrue(c.cheatItems.value[1].enabled)
    }

    @Test fun `a snapshot from before a disc switch never becomes the new disc's session`() {
        writeCht("a.cht", "One", "Two")
        val path = File(tmp.root, "Cheats/nes/Game/a.cht").absolutePath
        val bridge = bridgeWithSupport("a.cht" to listOf("One" to true, "Two" to true))
            .apply { discs = 2; deferCheatLoads = true }
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)
        c.handleKeyDown(BACK)

        val discIndex = c.buildMenuOptions().switchDiscIndex
        c.replaceTop((c.currentScreen as IGMScreen.Menu).copy(selectedIndex = discIndex))
        c.handleKeyDown(DPAD_RIGHT)

        onCheatsRow(c)
        c.handleKeyDown(CONFIRM)
        assertEquals(2, bridge.loadedCheatPaths.size)

        // The load in flight when the disc changed describes the disc that left. The new disc
        // reinitializes content state, so RetroArch takes a different set of rows from the file.
        bridge.deliverCheats()
        bridge.cheatRowsByPath[path] = listOf(
            RetroArchBridge.CheatRow(0, "One", "CODE0", enabled = false, supported = false),
            RetroArchBridge.CheatRow(1, "Two", "CODE1", enabled = false, supported = true),
        )
        bridge.deliverCheats()

        assertEquals(listOf(false, true), c.cheatItems.value.map { it.supported })
        assertEquals(1, selection(c))
    }

    @Test fun `a snapshot for the previous file never becomes the new file's session`() {
        writeCht("a.cht", "One")
        writeCht("b.cht", "Three")
        val bridge = bridgeFor("a.cht" to listOf("One"), "b.cht" to listOf("Three"))
            .apply { deferCheatLoads = true }
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)
        c.handleKeyDown(BACK)
        c.handleKeyDown(CONFIRM)
        assertEquals(2, bridge.loadedCheatPaths.size)

        bridge.deliverCheats()
        assertEquals(listOf("One"), c.cheatItems.value.map { it.label })

        c.handleKeyDown(DPAD_RIGHT)
        assertEquals("b.cht", c.cheatFileName.value)

        bridge.deliverCheats()

        assertTrue(c.cheatItems.value.isEmpty())

        bridge.deliverCheats()

        assertEquals(listOf("Three"), c.cheatItems.value.map { it.label })
        assertTrue(c.cheatItems.value.all { it.supported })

        c.handleKeyDown(DPAD_DOWN)
        c.handleKeyDown(CONFIRM)

        assertEquals(listOf(0), bridge.toggledCheatIndexes)
        assertTrue(c.cheatItems.value[0].enabled)
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

    @Test fun `backing out before a file cycle re-arms against the new file's live session`() {
        writeCht("a.cht", "Alpha", "Beta")
        writeCht("b.cht", "Three")
        val bridge = bridgeFor("a.cht" to listOf("Alpha", "Beta"), "b.cht" to listOf("Three"))
            .apply { hardcoreActive = true; deferCheatLoads = true }
        val c = testController(bridge)
        c.attachCheats(manager())
        c.openMenu(); onCheatsRow(c); c.handleKeyDown(CONFIRM)
        bridge.deliverCheats()

        // Arm on a.cht's second row (rowIndex 1), then back out before confirming it.
        c.handleKeyDown(DPAD_DOWN)
        c.handleKeyDown(DPAD_DOWN)
        c.handleKeyDown(CONFIRM)
        assertTrue(c.currentScreen is IGMScreen.CheatsHardcoreWarning)

        c.handleKeyDown(BACK)
        assertTrue(c.currentScreen is IGMScreen.Cheats)

        // Cycle to b.cht, which only has one row, so a leaked rowIndex 1 from a.cht would be
        // out of range there and prove the two arms were never conflated.
        c.handleKeyDown(DPAD_UP)
        c.handleKeyDown(DPAD_UP)
        c.handleKeyDown(DPAD_RIGHT)
        bridge.deliverCheats()
        assertEquals("b.cht", c.cheatFileName.value)
        assertEquals(listOf("Three"), c.cheatItems.value.map { it.label })

        c.handleKeyDown(DPAD_DOWN)
        c.handleKeyDown(CONFIRM)

        assertTrue("the warning must re-arm for the new file", c.currentScreen is IGMScreen.CheatsHardcoreWarning)
        assertTrue(bridge.toggledCheatIndexes.isEmpty())

        c.handleKeyDown(CONFIRM)

        assertTrue(c.currentScreen is IGMScreen.Cheats)
        assertEquals("b.cht", c.cheatFileName.value)
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
        c.handleKeyDown(CONFIRM)
        assertTrue(c.cheatItems.value[1].enabled)

        assertFalse(c.cheatHasRemembered.value)

        c.handleKeyDown(NORTH)

        assertEquals(listOf(1), bridge.toggledCheatIndexes)
        assertEquals(0, bridge.cheatApplies)
        assertEquals(-1, restored)
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
