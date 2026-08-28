package dev.cannoli.scorza.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cannoli.igm.ShortcutAction
import dev.cannoli.scorza.R
import dev.cannoli.scorza.ui.components.ListDialogScreen
import dev.cannoli.scorza.ui.screens.ControllerDetailScreen
import dev.cannoli.scorza.ui.screens.ControllersScreen
import dev.cannoli.scorza.ui.screens.EditButtonsScreen
import dev.cannoli.scorza.ui.screens.LegendWizardScreen
import dev.cannoli.scorza.ui.viewmodel.ControllersViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.Radius
import dev.cannoli.ui.theme.Spacing
import kotlinx.coroutines.delay

@Composable
internal fun InputScreens(
    currentScreen: LauncherScreen,
    controllersViewModel: ControllersViewModel,
    onListStateChanged: ((androidx.compose.foundation.lazy.LazyListState?) -> Unit)?,
    editButtonsController: dev.cannoli.scorza.input.EditButtonsController?,
    legendWizardState: dev.cannoli.scorza.input.legend.LegendWizardState,
    nav: NavigationController?,
    inputRouter: dev.cannoli.scorza.input.InputRouter?,
    appSettings: SettingsViewModel.AppSettings,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    labels: dev.cannoli.ui.ButtonStyle,
    shortcutKeyLabel: (Int) -> String,
    listVerticalPadding: Dp,
    itemHeight: Dp,
) {
    when (currentScreen) {
        is LauncherScreen.ShortcutBinding -> {
            if (inputRouter != null) {
                val handler = remember { inputRouter.currentHandler() }
                dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
            }
            ListDialogScreen(
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                title = stringResource(R.string.title_shortcuts),

                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                fullWidth = true,
                rightBottomItems = if (currentScreen.listening) listOf("" to stringResource(R.string.label_hold_buttons))
                    else listOf(labels.north to stringResource(R.string.label_clear), labels.confirm to stringResource(R.string.label_set)),
                buttonStyle = labels
            ) {
                List(
                    items = ShortcutAction.entries.toList(),
                    selectedIndex = currentScreen.selectedIndex,
                    itemHeight = itemHeight,
                    scrollTarget = currentScreen.scrollTarget,
                    onListStateChanged = onListStateChanged
                ) { _, action, isSelected ->
                    val chord = currentScreen.shortcuts[action]
                    val value = if (chord.isNullOrEmpty()) stringResource(R.string.value_none)
                    else chord.joinToString(" + ") { shortcutKeyLabel(it) }
                    PillRowKeyValue(
                        label = stringResource(action.labelRes),
                        value = value,
                        isSelected = isSelected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding
                    )
                }
            }
            if (currentScreen.listening) {
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
                        val actionName = ShortcutAction.entries.getOrNull(currentScreen.selectedIndex)
                            ?.let { stringResource(it.labelRes) } ?: ""
                        Text(
                            text = actionName,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = listFontSize * 1.1f,
                                color = colors.text
                            )
                        )
                        Spacer(modifier = Modifier.height(Spacing.Sm))
                        Text(
                            text = if (currentScreen.heldKeys.isEmpty()) stringResource(R.string.shortcut_hold_prompt)
                            else currentScreen.heldKeys.joinToString(" + ") { shortcutKeyLabel(it) },
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = listFontSize * 0.73f,
                                color = colors.text.copy(alpha = 0.6f)
                            )
                        )
                        Spacer(modifier = Modifier.height(Spacing.Lg))
                        if (currentScreen.heldKeys.isNotEmpty()) {
                            val progress = (currentScreen.countdownMs / 1500f).coerceIn(0f, 1f)
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
        }
        is LauncherScreen.Controllers -> {
            inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.controllersHandler) }
            ControllersScreen(
            screen = currentScreen,
            viewModel = controllersViewModel,
            modifier = Modifier.fillMaxSize(),
            backgroundImagePath = appSettings.backgroundImagePath,
            backgroundTint = appSettings.backgroundTint,
            listFontSize = listFontSize,
            listLineHeight = listLineHeight,
            listVerticalPadding = listVerticalPadding,
            buttonStyle = labels,
        )
        }
        is LauncherScreen.ControllerDetail -> {
            inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.controllerDetailHandler) }
            val controllersState by controllersViewModel.state.collectAsState()
            val mapping = controllersState.connected.firstOrNull { it.mapping.id == currentScreen.mappingId }?.mapping
                ?: controllersState.savedMappings.firstOrNull { it.id == currentScreen.mappingId }
            ControllerDetailScreen(
                screen = currentScreen,
                mapping = mapping,
                modifier = Modifier.fillMaxSize(),
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                buttonStyle = labels,
            )
        }
        is LauncherScreen.EditButtons -> {
            inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.editButtonsHandler) }
            val editState by controllersViewModel.state.collectAsState()
            val mapping = editState.connected.firstOrNull { it.mapping.id == currentScreen.mappingId }?.mapping
                ?: editState.savedMappings.firstOrNull { it.id == currentScreen.mappingId }
                ?: controllersViewModel.mappingById(currentScreen.mappingId)
            if (editButtonsController != null && nav != null) {
                androidx.compose.runtime.LaunchedEffect(currentScreen.listeningCanonical) {
                    if (currentScreen.listeningCanonical != null) {
                        val startedAt = System.currentTimeMillis()
                        while (currentScreen.listeningCanonical != null) {
                            kotlinx.coroutines.delay(50)
                            val finalized = editButtonsController.tickAndMaybeFinalize()
                            if (finalized != null || !editButtonsController.isListening) {
                                val cs = nav.currentScreen
                                if (cs is LauncherScreen.EditButtons) {
                                    nav.replaceTop(cs.copy(listeningCanonical = null, countdownMs = 0))
                                }
                                break
                            }
                            val cs = nav.currentScreen
                            if (cs is LauncherScreen.EditButtons && cs.listeningCanonical != null) {
                                val elapsed = (System.currentTimeMillis() - startedAt).toInt()
                                if (cs.countdownMs != elapsed) {
                                    nav.replaceTop(cs.copy(countdownMs = elapsed))
                                }
                            }
                        }
                    }
                }
            }
            EditButtonsScreen(
                screen = currentScreen,
                mapping = mapping,
                modifier = Modifier.fillMaxSize(),
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                buttonStyle = labels,
            )
        }
        is LauncherScreen.LegendWizard -> {
            LegendWizardScreen(
                state = legendWizardState,
                modifier = Modifier.fillMaxSize(),
                duringFirstRun = currentScreen.duringFirstRun,
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
            )
        }
        else -> {}
    }
}
