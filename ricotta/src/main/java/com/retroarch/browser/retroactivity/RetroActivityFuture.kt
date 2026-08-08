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
import com.retroarch.browser.preferences.util.ConfigFile
import com.retroarch.browser.preferences.util.UserPreferences
import dev.cannoli.ricotta.EmbeddedRetroArchBridge
import dev.cannoli.ricotta.RicottaOsdEvent
import java.io.File

class RetroActivityFuture : RetroActivityCamera() {

    private var quitfocus = false
    private lateinit var mDecorView: View
    private var igmOverlay: IGMOverlay? = null
    private var osdOverlay: OsdOverlay? = null

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
        quitfocus = intent.hasExtra("QUITFOCUS")

        try {
            val params = dev.cannoli.igm.RicottaLaunchParams.readFromIntent(intent)
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
                geometryWidthPct = 100,
                geometryHeightPct = 100,
                geometryXPct = 0,
                geometryYPct = 0,
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

            val bridge = EmbeddedRetroArchBridge(stateBasePath)
            igmOverlay = IGMOverlay(
                this, bridge, stateBasePath, gameTitle, hostConfig, cannoliRoot, platformTag, platformName,
                colors?.highlight, colors?.text, colors?.highlightText,
                colors?.accent, colors?.title, localeTag, romBaseName,
            )
            igmOverlay?.onCreate(savedInstanceState)
            params?.let { bridge.setIgmTriggerKeycodes(it.igmTriggerKeycodes.toIntArray()) }
            igmOverlay?.controller?.setInputMapping(params?.inputMapping)

            val osdFont = runCatching {
                val tf = android.graphics.Typeface.createFromAsset(assets, "fonts/MPlus-1c-NerdFont-Bold.ttf")
                androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Typeface(tf))
            }.getOrDefault(androidx.compose.ui.text.font.FontFamily.Default)
            val osd = OsdOverlay(
                this, osdFont,
                colors?.highlight, colors?.text, colors?.highlightText,
                colors?.accent, colors?.title,
            )
            osd.attach(savedInstanceState)
            osdOverlay = osd
            igmOverlay?.onOsdMessage = { message -> osd.showMessage(message) }
            igmOverlay?.onWindowAttached = { osd.raise() }
            bridge.onOsdEvent = { type, slot ->
                if (osdEventAllowed(type, bridge)) osd.showMessage(osdEventText(type, slot))
                // A save is queued, not written, when the IGM asks for it. The slot on disk only
                // changes once RetroArch reports back, so the polaroid is stale until then.
                if (type == RicottaOsdEvent.SAVE_STATE ||
                    type == RicottaOsdEvent.UNDO_SAVE_STATE
                ) {
                    igmOverlay?.controller?.onStateWritten()
                }
            }
            bridge.onOsdAchievement = { title -> osd.showAchievement(title) }
            bridge.localToggleGet = { key, def -> osdPrefs().getBoolean(key, def) }
            bridge.localToggleSet = { key, value -> osdPrefs().edit().putBoolean(key, value).apply() }
        } catch (e: Exception) {
            Log.e("RicottaArch", "Failed to initialize IGM overlay", e)
        }
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            intent.getStringExtra("REFRESH")?.let { refresh ->
                val params = window.attributes
                params.preferredRefreshRate = refresh.toFloat()
                window.attributes = params
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val configFile = ConfigFile(UserPreferences.getDefaultConfigPath(this))
                if (configFile.getBoolean("video_notch_write_over_enable")) {
                    window.attributes.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            } catch (e: Exception) {
                Log.w("RetroActivityFuture", "Key doesn't exist yet: ${e.message}")
            }
        }
    }

    override fun onPause() {
        igmOverlay?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
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

        try {
            val configFile = ConfigFile(UserPreferences.getDefaultConfigPath(this))
            if (configFile.getBoolean("input_auto_mouse_grab")) {
                inputGrabMouse(hasFocus)
            }
        } catch (e: Exception) {
            Log.w("RetroActivityFuture", "[onWindowFocusChanged] exception thrown: ${e.message}")
        }
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
        val where = if (slot < 0) "Auto" else "Slot $slot"
        return when (type) {
            RicottaOsdEvent.SAVE_STATE -> "Saved · $where"
            RicottaOsdEvent.LOAD_STATE -> "Loaded · $where"
            RicottaOsdEvent.RESET -> "Reset"
            RicottaOsdEvent.UNDO_SAVE_STATE -> "Save undone"
            RicottaOsdEvent.DISK_CHANGED -> "Disc ${slot + 1}"
            RicottaOsdEvent.SCREENSHOT -> "Screenshot saved"
            RicottaOsdEvent.CONTROLLER_PORT -> "Controller P$slot"
            else -> "Saved · $where"
        }
    }

    // Most RA-key-backed toggles gate natively in ricotta_osd_event. Reset has no RA key
    // (Cannoli pref), and the save events are never gated natively because they also drive the
    // menu's thumbnail refresh, so both are decided here.
    private fun osdEventAllowed(type: Int, bridge: EmbeddedRetroArchBridge): Boolean = when (type) {
        RicottaOsdEvent.RESET -> osdPrefs().getBoolean("cannoli_osd_reset", true)
        RicottaOsdEvent.SAVE_STATE,
        RicottaOsdEvent.LOAD_STATE,
        RicottaOsdEvent.UNDO_SAVE_STATE ->
            bridge.raGetSetting("notification_show_save_state")?.value != "false"
        else -> true
    }

    private fun osdPrefs() =
        android.preference.PreferenceManager.getDefaultSharedPreferences(this)

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

    }
}
