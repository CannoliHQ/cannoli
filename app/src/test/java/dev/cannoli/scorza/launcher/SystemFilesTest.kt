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
    fun `the heavy sets stay remote`() {
        val remote = SystemFiles.manifest(assets).filter { !it.bundled }
        assertEquals(
            setOf("blueMSX.zip", "ScummVM.zip", "LRPS2.zip"),
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
        val planted = File(dest, "mame2003-plus/cheat.dat")
        assertTrue(planted.exists())

        planted.delete()
        SystemFiles.ensureBundled(assets, "MAME", dest, "build-1")
        assertFalse(planted.exists())

        SystemFiles.ensureBundled(assets, "MAME", dest, "build-2")
        assertTrue(planted.exists())
    }

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

    @Test
    fun `remote sets name the folders their archives unpack`() {
        val folders = SystemFiles.manifest(assets)
            .filter { !it.bundled }
            .associate { it.archive to it.folders }
        assertEquals(
            mapOf(
                "blueMSX.zip" to listOf("Databases", "Machines"),
                "ScummVM.zip" to listOf("scummvm"),
                "LRPS2.zip" to listOf("pcsx2/resources"),
            ),
            folders,
        )
    }

    @Test
    fun `platforms gated on real console BIOS are claimed by no remote set`() {
        for (tag in listOf("SATURN", "NEOGEO", "PSX", "DREAMCAST")) {
            assertTrue(
                "$tag is claimed by a remote system-file set",
                SystemFiles.manifest(assets).none { !it.bundled && it.tag == tag }
            )
        }
    }

    @Test
    fun `a deleted bundled folder is laid down again despite a matching marker`() {
        val dest = tempDir("sysfiles-selfheal")
        SystemFiles.ensureBundled(assets, "MAME", dest, "build-1")
        val folder = File(dest, "mame2003-plus")
        assertTrue(folder.isDirectory)

        folder.deleteRecursively()
        SystemFiles.ensureBundled(assets, "MAME", dest, "build-1")

        assertTrue("the folder was not restored", folder.isDirectory)
        assertTrue(File(dest, "mame2003-plus/cheat.dat").exists())
    }

    @Test
    fun `a platform with no bundled set writes no marker`() {
        val dest = tempDir("sysfiles-none")
        SystemFiles.ensureBundled(assets, "SCUMMVM", dest, "build-1")
        assertFalse(File(dest, ".cannoli_system").exists())
    }

    @Test
    fun `armsx2 names a nested folder for PS2`() {
        val entries = SystemFiles.remoteFor(assets, "armsx2_libretro")
        assertEquals(listOf("PS2"), entries.map { it.tag })
        assertEquals(listOf(listOf("pcsx2/resources")), entries.map { it.folders })
    }

    @Test
    fun `a nested folder check is not satisfied by its own parent`() {
        val dest = tempDir("sysfiles-nested")
        File(dest, "pcsx2/bios").mkdirs()
        val entry = SystemFiles.remoteFor(assets, "armsx2_libretro").single()
        assertFalse(entry.foldersPresent(dest))

        File(dest, "pcsx2/resources").mkdirs()
        assertTrue(entry.foldersPresent(dest))
    }

    @Test
    fun `installing the nested folder does not disturb an existing bios dump`() {
        val dest = tempDir("sysfiles-merge")
        val bios = File(dest, "pcsx2/bios/scph39001.bin").apply { parentFile?.mkdirs(); writeText("dump") }

        SystemFiles.install(
            ByteArrayInputStream(
                zip("pcsx2/bios/" to "", "pcsx2/resources/GameIndex.yaml" to "y")
            ),
            dest,
        )

        assertTrue(File(dest, "pcsx2/resources/GameIndex.yaml").exists())
        assertEquals("dump", bios.readText())
    }
}
