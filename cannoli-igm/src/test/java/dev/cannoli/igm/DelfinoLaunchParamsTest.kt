package dev.cannoli.igm

import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DelfinoLaunchParamsTest {

    private fun sampleParams(discPaths: List<String> = emptyList()) = DelfinoLaunchParams(
        romPath = "/sd/Cannoli/Roms/GC/Baten Kaitos.m3u",
        cannoliRoot = "/sd/Cannoli",
        savesDir = null,
        saveStatesDir = null,
        biosDir = null,
        userDir = null,
        gameTitle = "Baten Kaitos",
        platformTag = "GC",
        igmTriggerKeycodes = listOf(4, 109),
        colors = null,
        displaySettings = null,
        inputMapping = null,
        discPaths = discPaths,
    )

    @Test
    fun `disc paths survive the parcel round trip`() {
        val params = sampleParams(discPaths = listOf("/a/disc1.iso", "/a/disc2.iso"))
        val parcel = Parcel.obtain()
        params.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        val restored = DelfinoLaunchParams.CREATOR.createFromParcel(parcel)
        parcel.recycle()
        assertEquals(listOf("/a/disc1.iso", "/a/disc2.iso"), restored.discPaths)
    }

    @Test
    fun `disc paths default to empty`() {
        assertEquals(emptyList<String>(), sampleParams().discPaths)
    }

    @Test
    fun `reading a v1 parcel defaults disc paths to empty`() {
        // Hand-writes the pre-discPaths wire format: same fields, no trailing disc list.
        val parcel = Parcel.obtain()
        parcel.writeInt(1)
        parcel.writeString("/sd/Cannoli/Roms/GC/Baten Kaitos.m3u")
        parcel.writeString("/sd/Cannoli")
        parcel.writeString(null)
        parcel.writeString(null)
        parcel.writeString(null)
        parcel.writeString(null)
        parcel.writeString("Baten Kaitos")
        parcel.writeString("GC")
        parcel.writeIntArray(intArrayOf(4, 109))
        parcel.writeParcelable(null, 0)
        parcel.writeParcelable(null, 0)
        parcel.writeParcelable(null, 0)
        parcel.setDataPosition(0)
        val restored = DelfinoLaunchParams.CREATOR.createFromParcel(parcel)
        parcel.recycle()
        assertEquals(emptyList<String>(), restored.discPaths)
        assertEquals("Baten Kaitos", restored.gameTitle)
    }
}
