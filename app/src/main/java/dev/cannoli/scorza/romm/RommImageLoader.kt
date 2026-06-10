package dev.cannoli.scorza.romm

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import java.io.File

object RommImageLoader {
    fun build(
        context: Context,
        http: RommHttp,
        cacheDir: File,
        maxSizeBytes: Long = 128L * 1024 * 1024,
    ): ImageLoader =
        ImageLoader.Builder(context)
            .okHttpClient { http.client() }
            .diskCache(
                DiskCache.Builder()
                    .directory(cacheDir)
                    .maxSizeBytes(maxSizeBytes)
                    .build()
            )
            .build()
}
