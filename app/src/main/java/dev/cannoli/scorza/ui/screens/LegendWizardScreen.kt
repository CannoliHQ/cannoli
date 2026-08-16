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
import dev.cannoli.scorza.R
import dev.cannoli.scorza.input.legend.LegendWizardState
import dev.cannoli.scorza.input.legend.WIZARD_CAPTURES
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
                val question = when (state.step) {
                    WizardStep.PressConfirm -> R.string.controller_wizard_press_confirm
                    WizardStep.ConfirmAgain, WizardStep.BackAgain -> R.string.controller_wizard_press_again
                    WizardStep.PressBack -> R.string.controller_wizard_press_back
                    WizardStep.PressMenu -> R.string.controller_wizard_press_menu
                    WizardStep.PressStart -> R.string.controller_wizard_press_start
                    WizardStep.Done -> null
                }
                if (question != null) {
                    PromptText(stringResource(question), typo.bodyLarge, colors.text)
                    // The second press is the one thing needing explanation: it is a confirmation,
                    // not the same question coming round again.
                    if (state.step == WizardStep.ConfirmAgain || state.step == WizardStep.BackAgain) {
                        Spacer(modifier = Modifier.height(Spacing.Sm))
                        PromptText(
                            text = stringResource(R.string.controller_wizard_again_detail),
                            style = typo.bodyMedium,
                            color = colors.text.copy(alpha = 0.6f),
                        )
                    }
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
                Spacer(modifier = Modifier.height(Spacing.Lg))
                ProgressPips(total = WIZARD_CAPTURES, filled = state.capturesDone)
            }
        }
    }
}

@Composable
private fun PromptText(text: String, style: TextStyle, color: Color) {
    Text(text = text, style = style.copy(textAlign = TextAlign.Center), color = color)
}
