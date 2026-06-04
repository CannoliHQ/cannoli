package dev.cannoli.scorza.launcher

import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.config.AppConfig
import dev.cannoli.scorza.config.DataBinding
import dev.cannoli.scorza.config.ExtraSpec
import dev.cannoli.scorza.config.ExtraValueKind
import dev.cannoli.scorza.config.LaunchMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class EmulatorIntentBuilderTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun rom(): File = tmp.newFile("foo.iso").apply { writeBytes(byteArrayOf(0)) }

    @Test fun `MAIN with explicit activity and uri_string extra`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cfg = AppConfig(
            packageName = "xyz.aethersx2.android",
            activity = "xyz.aethersx2.android.EmulationActivity",
            action = "android.intent.action.MAIN",
            extras = listOf(ExtraSpec("bootPath", ExtraValueKind.FILE_URI_STRING)),
            launchMethod = LaunchMethod.INTENT,
        )
        val resolved = EmulatorIntentBuilder.resolve(ctx, cfg, rom())
        assertEquals(ComponentName("xyz.aethersx2.android", "xyz.aethersx2.android.EmulationActivity"), resolved.component)
        assertNull(resolved.packageName)
        assertEquals("android.intent.action.MAIN", resolved.action)
        assertNull(resolved.dataUri)
        assertEquals(1, resolved.extras.size)
        val extra = resolved.extras[0] as ResolvedExtra.StringExtra
        assertEquals("bootPath", extra.key)
        assertTrue(extra.value.startsWith("content://"))
    }

    @Test fun `VIEW with file_provider data emits FileProvider URI`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cfg = AppConfig(
            packageName = "com.sky.SkyEmu",
            data = DataBinding.FileProvider(),
        )
        val resolved = EmulatorIntentBuilder.resolve(ctx, cfg, rom())
        assertNull(resolved.component)
        assertEquals("com.sky.SkyEmu", resolved.packageName)
        assertEquals("android.intent.action.VIEW", resolved.action)
        assertTrue(resolved.dataUri.toString().startsWith("content://"))
    }

    @Test fun `absolute_path data uses file URI`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cfg = AppConfig(
            packageName = "com.dsemu.drastic",
            data = DataBinding.AbsolutePath,
        )
        val resolved = EmulatorIntentBuilder.resolve(ctx, cfg, rom())
        assertEquals("file", resolved.dataUri?.scheme)
    }

    @Test fun `parcelable extra resolves to UriExtra`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cfg = AppConfig(
            packageName = "me.magnum.melonds",
            extras = listOf(ExtraSpec("uri", ExtraValueKind.FILE_URI_PARCELABLE)),
        )
        val resolved = EmulatorIntentBuilder.resolve(ctx, cfg, rom())
        val extra = resolved.extras[0] as ResolvedExtra.UriExtra
        assertEquals("uri", extra.key)
        assertEquals("content", extra.value.scheme)
    }

    @Test fun `path extra uses absolute path string`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val romFile = rom()
        val cfg = AppConfig(
            packageName = "com.hydra.noods",
            extras = listOf(ExtraSpec("LaunchPath", ExtraValueKind.FILE_PATH)),
        )
        val resolved = EmulatorIntentBuilder.resolve(ctx, cfg, romFile)
        val extra = resolved.extras[0] as ResolvedExtra.StringExtra
        assertEquals(romFile.absolutePath, extra.value)
    }

    @Test fun `string_array extra substitutes rom_contents with first non-blank line`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val romFile = tmp.newFile("Pinball Arcade.psvita").apply { writeText("PCSE00065\n") }
        val cfg = AppConfig(
            packageName = "org.vita3k.emulator",
            activity = "org.vita3k.emulator.Emulator",
            action = "android.intent.action.MAIN",
            extras = listOf(ExtraSpec("AppStartParameters", ExtraValueKind.STRING_ARRAY, listOf("-r", "{rom_contents}"))),
        )
        val resolved = EmulatorIntentBuilder.resolve(ctx, cfg, romFile)
        val extra = resolved.extras[0] as ResolvedExtra.StringArrayExtra
        assertEquals("AppStartParameters", extra.key)
        assertEquals(listOf("-r", "PCSE00065"), extra.values)
    }

    @Test fun `custom scheme builds scheme URI with rom path`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val romFile = rom()
        val cfg = AppConfig(
            packageName = "com.pixelrespawn.linkboy",
            data = DataBinding.CustomScheme("linkboy", "emulator"),
        )
        val resolved = EmulatorIntentBuilder.resolve(ctx, cfg, romFile)
        assertEquals("linkboy", resolved.dataUri?.scheme)
        assertEquals("emulator", resolved.dataUri?.authority)
        assertEquals(romFile.absolutePath, resolved.dataUri?.lastPathSegment)
    }
}
