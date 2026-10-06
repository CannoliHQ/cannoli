package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import dev.cannoli.igm.CanonicalButton
import dev.cannoli.scorza.R
import dev.cannoli.scorza.input.GlyphStyle
import dev.cannoli.scorza.input.legend.GLYPH_ORDER
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.PillRowText
import dev.cannoli.scorza.input.legend.LegendWizardState
import dev.cannoli.scorza.input.legend.MenuChord
import dev.cannoli.scorza.input.legend.WizardNotice
import dev.cannoli.scorza.input.legend.WizardSkipLine
import dev.cannoli.scorza.input.legend.WizardStep
import dev.cannoli.scorza.onboarding.OnboardingStep
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.screenInsets
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.Spacing

@Composable
fun LegendWizardScreen(
    state: LegendWizardState,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    listVerticalPadding: Dp,
    modifier: Modifier = Modifier,
    duringFirstRun: Boolean = false,
) {
    val typo = LocalCannoliTypography.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(screenInsets())
    ) {
            Column(modifier = Modifier.align(Alignment.TopStart)) {
                if (duringFirstRun) OnboardingStepCounter(OnboardingStep.WELCOME)
                ScreenTitle(
                    text = stringResource(R.string.controller_wizard_title),
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = footerReservation())
                    .widthIn(max = 480.dp),
            ) {
                val question: String? = when (state.step) {
                    WizardStep.PressConfirm -> stringResource(R.string.onboarding_welcome_press_thrice)
                    WizardStep.BackAgain -> stringResource(R.string.controller_wizard_press_back_again)
                    WizardStep.PressBack -> stringResource(R.string.controller_wizard_press_back)
                    WizardStep.PressMenu -> stringResource(R.string.controller_wizard_press_menu)
                    WizardStep.PressStart -> stringResource(R.string.controller_wizard_press_start)
                    WizardStep.Appearance -> stringResource(R.string.controller_wizard_appearance)
                    WizardStep.MenuChoice -> stringResource(R.string.controller_wizard_menu_choice)
                    WizardStep.Capture -> state.capturing?.let { button ->
                        stringResource(
                            R.string.controller_wizard_press_button,
                            dev.cannoli.scorza.util.canonicalLabel(
                                LocalContext.current,
                                button,
                                state.glyphStyle ?: GlyphStyle.REDMOND,
                            ),
                        )
                    }
                    WizardStep.Done -> null
                }
                if (question != null) {
                    PromptText(question, typo.bodyLarge, Color.White)
                }
                val notice = when (state.notice) {
                    WizardNotice.PressesDidNotMatch ->
                        stringResource(R.string.controller_wizard_mismatch)
                    WizardNotice.BackMustDifferFromConfirm ->
                        stringResource(R.string.controller_wizard_back_must_differ)
                    WizardNotice.MenuIsHoldStart ->
                        stringResource(R.string.controller_wizard_menu_is_hold_start)
                    null -> null
                }
                if (notice != null) {
                    Spacer(modifier = Modifier.height(Spacing.Md))
                    PromptText(notice, typo.bodyMedium, Color.White)
                }
                if (state.step == WizardStep.Appearance) {
                    Spacer(modifier = Modifier.height(Spacing.Lg))
                    GLYPH_ORDER.forEachIndexed { index, style ->
                        PillRowText(
                            label = dev.cannoli.scorza.util.glyphStyleName(LocalContext.current, style),
                            isSelected = index == state.appearanceIndex,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                        )
                    }
                }
                if (state.step == WizardStep.MenuChoice) {
                    Spacer(modifier = Modifier.height(Spacing.Lg))
                    state.menuChoices.forEachIndexed { index, chord ->
                        PillRowText(
                            label = stringResource(
                                when (chord) {
                                    MenuChord.SELECT_START -> R.string.controller_wizard_menu_select_start
                                    MenuChord.HOLD_START -> R.string.controller_wizard_menu_hold_start
                                }
                            ),
                            isSelected = index == state.menuChoiceIndex,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                        )
                    }
                }
                val skipLine = when (state.skipLine) {
                    WizardSkipLine.Generic -> stringResource(R.string.controller_wizard_skip)
                    WizardSkipLine.Menu -> stringResource(R.string.controller_wizard_menu_skip)
                    null -> null
                }
                if (skipLine != null) {
                    Spacer(modifier = Modifier.height(Spacing.Md))
                    PromptText(
                        text = skipLine,
                        style = typo.labelSmall,
                        color = Color.White,
                    )
                }
                if (state.step == WizardStep.PressConfirm) {
                    Spacer(modifier = Modifier.height(Spacing.Lg))
                    ProgressPips(
                        total = dev.cannoli.scorza.input.legend.CONFIRM_PRESSES_REQUIRED,
                        filled = state.confirmRunCount,
                    )
                }
            }
            val backFace = state.backFace
            val style = state.glyphStyle
            if (backFace != null && style != null) {
                BottomBar(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    leftItems = listOf(
                        dev.cannoli.scorza.util.faceGlyph(LocalContext.current, backFace, style)
                            .orEmpty() to stringResource(dev.cannoli.ui.R.string.label_back)
                    ),
                    rightItems = emptyList(),
                )
        }
    }
}

@Composable
private fun PromptText(text: String, style: TextStyle, color: Color) {
    Text(
        text = text,
        style = style.copy(
            textAlign = TextAlign.Center,
            lineBreak = androidx.compose.ui.text.style.LineBreak.Heading,
        ),
        color = color,
    )
}
