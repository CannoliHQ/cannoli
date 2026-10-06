package dev.cannoli.igm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.io.File

@Composable
fun OverlayLayer(path: String?, widthPx: Int, heightPx: Int) {
    if (path.isNullOrEmpty() || widthPx <= 0 || heightPx <= 0) return
    val bitmap = remember(path) { decodeScaled(path, widthPx, heightPx) } ?: return
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.FillBounds,
    )
}

private fun decodeScaled(path: String, widthPx: Int, heightPx: Int): Bitmap? {
    val file = File(path)
    if (!file.isFile) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, widthPx, heightPx)
            },
        )
    } catch (_: Throwable) {
        null
    }
}

internal fun sampleSize(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Int {
    var sample = 1
    while (srcW / (sample * 2) >= dstW && srcH / (sample * 2) >= dstH) sample *= 2
    return sample
}
