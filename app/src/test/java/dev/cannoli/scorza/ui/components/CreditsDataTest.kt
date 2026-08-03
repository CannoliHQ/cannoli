package dev.cannoli.scorza.ui.components

import dev.cannoli.scorza.i18n.LanguageCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreditsDataTest {

    @Test fun `no credit category is empty`() {
        for (category in CreditsCategory.entries) {
            assertTrue("$category has no entries", creditsItemCount(category) > 0)
        }
    }

    // A typo'd tag would render the raw tag as the section header instead of the endonym.
    @Test fun `every localization tag resolves in the language catalog`() {
        for (credit in CREDITS_LOCALIZATION) {
            assertNotNull(
                "${credit.languageTag} is not offered by LanguageCatalog",
                LanguageCatalog.byTag(credit.languageTag)
            )
        }
    }

    @Test fun `localization sections follow language catalog order`() {
        val catalogOrder = LanguageCatalog.ALL.map { it.tag }
        val actual = CREDITS_LOCALIZATION.map { it.languageTag }
        assertEquals(actual.sortedBy { catalogOrder.indexOf(it) }, actual)
    }

    @Test fun `contributors are alphabetical within a language`() {
        for (credit in CREDITS_LOCALIZATION) {
            assertEquals(
                "${credit.languageTag} contributors are out of order",
                credit.contributors.sortedBy { it.lowercase() },
                credit.contributors
            )
        }
    }

    @Test fun `no language is listed twice`() {
        val tags = CREDITS_LOCALIZATION.map { it.languageTag }
        assertEquals(tags.size, tags.toSet().size)
    }

    @Test fun `every localization language has at least one contributor`() {
        for (credit in CREDITS_LOCALIZATION) {
            assertTrue("${credit.languageTag} has no contributors", credit.contributors.isNotEmpty())
        }
    }

    @Test fun `root rows are the inspiration entries followed by every category`() {
        val people = CREDITS_ROOT_ROWS.filterIsInstance<CreditsRootRow.Person>()
        val categories = CREDITS_ROOT_ROWS.filterIsInstance<CreditsRootRow.Category>()
        assertEquals(CREDITS_INSPIRATION, people.map { it.entry })
        assertEquals(CreditsCategory.entries.toList(), categories.map { it.category })
        assertEquals(CREDITS_ROOT_ROWS.take(people.size), people)
    }
}
