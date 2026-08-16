package dev.cannoli.scorza.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.onboarding.OnboardingStep
import dev.cannoli.ui.ANY_BUTTON_GLYPH

@Composable
fun OnboardingWelcomeScreen(
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
) {
    OnboardingScaffold(
        step = OnboardingStep.WELCOME,
        title = stringResource(R.string.onboarding_welcome_title),
        listFontSize = listFontSize,
        listLineHeight = listLineHeight,
        leftItems = emptyList(),
        rightItems = listOf(ANY_BUTTON_GLYPH to stringResource(R.string.label_continue)),
    ) {
        OnboardingBodyText(stringResource(R.string.onboarding_welcome_body))
    }
}
