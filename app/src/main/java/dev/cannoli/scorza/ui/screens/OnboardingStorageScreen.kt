package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.onboarding.OnboardingStep
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.START_GLYPH
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.theme.Spacing
import dev.cannoli.ui.theme.Success

@Composable
fun OnboardingStorageScreen(
    screen: LauncherScreen.OnboardingStorage,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 8.dp,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    // Ending setup is a deliberate act, so it sits on START; confirm belongs to the custom row,
    // which is the only one with somewhere to go.
    val rightItems = buildList {
        if (screen.isCustomVolume) {
            add(buttonStyle.confirm to stringResource(R.string.onboarding_select_folder))
        }
        if (screen.canContinue) add(START_GLYPH to stringResource(R.string.label_finish))
    }

    OnboardingScaffold(
        step = OnboardingStep.STORAGE,
        title = stringResource(R.string.onboarding_storage_title),
        listFontSize = listFontSize,
        listLineHeight = listLineHeight,
        leftItems = listOf(buttonStyle.back to stringResource(R.string.label_back)),
        rightItems = rightItems,
    ) {
        screen.volumes.forEachIndexed { index, volume ->
            PillRowKeyValue(
                label = volume.first,
                value = volumeTarget(volume.second, screen.customPath),
                isSelected = index == screen.volumeIndex,
                fontSize = listFontSize,
                lineHeight = listLineHeight,
                verticalPadding = listVerticalPadding,
            )
        }
        screen.existingFolderPath?.let {
            Spacer(modifier = Modifier.height(Spacing.Md))
            OnboardingBodyText(it, color = Success)
        }
    }
}

// The custom row has no volume path of its own, so it shows whatever the browser picked.
@Composable
private fun volumeTarget(volumePath: String, customPath: String?): String = when {
    volumePath.isNotEmpty() -> volumePath + "Cannoli/"
    customPath != null -> customPath
    else -> stringResource(R.string.setup_folder_unset)
}
