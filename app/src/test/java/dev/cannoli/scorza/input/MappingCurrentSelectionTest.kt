package dev.cannoli.scorza.input

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.config.EmulatorChoice
import dev.cannoli.scorza.config.EmulatorSource
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.db.GameOverrideStore
import dev.cannoli.scorza.db.execute
import dev.cannoli.scorza.db.executeReturningId
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.ui.screens.MappingItem
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The invariant this whole rework exists for: a stored choice always renders as exactly one
 * selected row, whatever the option generator can or cannot produce.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MappingCurrentSelectionTest {

    @get:Rule val tmp = org.junit.rules.TemporaryFolder()

    private class Fixture(
        val builder: EmulatorMappingBuilder,
        val config: PlatformConfig,
        val store: GameOverrideStore,
        val romId: Long,
    )

    private fun fixture(name: String, bundled: List<String> = listOf("mgba_libretro")): Fixture {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(tmp.root, name).apply { mkdirs() }
        val libs = File(tmp.root, "$name-libs").apply { mkdirs() }
        bundled.forEach { File(libs, "${it}_android.so").writeText("stub") }

        val paths = mockk<CannoliPathsProvider>()
        every { paths.root } returns root
        every { paths.romDir } returns File(root, "Roms").apply { mkdirs() }
        val db = CannoliDatabase(paths)
        db.execute("INSERT OR IGNORE INTO platforms (tag, display_name) VALUES (?, ?)", "GBA", "GBA")
        val romId = db.executeReturningId(
            "INSERT INTO roms (path, platform_tag, display_name, sort_key, name_normalized) VALUES (?, ?, ?, ?, ?)",
            "GBA/game.gba", "GBA", "Game", "game", "game",
        )

        val config = PlatformConfig(root, ctx.assets, nativeLibDir = libs.absolutePath).also { it.load() }
        val settings = dev.cannoli.scorza.settings.SettingsRepository(ctx).also { it.sdCardRoot = root.absolutePath }
        val cores = dev.cannoli.scorza.launcher.InstalledCoreService(ctx, settings)
        val store = GameOverrideStore(db)
        return Fixture(EmulatorMappingBuilder(config, cores, settings, store, ctx), config, store, romId)
    }

    private fun currentRows(screen: LauncherScreen.PlatformMapping) =
        screen.items.filterIsInstance<MappingItem.EmulatorOption>().filter { it.isCurrent }

    private fun assertExactlyOneCurrent(name: String, choice: EmulatorChoice, gameScoped: Boolean) {
        val f = fixture(name)
        if (gameScoped) f.store.put(f.romId, choice) else f.config.setPlatformChoice("GBA", choice)
        val screen = f.builder.buildPlatformMapping(
            "GBA", "GBA", showAll = true, romId = if (gameScoped) f.romId else null,
        )
        val current = currentRows(screen)
        assertEquals("$name: expected exactly one selected row", 1, current.size)
        val opt = current.single().option
        if (choice.source == EmulatorSource.Standalone) {
            assertEquals(choice.appPackage, opt.appPackage)
        } else {
            assertEquals(choice.coreId, opt.coreId)
        }
    }

    @Test fun `platform internal core is current`() =
        assertExactlyOneCurrent("cur-p-int", EmulatorChoice(EmulatorSource.Internal, "mgba_libretro"), false)

    @Test fun `platform retroarch core is current`() =
        assertExactlyOneCurrent("cur-p-ra", EmulatorChoice(EmulatorSource.RetroArch, "mgba_libretro"), false)

    @Test fun `platform standalone app is current`() =
        assertExactlyOneCurrent(
            "cur-p-app", EmulatorChoice(EmulatorSource.Standalone, appPackage = "com.fastemulator.gba"), false,
        )

    @Test fun `game internal core is current`() =
        assertExactlyOneCurrent("cur-g-int", EmulatorChoice(EmulatorSource.Internal, "mgba_libretro"), true)

    @Test fun `game retroarch core is current`() =
        assertExactlyOneCurrent("cur-g-ra", EmulatorChoice(EmulatorSource.RetroArch, "mgba_libretro"), true)

    @Test fun `game standalone app is current`() =
        assertExactlyOneCurrent(
            "cur-g-app", EmulatorChoice(EmulatorSource.Standalone, appPackage = "com.fastemulator.gba"), true,
        )

    // A shipped update can drop an app from platforms.json, and a hand-edited cores.json can
    // name a core that is not a candidate for the tag. Neither may make the row disappear.
    @Test fun `a core the generator cannot produce is still current`() =
        assertExactlyOneCurrent("cur-orphan-core", EmulatorChoice(EmulatorSource.RetroArch, "not_a_real_core"), false)

    @Test fun `an app the generator cannot produce is still current`() =
        assertExactlyOneCurrent(
            "cur-orphan-app", EmulatorChoice(EmulatorSource.Standalone, appPackage = "com.example.gone"), true,
        )

    @Test fun `with no game override the platform default row is the current one`() {
        val f = fixture("cur-g-none")
        val screen = f.builder.buildPlatformMapping("GBA", "GBA", showAll = true, romId = f.romId)
        val default = screen.items.filterIsInstance<MappingItem.PlatformDefault>().single()
        assertTrue(default.isCurrent)
        assertEquals(0, currentRows(screen).size)
    }

    @Test fun `selectCurrent puts the cursor on the current row`() {
        val f = fixture("cur-cursor")
        f.config.setPlatformChoice("GBA", EmulatorChoice(EmulatorSource.RetroArch, "mgba_libretro"))
        val screen = f.builder.buildPlatformMapping("GBA", "GBA", showAll = true, selectCurrent = true)
        val item = screen.items.filter { it.isSelectable }[screen.selectedIndex]
        assertTrue(item is MappingItem.EmulatorOption && item.isCurrent)
    }

    @Test fun `game scope drops the platform wide actions and platform scope keeps them`() {
        val f = fixture("cur-actions")
        f.config.setPlatformChoice("GBA", EmulatorChoice(EmulatorSource.Internal, "mgba_libretro"))
        val scoped = f.builder.buildPlatformMapping("GBA", "GBA", showAll = true, romId = f.romId)
        assertTrue(scoped.items.none { it is MappingItem.Action })
        assertEquals(f.romId, scoped.romId)

        val platform = f.builder.buildPlatformMapping("GBA", "GBA", showAll = true)
        assertTrue(platform.items.any { it is MappingItem.Action })
        assertTrue(platform.items.none { it is MappingItem.PlatformDefault })
    }

    @Test fun `rebuild preserves tag and game scope`() {
        val f = fixture("cur-rebuild")
        val screen = f.builder.buildPlatformMapping(
            "GBA", "GBA", showAll = false, romId = f.romId, gameName = "Game",
        )
        val again = f.builder.rebuild(screen, showAll = true)
        assertEquals(f.romId, again.romId)
        assertEquals("GBA", again.tag)
        assertEquals("Game", again.gameName)
        assertTrue(again.showAll)
    }
}
