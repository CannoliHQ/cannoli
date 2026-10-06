package dev.cannoli.scorza.input.runtime

import android.os.Build
import android.view.InputDevice
import dev.cannoli.scorza.input.TriggerAxes

fun InputDevice.isExternalCompat(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isExternal else true

fun InputDevice.declaredTriggerAxes(): Set<Int> =
    TriggerAxes.all.filterTo(mutableSetOf()) { getMotionRange(it) != null }
