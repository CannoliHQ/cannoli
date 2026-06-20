package dev.cannoli.igm

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.BULLET
import dev.cannoli.ui.CIRCLE_EMPTY
import dev.cannoli.ui.DPAD_HORIZONTAL
import dev.cannoli.ui.HALF_CIRCLE
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.LocalStatusBarLeftEdge
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.StatusBar
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.pillInternalH
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliFont
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.LocalScaleFactor
import dev.cannoli.ui.theme.Radius
import dev.cannoli.ui.theme.Spacing
import dev.cannoli.ui.theme.buildCannoliTypography
import kotlinx.coroutines.delay

@Composable
fun CannoliIGM(
    screen: IGMScreen?,
    config: IGMHostConfig,
    gameTitle: String,
    menuOptions: InGameMenuOptions,
    selectedSlot: SaveSlotManager.Slot,
    slotThumbnail: Bitmap?,
    slotExists: Boolean,
    slotOccupied: List<Boolean>,
    undoLabel: String?,
    settingsItems: List<IGMSettingsItem>,
    coreInfo: String,
    gameInfo: IgmGameInfo,
    infoScrollDir: Int,
    guideFiles: List<GuideFile>,
    guidePageCount: Int,
    guideScrollDir: Int,
    guideScrollXDir: Int,
    guidePageJump: Int,
    guidePageJumpDir: Int,
    guideInitialScroll: Int,
    guideInitialScrollX: Int,
    onGuideScrollChanged: (y: Int, x: Int) -> Unit = { _, _ -> },
    players: List<PlayerSlotInfo> = emptyList(),
    inputRemapHasChanges: Boolean = false,
) {
    val showDescription = when (screen) {
        is IGMScreen.Emulator -> screen.showDescription
        is IGMScreen.EmulatorCategory -> screen.showDescription
        else -> false
    }
    val isGuideScreen = screen is IGMScreen.Guide
    val igmFontSize = config.fontSizeSp.sp
    val igmLineHeight = config.lineHeightSp.sp
    val igmScaleFactor = config.scaleFactor
    val igmTypography = buildCannoliTypography(baseSizeSp = config.fontSizeSp, fontFamily = LocalCannoliFont.current)
    val labels = ButtonStyle(config.buttonLabelSet, config.confirmButton)
    val statusBarEnabled = (config.showWifi || config.showBluetooth || config.showClock || config.batteryDisplay != BatteryDisplayMode.HIDE || config.showVpn) && !showDescription && !isGuideScreen
    val statusBarLeftEdge = remember { mutableIntStateOf(Int.MAX_VALUE) }

    val portraitMarginDp: Dp = if (config.portraitMarginPx > 0) {
        val configuration = LocalConfiguration.current
        val density = LocalDensity.current
        if (configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            with(density) { config.portraitMarginPx.toDp() }
        } else {
            0.dp
        }
    } else {
        0.dp
    }

    CompositionLocalProvider(
        LocalStatusBarLeftEdge provides statusBarLeftEdge,
        LocalScaleFactor provides igmScaleFactor,
        LocalCannoliTypography provides igmTypography,
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(bottom = portraitMarginDp)) {
            when (screen) {
                is IGMScreen.Menu -> {
                    InGameMenu(
                        gameTitle = gameTitle,
                        menuOptions = menuOptions,
                        selectedIndex = screen.selectedIndex,
                        selectedSlot = selectedSlot,
                        slotThumbnail = slotThumbnail,
                        slotExists = slotExists,
                        slotOccupied = slotOccupied,
                        undoLabel = undoLabel,
                        backLabel = stringResource(dev.cannoli.ui.R.string.label_back),
                        deleteLabel = stringResource(dev.cannoli.ui.R.string.label_delete),
                        slotLabel = stringResource(dev.cannoli.ui.R.string.label_slot),
                        saveLabel = stringResource(dev.cannoli.ui.R.string.label_save),
                        loadLabel = stringResource(dev.cannoli.ui.R.string.label_load),
                        discLabel = stringResource(dev.cannoli.ui.R.string.label_disc),
                        selectLabel = stringResource(dev.cannoli.ui.R.string.label_select),
                        fontSize = igmFontSize,
                        lineHeight = igmLineHeight,
                        buttonStyle = labels
                    )
                    if (screen.confirmDeleteSlot) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.85f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Text(
                                    text = stringResource(dev.cannoli.ui.R.string.igm_delete_slot, selectedSlot.label),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(Spacing.Lg))
                                Box(modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth()) {
                                    PolaroidFrame(
                                        thumbnail = slotThumbnail,
                                        selectedSlotIndex = selectedSlot.index,
                                        slotOccupied = slotOccupied,
                                        showIndicators = false
                                    )
                                }
                            }
                            BottomBar(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(screenPadding),
                                leftItems = listOf(labels.back to stringResource(dev.cannoli.ui.R.string.label_cancel)),
                                rightItems = listOf(labels.north to stringResource(dev.cannoli.ui.R.string.label_delete))
                            )
                        }
                    }
                }
                is IGMScreen.Settings, is IGMScreen.Video, is IGMScreen.Advanced,
                is IGMScreen.ShaderSettings,
                is IGMScreen.Emulator, is IGMScreen.EmulatorCategory,
                is IGMScreen.Shortcuts, is IGMScreen.SavePrompt,
                is IGMScreen.RaOptions, is IGMScreen.RaOptionsCategory,
                is IGMScreen.Buttons -> {
                    val description = if (showDescription) {
                        settingsItems.getOrNull(screen.selectedIndex)?.hint
                    } else null
                    val isOptionList = screen is IGMScreen.EmulatorCategory ||
                        (screen is IGMScreen.Emulator && settingsItems.all { it.value != null })
                    val changeLabel = stringResource(dev.cannoli.ui.R.string.label_change)
                    val selectLabel = stringResource(dev.cannoli.ui.R.string.label_select)
                    val showsCycleHint = isOptionList ||
                        (screen is IGMScreen.Shortcuts && screen.selectedIndex == 0) ||
                        screen is IGMScreen.Video ||
                        screen is IGMScreen.Advanced ||
                        screen is IGMScreen.ShaderSettings ||
                        screen is IGMScreen.RaOptionsCategory
                    val bottomBarRight = when {
                        isOptionList -> listOf(labels.confirm to stringResource(dev.cannoli.ui.R.string.label_info))
                        screen is IGMScreen.Shortcuts && screen.selectedIndex == 0 -> emptyList()
                        screen is IGMScreen.Shortcuts -> listOf(labels.north to stringResource(dev.cannoli.ui.R.string.label_clear), labels.confirm to stringResource(dev.cannoli.ui.R.string.label_set))
                        screen is IGMScreen.Video -> listOf(labels.confirm to selectLabel)
                        screen is IGMScreen.Advanced -> emptyList()
                        screen is IGMScreen.ShaderSettings -> emptyList()
                        screen is IGMScreen.Buttons -> listOf(
                            labels.north to stringResource(dev.cannoli.ui.R.string.label_clear),
                            labels.confirm to stringResource(dev.cannoli.ui.R.string.label_press),
                        )
                        screen is IGMScreen.RaOptions -> listOf(labels.confirm to selectLabel)
                        screen is IGMScreen.RaOptionsCategory -> emptyList()
                        else -> listOf(labels.confirm to selectLabel)
                    }
                    val emulatorLabel = stringResource(dev.cannoli.ui.R.string.igm_emulator)
                    val title = when (screen) {
                        is IGMScreen.Settings -> stringResource(dev.cannoli.ui.R.string.igm_settings)
                        is IGMScreen.Video -> stringResource(dev.cannoli.ui.R.string.igm_video)
                        is IGMScreen.Advanced -> stringResource(dev.cannoli.ui.R.string.igm_advanced)
                        is IGMScreen.ShaderSettings -> stringResource(dev.cannoli.ui.R.string.igm_shader_settings)
                        is IGMScreen.Emulator -> emulatorLabel
                        is IGMScreen.EmulatorCategory -> screen.categoryTitle.ifEmpty { emulatorLabel }
                        is IGMScreen.Shortcuts -> stringResource(dev.cannoli.ui.R.string.title_shortcuts)
                        is IGMScreen.SavePrompt -> stringResource(dev.cannoli.ui.R.string.igm_save_changes)
                        is IGMScreen.Buttons -> stringResource(dev.cannoli.ui.R.string.igm_buttons)
                        is IGMScreen.RaOptions -> stringResource(dev.cannoli.ui.R.string.igm_settings)
                        is IGMScreen.RaOptionsCategory -> screen.categoryTitle.ifEmpty {
                            stringResource(dev.cannoli.ui.R.string.igm_settings)
                        }
                        else -> stringResource(dev.cannoli.ui.R.string.igm_settings)
                    }
                    val bottomBarLeft = buildList {
                        add(labels.back to stringResource(dev.cannoli.ui.R.string.label_back))
                        if (screen is IGMScreen.Buttons && inputRemapHasChanges) {
                            add(labels.west to stringResource(dev.cannoli.ui.R.string.label_reset_all))
                        }
                        if (showsCycleHint) add(DPAD_HORIZONTAL to changeLabel)
                    }
                    IGMSettingsScreen(
                        title = title,
                        items = settingsItems,
                        selectedIndex = screen.selectedIndex,
                        bottomBarLeft = bottomBarLeft,
                        bottomBarRight = bottomBarRight,
                        coreInfo = if (screen is IGMScreen.RaOptionsCategory)
                            settingsItems.getOrNull(screen.selectedIndex)?.hint.orEmpty()
                        else coreInfo,
                        description = description,
                        fontSize = igmFontSize,
                        lineHeight = igmLineHeight
                    )
                }
                is IGMScreen.Info -> {
                    fun stripRoot(path: String): String {
                        if (gameInfo.rootPrefix.isNotEmpty() && path.startsWith(gameInfo.rootPrefix)) {
                            return path.removePrefix(gameInfo.rootPrefix).removePrefix("/")
                        }
                        return path
                    }
                    ScreenBackground(backgroundImagePath = null, backgroundAlpha = 0.85f, backgroundColor = Color.Black) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(screenPadding)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = footerReservation())
                            ) {
                                ScreenTitle(
                                    text = gameTitle,
                                    fontSize = igmFontSize,
                                    lineHeight = igmLineHeight
                                )
                                val infoModifier = Modifier.padding(start = pillInternalH)
                                val infoScrollState = remember { ScrollState(0) }
                                LaunchedEffect(infoScrollDir) {
                                    while (infoScrollDir != 0) {
                                        infoScrollState.dispatchRawDelta(infoScrollDir * 14f)
                                        delay(16L)
                                    }
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clipToBounds()
                                        .verticalScroll(infoScrollState)
                                ) {
                                    Spacer(modifier = Modifier.height(Spacing.Md))
                                    InfoRow(stringResource(dev.cannoli.ui.R.string.info_core), gameInfo.coreName, infoModifier)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    if (gameInfo.extractedPath.isNotEmpty()) {
                                        InfoRow(stringResource(dev.cannoli.ui.R.string.info_rom), stripRoot(gameInfo.romPath), infoModifier)
                                        Spacer(modifier = Modifier.height(12.dp))
                                        InfoRow(stringResource(dev.cannoli.ui.R.string.info_extracted), stripRoot(gameInfo.extractedPath), infoModifier)
                                    } else {
                                        InfoRow(stringResource(dev.cannoli.ui.R.string.info_rom), stripRoot(gameInfo.romPath), infoModifier)
                                    }
                                    if (gameInfo.savePath.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        InfoRow(stringResource(dev.cannoli.ui.R.string.info_save), stripRoot(gameInfo.savePath), infoModifier)
                                    }
                                    if (gameInfo.rendererName.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        InfoRow(stringResource(dev.cannoli.ui.R.string.info_renderer), gameInfo.rendererName, infoModifier)
                                    }
                                    if (gameInfo.raStatus.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        InfoRow(stringResource(dev.cannoli.ui.R.string.ra_title), gameInfo.raStatus, infoModifier)
                                    }
                                    if (gameInfo.raGameId.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        InfoRow(stringResource(dev.cannoli.ui.R.string.info_game_id), gameInfo.raGameId, infoModifier)
                                    }
                                    if (gameInfo.raDetection.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        InfoRow(stringResource(dev.cannoli.ui.R.string.info_ra_detection), gameInfo.raDetection, infoModifier)
                                    }
                                }
                            }
                            BottomBar(
                                modifier = Modifier.align(Alignment.BottomCenter),
                                leftItems = listOf(labels.back to stringResource(dev.cannoli.ui.R.string.label_back)),
                                rightItems = emptyList()
                            )
                        }
                    }
                }
                is IGMScreen.GuidePicker -> {
                    IGMSettingsScreen(
                        title = stringResource(dev.cannoli.ui.R.string.title_guide),
                        items = guideFiles.map { IGMSettingsItem(it.name) },
                        selectedIndex = screen.selectedIndex,
                        bottomBarLeft = listOf(labels.back to stringResource(dev.cannoli.ui.R.string.label_back)),
                        bottomBarRight = listOf(labels.confirm to stringResource(dev.cannoli.ui.R.string.label_select)),
                        fontSize = igmFontSize,
                        lineHeight = igmLineHeight
                    )
                }
                is IGMScreen.Guide -> {
                    val guide = guideFiles.firstOrNull { it.file.absolutePath == screen.filePath }
                    val type = guide?.type ?: GuideType.TXT
                    GuideScreen(
                        filePath = screen.filePath,
                        guideType = type,
                        page = screen.page,
                        initialScrollY = guideInitialScroll,
                        initialScrollX = guideInitialScrollX,
                        scrollDir = guideScrollDir,
                        scrollXDir = guideScrollXDir,
                        pageJump = guidePageJump,
                        pageJumpDir = guidePageJumpDir,
                        pageCount = guidePageCount,
                        textZoom = screen.textZoom,
                        onScrollPosChanged = onGuideScrollChanged
                    )
                }
                is IGMScreen.Achievements -> {
                    val filterLabel = when (screen.filter) {
                        1 -> stringResource(dev.cannoli.ui.R.string.label_unlocked)
                        2 -> stringResource(dev.cannoli.ui.R.string.label_locked)
                        else -> stringResource(dev.cannoli.ui.R.string.label_all)
                    }
                    val filtered = when (screen.filter) {
                        1 -> screen.achievements.filter { it.unlocked }
                        2 -> screen.achievements.filter { !it.unlocked }
                        else -> screen.achievements
                    }
                    IGMSettingsScreen(
                        title = stringResource(dev.cannoli.ui.R.string.ach_title, screen.achievements.count { it.unlocked }, screen.achievements.size),
                        items = filtered.map { ach ->
                            val prefix = when {
                                ach.pendingSync -> HALF_CIRCLE
                                ach.unlocked -> BULLET
                                else -> CIRCLE_EMPTY
                            }
                            IGMSettingsItem(
                                label = "$prefix ${ach.title}",
                                value = stringResource(dev.cannoli.ui.R.string.ach_points_short, ach.points)
                            )
                        },
                        selectedIndex = screen.selectedIndex.coerceAtMost((filtered.size - 1).coerceAtLeast(0)),
                        bottomBarLeft = listOf(labels.back to stringResource(dev.cannoli.ui.R.string.label_back)),
                        bottomBarRight = buildList {
                            if (screen.achievements.any { it.unlocked } && screen.achievements.any { !it.unlocked }) {
                                add(labels.west to filterLabel)
                            }
                            add(labels.confirm to stringResource(dev.cannoli.ui.R.string.label_details))
                        },
                        coreInfo = screen.status,
                        fontSize = igmFontSize,
                        lineHeight = igmLineHeight
                    )
                }
                is IGMScreen.AchievementDetail -> {
                    val ach = screen.achievement
                    val unlockText = if (ach.pendingSync) {
                        stringResource(dev.cannoli.ui.R.string.ach_unlocked_pending)
                    } else if (ach.unlocked && ach.unlockTime > 0) {
                        val date = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
                            .format(java.util.Date(ach.unlockTime * 1000))
                        stringResource(dev.cannoli.ui.R.string.ach_unlocked_date, date)
                    } else if (ach.unlocked) stringResource(dev.cannoli.ui.R.string.ach_unlocked) else stringResource(dev.cannoli.ui.R.string.ach_locked)

                    ScreenBackground(backgroundImagePath = null, backgroundAlpha = 0.85f, backgroundColor = Color.Black) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(screenPadding),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                            ) {
                                Text(
                                    text = ach.title,
                                    style = TextStyle(
                                        fontFamily = LocalCannoliFont.current,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 24.sp,
                                        color = Color.White,
                                        textAlign = TextAlign.Center
                                    )
                                )
                                Spacer(modifier = Modifier.height(Spacing.Xs))
                                Text(
                                    text = unlockText,
                                    style = TextStyle(
                                        fontFamily = LocalCannoliFont.current,
                                        fontSize = 16.sp,
                                        color = Color.White
                                    )
                                )
                                Spacer(modifier = Modifier.height(Spacing.Xs))
                                Text(
                                    text = stringResource(dev.cannoli.ui.R.string.ach_points, ach.points),
                                    style = TextStyle(
                                        fontFamily = LocalCannoliFont.current,
                                        fontSize = 16.sp,
                                        color = Color.White
                                    )
                                )
                                Spacer(modifier = Modifier.height(Spacing.Md))
                                Text(
                                    text = ach.description,
                                    style = TextStyle(
                                        fontFamily = LocalCannoliFont.current,
                                        fontSize = 18.sp,
                                        color = Color.White,
                                        textAlign = TextAlign.Center
                                    )
                                )
                            }
                            BottomBar(
                                modifier = Modifier.align(Alignment.BottomCenter),
                                leftItems = listOf(labels.back to stringResource(dev.cannoli.ui.R.string.label_back)),
                                rightItems = emptyList()
                            )
                        }
                    }
                }
                is IGMScreen.ReassignPlayers -> {
                    val portToName = players.filter { it.port >= 0 }.associate { it.port to it.displayName }
                    val items = (0 until 4).map { port ->
                        val name = portToName[port] ?: "—"
                        val display = if (port == screen.swapWithIndex) "→ $name" else name
                        IGMSettingsItem(
                            label = "Player ${port + 1}",
                            value = display,
                        )
                    }
                    val confirmLabel = when {
                        screen.swapWithIndex < 0 -> stringResource(dev.cannoli.ui.R.string.label_select)
                        screen.swapWithIndex == screen.selectedIndex -> stringResource(dev.cannoli.ui.R.string.label_cancel)
                        else -> stringResource(dev.cannoli.ui.R.string.label_swap)
                    }
                    IGMSettingsScreen(
                        title = stringResource(dev.cannoli.ui.R.string.igm_reassign_players),
                        items = items,
                        selectedIndex = screen.selectedIndex,
                        bottomBarLeft = listOf(labels.back to stringResource(dev.cannoli.ui.R.string.label_back)),
                        bottomBarRight = listOf(labels.confirm to confirmLabel),
                        fontSize = igmFontSize,
                        lineHeight = igmLineHeight,
                    )
                }
                null -> {}
            }

            if (screen is IGMScreen.Shortcuts && screen.listening) {
                val colors = LocalCannoliColors.current
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.92f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
                    ) {
                        val actionName = settingsItems.getOrNull(screen.selectedIndex)?.label ?: ""
                        Text(
                            text = actionName,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 24.sp,
                                color = colors.text
                            )
                        )
                        Spacer(modifier = Modifier.height(Spacing.Sm))
                        Text(
                            text = if (screen.heldKeys.isEmpty()) stringResource(dev.cannoli.ui.R.string.shortcut_hold_prompt)
                            else screen.heldKeys.joinToString(" + ") { config.keyCodeName(it) },
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 16.sp,
                                color = colors.text.copy(alpha = 0.6f)
                            )
                        )
                        Spacer(modifier = Modifier.height(Spacing.Lg))
                        if (screen.heldKeys.isNotEmpty()) {
                            val progress = (screen.countdownMs / 1500f).coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .widthIn(max = 280.dp).fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(Radius.Sm))
                                    .background(colors.text.copy(alpha = 0.2f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(progress)
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(Radius.Sm))
                                        .background(colors.highlight)
                                )
                            }
                        }
                    }
                }
            }

            val overlayVisible = screen != null
            if (statusBarEnabled) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(20.dp)
                        .alpha(if (overlayVisible) 1f else 0f)
                        .onGloballyPositioned { coords ->
                            statusBarLeftEdge.intValue = coords.positionInWindow().x.toInt()
                        }
                ) {
                    StatusBar(
                        showWifi = config.showWifi,
                        showBluetooth = config.showBluetooth,
                        showVpn = config.showVpn,
                        showClock = config.showClock,
                        showBattery = config.batteryDisplay != BatteryDisplayMode.HIDE,
                        batteryIconOnly = config.batteryDisplay == BatteryDisplayMode.ICON,
                        use24hTime = config.timeFormat == TimeFormatMode.TWENTY_FOUR_HOUR
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = LocalCannoliColors.current
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                color = colors.text.copy(alpha = 0.6f)
            )
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 18.sp,
                color = Color.White
            )
        )
    }
}
