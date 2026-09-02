package dev.cannoli.igm

import dev.cannoli.ui.components.ShortcutCaptureOverlay
import android.content.res.Configuration
import android.graphics.Bitmap
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.BULLET
import dev.cannoli.ui.CIRCLE_EMPTY
import dev.cannoli.ui.DPAD_HORIZONTAL
import dev.cannoli.ui.DPAD_VERTICAL
import dev.cannoli.ui.HALF_CIRCLE
import dev.cannoli.ui.MENU_GLYPH
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.HelpOverlay
import dev.cannoli.ui.components.KeyboardHelpOverlay
import dev.cannoli.ui.components.KeyboardOverlay
import dev.cannoli.ui.components.LocalStatusBarLeftEdge
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.StatusBar
import dev.cannoli.ui.components.screenInsets
import dev.cannoli.ui.theme.LocalCannoliFont
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.LocalPillScale
import dev.cannoli.ui.theme.LocalScaleFactor
import dev.cannoli.ui.theme.Spacing
import dev.cannoli.ui.theme.buildCannoliTypography

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
    undoAction: UndoAction?,
    settingsItems: List<IGMSettingsItem>,
    shortcutRows: List<RetroArchBridge.ShortcutBinding> = emptyList(),
    previewTitle: String = "",
    previewItems: List<String> = emptyList(),
    previewCanRestore: Boolean = false,
    settingsCanRestore: Boolean = false,
    settingsCanReorder: Boolean = false,
    settingsCanRemovePass: Boolean = false,
    settingsCanReset: Boolean = false,
    settingsReordering: Boolean = false,
    overlayImage: String? = null,
    cheatItems: List<IGMController.CheatItem>,
    cheatVisibleItems: List<IGMController.CheatItem>,
    cheatFilter: CheatFilter,
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
    val isGuideScreen = screen is IGMScreen.Guide
    // The picker judges what is on screen, so nothing of Cannoli's may sit over the game.
    val isPreviewScreen = screen is IGMScreen.PreviewPicker
    val igmFontSize = config.fontSizeSp.sp
    val igmLineHeight = config.lineHeightSp.sp
    val igmPillScale = config.pillScale
    val igmScaleFactor = config.scaleFactor
    val igmTypography = buildCannoliTypography(baseSizeSp = config.fontSizeSp, fontFamily = LocalCannoliFont.current)
    val labels = ButtonStyle(config.buttonLabelSet, config.confirmButton)
    val statusBarEnabled = (config.showWifi || config.showBluetooth || config.showClock || config.batteryDisplay != BatteryDisplayMode.HIDE || config.showVpn) && !isGuideScreen && !isPreviewScreen
    val statusBarLeftEdge = remember { mutableIntStateOf(Int.MAX_VALUE) }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    // LocalView.current.width/.height isn't Compose state: a layout pass doesn't invalidate this
    // composable, so reading it directly sticks at the pre-layout 0x0 forever. onSizeChanged below
    // hoists the laid-out size into state that recomposition actually observes.
    val surfaceSize = remember { mutableStateOf(IntSize.Zero) }
    val portrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val viewportPadding = dev.cannoli.ui.computeScreenGeometryPadding(
        surfaceWidthPx = surfaceSize.value.width,
        surfaceHeightPx = surfaceSize.value.height,
        surfaceWidthDp = configuration.screenWidthDp,
        surfaceHeightDp = configuration.screenHeightDp,
        widthPct = config.geometryWidthPct,
        heightPct = config.geometryHeightPct,
        xPct = config.geometryXPct,
        yPct = config.geometryYPct,
        portraitMarginPx = config.portraitMarginPx,
        portrait = portrait,
        density = density,
    )

    CompositionLocalProvider(
        LocalStatusBarLeftEdge provides statusBarLeftEdge,
        LocalScaleFactor provides igmScaleFactor,
        LocalCannoliTypography provides igmTypography,
        LocalPillScale provides igmPillScale,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { surfaceSize.value = it }
        ) {
            // Outside the viewport padding, and outside the when. A bezel frames the whole display
            // rather than sitting inside the usable area, and it belongs to the game rather than to
            // any one screen, so it stays put while screens come and go and while none is shown.
            // Inside the padding it also visibly resized, because that padding derives from a
            // surface size that is zero until the first layout resolves.
            OverlayLayer(
                path = overlayImage,
                widthPx = surfaceSize.value.width,
                heightPx = surfaceSize.value.height,
            )

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
                        undoLabel = when (undoAction) {
                            UndoAction.SAVE -> stringResource(dev.cannoli.ui.R.string.label_undo_save)
                            UndoAction.LOAD -> stringResource(dev.cannoli.ui.R.string.label_undo_load)
                            null -> null
                        },
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
                // The launcher's keyboard, unchanged: naming a preset here and naming a folder
                // there are the same act, and it brings its own legend and help with it.
                is IGMScreen.ShaderSaveName -> {
                    if (screen.help) {
                        KeyboardHelpOverlay(
                            layout = screen.keyboard.layout,
                            titleFontSize = igmFontSize,
                            titleLineHeight = igmLineHeight,
                            buttonStyle = labels,
                        )
                    } else {
                        KeyboardOverlay(
                            state = screen.keyboard,
                            title = stringResource(dev.cannoli.ui.R.string.igm_shader_save_title),
                            buttonStyle = labels,
                        )
                    }
                }
                is IGMScreen.PreviewPicker -> {
                    LivePreviewPicker(
                        title = previewTitle,
                        items = previewItems,
                        index = screen.selectedIndex,
                        labels = labels,
                        canRestore = previewCanRestore,
                        fontSize = igmFontSize,
                        lineHeight = igmLineHeight,
                    )
                }

                is IGMScreen.ProviderSettings, is IGMScreen.SettingsExitPrompt -> {
                    val description = (screen as? IGMScreen.ProviderSettings)?.description
                    val descriptionScroll = (screen as? IGMScreen.ProviderSettings)?.descriptionScroll ?: 0
                    val inShaderTree = screen is IGMScreen.ProviderSettings &&
                        screen.path.firstOrNull() == CuratedCatalog.CATEGORY_SHADER
                    val selectLabel = stringResource(dev.cannoli.ui.R.string.label_select)
                    val rowCycles = screen is IGMScreen.ProviderSettings &&
                        settingsItems.getOrNull(screen.selectedIndex)?.value != null
                    val hasDescription =
                        settingsItems.getOrNull(screen.selectedIndex)?.description != null
                    val bottomBarRight = when {
                        settingsReordering ->
                            listOf(labels.confirm to stringResource(dev.cannoli.ui.R.string.label_done))
                        description != null -> emptyList()
                        inShaderTree -> buildList {
                            // Offered only while this game overrides its platform, which is what
                            // makes the legend also the answer to where the shader came from.
                            if (settingsCanRestore) add(
                                labels.west to
                                    stringResource(dev.cannoli.ui.R.string.label_use_platform)
                            )
                            // Only on a row that is a pass, so the button cannot take away
                            // something the screen never offered.
                            if (settingsCanRemovePass) add(
                                labels.north to
                                    stringResource(dev.cannoli.ui.R.string.label_remove_pass)
                            )
                        }
                        hasDescription -> listOf(
                            MENU_GLYPH to stringResource(dev.cannoli.ui.R.string.igm_help)
                        )
                        rowCycles -> emptyList()
                        else -> listOf(labels.confirm to selectLabel)
                    }
                    val title = when (screen) {
                        is IGMScreen.ProviderSettings -> screen.title
                        // A prompt that asked its own question keeps it. Only the one on the way out
                        // of Settings has none of its own, and that one is about saving.
                        is IGMScreen.SettingsExitPrompt -> screen.title
                            ?: stringResource(dev.cannoli.ui.R.string.igm_save_changes)
                        else -> stringResource(dev.cannoli.ui.R.string.igm_save_changes)
                    }
                    val bottomBarLeft = buildList {
                        if (settingsReordering) {
                            add(DPAD_VERTICAL to stringResource(dev.cannoli.ui.R.string.label_move))
                            return@buildList
                        }
                        add(labels.back to stringResource(dev.cannoli.ui.R.string.label_back))
                        // Only where something is stored, so the button appearing is also the answer
                        // to whether this game or its platform has settings of its own.
                        if (settingsCanReset) {
                            add(labels.north to stringResource(dev.cannoli.ui.R.string.label_reset))
                        }
                        // Only where a row can actually be picked up, so the offer is also the
                        // answer to which rows have a position that matters.
                        if (settingsCanReorder) {
                            add(dev.cannoli.ui.SELECT_GLYPH to stringResource(dev.cannoli.ui.R.string.label_reorder))
                        }
                        // The description covers the list, so nothing below it can be cycled. Up
                        // and Down scroll the text instead.
                        when {
                            description != null ->
                                add(DPAD_VERTICAL to stringResource(dev.cannoli.ui.R.string.label_scroll))
                            rowCycles ->
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
                        descriptionScroll = descriptionScroll,
                        reorderingIndex = screen.selectedIndex.takeIf { settingsReordering },
                        fontSize = igmFontSize,
                        lineHeight = igmLineHeight,
                    )
                }
                is IGMScreen.Shortcuts -> {
                    // The launcher's capture screen, not a copy of it: one answer to how a chord is
                    // held, so the two menus cannot drift on the prompt or the wait.
                    if (screen.listening) {
                        ShortcutCaptureOverlay(
                            actionName = shortcutRows.getOrNull(screen.selectedIndex)
                                ?.let { stringResource(it.action.labelRes) } ?: "",
                            heldText = screen.heldKeys
                                .takeIf { it.isNotEmpty() }
                                ?.joinToString(" + ") { config.keyCodeName(it) },
                            progress = screen.countdownMs / BindingController.HOLD_MS.toFloat(),
                            fontSize = igmFontSize,
                        )
                        return@Box
                    }
                    val none = stringResource(dev.cannoli.ui.R.string.igm_shortcut_none)
                    val listening = stringResource(dev.cannoli.ui.R.string.igm_shortcut_listening)
                    val items = shortcutRows.mapIndexed { i, row ->
                        val binding = screen.listening && i == screen.selectedIndex
                        IGMSettingsItem(
                            label = stringResource(row.action.labelRes),
                            value = when {
                                binding && screen.heldKeys.isEmpty() -> listening
                                binding -> screen.heldKeys.joinToString(" + ") { config.keyCodeName(it) }
                                row.chord.isEmpty() -> none
                                else -> row.chord.joinToString(" + ") { config.keyCodeName(it) }
                            },
                        )
                    }
                    IGMSettingsScreen(
                        title = stringResource(dev.cannoli.ui.R.string.igm_shortcuts_title),
                        items = items,
                        selectedIndex = screen.selectedIndex,
                        bottomBarLeft = listOf(
                            labels.back to stringResource(dev.cannoli.ui.R.string.label_back),
                        ),
                        bottomBarRight = listOf(
                            labels.north to stringResource(dev.cannoli.ui.R.string.label_clear),
                            labels.confirm to stringResource(dev.cannoli.ui.R.string.label_set),
                        ),
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
                    if (screen.help) {
                        HelpOverlay(
                            titleRes = dev.cannoli.ui.R.string.guide_help_title,
                            groups = guideHelpGroups(type),
                            titleFontSize = igmFontSize,
                            titleLineHeight = igmLineHeight,
                            buttonStyle = labels,
                        )
                    } else {
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
                        onScrollPosChanged = onGuideScrollChanged,
                        helpHint = guideHelpHint(),
                    )
                    }
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
                    val selectedCheat = cheats.getOrNull(screen.selectedIndex - restoreRows)
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
                        cheatsHeader = stringResource(dev.cannoli.ui.R.string.cheats_available, filterName),
                        cheats = cheats,
                        selectedIndex = screen.selectedIndex,
                        bottomBarLeft = buildList {
                            add(labels.back to stringResource(dev.cannoli.ui.R.string.label_back))
                            if (cheatItems.isNotEmpty()) {
                                add(labels.west to stringResource(dev.cannoli.ui.R.string.label_filter))
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
                                    fontSize = (18 * igmScaleFactor).sp,
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
                        1 -> screen.achievements.filter { it.unlocked }.sortedByUnlockedNewestFirst()
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
                                        fontSize = (24 * igmScaleFactor).sp,
                                        color = Color.White,
                                        textAlign = TextAlign.Center
                                    )
                                )
                                Spacer(modifier = Modifier.height(Spacing.Xs))
                                Text(
                                    text = unlockText,
                                    style = TextStyle(
                                        fontFamily = LocalCannoliFont.current,
                                        fontSize = (16 * igmScaleFactor).sp,
                                        color = Color.White
                                    )
                                )
                                Spacer(modifier = Modifier.height(Spacing.Xs))
                                Text(
                                    text = stringResource(dev.cannoli.ui.R.string.ach_points, ach.points),
                                    style = TextStyle(
                                        fontFamily = LocalCannoliFont.current,
                                        fontSize = (16 * igmScaleFactor).sp,
                                        color = Color.White
                                    )
                                )
                                Spacer(modifier = Modifier.height(Spacing.Md))
                                Text(
                                    text = ach.description,
                                    style = TextStyle(
                                        fontFamily = LocalCannoliFont.current,
                                        fontSize = (18 * igmScaleFactor).sp,
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
}
