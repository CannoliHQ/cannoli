package dev.cannoli.scorza.input.resolver

import dev.cannoli.scorza.input.autoconfig.RetroArchCfgEntry
import dev.cannoli.scorza.input.ConnectedDevice
import org.junit.Assert.assertEquals
import org.junit.Test

class IdentityMatcherTest {

    private fun device(
        name: String,
        vendorId: Int,
        productId: Int,
        buildModel: String,
        isBuiltIn: Boolean = false,
    ) = ConnectedDevice(
        androidDeviceId = 1,
        descriptor = "",
        name = name,
        vendorId = vendorId,
        productId = productId,
        androidBuildModel = buildModel,
        sourceMask = 0,
        connectedAtMillis = 0L,
        isBuiltIn = isBuiltIn,
    )

    private fun entry(
        name: String,
        vendorId: Int? = null,
        productId: Int? = null,
        buildModel: String? = null,
        builtin: Boolean? = null,
        aliases: List<String> = emptyList(),
    ) = RetroArchCfgEntry(
        deviceName = name,
        vendorId = vendorId,
        productId = productId,
        buttonBindings = emptyMap(),
        buildModel = buildModel,
        builtin = builtin,
        deviceAliases = aliases,
    )

    // A DS4 on a Retroid Pocket Classic reports the host's phantom 0x2022:0x3001.
    private val phantomDs4 = device("Wireless Controller", 8226, 12289, "Retroid Pocket Classic")

    @Test fun `name contradiction is disqualifying even when vid pid match`() {
        val builtIn = entry("Retroid Pocket Controller", 8226, 12289, "Retroid Pocket Classic")
        assertEquals(null, IdentityMatcher.rank(builtIn, phantomDs4))
    }

    @Test fun `name match alone beats nothing for the phantom pad`() {
        val ds4 = entry("Wireless Controller", 1356, 2508)
        assertEquals(MatchRank.NAME_ONLY, IdentityMatcher.rank(ds4, phantomDs4))
    }

    @Test fun `model pin mismatch is disqualifying`() {
        val nova = entry("Retroid Pocket Controller", buildModel = "Retroid Pocket Nova")
        val classicPad = device("Retroid Pocket Controller", 8226, 12289, "Retroid Pocket Classic", isBuiltIn = true)
        assertEquals(null, IdentityMatcher.rank(nova, classicPad))
    }

    @Test fun `name plus model outranks name plus vid pid`() {
        val pad = device("Retroid Pocket Controller", 8226, 12289, "Retroid Pocket Nova", isBuiltIn = true)
        val pinned = entry("Retroid Pocket Controller", buildModel = "Retroid Pocket Nova")
        val unpinned = entry("Retroid Pocket Controller", 8226, 12289)
        assertEquals(MatchRank.NAME_AND_MODEL, IdentityMatcher.rank(pinned, pad))
        assertEquals(MatchRank.NAME_AND_VID_PID, IdentityMatcher.rank(unpinned, pad))
        assertEquals(true, MatchRank.NAME_AND_MODEL.ordinal < MatchRank.NAME_AND_VID_PID.ordinal)
    }

    @Test fun `honest ds4 scores name plus vid pid`() {
        val honest = device("Wireless Controller", 1356, 2508, "RG Rotate")
        assertEquals(MatchRank.NAME_AND_VID_PID, IdentityMatcher.rank(entry("Wireless Controller", 1356, 2508), honest))
    }

    @Test fun `a named entry never matches on vid pid alone`() {
        val other = entry("Some Other Pad", 8226, 12289)
        assertEquals(null, IdentityMatcher.rank(other, phantomDs4))
    }

    @Test fun `unnamed entry may match on vid pid`() {
        val unnamed = entry("", 8226, 12289)
        assertEquals(MatchRank.VID_PID_UNNAMED, IdentityMatcher.rank(unnamed, phantomDs4))
    }

    @Test fun `model pin still matches on name even when the entry vid pid contradicts the device`() {
        val pad = device("Retroid Pocket Controller", 8226, 12289, "Retroid Pocket Nova", isBuiltIn = true)
        val wrongVidPid = entry("Retroid Pocket Controller", vendorId = 1, productId = 2, buildModel = "Retroid Pocket Nova")
        assertEquals(MatchRank.NAME_AND_MODEL, IdentityMatcher.rank(wrongVidPid, pad))
    }

    @Test fun `builtin agreement is advisory not disqualifying`() {
        val builtInEntry = entry("Wireless Controller", 1356, 2508, builtin = true)
        assertEquals(MatchRank.NAME_AND_VID_PID, IdentityMatcher.rank(builtInEntry, phantomDs4.copy(vendorId = 1356, productId = 2508)))
        assertEquals(false, IdentityMatcher.builtinAgrees(builtInEntry, phantomDs4))
    }

    // Android merges this pad's three HID nodes into one InputDevice and names it after whichever
    // node enumerated first, so the same pad reports either name across reconnects.
    private val mergedAsKeyboard = device("GameSir-Pocket 1 Keyboard", 13623, 4402, "moto g play - 2024")

    @Test fun `a collected alias matches the pad`() {
        val taco = entry("GameSir-Pocket 1", 13623, 4402, aliases = listOf("GameSir-Pocket 1 Keyboard"))
        assertEquals(MatchRank.ALIAS, IdentityMatcher.rank(taco, mergedAsKeyboard))
    }

    @Test fun `an exact device name outranks an alias`() {
        assertEquals(true, MatchRank.NAME_ONLY.ordinal < MatchRank.ALIAS.ordinal)
    }

    @Test fun `another pad's alias does not match`() {
        val other = entry("Some Other Pad", 13623, 4402, aliases = listOf("Some Other Pad Keyboard"))
        assertEquals(null, IdentityMatcher.rank(other, mergedAsKeyboard))
    }

    @Test fun `a sibling product is not swept in by a neighbouring name`() {
        val pocketTen = device("GameSir-Pocket 10", 13623, 4402, "moto g play - 2024")
        val taco = entry("GameSir-Pocket 1", 13623, 4402, aliases = listOf("GameSir-Pocket 1 Keyboard"))
        assertEquals(null, IdentityMatcher.rank(taco, pocketTen))
    }
}
