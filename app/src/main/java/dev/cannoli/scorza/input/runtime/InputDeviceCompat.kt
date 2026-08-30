package dev.cannoli.scorza.input.runtime

import android.os.Build
import android.view.InputDevice
import dev.cannoli.scorza.input.TriggerAxes

// InputDevice.isExternal is API 29+; below that, assume external (matches the ConnectedDevice default).
fun InputDevice.isExternalCompat(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isExternal else true

/** Which trigger axes this pad says it reports. See [dev.cannoli.scorza.input.ConnectedDevice]. */
fun InputDevice.declaredTriggerAxes(): Set<Int> =
    TriggerAxes.all.filterTo(mutableSetOf()) { getMotionRange(it) != null }
