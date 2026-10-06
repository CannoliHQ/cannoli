package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import dev.cannoli.scorza.R
import dev.cannoli.scorza.onboarding.OnboardingStep
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.pillInternalPadding
import dev.cannoli.ui.components.screenInsets
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.Spacing

@Composable
fun OnboardingScaffold(
    step: OnboardingStep,
    title: String,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    leftItems: List<Pair<String, String>>,
    rightItems: List<Pair<String, String>>,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(screenInsets())
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = footerReservation())
                .verticalScroll(rememberScrollState())
        ) {
            OnboardingStepCounter(step)
            Spacer(modifier = Modifier.height(Spacing.Xs))
            ScreenTitle(text = title, fontSize = listFontSize, lineHeight = listLineHeight)
            Spacer(modifier = Modifier.height(Spacing.Md))
            content()
        }
        BottomBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            leftItems = leftItems,
            rightItems = rightItems,
        )
    }
}

@Composable
fun OnboardingStepCounter(step: OnboardingStep) {
    Text(
        text = stringResource(R.string.onboarding_step_of, step.number, OnboardingStep.COUNT),
        style = LocalCannoliTypography.current.labelSmall,
        color = Color.White,
        modifier = Modifier.padding(start = pillInternalPadding()),
    )
}

@Composable
fun OnboardingBodyText(
    text: String,
    color: Color = Color.White,
) {
    Text(
        text = text,
        style = LocalCannoliTypography.current.bodyMedium,
        color = color,
        modifier = Modifier.padding(horizontal = pillInternalPadding()),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.revealWhenFocused(isFocused: Boolean): Modifier {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(isFocused) { if (isFocused) requester.bringIntoView() }
    return bringIntoViewRequester(requester)
}

