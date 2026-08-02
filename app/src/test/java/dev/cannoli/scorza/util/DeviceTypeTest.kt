package dev.cannoli.scorza.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTypeTest {

    @Test
    fun studio_arm64_avd_is_detected() {
        assertTrue(
            DeviceType.isAvd(
                fingerprint = "google/sdk_gphone64_arm64/emu64a:14/UE1A.230829.036/11228894:userdebug/dev-keys",
                model = "sdk_gphone64_arm64",
                hardware = "ranchu",
                product = "sdk_gphone64_arm64",
            )
        )
    }

    @Test
    fun legacy_goldfish_avd_is_detected() {
        assertTrue(
            DeviceType.isAvd(
                fingerprint = "generic/sdk/generic:9/PSR1.180720.075/5124027:userdebug/test-keys",
                model = "Android SDK built for x86",
                hardware = "goldfish",
                product = "sdk",
            )
        )
    }

    @Test
    fun retroid_handheld_is_not_an_avd() {
        assertFalse(
            DeviceType.isAvd(
                fingerprint = "Retroid/RP5/RP5:13/TQ3A.230901.001/20240115:user/release-keys",
                model = "Retroid Pocket 5",
                hardware = "qcom",
                product = "RP5",
            )
        )
    }

    @Test
    fun pixel_phone_is_not_an_avd() {
        assertFalse(
            DeviceType.isAvd(
                fingerprint = "google/oriole/oriole:14/UP1A.231005.007/10754064:user/release-keys",
                model = "Pixel 6",
                hardware = "oriole",
                product = "oriole",
            )
        )
    }

    @Test
    fun empty_build_fields_are_not_an_avd() {
        assertFalse(DeviceType.isAvd(fingerprint = "", model = "", hardware = "", product = ""))
    }

    @Test
    fun handheld_with_unset_build_props_is_not_an_avd() {
        // Build.getString returns "unknown" for any unset property, and deriveFingerprint composes
        // brand/name/device from those when ro.build.fingerprint is empty. Handhelds running
        // hacked ROMs land here, and must not be mistaken for an emulator.
        assertFalse(
            DeviceType.isAvd(
                fingerprint = "unknown/unknown/unknown:13/TQ3A.230901.001/eng.root:user/release-keys",
                model = "unknown",
                hardware = "sun",
                product = "unknown",
            )
        )
    }

    @Test
    fun unbranded_aosp_handheld_is_not_an_avd() {
        assertFalse(
            DeviceType.isAvd(
                fingerprint = "generic/rk3566/rk3566:11/RQ3A.211001.001/20230415:user/release-keys",
                model = "RG353P",
                hardware = "rk30board",
                product = "rk3566",
            )
        )
    }
}
