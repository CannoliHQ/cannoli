package dev.cannoli.scorza.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RetroArchLanguageTest {

    @Test
    fun `every language Cannoli ships maps to a RetroArch one`() {
        val shipped = listOf(
            "ar", "es-419", "de", "el", "es", "fr", "it", "ja", "pt-BR", "pt-PT", "uk", "zh-CN",
        )
        for (tag in shipped) {
            assertEquals("$tag should map", true, RetroArchLanguage.forTag(tag) != null)
        }
    }

    @Test
    fun `the two Portuguese variants are kept apart`() {
        assertEquals(7, RetroArchLanguage.forTag("pt-BR"))
        assertEquals(8, RetroArchLanguage.forTag("pt-PT"))
    }

    // RetroArch has no Latin American Spanish, so it collapses onto Spanish rather than falling
    // back to English.
    @Test
    fun `latin american spanish falls back to spanish`() {
        assertEquals(3, RetroArchLanguage.forTag("es-419"))
        assertEquals(3, RetroArchLanguage.forTag("es"))
    }

    @Test
    fun `region is ignored when the base language is enough`() {
        assertEquals(2, RetroArchLanguage.forTag("fr-FR"))
        assertEquals(2, RetroArchLanguage.forTag("fr"))
    }

    @Test
    fun `android style underscores and casing are accepted`() {
        assertEquals(7, RetroArchLanguage.forTag("pt_br"))
        assertEquals(12, RetroArchLanguage.forTag("ZH-CN"))
    }

    @Test
    fun `simplified and traditional chinese are distinct`() {
        assertEquals(12, RetroArchLanguage.forTag("zh-CN"))
        assertEquals(11, RetroArchLanguage.forTag("zh-TW"))
    }

    // The repository never returns an empty tag, but a blank one carries no information, so it is
    // left to RetroArch rather than guessed at.
    @Test
    fun `a blank tag is left to RetroArch`() {
        assertNull(RetroArchLanguage.forTag(""))
        assertNull(RetroArchLanguage.forTag("   "))
    }

    @Test
    fun `an unknown language is left to RetroArch rather than forced to English`() {
        assertNull(RetroArchLanguage.forTag("xx"))
    }

    @Test
    fun `english is explicit when chosen`() {
        assertEquals(0, RetroArchLanguage.forTag("en"))
    }
}
