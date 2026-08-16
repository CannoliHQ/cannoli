package dev.cannoli.scorza.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.onboarding.OnboardingStep
import dev.cannoli.scorza.onboarding.OnboardingStorageAction
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.DPAD_HORIZONTAL
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.theme.Success

@Composable
fun OnboardingStorageScreen(
    screen: LauncherScreen.OnboardingStorage,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 8.dp,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    val leftItems = mutableListOf(buttonStyle.back to stringResource(R.string.label_back))
    if (screen.volumes.size > 1) {
        leftItems.add(DPAD_HORIZONTAL to stringResource(R.string.label_change))
    }
    val actionLabel = when (screen.action) {
        OnboardingStorageAction.SELECT_FOLDER -> stringResource(R.string.onboarding_select_folder)
        OnboardingStorageAction.CONTINUE -> stringResource(R.string.label_continue)
        OnboardingStorageAction.NONE -> null
    }

    OnboardingScaffold(
        step = OnboardingStep.STORAGE,
        title = stringResource(R.string.onboarding_storage_title),
        listFontSize = listFontSize,
        listLineHeight = listLineHeight,
        leftItems = leftItems,
        rightItems = actionLabel?.let { listOf(buttonStyle.confirm to it) } ?: emptyList(),
    ) {
        PillRowKeyValue(
            label = stringResource(R.string.onboarding_location_label),
            value = when {
                screen.isCustomVolume -> screen.customPath ?: stringResource(R.string.setup_folder_unset)
                else -> screen.selectedVolume?.first ?: stringResource(R.string.setup_folder_unset)
            },
            isSelected = true,
            fontSize = listFontSize,
            lineHeight = listLineHeight,
            verticalPadding = listVerticalPadding,
        )
        screen.existingFolderPath?.let { OnboardingBodyText(it, color = Success) }
    }
}
