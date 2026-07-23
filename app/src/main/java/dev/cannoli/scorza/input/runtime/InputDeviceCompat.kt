package dev.cannoli.scorza.input.runtime

import android.os.Build
import android.view.InputDevice

// InputDevice.isExternal is API 29+; below that, assume external (matches the ConnectedDevice default).
fun InputDevice.isExternalCompat(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isExternal else true
