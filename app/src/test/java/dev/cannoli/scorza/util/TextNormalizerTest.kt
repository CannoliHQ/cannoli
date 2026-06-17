package dev.cannoli.scorza.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormalizerTest {
    @Test fun `strips diacritics and lowercases`() {
        assertEquals("pokemon", TextNormalizer.normalize("Pokémon"))
        assertEquals("cafe", TextNormalizer.normalize("Café"))
    }

    @Test fun `prefix of normalized form is searchable`() {
        assertTrue(TextNormalizer.normalize("Pokémon").contains(TextNormalizer.normalize("poke")))
    }

    @Test fun `plain ascii is just lowercased and trimmed`() {
        assertEquals("zelda", TextNormalizer.normalize("  Zelda  "))
    }

    @Test fun `blank stays blank`() {
        assertEquals("", TextNormalizer.normalize("   "))
    }

    @Test fun `is idempotent`() {
        val once = TextNormalizer.normalize("Métroïd")
        assertEquals(once, TextNormalizer.normalize(once))
    }
}
