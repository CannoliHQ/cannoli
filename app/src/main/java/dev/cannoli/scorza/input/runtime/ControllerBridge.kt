package dev.cannoli.scorza.input.runtime

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import androidx.annotation.VisibleForTesting
import dev.cannoli.scorza.input.ConnectedDevice
import dev.cannoli.scorza.input.resolver.DevKeyboardMapping
import dev.cannoli.scorza.input.resolver.MappingResolver

class ControllerBridge(
    private val resolver: MappingResolver,
    private val portRouter: PortRouter,
    private val activeMappingHolder: ActiveMappingHolder,
    private val autoconfigRepository: dev.cannoli.scorza.input.autoconfig.AutoconfigRepository,
    private val blacklist: dev.cannoli.scorza.input.ControllerBlacklist? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val buildModel: String = Build.MODEL ?: "",
    /**
     * Enrolls an attached keyboard as a synthetic controller so the launcher and IGM can be driven
     * from an Android Virtual Device with no gamepad attached. Debug + AVD only; see the wiring in
     * ControllerBindingsModule. Off here by default so release behaviour cannot change.
     */
    val devKeyboardEnabled: Boolean = false,
) {

    data class DeviceFacts(
        val androidDeviceId: Int,
        val descriptor: String?,
        val name: String?,
        val vendorId: Int,
        val productId: Int,
        val sourceMask: Int,
        val isExternal: Boolean = true,
        val keyboardType: Int = InputDevice.KEYBOARD_TYPE_NONE,
        val declaredTriggerAxes: Set<Int> = emptySet(),
    )

    private var listener: InputManager.InputDeviceListener? = null
    private var initialEnumerationDone = false
    private var appContext: Context? = null

    init {
        portRouter.onActivatedListener = { device -> handleActivation(device) }
    }

    private val settleHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val settleRunnable = Runnable {
        settle()
        if (!initialEnumerationDone) {
            initialEnumerationDone = true
            dev.cannoli.scorza.util.InputLog.write("--- initial enumeration done ---")
        }
    }

    var onDeviceAdded: ((ConnectedDevice) -> Unit)? = null
    var onDeviceRemoved: ((DepartedDevice) -> Unit)? = null

    data class DepartedDevice(
        val androidDeviceId: Int,
        val displayName: String,
        val port: Int?,
    )

    fun start(context: Context) {
        if (listener != null) return
        appContext = context.applicationContext
        dev.cannoli.scorza.util.InputLog.write("--- bridge start (Build.MODEL='$buildModel') ---")
        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        val l = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                val facts = InputDevice.getDevice(deviceId)?.toFacts() ?: return
                handleDeviceAdded(facts)
            }

            override fun onInputDeviceRemoved(deviceId: Int) {
                handleDeviceRemoved(deviceId)
            }

            override fun onInputDeviceChanged(deviceId: Int) {
                val device = InputDevice.getDevice(deviceId) ?: return
                dev.cannoli.scorza.util.InputLog.write(
                    "changed id=$deviceId desc='${device.descriptor}' name='${device.name}' src=0x${device.sources.toString(16)}"
                )
            }
        }
        listener = l
        inputManager.registerInputDeviceListener(l, null)
        scheduleSettle()
    }

    fun stop(context: Context) {
        val l = listener ?: return
        settleHandler.removeCallbacks(settleRunnable)
        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager.unregisterInputDeviceListener(l)
        initialEnumerationDone = false
        listener = null
        appContext = null
    }

    private fun handleActivation(device: ConnectedDevice) {
        portRouter.mappingFor(device.androidDeviceId)?.let { activeMappingHolder.set(it) }
        // Activation is always a deliberate user press, so fire onDeviceAdded regardless of
        // whether the device was present during the initial enumeration burst. Suppression for
        // built-in devices is handled by the OSD layer, not here.
        onDeviceAdded?.invoke(device)
    }

    /**
     * True only for events from the keyboard actually enrolled as the dev controller (or its
     * `adb shell input` alias). Callers that special-case keyboard-sourced events must ask this
     * rather than reading [devKeyboardEnabled], so a device the bridge never enrolled -- a
     * handheld's GPIO menu button, say -- keeps its normal handling even if the AVD gate is
     * somehow wrong about the host.
     */
    fun isDevKeyboardDevice(androidDeviceId: Int): Boolean =
        devKeyboardEnabled &&
            portRouter.mappingFor(androidDeviceId)?.id == DevKeyboardMapping.ID

    fun markLaunchTrigger(androidDeviceId: Int) {
        portRouter.markLaunchTrigger(androidDeviceId)
    }

    fun handleDeviceAdded(facts: DeviceFacts) {
        dev.cannoli.scorza.util.InputLog.write(
            "event added id=${facts.androidDeviceId} desc='${facts.descriptor}' name='${facts.name}' vid=${facts.vendorId} pid=${facts.productId} src=0x${facts.sourceMask.toString(16)}"
        )
        scheduleSettle()
    }

    fun handleDeviceRemoved(androidDeviceId: Int) {
        dev.cannoli.scorza.util.InputLog.write("event removed id=$androidDeviceId")
        scheduleSettle()
    }

    /** Cancel any pending settle and run one immediately. */
    fun settleNow() {
        settleHandler.removeCallbacks(settleRunnable)
        settleHandler.post(settleRunnable)
    }

    private fun scheduleSettle() {
        settleHandler.removeCallbacks(settleRunnable)
        settleHandler.postDelayed(settleRunnable, SETTLE_DELAY_MS)
    }

    @VisibleForTesting
    fun settleSyncForTest(facts: List<DeviceFacts>) {
        settle(facts)
        if (!initialEnumerationDone) {
            initialEnumerationDone = true
        }
    }

    private fun enumerateFacts(): List<DeviceFacts> {
        val out = mutableListOf<DeviceFacts>()
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            out += device.toFacts()
        }
        return out
    }

    private fun settle(forcedFacts: List<DeviceFacts>? = null) {
        dev.cannoli.scorza.util.InputLog.write("--- settle ---")
        // The store caches its listing for the life of the process, and the first enumeration runs
        // before MANAGE_EXTERNAL_STORAGE is granted, so that cache can be an empty directory that
        // no later settle would ever look past.
        autoconfigRepository.invalidate()

        val enumerated = forcedFacts ?: enumerateFacts()
        val devKeyboard = devKeyboardFacts(enumerated)
        // The virtual keyboard that `adb shell input` injects under is never returned by
        // InputDevice.getDeviceIds(), so on a headless AVD it has to be appended by hand.
        val factsList = if (devKeyboard != null && enumerated.none { it.androidDeviceId == devKeyboard.androidDeviceId }) {
            enumerated + devKeyboard
        } else {
            enumerated
        }
        val devKeyboardId = devKeyboard?.androidDeviceId

        // Build the sibling-folding candidate set from ALL InputDevices, not just gamepads, so the
        // auxiliary endpoints (touchpad/IMU/keyboard/mouse) can contribute their MAC-bearing
        // descriptors when the gamepad's own is degenerate.
        val candidates = mutableListOf<SiblingFolder.Candidate>()
        for (facts in factsList) {
            val gamepad = isGamepad(facts) || facts.androidDeviceId == devKeyboardId
            if (gamepad && blacklist?.isBlocked(facts.name, facts.vendorId) == true) {
                dev.cannoli.scorza.util.InputLog.write(
                    "  blacklisted id=${facts.androidDeviceId} name='${facts.name}' vid=${facts.vendorId}"
                )
                continue
            }
            val zeroVidPid = facts.vendorId == 0 && facts.productId == 0
            if (gamepad && zeroVidPid && facts.name.isNullOrEmpty()) continue
            candidates += SiblingFolder.Candidate(
                androidDeviceId = facts.androidDeviceId,
                name = facts.name ?: "",
                descriptor = facts.descriptor ?: "",
                isGamepad = gamepad,
            )
        }

        val clusters = SiblingFolder.fold(candidates)
        val factsById = factsList.associateBy { it.androidDeviceId }

        val targetEntries = mutableMapOf<Int, ConnectedDevice>()
        val targetAliases = mutableMapOf<Int, Int>()
        for (cluster in clusters) {
            val gamepadFacts = factsById[cluster.gamepad.androidDeviceId] ?: continue
            // Retroid (and likely other handhelds) lie via InputDevice.isExternal and report
            // their internal pad as external. Fall back to a name-vs-Build.MODEL prefix check:
            // an internal pad almost always reports a name that starts with the handheld brand
            // (e.g. Build.MODEL='Retroid Pocket Classic' + name='Retroid Pocket Controller').
            val nameLooksInternal = nameMatchesBuildModelBrand(gamepadFacts.name, buildModel)
            val connected = ConnectedDeviceFactory.fromFields(
                androidDeviceId = gamepadFacts.androidDeviceId,
                descriptor = gamepadFacts.descriptor,
                name = gamepadFacts.name,
                vendorId = gamepadFacts.vendorId,
                productId = gamepadFacts.productId,
                androidBuildModel = buildModel,
                sourceMask = gamepadFacts.sourceMask,
                connectedAtMillis = clock(),
                isBuiltIn = !gamepadFacts.isExternal || nameLooksInternal,
                isExternal = gamepadFacts.isExternal,
                declaredTriggerAxes = gamepadFacts.declaredTriggerAxes,
            )
            targetEntries[connected.androidDeviceId] = connected
            dev.cannoli.scorza.util.InputLog.write(
                "  identify id=${connected.androidDeviceId} name='${connected.name}' vid=${connected.vendorId} pid=${connected.productId}"
            )
            for (alias in cluster.aliases) {
                targetAliases[alias.androidDeviceId] = connected.androidDeviceId
                dev.cannoli.scorza.util.InputLog.write(
                    "  phantom-alias: id=${alias.androidDeviceId} name='${alias.name}' -> primary id=${connected.androidDeviceId}"
                )
            }
        }

        // `adb shell input keyevent` injects under KeyCharacterMap.VIRTUAL_KEYBOARD, a device that
        // never enumerates. Aliasing it onto the dev keyboard's entry lets injected keys resolve
        // through the same evaluator and port instead of needing a second synthetic controller.
        if (devKeyboardId != null && devKeyboardId != VIRTUAL_KEYBOARD_ID) {
            targetAliases[VIRTUAL_KEYBOARD_ID] = devKeyboardId
        }

        val existingEntryIds = portRouter.snapshotEntries().map { it.androidDeviceId }.toSet()
        val targetEntryIds = targetEntries.keys

        val existingSnaps = portRouter.snapshotEntries()
        for (id in existingEntryIds - targetEntryIds) {
            val snap = existingSnaps.firstOrNull { it.androidDeviceId == id }
            val displayName = snap?.mapping?.displayName?.takeIf { it.isNotEmpty() }
                ?: snap?.device?.name?.takeIf { it.isNotEmpty() }
                ?: "Controller"
            val port = snap?.port
            dev.cannoli.scorza.util.InputLog.write("  removed id=$id name='$displayName' port=${port?.let { "P${it + 1}" } ?: "-"}")
            portRouter.onDisconnect(id)
            if (initialEnumerationDone) {
                onDeviceRemoved?.invoke(DepartedDevice(id, displayName, port))
            }
        }

        for (id in targetEntryIds - existingEntryIds) {
            val connected = targetEntries.getValue(id)
            // Bypass the resolver entirely: it has no profile for a keyboard, and the pad it would
            // report as unidentified would be sent to the setup wizard asking for gamepad buttons
            // this device cannot produce.
            if (id == devKeyboardId) {
                portRouter.onConnect(connected, DevKeyboardMapping.create(connected))
                dev.cannoli.scorza.util.InputLog.write(
                    "  enrolled dev keyboard id=$id name='${connected.name}'"
                )
                continue
            }
            val resolved = resolver.resolve(connected)
            portRouter.onConnect(connected, resolved)
            dev.cannoli.scorza.util.InputLog.write(
                "  enrolled id=${connected.androidDeviceId} mapping=${resolved.id} glyph=${resolved.glyphStyle}"
            )
        }

        // Devices that survived from the previous settle keep their entry, so re-resolve them here
        // rather than leaving whatever the first settle produced. The launcher enumerates before
        // MANAGE_EXTERNAL_STORAGE is granted, so that first resolve reads an empty autoconfig
        // directory and hands out a fallback; the re-settle after the grant reads the database
        // again (the invalidate above) and swaps the user's cfg in. Only a mapping that actually
        // changed is applied, so ordinary settles (one per device add/remove) leave enrolled
        // controllers alone.
        for (id in targetEntryIds intersect existingEntryIds) {
            if (id == devKeyboardId) continue
            val connected = targetEntries.getValue(id)
            val current = portRouter.mappingFor(id) ?: continue
            val resolved = resolver.resolve(connected)
            if (resolved == current) continue
            portRouter.replaceMapping(id, resolved)
            if (activeMappingHolder.active.value?.id == current.id) {
                activeMappingHolder.set(resolved)
            }
            dev.cannoli.scorza.util.InputLog.write(
                "  re-resolved id=$id mapping=${current.id} -> ${resolved.id} glyph=${resolved.glyphStyle}"
            )
        }

        val currentAliases = portRouter.aliasesSnapshot()
        for ((aliasId, primaryId) in currentAliases) {
            if (targetAliases[aliasId] != primaryId) {
                portRouter.removeAlias(aliasId)
            }
        }
        for ((aliasId, primaryId) in targetAliases) {
            if (currentAliases[aliasId] != primaryId) {
                portRouter.addAlias(primaryId, aliasId)
            }
        }
    }

    private fun nameMatchesBuildModelBrand(deviceName: String?, buildModel: String): Boolean {
        if (deviceName.isNullOrEmpty() || buildModel.isEmpty()) return false
        val brand = buildModel.substringBefore(' ').trim()
        if (brand.length < 3) return false
        return deviceName.startsWith("$brand ", ignoreCase = true) ||
            deviceName.equals(brand, ignoreCase = true)
    }

    private fun isGamepad(facts: DeviceFacts): Boolean {
        val sources = facts.sourceMask
        return (sources and SOURCE_GAMEPAD) == SOURCE_GAMEPAD ||
            (sources and SOURCE_JOYSTICK) == SOURCE_JOYSTICK
    }

    // Alphabetic-only: it excludes the DPAD-and-buttons-only virtual devices an AVD also exposes,
    // so we bind the host keyboard rather than a system endpoint. Gamepads are excluded outright
    // because pads commonly advertise SOURCE_KEYBOARD too, and they must keep their real mapping.
    private fun isDevKeyboard(facts: DeviceFacts): Boolean =
        (facts.sourceMask and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD &&
            facts.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
            !isGamepad(facts)

    /**
     * The single keyboard to enroll as the dev controller, or null when the feature is off.
     *
     * An AVD enumerates two alphabetic keyboards: the host keyboard the user types on (a real
     * device, id >= 0) and 'Virtual' (id -1), the endpoint `adb shell input` injects under.
     * Physical devices are preferred because only they carry real keystrokes; the virtual id is
     * then aliased onto the winner so both paths drive the same controller. Among real keyboards
     * the lowest id wins, so several attached keyboards cannot produce competing controllers.
     *
     * Falls back to a synthesized virtual entry when nothing enumerates at all, so `adb shell
     * input` still drives the UI on a headless AVD.
     */
    private fun devKeyboardFacts(enumerated: List<DeviceFacts>): DeviceFacts? {
        if (!devKeyboardEnabled) return null
        val keyboards = enumerated.filter { isDevKeyboard(it) }
        return keyboards.filter { it.androidDeviceId >= 0 }.minByOrNull { it.androidDeviceId }
            ?: keyboards.minByOrNull { it.androidDeviceId }
            ?: VIRTUAL_KEYBOARD_FACTS
    }

    private fun InputDevice.toFacts(): DeviceFacts = DeviceFacts(
        androidDeviceId = id,
        descriptor = descriptor,
        name = name,
        vendorId = vendorId,
        productId = productId,
        sourceMask = sources,
        isExternal = isExternalCompat(),
        keyboardType = keyboardType,
        declaredTriggerAxes = declaredTriggerAxes(),
    )

    companion object {
        const val SOURCE_GAMEPAD: Int = InputDevice.SOURCE_GAMEPAD
        const val SOURCE_JOYSTICK: Int = InputDevice.SOURCE_JOYSTICK
        private const val SETTLE_DELAY_MS = 500L

        const val VIRTUAL_KEYBOARD_ID: Int = android.view.KeyCharacterMap.VIRTUAL_KEYBOARD

        @VisibleForTesting
        val VIRTUAL_KEYBOARD_FACTS = DeviceFacts(
            androidDeviceId = VIRTUAL_KEYBOARD_ID,
            descriptor = "dev_keyboard_virtual",
            name = DevKeyboardMapping.DISPLAY_NAME,
            vendorId = 0,
            productId = 0,
            sourceMask = InputDevice.SOURCE_KEYBOARD,
            isExternal = false,
            keyboardType = InputDevice.KEYBOARD_TYPE_ALPHABETIC,
        )
    }
}
