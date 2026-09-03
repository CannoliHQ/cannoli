package dev.cannoli.scorza.ui.screens

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
import dev.cannoli.scorza.input.legend.WizardNotice
import dev.cannoli.scorza.input.legend.WizardStep
import dev.cannoli.scorza.onboarding.OnboardingStep
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.screenInsets
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.Spacing

/**
 * The wizard leads with why it appeared and then asks its four questions, laid out like the welcome
 * step it usually follows rather than like a list screen. The step counter is first run's, so it
 * only draws when the wizard was reached from there.
 */
@Composable
fun LegendWizardScreen(
    state: LegendWizardState,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    listVerticalPadding: Dp,
    modifier: Modifier = Modifier,
    duringFirstRun: Boolean = false,
    backgroundImagePath: String? = null,
    backgroundTint: Int = 0,
) {
    val typo = LocalCannoliTypography.current
    val colors = LocalCannoliColors.current

    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(modifier = modifier.fillMaxSize().padding(screenInsets())) {
            if (duringFirstRun) OnboardingStepCounter(OnboardingStep.WELCOME)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center).widthIn(max = 480.dp),
            ) {
                // Said once, on the question the wizard opens with, rather than repeated over
                // every one of them.
                if (state.step == WizardStep.PressConfirm) {
                    PromptText(
                        text = stringResource(R.string.controller_wizard_no_profile),
                        style = typo.bodyLarge,
                        color = colors.text.copy(alpha = 0.8f),
                    )
                    Spacer(modifier = Modifier.height(Spacing.Xl))
                }
                val question: String? = when (state.step) {
                    // The same question the welcome step asks, in the same words, because it is
                    // the same run of presses.
                    WizardStep.PressConfirm -> stringResource(R.string.onboarding_welcome_press_thrice)
                    WizardStep.BackAgain -> stringResource(R.string.controller_wizard_press_again)
                    WizardStep.PressBack -> stringResource(R.string.controller_wizard_press_back)
                    WizardStep.PressMenu -> stringResource(R.string.controller_wizard_press_menu)
                    WizardStep.PressStart -> stringResource(R.string.controller_wizard_press_start)
                    WizardStep.Appearance -> stringResource(R.string.controller_wizard_appearance)
                    // Named by what is printed on the pad, which is why this question comes after
                    // the appearance one rather than before it.
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
                    PromptText(question, typo.bodyLarge, colors.text)
                }
                val notice = when (state.notice) {
                    WizardNotice.PressesDidNotMatch ->
                        stringResource(R.string.controller_wizard_mismatch)
                    WizardNotice.BackMustDifferFromConfirm ->
                        stringResource(R.string.controller_wizard_back_must_differ)
                    null -> null
                }
                if (notice != null) {
                    Spacer(modifier = Modifier.height(Spacing.Md))
                    PromptText(notice, typo.bodyMedium, colors.accent)
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
                // Not on the layout question: that one is a pick with three answers, and a hint
                // about buttons the controller does not have says nothing there.
                if (state.canSkip && state.step == WizardStep.Capture) {
                    Spacer(modifier = Modifier.height(Spacing.Md))
                    PromptText(
                        text = stringResource(R.string.controller_wizard_skip),
                        style = typo.bodyMedium,
                        color = colors.text.copy(alpha = 0.6f),
                    )
                }
                // Undo was invisible until now: nothing on screen said back would step a question
                // returned. Drawn only once the layout is settled, because before that the glyph
                // printed on the back button is not yet known.
                val backFace = state.backFace
                val style = state.glyphStyle
                if (backFace != null && style != null) {
                    Spacer(modifier = Modifier.height(Spacing.Md))
                    BottomBar(
                        leftItems = listOf(
                            dev.cannoli.scorza.util.faceGlyph(LocalContext.current, backFace, style)
                                .orEmpty() to stringResource(dev.cannoli.ui.R.string.label_back)
                        ),
                        rightItems = emptyList(),
                    )
                }
                // Only the confirm run draws progress: it is the one question answered by several
                // presses, so it is the only one where a press with no visible effect reads as a
                // press that went missing.
                if (state.step == WizardStep.PressConfirm) {
                    Spacer(modifier = Modifier.height(Spacing.Lg))
                    ProgressPips(
                        total = dev.cannoli.scorza.input.legend.CONFIRM_PRESSES_REQUIRED,
                        filled = state.confirmRunCount,
                    )
                }
            }
        }
    }
}

@Composable
private fun PromptText(text: String, style: TextStyle, color: Color) {
    Text(text = text, style = style.copy(textAlign = TextAlign.Center), color = color)
}
