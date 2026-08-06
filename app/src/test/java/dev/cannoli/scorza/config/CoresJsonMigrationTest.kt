package dev.cannoli.scorza.config

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoresJsonMigrationTest {

    @get:Rule val tmp = TemporaryFolder()

    private val RA_JSON =
        """{"v":2,"platforms":{"NES":{"source":"RetroArch","core":"nestopia_libretro"}}}"""

    private fun load(name: String, json: String, bundledDir: String? = null): PlatformConfig {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(tmp.root, name).apply { mkdirs() }
        File(root, "Config").apply { mkdirs() }.also { File(it, "cores.json").writeText(json) }
        return PlatformConfig(root, ctx.assets, nativeLibDir = bundledDir).also { it.load() }
    }

    private fun loadWithLegacyRa(name: String, json: String, legacyExternal: String?): PlatformConfig {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(tmp.root, name).apply { mkdirs() }
        File(root, "Config").apply { mkdirs() }.also { File(it, "cores.json").writeText(json) }
        return PlatformConfig(
            { root }, ctx.assets, legacyExternalRaPackage = { legacyExternal },
        ).also { it.load() }
    }

    // The built-in libretro runner is gone. A v1 platform mapped to it has to land on the embedded
    // RetroArch, keeping its core, rather than on a source that no longer exists.
    @Test fun `explicit Internal migrates to Embedded and keeps the core`() = assertEquals(
        EmulatorChoice(EmulatorSource.Embedded, "nestopia_libretro"),
        load("m-int", """{"cores":{"NES":"nestopia_libretro"},"runners":{"NES":"Internal"}}""")
            .getPlatformChoice("NES"),
    )

    @Test fun `RicottaArch migrates to Embedded`() = assertEquals(
        EmulatorSource.Embedded,
        load("m-ric", """{"cores":{"NES":"n_libretro"},"runners":{"NES":"RicottaArch"}}""")
            .getPlatformChoice("NES")?.source,
    )

    @Test fun `the Unknown suffix migrates to Embedded`() = assertEquals(
        EmulatorSource.Embedded,
        load("m-unk", """{"cores":{"NES":"n_libretro"},"runners":{"NES":"RetroArch (Unknown)"}}""")
            .getPlatformChoice("NES")?.source,
    )

    @Test fun `a raw package id migrates to Embedded`() = assertEquals(
        EmulatorSource.Embedded,
        load("m-raw", """{"cores":{"NES":"n_libretro"},"runners":{"NES":"com.retroarch.ra32"}}""")
            .getPlatformChoice("NES")?.source,
    )

    @Test fun `App migrates to Standalone and keeps the package`() = assertEquals(
        EmulatorChoice(EmulatorSource.Standalone, appPackage = "com.fastemulator.gba"),
        load("m-app", """{"cores":{},"runners":{"GBA":"App"},"apps":{"GBA":"com.fastemulator.gba"}}""")
            .getPlatformChoice("GBA"),
    )

    @Test fun `an app with no runner migrates to Standalone`() = assertEquals(
        EmulatorSource.Standalone,
        load("m-app2", """{"cores":{},"apps":{"GBA":"com.fastemulator.gba"}}""")
            .getPlatformChoice("GBA")?.source,
    )

    @Test fun `a runnerless core with no bundled so migrates to Embedded`() = assertEquals(
        EmulatorSource.Embedded,
        load("m-nolib", """{"cores":{"NES":"nestopia_libretro"}}""").getPlatformChoice("NES")?.source,
    )

    // v1 re-derived the source from a bundled .so on every read. With no runner to load one, a
    // runnerless core resolves to RetroArch whether or not the .so is there.
    @Test fun `a runnerless core with a bundled so migrates to Embedded`() {
        val libs = File(tmp.root, "m-libs").apply { mkdirs() }
        File(libs, "nestopia_libretro_android.so").writeText("stub")
        assertEquals(
            EmulatorSource.Embedded,
            load("m-lib", """{"cores":{"NES":"nestopia_libretro"}}""", libs.absolutePath)
                .getPlatformChoice("NES")?.source,
        )
    }

    @Test fun `a v2 file round trips`() = assertEquals(
        EmulatorChoice(EmulatorSource.Embedded, "nestopia_libretro"),
        load("m-v2", """{"v":2,"platforms":{"NES":{"source":"Embedded","core":"nestopia_libretro"}}}""")
            .getPlatformChoice("NES"),
    )

    // A v2 file written before the runner was removed still names Internal as its source.
    @Test fun `a stored v2 Internal source migrates to Embedded`() = assertEquals(
        EmulatorChoice(EmulatorSource.Embedded, "nestopia_libretro"),
        load("m-v2-int", """{"v":2,"platforms":{"NES":{"source":"Internal","core":"nestopia_libretro"}}}""")
            .getPlatformChoice("NES"),
    )

    // Before the source split, a stored RetroArch choice did not say which RetroArch it meant,
    // because a single global setting answered that. These two cover both readings of it.
    @Test fun `a packageless RetroArch choice migrates to Embedded when no external was configured`() =
        assertEquals(
            EmulatorChoice(EmulatorSource.Embedded, "nestopia_libretro"),
            loadWithLegacyRa("m-v2-ra-emb", RA_JSON, legacyExternal = null).getPlatformChoice("NES"),
        )

    @Test fun `a packageless RetroArch choice keeps the configured external install`() = assertEquals(
        EmulatorChoice(EmulatorSource.RetroArch, "nestopia_libretro", "com.retroarch.aarch64"),
        loadWithLegacyRa("m-v2-ra-ext", RA_JSON, legacyExternal = "com.retroarch.aarch64")
            .getPlatformChoice("NES"),
    )

    @Test fun `a v2 standalone entry round trips`() = assertEquals(
        EmulatorChoice(EmulatorSource.Standalone, appPackage = "com.armsx2"),
        load("m-v2app", """{"v":2,"platforms":{"PS2":{"source":"Standalone","app":"com.armsx2"}}}""")
            .getPlatformChoice("PS2"),
    )

    // v1 setCoreMapping dropped the core when the pick equalled the platform default but kept
    // the runner, so this shape is what an explicit "use the default core" pick looked like.
    // Migrating it to an empty core produced a blank, permanently not-installed row.
    @Test fun `a runner with no core resolves to the platform default core`() = assertEquals(
        EmulatorChoice(EmulatorSource.Embedded, "mgba_libretro"),
        load("m-nocore", """{"cores":{},"runners":{"GBA":"Internal"}}""").getPlatformChoice("GBA"),
    )

    @Test fun `a runner with no core and no platform default is dropped`() =
        assertNull(load("m-nocore-nodef", """{"cores":{},"runners":{"NOSUCHTAG":"Internal"}}""")
            .getPlatformChoice("NOSUCHTAG"))

    // The broken shape above already reached real devices as v2, so it has to self-heal on read.
    @Test fun `a v2 core choice with no core is dropped rather than rendered blank`() =
        assertNull(load("m-v2-nocore", """{"v":2,"platforms":{"GBA":{"source":"Internal"}}}""")
            .getPlatformChoice("GBA"))

    @Test fun `a v2 standalone choice with no app is dropped`() =
        assertNull(load("m-v2-noapp", """{"v":2,"platforms":{"PS2":{"source":"Standalone"}}}""")
            .getPlatformChoice("PS2"))

    @Test fun `an empty file yields no choices`() =
        assertNull(load("m-empty", "{}").getPlatformChoice("NES"))

    @Test fun `a migrated v1 file saves as v2 with no games section`() {
        val pc = load("m-save", """{"cores":{"NES":"nestopia_libretro"},"runners":{"NES":"Internal"}}""")
        pc.saveCoreMappings()
        val written = File(tmp.root, "m-save/Config/cores.json").readText()
        val json = org.json.JSONObject(written)
        assertEquals(PlatformConfig.CORES_JSON_VERSION, json.getInt("v"))
        assertEquals("Embedded", json.getJSONObject("platforms").getJSONObject("NES").getString("source"))
        assertNull(json.optJSONObject("games"))
        assertNull(json.optJSONObject("cores"))
    }
}
