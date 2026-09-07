package dev.cannoli.scorza.sigil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SigilPlatformTest {

    @Test fun `every mapped tag resolves`() {
        assertEquals(SigilPlatform.PSX, SigilPlatform.fromTag("PS"))
        assertEquals(SigilPlatform.PS2, SigilPlatform.fromTag("PS2"))
        assertEquals(SigilPlatform.PSP, SigilPlatform.fromTag("PSP"))
        assertEquals(SigilPlatform.PSVITA, SigilPlatform.fromTag("PSVITA"))
        assertEquals(SigilPlatform.GAMECUBE, SigilPlatform.fromTag("GC"))
        assertEquals(SigilPlatform.WII, SigilPlatform.fromTag("WII"))
        assertEquals(SigilPlatform.WIIU, SigilPlatform.fromTag("WIIU"))
        assertEquals(SigilPlatform.THREEDS, SigilPlatform.fromTag("3DS"))
        assertEquals(SigilPlatform.DREAMCAST, SigilPlatform.fromTag("DC"))
        assertEquals(9, SigilPlatform.TAGS.size)
    }

    /** Sigil reads all three; Cannoli has nowhere to put a prod.keys and no tag for either Xbox. */
    @Test fun `switch ps3 and xbox stay unmapped`() {
        assertNull(SigilPlatform.fromTag("NSW"))
        assertNull(SigilPlatform.fromTag("PS3"))
        assertNull(SigilPlatform.fromTag("XBOX"))
    }

    @Test fun `cartridge platforms and unknown input resolve to nothing`() {
        assertNull(SigilPlatform.fromTag("SNES"))
        assertNull(SigilPlatform.fromTag("NEOGEOCD"))
        assertNull(SigilPlatform.fromTag(""))
        assertNull(SigilPlatform.fromTag("not a tag"))
    }

    @Test fun `a tag resolves whatever case it arrives in`() {
        assertEquals(SigilPlatform.PS2, SigilPlatform.fromTag("ps2"))
        assertEquals(SigilPlatform.GAMECUBE, SigilPlatform.fromTag("gc"))
    }

    /** The ordinal is the wire format the JNI shim switches on, so it is part of the contract. */
    @Test fun `ordinals are the order the shim expects`() {
        assertEquals(
            listOf("PSX", "PS2", "PSP", "PSVITA", "GAMECUBE", "WII", "WIIU", "THREEDS", "DREAMCAST"),
            SigilPlatform.entries.map { it.name },
        )
    }
}
