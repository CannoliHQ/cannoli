package dev.cannoli.scorza.navigation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.runtime.Composable
import dev.cannoli.scorza.ui.screens.GuidePickerScreen
import dev.cannoli.scorza.ui.screens.GuideViewerScreen
import dev.cannoli.scorza.ui.screens.SaveSlotsScreen
import dev.cannoli.scorza.ui.screens.SaveStatePickerScreen
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel

@Composable
internal fun GameScreens(
    currentScreen: LauncherScreen,
    inputRouter: dev.cannoli.scorza.input.InputRouter?,
    appSettings: SettingsViewModel.AppSettings,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    labels: dev.cannoli.ui.ButtonStyle,
    listVerticalPadding: Dp,
) {
    when (currentScreen) {
        is LauncherScreen.SaveStatePicker -> {
            inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.saveStatePickerHandler) }
            SaveStatePickerScreen(
                rom = currentScreen.rom,
                stateBasePath = currentScreen.stateBasePath,
                slotOccupied = currentScreen.slotOccupied,
                selectedSlotIndex = currentScreen.selectedSlotIndex,
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                buttonStyle = labels,
            )
        }
        is LauncherScreen.SaveSlots -> {
            inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.saveSlotsHandler) }
            SaveSlotsScreen(
                gameName = currentScreen.displayName,
                slots = currentScreen.slots,
                selectedIndex = currentScreen.selectedIndex,
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                buttonStyle = labels,
                pendingDelete = currentScreen.pendingDelete,
            )
        }
        is LauncherScreen.GuidePicker -> {
            inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.guideHandler) }
            GuidePickerScreen(
                files = currentScreen.files,
                selectedIndex = currentScreen.selectedIndex,
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                buttonStyle = labels,
            )
        }
        is LauncherScreen.Guide -> {
            inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.guideHandler) }
            val c = inputRouter?.guideHandler?.controller
            GuideViewerScreen(
                filePath = currentScreen.filePath,
                guideType = currentScreen.guideType,
                page = currentScreen.page,
                textZoom = currentScreen.textZoom,
                initialScrollY = c?.guideInitialScroll?.intValue ?: 0,
                initialScrollX = c?.guideInitialScrollX?.intValue ?: 0,
                scrollDir = c?.guideScrollDir?.intValue ?: 0,
                scrollXDir = c?.guideScrollXDir?.intValue ?: 0,
                pageJump = c?.guidePageJump?.intValue ?: 0,
                pageJumpDir = c?.guidePageJumpDir?.intValue ?: 0,
                pageCount = c?.guidePageCount?.intValue ?: 0,
                onScrollPosChanged = { y, x -> c?.onScrollChanged(y, x) },
            )
        }
        else -> {}
    }
}
