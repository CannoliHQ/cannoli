package dev.cannoli.igm

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * Guide navigation state plus the shared open/save/scroll-signal logic used by every host that
 * shows the Cannoli guide viewer: the in-game menu (IGMController, also consumed by the RicottaArch
 * fork via ricotta/IGMOverlay.kt) and the launcher's browse-time viewer. Host-agnostic: owns no
 * screen type and no EmulatorBridge. Each host keeps page/textZoom/selectedIndex in its own screen
 * data class and maps its input to the neutral methods here.
 */
class GuideController {
    private var guideManager: GuideManager? = null

    var guideFiles = mutableStateOf<List<GuideFile>>(emptyList())
        private set
    var guidePageCount = mutableIntStateOf(0)
        private set
    var guideScrollDir = mutableIntStateOf(0)
        private set
    var guideScrollXDir = mutableIntStateOf(0)
        private set
    var guidePageJump = mutableIntStateOf(0)
        private set
    var guidePageJumpDir = mutableIntStateOf(0)
        private set
    var guideInitialScroll = mutableIntStateOf(0)
        private set
    var guideInitialScrollX = mutableIntStateOf(0)
        private set

    private var guideScrollPos = 0
    private var guideScrollXPos = 0

    data class GuideOpenState(val filePath: String, val initialPage: Int, val textZoom: Int)

    fun attach(manager: GuideManager) {
        guideManager = manager
        guideFiles.value = manager.findGuides()
    }

    fun onScrollChanged(y: Int, x: Int) {
        guideScrollPos = y
        guideScrollXPos = x
    }

    fun prepareGuide(guide: GuideFile): GuideOpenState? {
        val manager = guideManager ?: return null
        val saved = manager.loadSavedPosition(guide.file)
        guideScrollDir.intValue = 0
        guideScrollXDir.intValue = 0
        guidePageJump.intValue = 0
        guideScrollXPos = saved.scrollX
        guideInitialScrollX.intValue = saved.scrollX
        guidePageCount.intValue = if (guide.type == GuideType.PDF) {
            try {
                val pfd = android.os.ParcelFileDescriptor.open(
                    guide.file, android.os.ParcelFileDescriptor.MODE_READ_ONLY
                )
                pfd.use { android.graphics.pdf.PdfRenderer(it).use { r -> r.pageCount } }
            } catch (_: Exception) { 1 }
        } else 0
        guideScrollPos = if (guide.type == GuideType.PDF) saved.scrollY else saved.position
        guideInitialScroll.intValue = guideScrollPos
        val initialPage = if (guide.type == GuideType.PDF) {
            saved.position.coerceIn(0, (guidePageCount.intValue - 1).coerceAtLeast(0))
        } else 0
        return GuideOpenState(guide.file.absolutePath, initialPage, saved.zoom)
    }

    fun scroll(dir: Int) { guideScrollDir.intValue = dir }

    fun scrollX(dir: Int) { guideScrollXDir.intValue = dir }

    fun pageJump(dir: Int) {
        guidePageJumpDir.intValue = dir
        guidePageJump.intValue++
    }

    fun beginZoomReseed() {
        guideInitialScroll.intValue = guideScrollPos
        guideInitialScrollX.intValue = guideScrollXPos
    }

    fun saveGuide(guide: GuideFile, pdfPage: Int?, textZoom: Int) {
        val pos = pdfPage ?: guideScrollPos
        guideManager?.save(guide.file, pos, guideScrollPos, guideScrollXPos, textZoom)
    }
}
