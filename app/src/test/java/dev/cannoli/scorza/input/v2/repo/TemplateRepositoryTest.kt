package dev.cannoli.scorza.input.v2.repo

import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.DeviceMatchRule
import dev.cannoli.scorza.input.v2.DeviceTemplate
import dev.cannoli.scorza.input.v2.InputBinding
import dev.cannoli.scorza.input.v2.TemplateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TemplateRepositoryTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private fun makeRepo() = TemplateRepository(tempFolder.root)

    private fun makeTemplate(id: String) = DeviceTemplate(
        id = id,
        displayName = id,
        match = DeviceMatchRule(name = id, vendorId = 1, productId = 2),
        bindings = mapOf(CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96))),
        source = TemplateSource.RETROARCH_AUTOCONFIG,
    )

    @Test
    fun list_is_empty_for_fresh_repo() {
        assertTrue(makeRepo().list().isEmpty())
    }

    @Test
    fun save_then_findById_returns_the_same_template() {
        val repo = makeRepo()
        val t = makeTemplate("stadia_controller")
        repo.save(t)
        val found = repo.findById("stadia_controller")
        assertNotNull(found)
        assertEquals(t, found)
    }

    @Test
    fun list_returns_all_saved_templates() {
        val repo = makeRepo()
        repo.save(makeTemplate("a"))
        repo.save(makeTemplate("b"))
        val ids = repo.list().map { it.id }.toSet()
        assertEquals(setOf("a", "b"), ids)
    }

    @Test
    fun delete_removes_the_template_file() {
        val repo = makeRepo()
        repo.save(makeTemplate("doomed"))
        assertNotNull(repo.findById("doomed"))
        repo.delete("doomed")
        assertNull(repo.findById("doomed"))
    }

    @Test
    fun save_overwrites_existing_template() {
        val repo = makeRepo()
        repo.save(makeTemplate("x"))
        repo.save(makeTemplate("x").copy(displayName = "Renamed"))
        assertEquals("Renamed", repo.findById("x")?.displayName)
    }

    @Test
    fun malformed_template_file_is_skipped_in_list() {
        val repo = makeRepo()
        repo.save(makeTemplate("good"))
        java.io.File(tempFolder.root, "broken.ini").writeText("not a real ini at all")
        val ids = repo.list().map { it.id }
        assertTrue(ids.contains("good"))
        // broken.ini is best-effort parsed; the repo should not throw.
    }
}
