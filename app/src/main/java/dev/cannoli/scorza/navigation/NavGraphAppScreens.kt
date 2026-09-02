package dev.cannoli.scorza.navigation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.cannoli.scorza.R
import dev.cannoli.scorza.ui.components.CreditsCategoryOverlay
import dev.cannoli.scorza.ui.components.CreditsOverlay
import dev.cannoli.scorza.ui.components.ListDialogScreen
import dev.cannoli.scorza.ui.screens.LoggingSettingsScreen
import dev.cannoli.scorza.ui.screens.PermissionsScreen
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.PillRowText

@Composable
internal fun AppScreens(
    currentScreen: LauncherScreen,
    onListStateChanged: ((androidx.compose.foundation.lazy.LazyListState?) -> Unit)?,
    onboardingMapping: dev.cannoli.scorza.input.DeviceMapping?,
    onboardingConfirmPresses: Int,
    onOnboardingRunExpired: () -> Unit,
    inputRouter: dev.cannoli.scorza.input.InputRouter?,
    appSettings: SettingsViewModel.AppSettings,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    labels: dev.cannoli.ui.ButtonStyle,
    listVerticalPadding: Dp,
    itemHeight: Dp,
) {
    when (currentScreen) {
        is LauncherScreen.Credits -> {
            if (inputRouter != null) {
                val handler = remember { inputRouter.currentHandler() }
                dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
            }
            CreditsOverlay(
                selectedIndex = currentScreen.selectedIndex,
                scrollTarget = currentScreen.scrollTarget,
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                buttonStyle = labels,
                onListStateChanged = onListStateChanged
            )
        }
        is LauncherScreen.CreditsSection -> {
            if (inputRouter != null) {
                val handler = remember { inputRouter.currentHandler() }
                dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
            }
            CreditsCategoryOverlay(
                category = currentScreen.category,
                selectedIndex = currentScreen.selectedIndex,
                scrollTarget = currentScreen.scrollTarget,
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                buttonStyle = labels,
                onListStateChanged = onListStateChanged
            )
        }
        is LauncherScreen.LoggingSettings -> {
            inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.loggingSettingsHandler) }
            LoggingSettingsScreen(
                screen = currentScreen,
                modifier = Modifier.fillMaxSize(),
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                buttonStyle = labels,
            )
        }
        is LauncherScreen.Permissions -> {
            inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.permissionsHandler) }
            PermissionsScreen(
                screen = currentScreen,
                modifier = Modifier.fillMaxSize(),
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                buttonStyle = labels,
            )
        }
        is LauncherScreen.RetroAchievements -> {
            if (inputRouter != null) {
                val handler = remember { inputRouter.currentHandler() }
                dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
            }
            val rows = dev.cannoli.scorza.ui.components.RaAccountRow.entries.toList()
            val selIdx = currentScreen.selectedIndex.coerceIn(0, rows.size - 1)
            ListDialogScreen(
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                title = stringResource(R.string.achievos_title),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                fullWidth = true,
                leftBottomItems = buildList {
                    if (rows[selIdx].isCycle) add(dev.cannoli.ui.DPAD_HORIZONTAL to stringResource(R.string.label_change))
                },
                rightBottomItems = buildList {
                    when (rows[selIdx]) {
                        dev.cannoli.scorza.ui.components.RaAccountRow.ACCOUNT ->
                            add(labels.confirm to stringResource(R.string.label_logout))
                        dev.cannoli.scorza.ui.components.RaAccountRow.OFFLINE_SETS ->
                            add(labels.confirm to stringResource(R.string.label_select))
                        dev.cannoli.scorza.ui.components.RaAccountRow.HARDCORE -> {}
                    }
                },
                buttonStyle = labels,
            ) {
                List(
                    items = rows,
                    selectedIndex = selIdx,
                    itemHeight = itemHeight,
                    scrollTarget = currentScreen.scrollTarget,
                    onListStateChanged = onListStateChanged,
                ) { _, row, isSelected ->
                    when (row) {
                        dev.cannoli.scorza.ui.components.RaAccountRow.ACCOUNT -> PillRowKeyValue(
                            label = currentScreen.username,
                            value = stringResource(dev.cannoli.scorza.ui.components.raTokenStatusRes(currentScreen.tokenState)),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                        )
                        dev.cannoli.scorza.ui.components.RaAccountRow.HARDCORE -> PillRowKeyValue(
                            label = stringResource(R.string.achievos_account_row_hardcore),
                            value = stringResource(
                                if (currentScreen.hardcore) dev.cannoli.ui.R.string.achievos_mode_hardcore
                                else dev.cannoli.ui.R.string.achievos_mode_softcore
                            ),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                        )
                        else -> PillRowText(
                            label = stringResource(row.labelRes),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                        )
                    }
                }
            }
        }
        is LauncherScreen.RetroAchievementsOfflinePlatforms -> {
            if (inputRouter != null) {
                val handler = remember { inputRouter.currentHandler() }
                dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
            }
            dev.cannoli.scorza.ui.screens.RetroAchievementsOfflinePlatformsScreen(
                screen = currentScreen,
                modifier = Modifier.fillMaxSize(),
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                buttonStyle = labels,
                onListStateChanged = onListStateChanged,
            )
        }
        is LauncherScreen.RetroAchievementsOfflineSets -> {
            if (inputRouter != null) {
                val handler = remember { inputRouter.currentHandler() }
                dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
            }
            dev.cannoli.scorza.ui.screens.RetroAchievementsOfflineSetsScreen(
                screen = currentScreen,
                modifier = Modifier.fillMaxSize(),
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                buttonStyle = labels,
                onListStateChanged = onListStateChanged,
            )
        }
        is LauncherScreen.OnboardingWelcome -> {
            dev.cannoli.scorza.ui.screens.OnboardingWelcomeScreen(
                mapping = onboardingMapping,
                confirmPresses = onboardingConfirmPresses,
                onRunExpired = onOnboardingRunExpired,
            )
        }
        is LauncherScreen.OnboardingPermissions -> {
            inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.onboardingPermissionsHandler) }
            dev.cannoli.scorza.ui.screens.OnboardingPermissionsScreen(
                screen = currentScreen,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                buttonStyle = labels,
            )
        }
        is LauncherScreen.OnboardingStorage -> {
            inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.onboardingStorageHandler) }
            dev.cannoli.scorza.ui.screens.OnboardingStorageScreen(
                screen = currentScreen,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                buttonStyle = labels,
            )
        }
        else -> {}
    }
}
