package com.retroarch.browser.retroactivity

import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.PointerIcon
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import dev.cannoli.ricotta.EmbeddedRetroArchBridge
import dev.cannoli.ricotta.RicottaOsdEvent
import dev.cannoli.ricotta.ViewportController
import dev.cannoli.ricotta.ViewportSettings
import dev.cannoli.ui.R
import java.io.File

class RetroActivityFuture : RetroActivityCamera() {

    private var quitfocus = false
    private var preferredRefreshRate: Int? = null
    private lateinit var mDecorView: View
    private var igmOverlay: IGMOverlay? = null
    private var osdOverlay: OsdOverlay? = null
    private var viewportController: ViewportController? = null
    // Held so the echo listener can be released: the native side keeps a global ref to the
    // bridge, so it outlives the local scope it is built in.
    private var raBridge: EmbeddedRetroArchBridge? = null

    // The OSD sits beside the IGM, so it must resolve strings in the launcher's chosen
    // language rather than the system one, exactly as IGMOverlay's uiContext does.
    private var osdContext: Context = this

    private val mHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val state = msg.arg1 == HANDLER_ARG_TRUE
            when (msg.what) {
                HANDLER_WHAT_TOGGLE_IMMERSIVE -> attemptToggleImmersiveMode(state)
                HANDLER_WHAT_TOGGLE_POINTER_CAPTURE -> attemptTogglePointerCapture(state)
                HANDLER_WHAT_TOGGLE_POINTER_NVIDIA -> attemptToggleNvidiaCursorVisibility(state)
                HANDLER_WHAT_TOGGLE_POINTER_ICON -> attemptTogglePointerIcon(state)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val libretro = intent.getStringExtra("LIBRETRO")
        if (!libretro.isNullOrEmpty() && !File(libretro).exists()) {
            super.onCreate(savedInstanceState)
            Toast.makeText(applicationContext, "Core not installed: ${File(libretro).name}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        super.onCreate(savedInstanceState)

        isRunning = true
        mDecorView = window.decorView

        // Immersive before the surface exists. RetroArch posts this toggle a few hundred ms into
        // the run, and applying it first time round let the system bars lay out and then resized
        // the window under a live surface. Setting the flags here keeps the window one size for
        // the whole launch.
        attemptToggleImmersiveMode(true)

        try {
            val params = dev.cannoli.igm.RicottaLaunchParams.readFromIntent(intent)
            quitfocus = params?.quitOnFocusLoss == true
            preferredRefreshRate = params?.preferredRefreshRate
            val gameTitle = (params?.gameTitle?.takeIf { it.isNotEmpty() }
                ?: intent.getStringExtra("ROM")?.substringAfterLast('/')?.substringBeforeLast('.')
                ?: "Game")
            val stateBasePath = params?.stateBasePath ?: ""
            val cannoliRoot = params?.cannoliRoot ?: ""
            val platformTag = params?.platformTag ?: ""
            val platformName = params?.platformName ?: ""
            val colors = params?.colors
            val localeTag = params?.localeTag ?: ""
            val romBaseName = params?.romBaseName?.takeIf { it.isNotEmpty() }
                ?: intent.getStringExtra("ROM")?.substringAfterLast('/')?.substringBeforeLast('.')
                ?: gameTitle

            val ds = params?.displaySettings
            val fontSizeSp = ds?.fontSizeSp ?: 24
            val hostConfig = dev.cannoli.igm.IGMHostConfig(
                fontSizeSp = fontSizeSp,
                lineHeightSp = dev.cannoli.ui.components.pillLineHeightSp(fontSizeSp),
                pillScale = dev.cannoli.ui.components.pillScaleFor(fontSizeSp),
                scaleFactor = fontSizeSp / 22f,
                portraitMarginPx = ds?.portraitMarginPx ?: 0,
                geometryWidthPct = ds?.geometryWidthPct ?: 100,
                geometryHeightPct = ds?.geometryHeightPct ?: 100,
                geometryXPct = ds?.geometryXPct ?: 0,
                geometryYPct = ds?.geometryYPct ?: 0,
                showWifi = ds?.showWifi ?: true,
                showBluetooth = ds?.showBluetooth ?: true,
                showVpn = ds?.showVpn ?: false,
                showClock = ds?.showClock ?: true,
                batteryDisplay = ds?.batteryDisplay ?: dev.cannoli.igm.BatteryDisplayMode.ICON,
                timeFormat = ds?.timeFormat ?: dev.cannoli.igm.TimeFormatMode.TWELVE_HOUR,
                buttonLabelSet = ds?.buttonLabelSet ?: dev.cannoli.ui.ButtonLabelSet.PLUMBER,
                confirmButton = ds?.confirmButton ?: dev.cannoli.ui.ConfirmButton.SOUTH,
                keyCodeName = { android.view.KeyEvent.keyCodeToString(it) },
            )

            osdContext = localeContext(this, localeTag)

            val bridge = EmbeddedRetroArchBridge(
                params?.hardcoreInEffect ?: false, cannoliRoot, platformTag, romBaseName,
                params?.coreId ?: "",
            )
            raBridge = bridge
            viewportController = ViewportController(
                coreGeometry = { bridge.coreGeometry() },
                applyViewport = bridge::applyViewport,
                clearViewport = bridge::clearViewport,
                readAspectIdx = bridge::raAspectIndex,
                readIntegerScale = bridge::raIntegerScale,
                readAspectValue = bridge::raAspectValue,
                settings = ViewportSettings(
                    portraitMarginPx = ds?.portraitMarginPx ?: 0,
                    geometryWidthPct = ds?.geometryWidthPct ?: 100,
                    geometryHeightPct = ds?.geometryHeightPct ?: 100,
                    geometryXPct = ds?.geometryXPct ?: 0,
                    geometryYPct = ds?.geometryYPct ?: 0,
                ),
            )
            bridge.shadowedSettingsProvider = { viewportController?.shadowedSettings() ?: emptyMap() }
            igmOverlay = IGMOverlay(
                this, bridge, stateBasePath, gameTitle, hostConfig, cannoliRoot, platformTag, platformName,
                colors?.highlight, colors?.text, colors?.highlightText,
                colors?.accent, colors?.title, localeTag, romBaseName,
            )
            igmOverlay?.onCreate(savedInstanceState)
            // The scaling row owns aspect_ratio_index, so writing a new preset pulls RetroArch out
            // of the custom viewport Cannoli applied; once the whole menu closes and play resumes,
            // claim it back for whatever the user picked.
            igmOverlay?.onHidden = { refreshViewport() }
            // The menu writes the scaling row through a queued command while the viewport reads
            // settings synchronously, so a refresh fired on dismissal can read the value from
            // before the change and remember it. Once a viewport is live the index reads as custom
            // and that remembered mode wins on every later refresh, so the wrong choice sticks.
            // The applied echo arrives after RetroArch has taken the write, which is the only
            // moment the index actually reflects what the user picked.
            bridge.setOnRaSettingAppliedLocal { key, _ ->
                if (key == dev.cannoli.igm.RaKeys.ASPECT_RATIO_INDEX ||
                    key == dev.cannoli.igm.RaKeys.VIDEO_SCALE_INTEGER) refreshViewport()
            }
            params?.let { bridge.setIgmTriggerKeycodes(it.igmTriggerKeycodes.toIntArray()) }
            params?.let { bridge.setBuiltinPorts(it.builtinPorts.toIntArray()) }
            params?.let { wireShortcuts(bridge, it.shortcuts, it.igmTriggerKeycodes.toSet()) }
            bridge.curatedSettings = params?.curatedSettings ?: true
            igmOverlay?.controller?.setInputMapping(params?.inputMapping)

            val osdFont = runCatching {
                val tf = android.graphics.Typeface.createFromAsset(assets, "fonts/MPlus-1c-NerdFont-Bold.ttf")
                androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Typeface(tf))
            }.getOrDefault(androidx.compose.ui.text.font.FontFamily.Default)
            val osd = OsdOverlay(
                this, osdFont,
                colors?.highlight, colors?.text, colors?.highlightText,
                colors?.accent, colors?.title,
                portraitMarginPx = ds?.portraitMarginPx ?: 0,
                geometryWidthPct = ds?.geometryWidthPct ?: 100,
                geometryHeightPct = ds?.geometryHeightPct ?: 100,
                geometryXPct = ds?.geometryXPct ?: 0,
                geometryYPct = ds?.geometryYPct ?: 0,
            )
            osd.attach(savedInstanceState)
            osdOverlay = osd
            igmOverlay?.onOsdMessage = { message -> osd.showMessage(message) }
            igmOverlay?.onWindowAttached = { osd.raise() }
            igmOverlay?.onVisibilityChanged = { open -> osd.setIgmOpen(open) }
            bridge.onOsdEvent = { type, slot ->
                // Fast forward is the one event that reports a state rather than something that
                // happened, so it holds a pill for as long as it lasts instead of flashing a toast
                // the game then outlives.
                if (type == RicottaOsdEvent.FASTFORWARD) osd.setFastForward(slot != 0)
                else osd.showMessage(osdEventText(type, slot))
                refreshViewport()
                // A save is queued, not written, when the IGM asks for it. The slot on disk only
                // changes once RetroArch reports back, so the polaroid is stale until then.
                if (type == RicottaOsdEvent.SAVE_STATE ||
                    type == RicottaOsdEvent.UNDO_SAVE_STATE
                ) {
                    igmOverlay?.controller?.onStateWritten()
                }
            }
            bridge.onOsdAchievement = { title -> osd.showAchievement(title) }
            osd.fpsProvider = { bridge.fps() }
            bridge.onShowFpsChanged = { on -> osd.setShowFps(on) }
        } catch (e: Exception) {
            Log.e("RicottaArch", "Failed to initialize IGM overlay", e)
        }
    }

    private var shortcuts: dev.cannoli.igm.ShortcutController? = null
    /**
     * Chords bound in the launcher, pushed to native because that is where they are matched.
     *
     * Matching cannot happen up here. A chord is only known to be one when its last key lands, and
     * the keys it claims have to come back out of the input state before the core's next poll;
     * anything reaching this process is already frames late, by which time the game has acted on
     * the press. This wires up what happens once native has decided.
     */
    private fun wireShortcuts(
        bridge: EmbeddedRetroArchBridge,
        table: Map<dev.cannoli.igm.ShortcutAction, Set<Int>>,
        triggerKeycodes: Set<Int>,
    ) {
        val overlay = igmOverlay ?: return
        // What the launcher passed is the global table. This game's and this platform's tiers can
        // add to it or switch a chord off, so the effective table is what native and the menu see.
        val effective = bridge.resolveShortcuts(table)
        if (effective.isEmpty()) {
            bridge.setShortcutChords(IntArray(0))
            return
        }
        val union = effective.values.flatten().toSet()
        val controller = dev.cannoli.igm.ShortcutController(
            controller = overlay.controller,
            showMenu = { overlay.show() },
            // Only the trigger keys a chord actually uses. Native keeps opening the menu itself for
            // the rest, so claiming them here would leave a press with nothing left to open it.
            menuKeys = triggerKeycodes.intersect(union),
        )
        shortcuts = controller
        bridge.setShortcutChords(dev.cannoli.igm.ShortcutTable.encode(effective))
        controller.onToast = { action ->
            val text = when (action) {
                dev.cannoli.igm.ShortcutAction.CYCLE_EFFECT -> getString(
                    when {
                        bridge.appliedShaderPreset() != null -> R.string.osd_event_shader_on
                        // Nothing to turn on is not the same as having turned it off, and saying
                        // off would describe a state the game was already in.
                        bridge.shaderToRestore() == null -> R.string.osd_event_shader_none
                        else -> R.string.osd_event_shader_off
                    }
                )
                // Fast forward and the frame counter hold a pill of their own for as long as they
                // are on, and everything else is either visible on its own or leaves the game.
                else -> null
            }
            text?.let { osdOverlay?.showMessage(it) }
        }
        // Not the action's own label, which already ends in "(Hold)" and would read twice over.
        controller.onHoldArmed = { action ->
            when (action) {
                dev.cannoli.igm.ShortcutAction.SAVE_AND_QUIT_HOLD ->
                    getString(R.string.osd_hold_to_save_and_quit)
                else -> null
            }?.let {
                osdOverlay?.showHoldPrompt(it, getString(R.string.osd_saving_and_quitting), action.holdMs)
            }
        }
        controller.onHoldCancelled = { osdOverlay?.clearHoldPrompt() }
        bridge.onShortcutKey = { keycode: Int, down: Boolean -> controller.onKey(keycode, down) }
        bridge.onShortcutAction = { action: Int, kind: Int -> controller.onAction(action, kind) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // Every launch restarts, including a relaunch of the game already running.
        //
        // This used to restart only when the ROM or core differed, and continue the running
        // session otherwise. That was unreachable while backgrounding killed the process, but now
        // that a backgrounded session survives it would silently override the launcher: the
        // launcher decides whether a launch is a fresh Play or a Resume and encodes it in the
        // config this intent points at, so carrying on regardless would drop Play back into a game
        // already in progress and make Resume's slot never load. Same ROM relaunched is precisely
        // the case that has to restart, so comparing content cannot be the test.
        if (intent.getStringExtra("ROM") == null) {
            setIntent(intent)
            return
        }

        // NEW_TASK only. This activity shares the launcher's task, so CLEAR_TASK would take
        // MainActivity down with it and leave nothing to come back to. Task affinity puts the
        // replacement back on the same task, and exiting is what gives the next game a clean core
        // rather than one carrying the last one's state.
        val restartIntent = Intent(intent).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(restartIntent)
        System.exit(0)
    }

    override fun onResume() {
        super.onResume()
        igmOverlay?.onResume()
        setSustainedPerformanceMode(sustainedPerformanceMode)

        preferredRefreshRate?.let { rate ->
            val attrs = window.attributes
            attrs.preferredRefreshRate = rate.toFloat()
            window.attributes = attrs
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && notchWriteOver) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    override fun onPause() {
        igmOverlay?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        raBridge?.setOnRaSettingAppliedLocal(null)
        raBridge = null
        osdOverlay?.detach()
        igmOverlay?.onDestroy()
        super.onDestroy()
        isRunning = false
        // `quitfocus` means "do not linger once the player has moved on". Finishing is the signal
        // for that; stopping is not. This used to run in onStop, which cannot tell a player who is
        // done from a screen that locked, a call that came in, or a home press they meant to come
        // back from, and killed a running game in all of those cases.
        //
        // Quitting from the in-game menu does not depend on this: it enqueues CMD_EVENT_QUIT and
        // RetroArch exits on its own.
        if (quitfocus && isFinishing) System.exit(0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        sendUiMessage(HANDLER_WHAT_TOGGLE_IMMERSIVE, hasFocus)

        if (autoMouseGrab) inputGrabMouse(hasFocus)
    }

    private fun refreshViewport() {
        val v = mDecorView ?: return
        val w = v.width
        val h = v.height
        if (w > 0 && h > 0)
            refreshViewportRetrying(w, h)
    }

    // The surface commonly becomes ready before the core has reported its geometry, so a decline
    // for that reason alone is retried rather than left to silently do nothing for the session.
    // Ten attempts 100ms apart cover about a second, which is comfortably past the gap seen on
    // device (geometry showed up 449ms after the first decline), while still giving up if a core
    // never reports geometry at all.
    private fun refreshViewportRetrying(width: Int, height: Int, attempt: Int = 0) {
        val result = viewportController?.refresh(width, height) ?: return
        if (result == ViewportController.RefreshResult.NOT_READY && attempt < GEOMETRY_RETRY_MAX_ATTEMPTS) {
            mHandler.postDelayed(
                { refreshViewportRetrying(width, height, attempt + 1) },
                GEOMETRY_RETRY_DELAY_MS
            )
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshViewport()
    }

    // The surface is (re)created after the core loads, so this also carries the core-load
    // trigger: RetroArch has no Kotlin-visible hook for "content is running" here.
    override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
        super.surfaceChanged(holder, format, width, height)
        refreshViewportRetrying(width, height)
    }

    private fun sendUiMessage(what: Int, state: Boolean) {
        val msg = mHandler.obtainMessage(what, if (state) HANDLER_ARG_TRUE else HANDLER_ARG_FALSE, -1)
        mHandler.sendMessageDelayed(msg, HANDLER_MESSAGE_DELAY_DEFAULT_MS.toLong())
    }

    fun inputGrabMouse(state: Boolean) {
        sendUiMessage(HANDLER_WHAT_TOGGLE_POINTER_CAPTURE, state)
        sendUiMessage(HANDLER_WHAT_TOGGLE_POINTER_NVIDIA, state)
        sendUiMessage(HANDLER_WHAT_TOGGLE_POINTER_ICON, state)
    }

    @Suppress("DEPRECATION")
    private fun attemptToggleImmersiveMode(state: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            try {
                mDecorView.systemUiVisibility = if (state) {
                    (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LOW_PROFILE
                            or View.SYSTEM_UI_FLAG_IMMERSIVE)
                } else {
                    (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
                }
            } catch (e: Exception) {
                Log.w("RetroActivityFuture", "[attemptToggleImmersiveMode] exception: ${e.message}")
            }
        }
    }

    private fun attemptTogglePointerCapture(state: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                if (state) mDecorView.requestPointerCapture() else mDecorView.releasePointerCapture()
            } catch (e: Exception) {
                Log.w("RetroActivityFuture", "[attemptTogglePointerCapture] exception: ${e.message}")
            }
        }
    }

    private fun attemptToggleNvidiaCursorVisibility(state: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            try {
                val method = InputManager::class.java.getMethod("setCursorVisibility", Boolean::class.javaPrimitiveType)
                val inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
                method.invoke(inputManager, !state)
            } catch (_: NoSuchMethodException) {
            } catch (e: Exception) {
                Log.w("RetroActivityFuture", "[attemptToggleNvidiaCursorVisibility] exception: ${e.message}")
            }
        }
    }

    private fun attemptTogglePointerIcon(state: Boolean) {
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.N until Build.VERSION_CODES.O) {
            try {
                mDecorView.pointerIcon = if (state) {
                    PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL)
                } else null
            } catch (e: Exception) {
                Log.w("RetroActivityFuture", "[attemptTogglePointerIcon] exception: ${e.message}")
            }
        }
    }

    // Formats a structured OSD event into a Cannoli pill. No string parsing -
    // type and slot come straight from the source site. RetroArch state_slot N
    // matches the IGM's "Slot N"; slot < 0 is the auto slot.
    private fun osdEventText(type: Int, slot: Int): String {
        val where = if (slot < 0) {
            osdContext.getString(R.string.igm_slot_auto)
        } else {
            osdContext.getString(R.string.igm_slot_numbered, slot)
        }
        return when (type) {
            RicottaOsdEvent.SAVE_STATE -> osdContext.getString(R.string.osd_event_saved, where)
            RicottaOsdEvent.LOAD_STATE -> osdContext.getString(R.string.osd_event_loaded, where)
            RicottaOsdEvent.RESET -> osdContext.getString(R.string.osd_reset)
            RicottaOsdEvent.UNDO_SAVE_STATE -> osdContext.getString(R.string.osd_event_save_undone)
            RicottaOsdEvent.DISK_CHANGED -> osdContext.getString(R.string.igm_disc_number, slot + 1)
            RicottaOsdEvent.SCREENSHOT -> osdContext.getString(R.string.osd_event_screenshot)
            RicottaOsdEvent.CONTROLLER_PORT ->
                osdContext.getString(R.string.osd_event_controller_port, slot)
            RicottaOsdEvent.LOAD_REFUSED ->
                osdContext.getString(R.string.osd_event_hardcore_load_blocked)
            RicottaOsdEvent.HARDCORE_PAUSED ->
                osdContext.getString(R.string.osd_event_hardcore_paused)
            RicottaOsdEvent.CHEEVOS_LOGIN_FAILED -> osdContext.getString(
                if (slot == 1) R.string.achievos_session_expired else R.string.achievos_login_failed
            )
            else -> osdContext.getString(R.string.osd_event_saved, where)
        }
    }

    companion object {
        @JvmField
        @Volatile
        var isRunning = false

        private const val HANDLER_WHAT_TOGGLE_IMMERSIVE = 1
        private const val HANDLER_WHAT_TOGGLE_POINTER_CAPTURE = 2
        private const val HANDLER_WHAT_TOGGLE_POINTER_NVIDIA = 3
        private const val HANDLER_WHAT_TOGGLE_POINTER_ICON = 4
        private const val HANDLER_ARG_TRUE = 1
        private const val HANDLER_ARG_FALSE = 0
        private const val HANDLER_MESSAGE_DELAY_DEFAULT_MS = 300
        private const val GEOMETRY_RETRY_DELAY_MS = 100L
        private const val GEOMETRY_RETRY_MAX_ATTEMPTS = 10
    }
}
