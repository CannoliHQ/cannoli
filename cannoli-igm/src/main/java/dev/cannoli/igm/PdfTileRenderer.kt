package dev.cannoli.igm

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.PdfiumCore
import io.legere.pdfiumandroid.util.Config
import java.io.Closeable
import java.io.File

/**
 * Draws one visible window of a page at a time.
 *
 * This is the whole reason pdfium is here. PdfRenderer only fills a bitmap with a complete page, so
 * the allocation followed the page's own size and the zoom: a letter page at the top zoom came to
 * about 31 MB, and a poster-sized map, which game guides really do ship, was far worse. Rendering a
 * window means the allocation follows the screen instead, whatever the page and whatever the zoom.
 */
class PdfTileRenderer(context: Context, file: File) : Closeable {

    private val fd: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

    private val doc = PdfiumCore(context, Config()).newDocument(fd)

    // A pan cancels the render it supersedes, but cancelling the coroutine does not stop the pdfium
    // call already inside it, and two overlapping openPage calls on one document crash the process.
    // Every entry point takes this, so the native side only ever sees one caller.
    private val lock = Any()

    val pageCount: Int get() = synchronized(lock) { doc.getPageCount() }

    /** Height over width, which is what sizes the scrollable content from the viewport's width. */
    fun aspectOf(page: Int): Float = synchronized(lock) {
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
    fun renderTile(
        page: Int,
        contentWidth: Int,
        contentHeight: Int,
        srcX: Int,
        srcY: Int,
        width: Int,
        height: Int,
    ): Bitmap = synchronized(lock) {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        doc.openPage(page).use { p ->
            p.renderPageBitmap(bmp, -srcX, -srcY, contentWidth, contentHeight)
        }
        bmp
    }

    override fun close() = synchronized(lock) {
        runCatching { doc.close() }
        runCatching { fd.close() }
        Unit
    }
}
