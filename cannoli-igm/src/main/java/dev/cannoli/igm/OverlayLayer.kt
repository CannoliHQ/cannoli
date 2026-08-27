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

/**
 * Draws the chosen bezel over the game.
 *
 * Cannoli draws this rather than handing the image to RetroArch's input_overlay. That subsystem
 * exists to serve touch controls, which Cannoli does not have, and everything it brings with it -
 * pausing, window focus, hide-when-a-pad-is-connected - fights a frontend that owns its own
 * presentation. Drawing here also keeps the bezel out of the shader chain, which is what anyone
 * pairing a CRT shader with a bezel wants.
 *
 * The bitmap is decoded once per path and downsampled to the surface, so a 4K pack costs the same
 * as a 1080p one. Nothing redraws per frame: this is a static layer the compositor carries.
 */
@Composable
fun OverlayLayer(path: String?, widthPx: Int, heightPx: Int) {
    if (path.isNullOrEmpty() || widthPx <= 0 || heightPx <= 0) return
    // Keyed on the path alone. Sampling only needs a target to halve against, and re-decoding a
    // full-screen bitmap every time the surface is remeasured costs far more than the sharpness it
    // could buy back.
    val bitmap = remember(path) { decodeScaled(path, widthPx, heightPx) } ?: return
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        // The artwork is authored for the whole screen, and a bezel's cutout only lines up with the
        // game when it covers exactly that. Cropping or letterboxing it would move the window.
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
        // An unreadable or oversized image loses the bezel, never the game.
        null
    }
}

// Halves until both edges are within the target, the contract BitmapFactory documents for
// inSampleSize. A bezel larger than the screen buys nothing and costs width x height x 4 bytes.
internal fun sampleSize(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Int {
    var sample = 1
    while (srcW / (sample * 2) >= dstW && srcH / (sample * 2) >= dstH) sample *= 2
    return sample
}
