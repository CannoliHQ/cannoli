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

class PdfTileRenderer private constructor(
    private val worker: ExecutorService,
    private val dispatcher: CoroutineDispatcher,
    private val fd: ParcelFileDescriptor,
    private val doc: PdfDocument,
    val pageCount: Int,
) : Closeable {

    suspend fun aspectOf(page: Int): Float = withContext(dispatcher) {
        doc.openPage(page).use { p ->
            val w = p.getPageWidthPoint()
            if (w <= 0) 1f else p.getPageHeightPoint().toFloat() / w
        }
    }

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
