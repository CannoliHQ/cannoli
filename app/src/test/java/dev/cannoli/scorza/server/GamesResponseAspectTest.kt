package dev.cannoli.scorza.server

import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.model.Rom
import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The measured platform shape the cover grid's uniform mode draws every card at. */
class GamesResponseAspectTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun repoWith(names: List<String>): RomsRepository {
        val roms = names.mapIndexed { i, name ->
            Rom(
                id = i.toLong(),
                path = File(tmp.root, "Roms/SNES/$name.sfc"),
                platformTag = "SNES",
                displayName = name,
                tags = null,
                artFile = null,
                raGameId = null,
            )
        }
        val repo = mockk<RomsRepository>()
        every { repo.allRomsForPlatform("SNES") } returns roms
        return repo
    }

    private fun art(names: List<String>): File {
        val dir = File(tmp.root, "Art/SNES").also { it.mkdirs() }
        names.forEach { File(dir, "$it.png").writeBytes(ByteArray(8)) }
        return dir
    }

    private fun aspectOf(names: List<String>, measure: (File) -> Float?): Double? {
        art(names)
        val json = JSONObject(
            GamesResponse.buildList(
                repoWith(names), tmp.root, File(tmp.root, "Roms"), "SNES", "Super Nintendo",
                measureArt = measure,
            )
        )
        return if (json.isNull("artAspect")) null else json.getDouble("artAspect")
    }

    @Test fun `a platform whose covers agree reports their shape`() {
        val aspect = aspectOf(listOf("a", "b", "c")) { 1.5f }
        assertEquals(1.5, aspect!!, 0.0001)
    }

    // One screenshot scraped in among the box art would drag a mean off the shape every other
    // cover shares, which is the whole reason this is a median.
    @Test fun `one odd cover does not move the platform shape`() {
        val wide = mapOf("d" to 4.0f)
        val aspect = aspectOf(listOf("a", "b", "c", "d")) { wide[it.nameWithoutExtension] ?: 0.75f }
        assertEquals(0.75, aspect!!, 0.0001)
    }

    @Test fun `a platform with no art reports no shape`() {
        val repo = repoWith(listOf("a"))
        val json = JSONObject(
            GamesResponse.buildList(
                repo, tmp.root, File(tmp.root, "Roms"), "SNES", "Super Nintendo",
                measureArt = { 1.5f },
            )
        )
        assertTrue(json.isNull("artAspect"))
    }

    @Test fun `art that cannot be measured reports no shape`() {
        assertEquals(null, aspectOf(listOf("a", "b")) { null })
    }

    // Twenty-five covers answer the question as well as a thousand, and a large platform must not
    // pay a decode per game to build its list.
    @Test fun `only a sample of the covers is opened`() {
        val opened = mutableListOf<String>()
        aspectOf((1..80).map { "game$it" }) { opened.add(it.name); 1f }
        assertEquals(25, opened.size)
    }

    // Directory order is not stable across filesystems, so neither the sample nor the answer may
    // depend on it.
    @Test fun `the sample does not depend on directory order`() {
        val opened = mutableListOf<String>()
        aspectOf((1..40).map { "game$it" }) { opened.add(it.nameWithoutExtension); 1f }
        assertEquals(opened.sortedBy { it.lowercase() }, opened)
    }

    @Test fun `an even sample takes the lower of the two middles`() {
        assertEquals(0.75f, GamesResponse.medianAspect(listOf(0.75f, 1.5f)))
        assertEquals(1.0f, GamesResponse.medianAspect(listOf(2.0f, 1.0f, 0.5f, 1.6f)))
    }

    @Test fun `no ratios means no shape`() {
        assertEquals(null, GamesResponse.medianAspect(emptyList()))
    }
}
