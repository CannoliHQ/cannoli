package dev.cannoli.scorza.server

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Semaphore

/** Downscaled copies of box art, cached on internal storage. Full-size art runs to a couple of MB
 *  per file, so a grid of them saturates the connection and the decode congests the browser's main
 *  thread; the cards only ever need a few hundred pixels. */
internal class ArtThumbnails(private val cacheDir: File) {

    // A full-size decode is far larger in memory than the file on disk, and the server can run many
    // requests at once, so generation is throttled independently of request concurrency.
    private val decodePermits = Semaphore(MAX_CONCURRENT_DECODES, true)

    /** A cached thumbnail of [source] at [width], or null when one cannot or need not be made, in
     *  which case the caller should serve the original. */
    fun thumbnail(source: File, width: Int): File? {
        if (width !in MIN_WIDTH..MAX_WIDTH) return null
        val cached = File(cacheDir, "${cacheKey(source, width)}.webp")
        if (cached.isFile && cached.length() > 0) return cached
        return try {
            decodePermits.acquire()
            try {
                generate(source, width, cached)
            } finally {
                decodePermits.release()
            }
        } catch (_: Throwable) { null }
    }

    private fun generate(source: File, width: Int, dest: File): File? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        // Nothing to gain from upscaling, and a source we cannot measure is one we should not touch.
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth <= width) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, width)
        }
        val decoded = BitmapFactory.decodeFile(source.absolutePath, opts) ?: return null
        val scaled = try {
            if (decoded.width <= width) decoded
            else {
                val height = (decoded.height.toLong() * width / decoded.width).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded, width, height, true)
            }
        } catch (_: Throwable) {
            decoded.recycle()
            return null
        }

        return try {
            dest.parentFile?.mkdirs()
            // Written aside and moved so a concurrent reader never sees a half-written file.
            val temp = File(dest.parentFile, "${dest.name}.${Thread.currentThread().id}.tmp")
            temp.outputStream().use { out -> scaled.compress(webpFormat(), QUALITY, out) }
            if (temp.renameTo(dest)) dest else { temp.delete(); null }
        } catch (_: Throwable) {
            null
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    private fun cacheKey(source: File, width: Int): String {
        // Size and mtime in the key mean replacing the art file yields a different key, so a stale
        // thumbnail can never be served and nothing has to be actively evicted.
        val raw = "${source.absolutePath}|${source.length()}|${source.lastModified()}|$width"
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
    }

    private fun sampleSizeFor(sourceWidth: Int, targetWidth: Int): Int {
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth) sample *= 2
        return sample
    }

    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP

    companion object {
        const val MIN_WIDTH = 32
        const val MAX_WIDTH = 1024
        private const val QUALITY = 80
        private const val MAX_CONCURRENT_DECODES = 4
    }
}
