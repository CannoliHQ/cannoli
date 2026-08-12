package dev.cannoli.scorza.launcher

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dagger.hilt.android.AndroidEntryPoint
import dev.cannoli.igm.GuideManager
import dev.cannoli.igm.GuideOverlayContract.EXTRA_DISPLAY_ID
import dev.cannoli.igm.GuideOverlayContract.EXTRA_FILE_PATH
import dev.cannoli.igm.GuideOverlayContract.EXTRA_GUIDE_TYPE
import dev.cannoli.igm.GuideOverlayContract.EXTRA_PAGE
import dev.cannoli.igm.GuideOverlayContract.EXTRA_PAGE_COUNT
import dev.cannoli.igm.GuideOverlayContract.EXTRA_PLATFORM_TAG
import dev.cannoli.igm.GuideOverlayContract.EXTRA_ROM_BASE_NAME
import dev.cannoli.igm.GuideOverlayContract.EXTRA_SCROLL_X
import dev.cannoli.igm.GuideOverlayContract.EXTRA_SCROLL_Y
import dev.cannoli.igm.GuideOverlayContract.EXTRA_TEXT_ZOOM
import dev.cannoli.igm.GuideScreen
import dev.cannoli.igm.GuideType
import dev.cannoli.scorza.di.AppFonts
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.util.ErrorLog
import dev.cannoli.ui.components.LegendPill
import dev.cannoli.ui.theme.CannoliTheme
import java.io.File
import javax.inject.Inject

private data class GuideRequest(
    val filePath: String,
    val guideType: GuideType,
    val platformTag: String,
    val romBaseName: String,
    val page: Int,
    val scrollY: Int,
    val scrollX: Int,
    val textZoom: Int,
    val pageCount: Int,
)

/**
 * Guide viewer on the second display, hosted in an overlay window this service owns.
 *
 * An Activity cannot be placed on the second display by a normally installed app: the launch
 * display id is silently ignored and the guide lands on the main screen. An overlay window added
 * through a display context is the only unprivileged way onto that panel.
 *
 * The window is FLAG_NOT_FOCUSABLE, so it receives touch but never takes key focus: the controller
 * keeps driving whatever owns the main display. That flag is load-bearing. Without it a touch here
 * moves mTopFocusedDisplayId to this display and the game stops responding until the main screen
 * is touched. FLAG_NOT_TOUCHABLE must never be set; it would kill the gestures.
 */
@AndroidEntryPoint
class GuideOverlayService : Service() {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var appFonts: AppFonts

    // Write-only sinks seeded from the request and fed by the composition's callbacks, read only in
    // savePosition.
    // Never read these during composition: GuideScreen owns scroll position via its own ScrollState.
    private var scrollY = 0
    private var scrollX = 0
    private var currentPage = 0
    private var currentZoom = 1

    private var request by mutableStateOf<GuideRequest?>(null)
    private var savedManager: GuideManager? = null
    private var savedFile: File? = null
    private var savedIsPdf = false

    private var windowManager: WindowManager? = null
    private var overlay: ComposeView? = null
    private var owners: OverlayViewOwners? = null
    private var overlayDisplayId = Display.INVALID_DISPLAY

