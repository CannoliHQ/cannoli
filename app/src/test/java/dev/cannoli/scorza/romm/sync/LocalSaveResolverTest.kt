package dev.cannoli.scorza.romm.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class LocalSaveResolverTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun saves(tag: String) = File(tmp.root, "Saves/$tag").apply { mkdirs() }
    private fun game(tag: String, base: String) = File(saves(tag), base)

    @Test fun resolve_null_when_no_save() {
        assertNull(LocalSaveResolver(tmp.root).resolve("SNES", "Mario"))
    }

    // An install that has not migrated yet still resolves, which is what lets the sweep run in the
    // background instead of having to finish before sync may touch anything.
    @Test fun resolve_single_srm_is_not_bundle() {
        File(saves("SNES"), "Mario.srm").writeBytes("SRAM".toByteArray())
        val s = LocalSaveResolver(tmp.root).resolve("SNES", "Mario")!!
        assertFalse(s.isBundle)
        assertEquals("Mario.srm", s.uploadFileName)
        assertEquals(SaveHasher.hashFile(File(saves("SNES"), "Mario.srm")), s.contentHash)
    }

    @Test fun resolve_multifile_is_bundle_with_zip_hash() {
        File(saves("GBA"), "Pokemon.srm").writeBytes("SAVE".toByteArray())
        File(saves("GBA"), "Pokemon.rtc").writeBytes("RTC".toByteArray())
        val s = LocalSaveResolver(tmp.root).resolve("GBA", "Pokemon")!!
        assertTrue(s.isBundle)
        assertEquals("Pokemon.zip", s.uploadFileName)
        val expected = SaveHasher.hashBundle(mapOf(
            "Pokemon.rtc" to File(saves("GBA"), "Pokemon.rtc"),
            "Pokemon.srm" to File(saves("GBA"), "Pokemon.srm"),
        ))
        assertEquals(expected, s.contentHash)
    }

    /**
     * The migration changes the hash, once, and that is the right trade.
     *
     * The hash has to be the hash of the archive the server receives, and a folder save's archive
     * roots its entries at the folder name where a loose bundle's does not, so the same bytes
     * genuinely produce a different archive. The cost is one re-upload per folder save after the
     * migration, after which both sides agree again. Keying on anything stabler would mean a hash
     * the server can never arrive at, and a negotiate that never sees two copies as identical.
     */
    @Test fun the_hash_follows_the_archive_across_the_migration() {
        File(saves("GBA"), "Pokemon.srm").writeBytes("SAVE".toByteArray())
        File(saves("GBA"), "Pokemon.rtc").writeBytes("RTC".toByteArray())
        val resolver = LocalSaveResolver(tmp.root)
        val loose = resolver.resolve("GBA", "Pokemon")!!.contentHash

        dev.cannoli.scorza.saves.SaveMigration(dev.cannoli.scorza.config.CannoliPaths(tmp.root))
            .migrateGame("GBA", "Pokemon")
        val migrated = resolver.resolve("GBA", "Pokemon")!!.contentHash

        assertTrue(loose != migrated)
        assertEquals(serverStyleHash(resolver.bundleToZip("GBA", "Pokemon", tmp.newFile("m.zip"))), migrated)
    }

    @Test fun a_folder_save_wins_over_loose_files() {
        File(saves("GBA"), "Pokemon.srm").writeBytes("LOOSE".toByteArray())
        File(game("GBA", "Pokemon").apply { mkdirs() }, "Pokemon.srm").writeBytes("FOLDER".toByteArray())

        val s = LocalSaveResolver(tmp.root).resolve("GBA", "Pokemon")!!

        assertEquals("FOLDER", s.files.single().readText())
    }

    @Test fun an_empty_folder_does_not_shadow_loose_files() {
        File(saves("GBA"), "Pokemon.srm").writeBytes("LOOSE".toByteArray())
        game("GBA", "Pokemon").mkdirs()

        assertEquals("LOOSE", LocalSaveResolver(tmp.root).resolve("GBA", "Pokemon")!!.files.single().readText())
    }

    @Test fun a_folder_save_zips_rooted_at_the_game_name() {
        val dir = game("PSP", "God of War").apply { mkdirs() }
        File(dir, "PSP/SAVEDATA/UCUS98653").apply { mkdirs() }
        File(dir, "PSP/SAVEDATA/UCUS98653/DATA.BIN").writeBytes("SAVE".toByteArray())

        val zip = LocalSaveResolver(tmp.root).bundleToZip("PSP", "God of War", tmp.newFile("gow.zip"))

        ZipFile(zip).use {
            assertEquals(
                setOf("God of War/PSP/SAVEDATA/UCUS98653/DATA.BIN"),
                it.entries().toList().map { e -> e.name }.toSet(),
            )
        }
    }

    @Test fun bundleToZip_round_trips_via_applyDownload() {
        File(saves("GBA"), "Pokemon.srm").writeBytes("SAVE".toByteArray())
        File(saves("GBA"), "Pokemon.rtc").writeBytes("RTC".toByteArray())
        val resolver = LocalSaveResolver(tmp.root)
        val zip = resolver.bundleToZip("GBA", "Pokemon", tmp.newFile("Pokemon.zip"))
        File(saves("GBA"), "Pokemon.srm").delete()
        File(saves("GBA"), "Pokemon.rtc").delete()

        resolver.applyDownload("GBA", "Pokemon", zip)

        assertEquals("SAVE", File(game("GBA", "Pokemon"), "Pokemon.srm").readText())
        assertEquals("RTC", File(game("GBA", "Pokemon"), "Pokemon.rtc").readText())
    }

    /** A download is verified against the hash negotiation reported, so the zip must hash like the folder. */
    @Test fun a_bundle_zip_hashes_to_the_bundle_content_hash() {
        File(saves("GBA"), "Pokemon.srm").writeBytes("SAVE".toByteArray())
        File(saves("GBA"), "Pokemon.rtc").writeBytes("RTC".toByteArray())
        val resolver = LocalSaveResolver(tmp.root)

        val zip = resolver.bundleToZip("GBA", "Pokemon", tmp.newFile("Pokemon.zip"))

        assertEquals(resolver.resolve("GBA", "Pokemon")!!.contentHash, SaveHasher.hashZipContents(zip))
    }

    @Test fun a_shared_root_zip_hashes_to_its_content_hash() {
        savedata("UCUS98653")
        savedata("UCUS98653DATA00", body = "PROFILE")
        val resolver = pspResolver()

        val zip = resolver.bundleToZip("PSP", "God of War", tmp.newFile("gow.zip"))

        assertEquals(resolver.resolve("PSP", "God of War")!!.contentHash, SaveHasher.hashZipContents(zip))
    }

    /** Argosy roots a save at the folder it lives in, which for PSP is the save id, not the game. */
    @Test fun a_foreign_root_is_kept_because_the_emulator_reads_it() {
        val zip = tmp.newFile("argosy.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("ULUS10064/DATA.BIN")); zos.write("SAVE".toByteArray()); zos.closeEntry()
            zos.putNextEntry(ZipEntry("ULUS10064/ICON0.PNG")); zos.write("ICON".toByteArray()); zos.closeEntry()
        }

        LocalSaveResolver(tmp.root).applyDownload("PSP", "God of War", zip)

        val dir = game("PSP", "God of War")
        assertEquals("SAVE", File(dir, "ULUS10064/DATA.BIN").readText())
        assertEquals("ICON", File(dir, "ULUS10064/ICON0.PNG").readText())
    }

    /** Our own root is dropped, or every round trip would nest the game one level deeper. */
    @Test fun our_own_root_is_dropped_rather_than_nested() {
        val zip = tmp.newFile("ours.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("Pokemon/Pokemon.srm")); zos.write("SAVE".toByteArray()); zos.closeEntry()
        }

        LocalSaveResolver(tmp.root).applyDownload("GBA", "Pokemon", zip)

        assertEquals("SAVE", File(game("GBA", "Pokemon"), "Pokemon.srm").readText())
        assertFalse(File(game("GBA", "Pokemon"), "Pokemon/Pokemon.srm").exists())
    }

    @Test fun an_entry_escaping_the_save_folder_is_refused() {
        val zip = tmp.newFile("evil.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("../../escaped.srm")); zos.write("PWN".toByteArray()); zos.closeEntry()
            zos.putNextEntry(ZipEntry("Mario.srm")); zos.write("SAVE".toByteArray()); zos.closeEntry()
        }

        LocalSaveResolver(tmp.root).applyDownload("SNES", "Mario", zip)

        assertFalse(File(tmp.root, "escaped.srm").exists())
        assertFalse(File(tmp.root, "Saves/escaped.srm").exists())
        assertEquals("SAVE", File(game("SNES", "Mario"), "Mario.srm").readText())
    }

    /** mupen writes .eep, .sra, .fla and .mpk; calling any of them .srm sends back an unreadable save. */
    @Test fun a_lone_save_uploads_under_the_extension_it_actually_has() {
        File(saves("N64"), "Zelda.eep").writeBytes("SAVE".toByteArray())

        assertEquals("Zelda.eep", LocalSaveResolver(tmp.root).resolve("N64", "Zelda")!!.uploadFileName)
    }

    @Test fun a_save_the_emulator_named_uploads_under_the_game_name() {
        val dir = game("DC", "Crazy Taxi").apply { mkdirs() }
        File(dir, "MK-51035.A1.bin").writeBytes("VMU".toByteArray())

        assertEquals("Crazy Taxi.bin", LocalSaveResolver(tmp.root).resolve("DC", "Crazy Taxi")!!.uploadFileName)
    }

    @Test fun a_download_is_named_by_the_format_the_server_says_it_is() {
        val single = tmp.newFile("dl4.bin").apply { writeBytes("SAVE".toByteArray()) }

        LocalSaveResolver(tmp.root).applyDownload("N64", "Zelda", single, "Zelda.eep")

        assertEquals("SAVE", File(game("N64", "Zelda"), "Zelda.eep").readText())
        assertFalse(File(game("N64", "Zelda"), "Zelda.srm").exists())
    }

    /** No name from the server, but the core already told us which extension it writes. */
    @Test fun a_download_keeps_the_extension_the_save_on_disk_already_has() {
        File(saves("N64"), "Zelda.sra").writeBytes("OLD".toByteArray())
        val single = tmp.newFile("dl5.bin").apply { writeBytes("NEW".toByteArray()) }

        LocalSaveResolver(tmp.root).applyDownload("N64", "Zelda", single)

        assertEquals("NEW", File(game("N64", "Zelda"), "Zelda.sra").readText())
    }

    @Test fun a_first_download_for_a_game_nothing_has_saved_falls_back_to_srm() {
        val single = tmp.newFile("dl6.bin").apply { writeBytes("SAVE".toByteArray()) }

        LocalSaveResolver(tmp.root).applyDownload("SNES", "Mario", single)

        assertEquals("SAVE", File(game("SNES", "Mario"), "Mario.srm").readText())
    }

    /**
     * The server hashes the archive it receives, so the hash we record has to be the hash of the
     * archive we send. RomM's hash_zip_contents, Argosy's calculateZipHash and our hashBundle are
     * the same algorithm; agreeing on the algorithm is worth nothing if the entry names differ,
     * and a negotiate that cannot see two copies are identical falls back to timestamps.
     */
    private fun serverStyleHash(zip: File): String {
        val entries = ZipFile(zip).use { zf ->
            zf.entries().toList().filter { !it.isDirectory }.map { e ->
                e.name to SaveHasher.md5Hex(zf.getInputStream(e).readBytes())
            }
        }
        return SaveHasher.md5Hex(
            entries.sortedBy { it.first }.joinToString("\n") { "${it.first}:${it.second}" }
                .toByteArray(Charsets.UTF_8)
        )
    }

    @Test fun a_folder_save_hashes_as_the_archive_the_server_receives() {
        val dir = game("GBA", "Pokemon").apply { mkdirs() }
        File(dir, "Pokemon.srm").writeText("SAVE")
        File(dir, "Pokemon.rtc").writeText("RTC")
        val resolver = LocalSaveResolver(tmp.root)

        val zip = resolver.bundleToZip("GBA", "Pokemon", tmp.newFile("h1.zip"))

        assertEquals(serverStyleHash(zip), resolver.resolve("GBA", "Pokemon")!!.contentHash)
    }

    @Test fun a_loose_bundle_hashes_as_the_archive_the_server_receives() {
        File(saves("N64"), "Zelda.sra").writeText("SRA")
        File(saves("N64"), "Zelda.eep").writeText("EEP")
        val resolver = LocalSaveResolver(tmp.root)

        val zip = resolver.bundleToZip("N64", "Zelda", tmp.newFile("h2.zip"))

        assertEquals(serverStyleHash(zip), resolver.resolve("N64", "Zelda")!!.contentHash)
    }

    @Test fun a_shared_root_hashes_as_the_archive_the_server_receives() {
        savedata("UCUS98653")
        savedata("UCUS98653DATA00", body = "PROFILE")
        val resolver = pspResolver()

        val zip = resolver.bundleToZip("PSP", "God of War", tmp.newFile("h3.zip"))

        assertEquals(serverStyleHash(zip), resolver.resolve("PSP", "God of War")!!.contentHash)
    }

    private fun pspId(saveId: String = "UCUS98653") = dev.cannoli.scorza.sigil.GameId(
        titleId = saveId, saveId = saveId, rawSerial = saveId,
        usage = dev.cannoli.scorza.sigil.SaveUsage.FOLDER_PREFIX,
        source = dev.cannoli.scorza.sigil.IdSource.BINARY, experimental = false,
    )

    private fun pspResolver(id: dev.cannoli.scorza.sigil.GameId? = pspId()) =
        LocalSaveResolver({ tmp.root }, gameIdFor = { _, _ -> id })

    private fun savedata(name: String, file: String = "DATA.BIN", body: String = "SAVE"): File {
        val d = File(saves("PSP"), "SAVEDATA/$name").apply { mkdirs() }
        File(d, file).writeText(body)
        return d
    }

    /**
     * PPSSPP shares one memory stick, so a game is the folders inside SAVEDATA whose names begin
     * with its disc id, not a directory of its own. Sigil supplies the id and says it is a prefix.
     */
    @Test fun a_shared_root_resolves_the_folders_the_disc_id_owns() {
        savedata("UCUS98653")
        savedata("UCUS98653DATA00", body = "PROFILE")
        savedata("ULUS10064", body = "ANOTHER GAME")

        val save = pspResolver().resolve("PSP", "God of War")!!

        assertEquals(2, save.files.size)
        assertTrue(save.files.none { it.readText() == "ANOTHER GAME" })
        assertEquals("God of War [UCUS98653].zip", save.uploadFileName)
    }

    /** Argosy roots each matched folder at its own name, and reads the same shape back. */
    @Test fun a_shared_root_zips_each_folder_at_its_own_name() {
        savedata("UCUS98653")
        savedata("UCUS98653DATA00")

        val zip = pspResolver().bundleToZip("PSP", "God of War", tmp.newFile("psp.zip"))

        ZipFile(zip).use {
            assertEquals(
                setOf("UCUS98653/DATA.BIN", "UCUS98653DATA00/DATA.BIN"),
                it.entries().toList().map { e -> e.name }.toSet(),
            )
        }
    }

    /** Every other game lives in the same directory, so a restore must not sweep them away. */
    @Test fun a_shared_root_restore_leaves_other_games_alone() {
        savedata("UCUS98653", body = "OLD")
        val other = savedata("ULUS10064", body = "ANOTHER GAME")
        val zip = tmp.newFile("incoming.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("UCUS98653/DATA.BIN")); zos.write("NEW".toByteArray()); zos.closeEntry()
        }

        pspResolver().applyDownload("PSP", "God of War", zip)

        assertEquals("NEW", File(saves("PSP"), "SAVEDATA/UCUS98653/DATA.BIN").readText())
        assertEquals("ANOTHER GAME", File(other, "DATA.BIN").readText())
    }

    /** A folder the game owned but the archive does not carry is gone, as Argosy clears them too. */
    @Test fun a_shared_root_restore_clears_the_game_own_stale_folders() {
        savedata("UCUS98653")
        savedata("UCUS98653DATA00")
        val zip = tmp.newFile("incoming2.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("UCUS98653/DATA.BIN")); zos.write("NEW".toByteArray()); zos.closeEntry()
        }

        pspResolver().applyDownload("PSP", "God of War", zip)

        assertFalse(File(saves("PSP"), "SAVEDATA/UCUS98653DATA00").exists())
    }

    /** No id means no way to tell one game's folders from another's, so nothing is claimed. */
    @Test fun a_shared_root_without_an_id_resolves_nothing() {
        savedata("UCUS98653")
        assertNull(pspResolver(id = null).resolve("PSP", "God of War"))
    }

    /** The stick's own folders are not any one game's save. */
    @Test fun a_shared_root_ignores_the_memory_stick_own_folders() {
        savedata("UCUS98653")
        File(saves("PSP"), "SYSTEM/CACHE").mkdirs()
        File(saves("PSP"), "PPSSPP_STATE").mkdirs()

        val save = pspResolver().resolve("PSP", "God of War")!!

        assertTrue(save.files.all { it.absolutePath.contains("SAVEDATA") })
    }

    @Test fun applyDownload_single_clears_stale_bundle_files() {
        File(saves("GBA"), "Pokemon.srm").writeBytes("OLD".toByteArray())
        File(saves("GBA"), "Pokemon.rtc").writeBytes("OLDRTC".toByteArray())
        val single = tmp.newFile("dl.bin").apply { writeBytes("NEWSAVE".toByteArray()) }

        LocalSaveResolver(tmp.root).applyDownload("GBA", "Pokemon", single)

        assertEquals("NEWSAVE", File(game("GBA", "Pokemon"), "Pokemon.srm").readText())
        assertFalse(File(game("GBA", "Pokemon"), "Pokemon.rtc").exists())
    }

    @Test fun applyDownload_zip_clears_stale_files_not_in_archive() {
        File(saves("GBA"), "Game.srm").writeBytes("OLD".toByteArray())
        File(saves("GBA"), "Game.rtc").writeBytes("OLDRTC".toByteArray())
        File(saves("GBA"), "Game.eep").writeBytes("STALE".toByteArray())
        val stageRoot = File(tmp.root, "stage")
        File(stageRoot, "Saves/GBA").apply { mkdirs() }
        File(stageRoot, "Saves/GBA/Game.srm").writeBytes("NEW".toByteArray())
        File(stageRoot, "Saves/GBA/Game.rtc").writeBytes("NEWRTC".toByteArray())
        val zip = LocalSaveResolver(stageRoot).bundleToZip("GBA", "Game", tmp.newFile("Game.zip"))

        LocalSaveResolver(tmp.root).applyDownload("GBA", "Game", zip)

        assertEquals("NEW", File(game("GBA", "Game"), "Game.srm").readText())
        assertEquals("NEWRTC", File(game("GBA", "Game"), "Game.rtc").readText())
        assertFalse(File(game("GBA", "Game"), "Game.eep").exists())
    }

    @Test fun applyDownload_leaves_no_staging_behind() {
        File(saves("SNES"), "Mario.srm").writeBytes("OLD".toByteArray())
        val single = tmp.newFile("dl2.bin").apply { writeBytes("NEW".toByteArray()) }

        LocalSaveResolver(tmp.root).applyDownload("SNES", "Mario", single)

        val leftover = saves("SNES").listFiles()!!.filter { it.name.startsWith(".part_") || it.name.startsWith(".old_") }
        assertTrue(leftover.isEmpty())
        assertEquals("NEW", File(game("SNES", "Mario"), "Mario.srm").readText())
    }

    // Two applies of the same save must not share a staging path: whichever renames first would
    // otherwise publish the other's half-written bytes.
    @Test fun applyDownload_does_not_stage_through_a_shared_temp_path() {
        val inFlight = File(saves("GBA"), ".part_Pokemon.srm").apply { writeBytes("IN-FLIGHT".toByteArray()) }
        val src = tmp.newFile("dl3.bin").apply { writeBytes("MINE".toByteArray()) }

        LocalSaveResolver(tmp.root).applyDownload("GBA", "Pokemon", src)

        assertEquals("MINE", File(game("GBA", "Pokemon"), "Pokemon.srm").readText())
        assertTrue(inFlight.isFile)
        assertEquals("IN-FLIGHT", inFlight.readText())
    }
}
