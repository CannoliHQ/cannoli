package dev.cannoli.igm

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfiumCore
import io.legere.pdfiumandroid.util.Config
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Draws one visible window of a page at a time.
 *
 * This is the whole reason pdfium is here. PdfRenderer only fills a bitmap with a complete page, so
 * the allocation followed the page's own size and the zoom: a letter page at the top zoom came to
 * about 31 MB, and a poster-sized map, which game guides really do ship, was far worse. Rendering a
 * window means the allocation follows the screen instead, whatever the page and whatever the zoom.
 *
 * Everything native happens on one thread of its own. That serialises pdfium, which two overlapping
 * openPage calls on a document would otherwise crash, and it keeps opening, drawing and closing off
 * the main thread: the menu sits over a running game, so a stall there is a stutter in the game.
 */
class PdfTileRenderer private constructor(
    private val worker: ExecutorService,
    private val dispatcher: CoroutineDispatcher,
    private val fd: ParcelFileDescriptor,
    private val doc: PdfDocument,
    val pageCount: Int,
) : Closeable {

    /** Height over width, which is what sizes the scrollable content from the viewport's width. */
    suspend fun aspectOf(page: Int): Float = withContext(dispatcher) {
        doc.openPage(page).use { p ->
            val w = p.getPageWidthPoint()
            if (w <= 0) 1f else p.getPageHeightPoint().toFloat() / w
        }
    }

    /**
     * [contentWidth] by [contentHeight] is the page at its on-screen size for the current zoom, and
     * the tile is the [width] by [height] window of that starting at [srcX], [srcY]. pdfium is told
     * where the page's origin sits relative to the bitmap, so a negative offset scrolls it.
     */
    suspend fun renderTile(
        page: Int,
        contentWidth: Int,
        contentHeight: Int,
        srcX: Int,
        srcY: Int,
        width: Int,
        height: Int,
    ): Bitmap = withContext(dispatcher) {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        doc.openPage(page).use { p ->
            p.renderPageBitmap(bmp, -srcX, -srcY, contentWidth, contentHeight)
        }
        bmp
    }

    /**
     * Returns as soon as the close is queued. The worker runs its queue in order, so a render
     * already waiting still finds an open document, and shutdown only refuses new ones.
     */
    override fun close() {
        runCatching {
            worker.execute {
                runCatching { doc.close() }
                runCatching { fd.close() }
            }
        }
        runCatching { worker.shutdown() }
    }

    companion object {
        /** Null when the file cannot be opened or parsed, which is the caller's cue to draw nothing. */
        suspend fun open(context: Context, file: File): PdfTileRenderer? {
            val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-tile") }
            val dispatcher = worker.asCoroutineDispatcher()
            return runCatching {
                withContext(dispatcher) {
                    val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val doc = PdfiumCore(context, Config()).newDocument(fd)
                    PdfTileRenderer(worker, dispatcher, fd, doc, doc.getPageCount())
                }
            }.getOrElse {
                worker.shutdown()
                null
            }
        }
    }
}
