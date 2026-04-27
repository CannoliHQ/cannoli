package dev.cannoli.scorza.input.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMatchRuleTest {

    private val device = MatchInput(
        name = "Stadia Controller",
        vendorId = 6353,
        productId = 37888,
        androidBuildModel = "RP4PRO",
        sourceMask = 0x00000401,
    )

    @Test
    fun vendor_and_product_match_scores_100() {
        val rule = DeviceMatchRule(vendorId = 6353, productId = 37888)
        assertEquals(100, rule.score(device))
    }

    @Test
    fun name_only_match_scores_50() {
        val rule = DeviceMatchRule(name = "Stadia Controller")
        assertEquals(50, rule.score(device))
    }

    @Test
    fun android_build_model_match_scores_100() {
        val rule = DeviceMatchRule(androidBuildModel = "RP4PRO")
        assertEquals(100, rule.score(device))
    }

    @Test
    fun source_mask_match_adds_10() {
        val rule = DeviceMatchRule(name = "Stadia Controller", sourceMask = 0x00000401)
        assertEquals(60, rule.score(device))
    }

    @Test
    fun no_fields_set_yields_zero() {
        val rule = DeviceMatchRule()
        assertEquals(0, rule.score(device))
    }

    @Test
    fun vendor_match_without_product_does_not_score_100() {
        val rule = DeviceMatchRule(vendorId = 6353, productId = 0)
        // productId 0 is treated as unset; expect 0 because we require both vid+pid.
        assertEquals(0, rule.score(device))
    }

    @Test
    fun mismatched_name_scores_zero() {
        val rule = DeviceMatchRule(name = "Wrong Controller")
        assertEquals(0, rule.score(device))
    }

    @Test
    fun mismatched_source_mask_does_not_add_bonus() {
        val rule = DeviceMatchRule(name = "Stadia Controller", sourceMask = 0xDEAD)
        // Mask must AND-match a non-zero subset; mismatched mask contributes 0 bonus.
        assertEquals(50, rule.score(device))
    }

    @Test
    fun rule_score_caps_at_100_plus_10() {
        val rule = DeviceMatchRule(
            name = "Stadia Controller",
            vendorId = 6353,
            productId = 37888,
            sourceMask = 0x00000401,
        )
        assertEquals(110, rule.score(device))
    }
}
