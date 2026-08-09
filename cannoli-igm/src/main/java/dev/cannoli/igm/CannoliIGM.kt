package dev.cannoli.igm

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import dev.cannoli.ui.components.pillInternalPadding
import dev.cannoli.ui.components.screenInsets
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliFont
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.LocalPillScale
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
    selectedSlot: Int,
    slotThumbnail: Bitmap?,
    slotThumbnailLoaded: Boolean,
    slotExists: Boolean,
    slotOccupied: List<Boolean>,
    undoLabel: String?,
    settingsItems: List<IGMSettingsItem>,
    cheatItems: List<IGMController.CheatItem>,
    cheatVisibleItems: List<IGMController.CheatItem>,
    cheatFilter: CheatFilter,
    cheatFileName: String,
    cheatFileCount: Int,
    cheatHasRemembered: Boolean,
    guideFiles: List<GuideFile>,
    guidePageCount: Int,
    guideScrollDir: Int,
    guideScrollXDir: Int,
    guidePageJump: Int,
    guidePageJumpDir: Int,
    guideInitialScroll: Int,
    guideInitialScrollX: Int,
    onGuideScrollChanged: (y: Int, x: Int) -> Unit = { _, _ -> },
) {
    val showDescription = false
    val isGuideScreen = screen is IGMScreen.Guide
    val igmFontSize = config.fontSizeSp.sp
    val igmLineHeight = config.lineHeightSp.sp
    val igmPillScale = config.pillScale
    val igmScaleFactor = config.scaleFactor
    val igmTypography = buildCannoliTypography(baseSizeSp = config.fontSizeSp, fontFamily = LocalCannoliFont.current)
    val labels = ButtonStyle(config.buttonLabelSet, config.confirmButton)
    val statusBarEnabled = (config.showWifi || config.showBluetooth || config.showClock || config.batteryDisplay != BatteryDisplayMode.HIDE || config.showVpn) && !showDescription && !isGuideScreen
    val statusBarLeftEdge = remember { mutableIntStateOf(Int.MAX_VALUE) }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val portrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val geoRect = dev.cannoli.ui.computeScreenGeometryRect(
        configuration.screenWidthDp, configuration.screenHeightDp,
        config.geometryWidthPct, config.geometryHeightPct, config.geometryXPct, config.geometryYPct,
    )
    val bottomMarginPx = if (portrait) config.portraitMarginPx else 0
    val viewportPadding = with(density) {
        PaddingValues(
            start = geoRect.x.dp,
            top = geoRect.y.dp,
            end = (configuration.screenWidthDp - geoRect.x - geoRect.w).coerceAtLeast(0).dp,
            bottom = (configuration.screenHeightDp - geoRect.y - geoRect.h).coerceAtLeast(0).dp + bottomMarginPx.toDp(),
        )
    }

    CompositionLocalProvider(
        LocalStatusBarLeftEdge provides statusBarLeftEdge,
        LocalScaleFactor provides igmScaleFactor,
        LocalCannoliTypography provides igmTypography,
        LocalPillScale provides igmPillScale,
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(viewportPadding)) {
            when (screen) {
                is IGMScreen.Menu -> {
                    InGameMenu(
                        gameTitle = gameTitle,
                        menuOptions = menuOptions,
                        selectedIndex = screen.selectedIndex,
                        selectedSlot = selectedSlot,
                        slotThumbnail = slotThumbnail,
                        slotThumbnailLoaded = slotThumbnailLoaded,
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
                                val slotName = if (selectedSlot == 0) {
                                    stringResource(dev.cannoli.ui.R.string.igm_slot_auto)
                                } else {
                                    stringResource(dev.cannoli.ui.R.string.igm_slot_numbered, selectedSlot - 1)
                                }
                                Text(
                                    text = stringResource(dev.cannoli.ui.R.string.igm_delete_slot, slotName),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(Spacing.Lg))
                                Box(modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth()) {
                                    PolaroidFrame(
                                        thumbnail = slotThumbnail,
                                        selectedSlotIndex = selectedSlot,
                                        slotOccupied = slotOccupied,
                                        showIndicators = false,
                                        thumbnailLoaded = slotThumbnailLoaded
                                    )
                                }
                            }
                            BottomBar(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(screenInsets()),
                                leftItems = listOf(labels.back to stringResource(dev.cannoli.ui.R.string.label_cancel)),
                                rightItems = listOf(labels.north to stringResource(dev.cannoli.ui.R.string.label_delete))
                            )
                        }
                    }
                }
                is IGMScreen.ProviderSettings, is IGMScreen.SettingsExitPrompt -> {
                    val description = if (showDescription) {
                        settingsItems.getOrNull(screen.selectedIndex)?.hint
                    } else null
                    val selectLabel = stringResource(dev.cannoli.ui.R.string.label_select)
                    val rowCycles = screen is IGMScreen.ProviderSettings &&
                        settingsItems.getOrNull(screen.selectedIndex)?.value != null
                    val bottomBarRight = if (rowCycles) emptyList() else listOf(labels.confirm to selectLabel)
                    val title = when (screen) {
                        is IGMScreen.ProviderSettings -> screen.title
                        else -> stringResource(dev.cannoli.ui.R.string.igm_save_changes)
                    }
                    val bottomBarLeft = buildList {
                        add(labels.back to stringResource(dev.cannoli.ui.R.string.label_back))
                        if (rowCycles) {
                            add(DPAD_HORIZONTAL to stringResource(dev.cannoli.ui.R.string.label_change))
                        }
                    }
                    IGMSettingsScreen(
                        title = title,
                        items = settingsItems,
                        selectedIndex = screen.selectedIndex,
                        bottomBarLeft = bottomBarLeft,
                        bottomBarRight = bottomBarRight,
                        coreInfo = if (screen is IGMScreen.ProviderSettings && screen.path.isNotEmpty())
                            settingsItems.getOrNull(screen.selectedIndex)?.hint.orEmpty()
                        else "",
                        description = description,
                        fontSize = igmFontSize,
                        lineHeight = igmLineHeight
                    )
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
                is IGMScreen.Cheats -> {
                    val onValue = stringResource(dev.cannoli.ui.R.string.value_on)
                    val offValue = stringResource(dev.cannoli.ui.R.string.value_off)
                    val cheats = cheatVisibleItems.map {
                        CheatListItem.Cheat(
                            label = it.label,
                            value = if (it.enabled) onValue else offValue,
                            supported = it.supported,
                        )
                    }
                    val restoreRows = if (cheatHasRemembered) 1 else 0
                    val onRestore = restoreRows == 1 && screen.selectedIndex == 0
                    val onSelector = screen.selectedIndex == restoreRows
                    val selectedCheat = cheats.getOrNull(screen.selectedIndex - restoreRows - 1)
                    val filterName = when (cheatFilter) {
                        CheatFilter.ALL -> stringResource(dev.cannoli.ui.R.string.value_all)
                        CheatFilter.ON -> onValue
                        CheatFilter.OFF -> offValue
                    }
                    CheatsScreen(
                        title = if (cheatItems.isEmpty()) {
                            stringResource(dev.cannoli.ui.R.string.title_cheats)
                        } else {
                            stringResource(
                                dev.cannoli.ui.R.string.title_cheats_count,
                                cheatItems.count { it.enabled },
                                cheatItems.size,
                            )
                        },
                        restoreLabel = if (cheatHasRemembered) {
                            stringResource(dev.cannoli.ui.R.string.cheats_restore)
                        } else null,
                        fileHeader = stringResource(dev.cannoli.ui.R.string.cheats_file),
                        fileName = if (cheatFileName.endsWith(".cht", ignoreCase = true)) {
                            cheatFileName.dropLast(4)
                        } else cheatFileName,
                        cheatsHeader = stringResource(dev.cannoli.ui.R.string.cheats_available, filterName),
                        cheats = cheats,
                        selectedIndex = screen.selectedIndex,
                        bottomBarLeft = buildList {
                            add(labels.back to stringResource(dev.cannoli.ui.R.string.label_back))
                            if (cheatItems.isNotEmpty()) {
                                add(labels.west to stringResource(dev.cannoli.ui.R.string.label_filter))
                            }
                            if (onSelector && cheatFileCount > 1) {
                                add(DPAD_HORIZONTAL to stringResource(dev.cannoli.ui.R.string.label_change))
                            }
                        },
                        bottomBarRight = buildList {
                            if (onRestore) {
                                add(labels.confirm to stringResource(dev.cannoli.ui.R.string.label_select))
                            }
                            if (selectedCheat?.supported == true) {
                                add(labels.confirm to stringResource(dev.cannoli.ui.R.string.label_toggle))
                            }
                        },
                        fontSize = igmFontSize,
                        lineHeight = igmLineHeight
                    )
                }
                is IGMScreen.CheatsHardcoreWarning -> {
                    ScreenBackground(backgroundImagePath = null, backgroundAlpha = 0.85f, backgroundColor = Color.Black) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(screenInsets()),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(dev.cannoli.ui.R.string.cheats_hardcore_warning),
                                style = TextStyle(
                                    fontFamily = LocalCannoliFont.current,
                                    fontSize = 18.sp,
                                    color = Color.White,
                                    textAlign = TextAlign.Center
                                ),
                                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                            )
                            BottomBar(
                                modifier = Modifier.align(Alignment.BottomCenter),
                                leftItems = listOf(labels.back to stringResource(dev.cannoli.ui.R.string.label_cancel)),
                                rightItems = listOf(labels.confirm to stringResource(dev.cannoli.ui.R.string.label_continue))
                            )
                        }
                    }
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
                                .padding(screenInsets()),
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
                null -> {}
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
