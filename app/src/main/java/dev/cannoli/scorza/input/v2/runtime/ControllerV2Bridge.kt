package dev.cannoli.scorza.input.v2.runtime

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.view.InputDevice
import dev.cannoli.scorza.input.v2.resolver.TemplateResolver

class ControllerV2Bridge(
    private val resolver: TemplateResolver,
    private val portRouter: PortRouter,
    private val activeTemplateHolder: ActiveTemplateHolder,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val buildModel: String = Build.MODEL ?: "",
) {

    data class DeviceFacts(
        val androidDeviceId: Int,
        val descriptor: String?,
        val name: String?,
        val vendorId: Int,
        val productId: Int,
        val sourceMask: Int,
    )

    private val knownDeviceIds = mutableSetOf<Int>()
    private var listener: InputManager.InputDeviceListener? = null

    fun start(context: Context) {
        if (listener != null) return
        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        val l = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                val device = InputDevice.getDevice(deviceId) ?: return
                handleDeviceAdded(device.toFacts())
            }

            override fun onInputDeviceRemoved(deviceId: Int) {
                handleDeviceRemoved(deviceId)
            }

            override fun onInputDeviceChanged(deviceId: Int) {}
        }
        listener = l
        inputManager.registerInputDeviceListener(l, null)
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            handleDeviceAdded(device.toFacts())
        }
    }

    fun stop(context: Context) {
        val l = listener ?: return
        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager.unregisterInputDeviceListener(l)
        listener = null
    }

    fun markLaunchTrigger(androidDeviceId: Int) {
        portRouter.markLaunchTrigger(androidDeviceId)
    }

    fun handleDeviceAdded(facts: DeviceFacts) {
        if (!isGamepad(facts)) return
        val zeroVidPid = facts.vendorId == 0 && facts.productId == 0
        if (zeroVidPid && facts.name.isNullOrEmpty()) return
        if (!knownDeviceIds.add(facts.androidDeviceId)) return
        val connected = ConnectedDeviceFactory.fromFields(
            androidDeviceId = facts.androidDeviceId,
            descriptor = facts.descriptor,
            name = facts.name,
            vendorId = facts.vendorId,
            productId = facts.productId,
            androidBuildModel = buildModel,
            sourceMask = facts.sourceMask,
            connectedAtMillis = clock(),
            isBuiltIn = zeroVidPid,
        )
        val resolved = resolver.resolve(connected)
        portRouter.onConnect(connected, resolved.template)
        activeTemplateHolder.set(resolved.template)
    }

    fun handleDeviceRemoved(androidDeviceId: Int) {
        if (!knownDeviceIds.remove(androidDeviceId)) return
        portRouter.onDisconnect(androidDeviceId)
    }

    private fun isGamepad(facts: DeviceFacts): Boolean {
        val sources = facts.sourceMask
        return (sources and SOURCE_GAMEPAD) == SOURCE_GAMEPAD ||
            (sources and SOURCE_JOYSTICK) == SOURCE_JOYSTICK
    }

    private fun InputDevice.toFacts(): DeviceFacts = DeviceFacts(
        androidDeviceId = id,
        descriptor = descriptor,
        name = name,
        vendorId = vendorId,
        productId = productId,
        sourceMask = sources,
    )

    companion object {
        const val SOURCE_GAMEPAD: Int = InputDevice.SOURCE_GAMEPAD
        const val SOURCE_JOYSTICK: Int = InputDevice.SOURCE_JOYSTICK
    }
}