    // The panel can be pulled out from under a live window, which leaves the service running with
    // nothing on screen and no callback of its own to notice.
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == overlayDisplayId) stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(DisplayManager::class.java)
            ?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val displayId = intent?.getIntExtra(EXTRA_DISPLAY_ID, Display.INVALID_DISPLAY)
            ?: Display.INVALID_DISPLAY
        val incoming = intent?.let { parseRequest(it) }
        // Nothing persisted is touched until there is a window to show it in: a failure here must
        // leave the guide that was already open, and its stored position, exactly as they were.
        if (incoming == null || !showOverlay(displayId)) {
            stopSelf()
            return START_NOT_STICKY
        }
        publish(incoming)
        return START_NOT_STICKY
    }

    private fun parseRequest(intent: Intent): GuideRequest? {
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: return null
        val pageCount = intent.getIntExtra(EXTRA_PAGE_COUNT, 0)
        return GuideRequest(
            filePath = filePath,
            guideType = GuideType.valueOf(
                intent.getStringExtra(EXTRA_GUIDE_TYPE) ?: GuideType.TXT.name
            ),
            platformTag = intent.getStringExtra(EXTRA_PLATFORM_TAG).orEmpty(),
            romBaseName = intent.getStringExtra(EXTRA_ROM_BASE_NAME).orEmpty(),
            page = intent.getIntExtra(EXTRA_PAGE, 0).let {
                if (pageCount > 0) it.coerceIn(0, pageCount - 1) else it.coerceAtLeast(0)
            },
            scrollY = intent.getIntExtra(EXTRA_SCROLL_Y, 0).coerceAtLeast(0),
            scrollX = intent.getIntExtra(EXTRA_SCROLL_X, 0).coerceAtLeast(0),
            textZoom = intent.getIntExtra(EXTRA_TEXT_ZOOM, 1),
            pageCount = pageCount,
        )
    }

    // Saving before the swap keeps the outgoing guide's position. An identical request is a re-show
    // of what is already up, where reseeding the sinks would stomp scroll not yet written to disk.
    private fun publish(incoming: GuideRequest) {
        if (incoming == request) return

        savePosition()

        scrollY = incoming.scrollY
        scrollX = incoming.scrollX
        currentPage = incoming.page
        currentZoom = incoming.textZoom

        savedManager = GuideManager(settings.sdCardRoot, incoming.platformTag, incoming.romBaseName)
        savedFile = File(incoming.filePath)
        savedIsPdf = incoming.guideType == GuideType.PDF

        request = incoming
    }

    private fun showOverlay(displayId: Int): Boolean {
        val existing = overlay
        if (existing != null) {
            // The window's root becomes the view's parent inside addView and is nulled when the
            // system tears the window down. isAttachedToWindow only catches up on the next
            // traversal, so it reads false for a window that was just added.
            if (displayId == overlayDisplayId && existing.parent != null) return true
            removeOverlay()
        }
        val display = getSystemService(DisplayManager::class.java)?.getDisplay(displayId) ?: return false
        val displayContext = createDisplayContext(display)
        // A window context ties the window to this display and to the overlay type it will be added
        // as, which is what the window manager checks from R on. Before R the display context alone
        // is the whole story.
        val windowContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            displayContext
        }
        val manager = windowContext.getSystemService(WindowManager::class.java) ?: return false

        // A ComposeView outside an Activity composes only if its root view carries these three
        // owners; the recomposer also stays parked until the lifecycle is at least STARTED.
        val viewOwners = OverlayViewOwners()
        val view = ComposeView(windowContext).apply {
            setViewTreeLifecycleOwner(viewOwners)
            setViewTreeViewModelStoreOwner(viewOwners)
            setViewTreeSavedStateRegistryOwner(viewOwners)
            setContent { OverlayContent() }
        }
        viewOwners.start()

        return try {
            manager.addView(view, overlayLayoutParams())
            windowManager = manager
            overlay = view
            owners = viewOwners
            overlayDisplayId = displayId
            true
        } catch (e: RuntimeException) {
            ErrorLog.error("guide overlay addView failed: display=$displayId", e)
            viewOwners.stop()
            false
        }
    }

    private fun overlayLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    )

    // Leaves the request and its sinks alone: a caller that removes the window to re-add it on
    // another display still owes the outgoing guide a save.
    private fun removeOverlay() {
        val view = overlay
        if (view != null) {
            try { windowManager?.removeView(view) } catch (_: IllegalArgumentException) {}
            view.disposeComposition()
        }
        owners?.stop()
        overlay = null
        owners = null
        windowManager = null
        overlayDisplayId = Display.INVALID_DISPLAY
    }

    private fun savePosition() {
        val file = savedFile ?: return
        savedManager?.save(
            file,
            if (savedIsPdf) currentPage else scrollY,
            scrollY,
            scrollX,
            currentZoom,
        )
    }

    override fun onDestroy() {
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        savePosition()
        removeOverlay()
        super.onDestroy()
    }

    @Composable
    private fun OverlayContent() {
        CannoliTheme(
            fontFamily = appFonts.mplus1Code,
            iconFontFamily = appFonts.mplus1Code,
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                request?.let { req ->
                    // Keying on the request tears down and rebuilds the whole subtree (GuideScreen
                    // included) on a new request, so its internal ScrollState is not inherited from
                    // whatever guide was showing before.
                    key(req) {
                        GuideContent(
                            request = req,
                            onScrollChanged = { y, x -> scrollY = y; scrollX = x },
                            onPageChanged = { currentPage = it },
                            onZoomChanged = { currentZoom = it },
                            onClose = { stopSelf() },
                        )
                    }
                }
            }
        }
    }

    companion object {

        // The sidecar must never outlive what it accompanies, and nothing else tears this service
        // down, so teardown is explicit. Stopping a service that is not running is a no-op.
        fun hide(context: Context) {
            context.stopService(Intent(context, GuideOverlayService::class.java))
        }
    }
}

// Minimal owner set for a ComposeView with no Activity behind it. There is no state to persist
// across process death here, so the saved-state registry is restored from nothing.
private class OverlayViewOwners : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    // performRestore must happen while the lifecycle is still below STARTED.
    fun start() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}

@Composable
private fun GuideContent(
    request: GuideRequest,
    onScrollChanged: (Int, Int) -> Unit,
    onPageChanged: (Int) -> Unit,
    onZoomChanged: (Int) -> Unit,
    onClose: () -> Unit,
) {
    var chromeVisible by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(request.page) }
    var zoom by remember { mutableIntStateOf(request.textZoom) }
    // Seeded once per request: GuideScreen re-keys its ScrollState whenever these change, so a value
    // that moved with the live scroll position would rebuild the scroller on every recomposition.
    val initialScrollY = remember { request.scrollY }
    val initialScrollX = remember { request.scrollX }

    Box(modifier = Modifier.fillMaxSize()) {
        GuideScreen(
            filePath = request.filePath,
            guideType = request.guideType,
            page = page,
            initialScrollY = initialScrollY,
            initialScrollX = initialScrollX,
            scrollDir = 0,
            scrollXDir = 0,
            pageJump = 0,
            pageJumpDir = 0,
            pageCount = request.pageCount,
            textZoom = zoom,
            onScrollPosChanged = { y, x ->
                onScrollChanged(y, x)
                if (chromeVisible) chromeVisible = false
            },
            onZoomLevelChanged = {
                zoom = it
                onZoomChanged(it)
            },
            onPageStep = { step ->
                page = if (request.pageCount > 0) {
                    (page + step).coerceIn(0, request.pageCount - 1)
                } else {
                    (page + step).coerceAtLeast(0)
                }
                onPageChanged(page)
            },
            onTapped = { chromeVisible = !chromeVisible },
        )

        if (chromeVisible) {
            TouchChrome(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp),
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun TouchChrome(
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.clickableNoRipple(onClose)) {
            LegendPill(label = stringResource(dev.cannoli.ui.R.string.label_close)) { }
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.then(
    Modifier.pointerInput(Unit) { detectTapGestures { onClick() } }
)
