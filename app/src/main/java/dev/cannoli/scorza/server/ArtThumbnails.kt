package dev.cannoli.scorza.server

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Semaphore

internal class ArtThumbnails(private val cacheDir: File) {

    private val decodePermits = Semaphore(MAX_CONCURRENT_DECODES, true)

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
