package dev.cannoli.scorza.libretro

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.cannoli.igm.BatteryDisplayMode
import dev.cannoli.igm.CannoliIGM
import dev.cannoli.igm.GuideFile
import dev.cannoli.igm.IGMHostConfig
import dev.cannoli.igm.IGMScreen
import dev.cannoli.igm.IGMSettingsItem
import dev.cannoli.igm.IgmGameInfo
import dev.cannoli.igm.InGameMenuOptions
import dev.cannoli.igm.PlayerSlotInfo
import dev.cannoli.igm.TimeFormatMode
import dev.cannoli.scorza.input.runtime.confirmButton
import dev.cannoli.scorza.input.runtime.labelSet
import dev.cannoli.ui.components.OsdHost
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.Radius
import kotlinx.coroutines.delay

data class GameInfo(
    val coreName: String,
    val romPath: String,
    val savePath: String?,
    val rootPrefix: String = "",
    val originalRomPath: String? = null,
    val rendererName: String = "",
    val raStatus: String? = null,
    val raGameId: String? = null,
    val raDetection: String? = null
)

@Composable
fun LibretroScreen(
    glSurfaceView: android.view.View,
    gameTitle: String,
    screen: IGMScreen?,
    menuOptions: InGameMenuOptions,
    selectedSlot: Slot,
    slotThumbnail: Bitmap?,
    slotExists: Boolean,
    slotOccupied: List<Boolean>,
    undoLabel: String?,
    settingsItems: List<IGMSettingsItem>,
    coreInfo: String,
    debugHud: Boolean,
    showFps: Boolean,
    renderer: LibretroRenderer,
    runner: LibretroRunner,
    audioSampleRate: Int,
    osdController: dev.cannoli.ui.components.OsdController,
    fastForwarding: Boolean,
    settings: dev.cannoli.scorza.settings.SettingsRepository,
    guideFiles: List<GuideFile> = emptyList(),
    cheatSections: List<dev.cannoli.ui.components.ListSection<dev.cannoli.igm.CheatRowUi>> = emptyList(),
    cheatHasRemembered: Boolean = false,
    guidePageCount: Int = 0,
    guideScrollDir: Int = 0,
    guideScrollXDir: Int = 0,
    guidePageJump: Int = 0,
    guidePageJumpDir: Int = 0,
    guideInitialScroll: Int = 0,
    guideInitialScrollX: Int = 0,
    onGuideScrollChanged: (y: Int, x: Int) -> Unit = { _, _ -> },
    infoScrollDir: Int = 0,
    gameInfo: GameInfo = GameInfo("", "", null),
    activeMapping: dev.cannoli.scorza.input.DeviceMapping? = null,
    controllersViewModel: dev.cannoli.scorza.ui.viewmodel.ControllersViewModel? = null,
    inputRemapHasChanges: Boolean = false,
) {
    val overlayVisible = screen != null

    val batteryDisplay = when (settings.batteryDisplay) {
        dev.cannoli.scorza.settings.BatteryDisplay.HIDE -> BatteryDisplayMode.HIDE
        dev.cannoli.scorza.settings.BatteryDisplay.PERCENT -> BatteryDisplayMode.PERCENT
        dev.cannoli.scorza.settings.BatteryDisplay.ICON -> BatteryDisplayMode.ICON
    }
    val timeFormat = when (settings.timeFormat) {
        dev.cannoli.scorza.settings.TimeFormat.TWELVE_HOUR -> TimeFormatMode.TWELVE_HOUR
        dev.cannoli.scorza.settings.TimeFormat.TWENTY_FOUR_HOUR -> TimeFormatMode.TWENTY_FOUR_HOUR
    }

    val config = IGMHostConfig(
        fontSizeSp = settings.textSize.sp,
        lineHeightSp = settings.textSize.sp + 10,
        scaleFactor = settings.textSize.sp / 22f,
        portraitMarginPx = settings.portraitMarginPx,
        showWifi = settings.showWifi,
        showBluetooth = settings.showBluetooth,
        showVpn = settings.showVpn,
        showClock = settings.showClock,
        batteryDisplay = batteryDisplay,
        timeFormat = timeFormat,
        buttonLabelSet = activeMapping.labelSet(dev.cannoli.ui.ButtonLabelSet.PLUMBER),
        confirmButton = activeMapping.confirmButton(),
        keyCodeName = { dev.cannoli.scorza.util.keyCodeName(it) },
    )

    val igmGameInfo = IgmGameInfo(
        coreName = gameInfo.coreName,
        romPath = gameInfo.originalRomPath ?: gameInfo.romPath,
        extractedPath = if (gameInfo.originalRomPath != null) gameInfo.romPath else "",
        savePath = gameInfo.savePath ?: "",
        rendererName = gameInfo.rendererName,
        raStatus = gameInfo.raStatus ?: "",
        raGameId = gameInfo.raGameId ?: "",
        raDetection = gameInfo.raDetection ?: "",
        rootPrefix = gameInfo.rootPrefix,
    )

    val controllersState = controllersViewModel?.state?.collectAsState()?.value
    val players = (controllersState?.connected.orEmpty())
        .map { PlayerSlotInfo(port = it.port ?: -1, displayName = it.mapping.displayName) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { glSurfaceView },
            modifier = Modifier.fillMaxSize()
        )

        CannoliIGM(
            screen = screen,
            config = config,
            gameTitle = gameTitle,
            menuOptions = menuOptions,
            selectedSlot = selectedSlot,
            slotThumbnail = slotThumbnail,
            slotExists = slotExists,
            slotOccupied = slotOccupied,
            undoLabel = undoLabel,
            settingsItems = settingsItems,
            coreInfo = coreInfo,
            gameInfo = igmGameInfo,
            infoScrollDir = infoScrollDir,
            guideFiles = guideFiles,
            cheatSections = cheatSections,
            cheatHasRemembered = cheatHasRemembered,
            guidePageCount = guidePageCount,
            guideScrollDir = guideScrollDir,
            guideScrollXDir = guideScrollXDir,
            guidePageJump = guidePageJump,
            guidePageJumpDir = guidePageJumpDir,
            guideInitialScroll = guideInitialScroll,
            guideInitialScrollX = guideInitialScrollX,
            onGuideScrollChanged = onGuideScrollChanged,
            players = players,
            inputRemapHasChanges = inputRemapHasChanges,
        )

        val osdRequest = osdController.request.value
        if (debugHud && !overlayVisible && osdRequest == null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
            ) {
                DebugHud(
                    renderer = renderer,
                    runner = runner,
                    coreName = coreInfo,
                    audioSampleRate = audioSampleRate
                )
            }
        }

        if ((showFps || fastForwarding) && !overlayVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 16.dp)
            ) {
                StatusPill(showFps = showFps, fastForwarding = fastForwarding, renderer = renderer)
            }
        }

        val osdConfiguration = androidx.compose.ui.platform.LocalConfiguration.current
        val osdDensity = androidx.compose.ui.platform.LocalDensity.current
        val osdBottomMargin = if (
            settings.portraitMarginPx > 0 &&
            osdConfiguration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        ) {
            with(osdDensity) { settings.portraitMarginPx.toDp() }
        } else {
            0.dp
        }
        Box(modifier = Modifier.fillMaxSize().padding(bottom = osdBottomMargin)) {
            OsdHost(controller = osdController)
        }
    }
}

@Composable
private fun StatusPill(showFps: Boolean, fastForwarding: Boolean, renderer: LibretroRenderer) {
    val colors = LocalCannoliColors.current
    val fpsValue = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(showFps) {
        while (showFps) {
            fpsValue.floatValue = renderer.fps
            delay(500)
        }
    }
    val text = buildString {
        if (fastForwarding) append("▶▶")
        if (fastForwarding && showFps) append("  ")
        if (showFps) append("%.2f".format(fpsValue.floatValue))
    }
    Box(
        modifier = Modifier
            .clip(Radius.Pill)
            .background(colors.highlight)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = colors.highlightText
        )
    }
}
