package dev.cannoli.scorza.boot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

interface PermissionStatus {
    fun hasStorage(): Boolean
    fun hasOverlay(): Boolean
}

class AndroidPermissionStatus @Inject constructor(
    @ApplicationContext private val context: Context,
) : PermissionStatus {
    override fun hasStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    override fun hasOverlay(): Boolean = Settings.canDrawOverlays(context)
}
