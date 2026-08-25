package dev.cannoli.scorza.launcher

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.config.CoreInfoRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The manifest carries archive names with spaces and parentheses, and the destination is a
 * directory cores read by path, so both the parse and the extraction have to keep what they are
 * given intact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemFilesTest {

    private val assets get() =
        ApplicationProvider.getApplicationContext<android.content.Context>().assets

    private fun tempDir(name: String): File {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return File(ctx.cacheDir, name).apply { deleteRecursively(); mkdirs() }
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, body) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(body.toByteArray())
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `archive names keep their spaces and parentheses`() {
        val fbneo = SystemFiles.manifest(assets).filter { it.core == "fbneo_libretro" }
        assertEquals(2, fbneo.size)
        assertTrue(fbneo.all { it.archive == "FinalBurn Neo (hiscore).zip" })
        // The bare name is a 404 on the buildbot; the parenthesised one is the real asset.
        assertTrue(fbneo.all { it.assetPath == "system/FinalBurn Neo (hiscore).zip" })
    }

    @Test
    fun `every bundled archive named by the manifest actually ships`() {
        val shipped = assets.list("system")?.toSet().orEmpty()
        val bundled = SystemFiles.manifest(assets).filter { it.bundled }
        assertTrue("manifest declares no bundled sets", bundled.isNotEmpty())
        for (entry in bundled) {
            assertTrue("${entry.archive} is not in assets/system", entry.archive in shipped)
        }
    }

    @Test
    fun `the two heavy sets stay remote`() {
        val remote = SystemFiles.manifest(assets).filter { !it.bundled }
        assertEquals(
            setOf("blueMSX.zip", "ScummVM.zip"),
            remote.map { it.archive }.toSet()
        )
    }

    @Test
    fun `a core is found whether or not the caller spells the libretro suffix`() {
        assertEquals(
            SystemFiles.remoteFor(assets, "scummvm_libretro").map { it.archive },
            SystemFiles.remoteFor(assets, "scummvm").map { it.archive }
        )
        assertEquals(listOf("ScummVM.zip"), SystemFiles.remoteFor(assets, "scummvm").map { it.archive })
    }

    @Test
    fun `bluemsx serves only ColecoVision now that SG1000 excludes it`() {
        val tags = SystemFiles.remoteFor(assets, "bluemsx_libretro").map { it.tag }
        assertEquals(listOf("COLECOVISION"), tags)
    }

    @Test
    fun `install keeps the paths a core looks for`() {
        val dest = tempDir("sysfiles-paths")
        SystemFiles.install(ByteArrayInputStream(zip("mame2003-plus/cheat.dat" to "x")), dest)
        assertTrue(File(dest, "mame2003-plus/cheat.dat").exists())
    }

    @Test
    fun `install refuses an entry that escapes the system directory`() {
        val dest = tempDir("sysfiles-slip")
        val outside = File(dest.parentFile, "escaped.txt").also { it.delete() }
        SystemFiles.install(ByteArrayInputStream(zip("../escaped.txt" to "x")), dest)
        assertFalse("zip-slip wrote outside the system directory", outside.exists())
    }

    @Test
    fun `extraction runs once per build and again when the build changes`() {
        val dest = tempDir("sysfiles-marker")
        val marker = File(dest, ".cannoli_system")

        SystemFiles.ensureBundled(assets, "MAME", dest, "build-1")
        assertTrue(marker.exists())
        val planted = File(dest, "mame2003/cheat.dat")
        assertTrue(planted.exists())

        // A second launch on the same build must not re-extract: prove it by deleting a file the
        // archive supplies and showing the marker keeps it deleted.
        planted.delete()
        SystemFiles.ensureBundled(assets, "MAME", dest, "build-1")
        assertFalse(planted.exists())

        // A new build re-lays what it ships.
        SystemFiles.ensureBundled(assets, "MAME", dest, "build-2")
        assertTrue(planted.exists())
    }

    /**
     * The gate that blocks a launch on absent required firmware runs before the launch config is
     * built, so a bundled set has to satisfy its own core's required entries. PSP is the live case:
     * `PPSSPP/ppge_atlas.zim` is declared required and ships inside `PPSSPP.zip`, so a bundle that
     * stopped carrying it would block the platform rather than degrade it.
     */
    @Test
    fun `every required firmware of a bundled core ships inside its archive`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val coreInfo = CoreInfoRepository(ctx.assets).also { it.load() }
        var checked = 0
        for (entry in SystemFiles.manifest(assets).filter { it.bundled }) {
            val required = coreInfo.getFirmwareFor(entry.core)
                .filter { !it.optional }
                .map { it.path }
            if (required.isEmpty()) continue
            val carried = mutableSetOf<String>()
            assets.open(entry.assetPath).use { raw ->
                java.util.zip.ZipInputStream(raw.buffered()).use { zis ->
                    while (true) {
                        val e = zis.nextEntry ?: break
                        if (!e.isDirectory) carried.add(e.name)
                        zis.closeEntry()
                    }
                }
            }
            for (path in required) {
                assertTrue("${entry.archive} does not carry $path, required by ${entry.core}",
                    path in carried)
                checked++
            }
        }
        assertTrue("no bundled core declares required firmware", checked > 0)
    }

    /**
     * The folders column is what decides whether a core reads as installed, so a remote set has to
     * name the folders its archive really unpacks. Verified against the archives themselves.
     */
    @Test
    fun `remote sets name the folders their archives unpack`() {
        val folders = SystemFiles.manifest(assets)
            .filter { !it.bundled }
            .associate { it.archive to it.folders }
        assertEquals(
            mapOf(
                "blueMSX.zip" to listOf("Databases", "Machines"),
                "ScummVM.zip" to listOf("scummvm"),
            ),
            folders,
        )
    }

    /**
     * A console BIOS is the user's to supply, so no remote set may claim a platform whose gate is
     * about one. Claiming it would make a missing BIOS read as a core that is not installed and
     * send the user to download something that would never contain it.
     */
    @Test
    fun `platforms gated on real console BIOS are claimed by no remote set`() {
        for (tag in listOf("SATURN", "NEOGEO", "PSX", "DREAMCAST")) {
            assertTrue(
                "$tag is claimed by a remote system-file set",
                SystemFiles.manifest(assets).none { !it.bundled && it.tag == tag }
            )
        }
    }

    /**
     * The marker alone is not enough. A user who deletes a bundled folder keeps a marker claiming
     * this build already delivered it, and without the folder check the platform would stay broken
     * with the fix sitting in the APK.
     */
    @Test
    fun `a deleted bundled folder is laid down again despite a matching marker`() {
        val dest = tempDir("sysfiles-selfheal")
        SystemFiles.ensureBundled(assets, "MAME", dest, "build-1")
        val folder = File(dest, "mame2003")
        assertTrue(folder.isDirectory)

        folder.deleteRecursively()
        SystemFiles.ensureBundled(assets, "MAME", dest, "build-1")

        assertTrue("the folder was not restored", folder.isDirectory)
        assertTrue(File(dest, "mame2003/cheat.dat").exists())
    }

    @Test
    fun `a platform with no bundled set writes no marker`() {
        val dest = tempDir("sysfiles-none")
        SystemFiles.ensureBundled(assets, "SCUMMVM", dest, "build-1")
        assertFalse(File(dest, ".cannoli_system").exists())
    }
}
