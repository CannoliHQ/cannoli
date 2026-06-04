package dev.cannoli.scorza.launcher

import android.content.ComponentName
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShellCommandFormatterTest {

    @Test fun `minimal action-only intent`() {
        val resolved = ResolvedIntent(
            component = null,
            packageName = "com.example",
            action = "android.intent.action.VIEW",
            dataUri = null,
            mimeType = null,
            flagsHex = "0x10000000",
            extras = emptyList()
        )
        assertEquals(
            listOf("am", "start",
                "-a", "android.intent.action.VIEW",
                "-p", "com.example",
                "-f", "0x10000000"),
            ShellCommandFormatter.format(resolved)
        )
    }

    @Test fun `component pin uses -n pkg slash activity`() {
        val resolved = ResolvedIntent(
            component = ComponentName("xyz.aethersx2.android", "xyz.aethersx2.android.EmulationActivity"),
            packageName = null,
            action = "android.intent.action.MAIN",
            dataUri = null,
            mimeType = null,
            flagsHex = "0x10000000",
            extras = listOf(
                ResolvedExtra.StringExtra("bootPath", "content://dev.cannoli.scorza.fileprovider/roms/foo.iso")
            )
        )
        assertEquals(
            listOf("am", "start",
                "-n", "xyz.aethersx2.android/xyz.aethersx2.android.EmulationActivity",
                "-a", "android.intent.action.MAIN",
                "-f", "0x10000000",
                "--es", "bootPath", "content://dev.cannoli.scorza.fileprovider/roms/foo.iso"),
            ShellCommandFormatter.format(resolved)
        )
    }

    @Test fun `data uri and mime are emitted`() {
        val resolved = ResolvedIntent(
            component = null,
            packageName = "com.dsemu.drastic",
            action = "android.intent.action.VIEW",
            dataUri = Uri.parse("file:///sdcard/foo.nds"),
            mimeType = "*/*",
            flagsHex = "0x10000000",
            extras = emptyList()
        )
        assertEquals(
            listOf("am", "start",
                "-a", "android.intent.action.VIEW",
                "-p", "com.dsemu.drastic",
                "-d", "file:///sdcard/foo.nds",
                "-t", "*/*",
                "-f", "0x10000000"),
            ShellCommandFormatter.format(resolved)
        )
    }

    @Test fun `parcelable uri extra uses --eu`() {
        val resolved = ResolvedIntent(
            component = ComponentName("me.magnum.melonds", "me.magnum.melonds.ui.emulator.EmulatorActivity"),
            packageName = null,
            action = "me.magnum.melonds.LAUNCH_ROM",
            dataUri = null,
            mimeType = null,
            flagsHex = "0x10000000",
            extras = listOf(
                ResolvedExtra.UriExtra("uri", Uri.parse("content://dev.cannoli.scorza.fileprovider/roms/foo.nds"))
            )
        )
        assertEquals(
            listOf("am", "start",
                "-n", "me.magnum.melonds/me.magnum.melonds.ui.emulator.EmulatorActivity",
                "-a", "me.magnum.melonds.LAUNCH_ROM",
                "-f", "0x10000000",
                "--eu", "uri", "content://dev.cannoli.scorza.fileprovider/roms/foo.nds"),
            ShellCommandFormatter.format(resolved)
        )
    }

    @Test fun `string array extra uses --esa with comma joined values`() {
        val resolved = ResolvedIntent(
            component = ComponentName("org.vita3k.emulator", "org.vita3k.emulator.Emulator"),
            packageName = null,
            action = "android.intent.action.MAIN",
            dataUri = null,
            mimeType = null,
            flagsHex = "0x10000000",
            extras = listOf(ResolvedExtra.StringArrayExtra("AppStartParameters", listOf("-r", "PCSE00065")))
        )
        assertEquals(
            listOf("am", "start",
                "-n", "org.vita3k.emulator/org.vita3k.emulator.Emulator",
                "-a", "android.intent.action.MAIN",
                "-f", "0x10000000",
                "--esa", "AppStartParameters", "-r,PCSE00065"),
            ShellCommandFormatter.format(resolved)
        )
    }

    @Test fun `single quote in any value rejects with IllegalArgumentException`() {
        val resolved = ResolvedIntent(
            component = null,
            packageName = "com.example",
            action = "android.intent.action.VIEW",
            dataUri = null,
            mimeType = null,
            flagsHex = "0x0",
            extras = listOf(ResolvedExtra.StringExtra("path", "/sdcard/foo'bar.iso"))
        )
        try {
            ShellCommandFormatter.format(resolved)
            error("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
