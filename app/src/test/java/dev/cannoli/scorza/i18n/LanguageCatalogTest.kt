package dev.cannoli.scorza.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageCatalogTest {

    @Test fun `tags are in the expected order and complete`() {
        assertEquals(
            listOf("en", "fr-FR", "pt-PT", "uk-UA", "zh-CN"),
            LanguageCatalog.ALL.map { it.tag }
        )
    }

    @Test fun `all tags are unique`() {
        val tags = LanguageCatalog.ALL.map { it.tag }
        assertEquals(tags.size, tags.toSet().size)
    }

    @Test fun `only non-latin languages carry a coverage sample`() {
        val nonLatin = setOf("uk-UA", "zh-CN")
        for (o in LanguageCatalog.ALL) {
            if (o.tag in nonLatin) assertNotNull("expected sample for ${o.tag}", o.coverageSample)
            else assertNull("unexpected sample for ${o.tag}", o.coverageSample)
        }
    }

    @Test fun `arabic is excluded`() {
        assertNull(LanguageCatalog.byTag("ar-SA"))
        assertFalse(LanguageCatalog.ALL.any { it.tag.startsWith("ar") })
    }

    @Test fun `endonyms are non-blank`() {
        assertTrue(LanguageCatalog.ALL.all { it.nativeName.isNotBlank() })
    }

    @Test fun `every tag maps to a shipped resource directory`() {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var found: File? = null
        while (dir != null) {
            val candidate = File(dir, "cannoli-ui/src/main/res")
            if (candidate.isDirectory) {
                found = candidate
                break
            }
            dir = dir.parentFile
        }
        val resDir = checkNotNull(found) {
            "could not locate cannoli-ui/src/main/res from ${System.getProperty("user.dir") ?: "."}"
        }

        for (option in LanguageCatalog.ALL) {
            val tag = option.tag
            val expectedDirName = when {
                tag == "en" -> "values"
                tag == "es-419" -> "values-b+es+419"
                else -> {
                    val (language, region) = tag.split("-", limit = 2)
                    "values-$language-r$region"
                }
            }
            val expectedDir = File(resDir, expectedDirName)
            assertTrue(
                "tag $tag expects resource directory $expectedDir to exist",
                expectedDir.isDirectory
            )
        }
    }
}
