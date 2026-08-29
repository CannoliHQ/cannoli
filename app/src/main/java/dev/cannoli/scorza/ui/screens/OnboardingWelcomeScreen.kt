package dev.cannoli.scorza.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.legend.CONFIRM_PRESSES_REQUIRED
import dev.cannoli.scorza.input.legend.CONFIRM_RUN_FADE_MS
import dev.cannoli.scorza.input.legend.CONFIRM_RUN_TIMEOUT_MS
import dev.cannoli.scorza.onboarding.OnboardingStep
import dev.cannoli.ui.components.screenInsets
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * The one first-run step with no rows, so it centres its content instead of borrowing the list
 * layout the other two need. It keeps [OnboardingStepCounter] so the three steps still read as one
 * flow, and carries no footer: the run of presses the pips count is the only action. The instruction
 * names no button, because the same label sits at a different position on each controller family.
 */
@Composable
fun OnboardingWelcomeScreen(
    mapping: DeviceMapping?,
    confirmPresses: Int = 0,
    onRunExpired: () -> Unit = {},
) {
    val typo = LocalCannoliTypography.current
    val colors = LocalCannoliColors.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(screenInsets())
    ) {
        OnboardingStepCounter(OnboardingStep.WELCOME)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center).widthIn(max = 480.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.logo),
                contentDescription = null,
                modifier = Modifier.size(128.dp),
            )
            Spacer(modifier = Modifier.height(Spacing.Xl))
            Text(
                text = stringResource(R.string.onboarding_welcome_greeting),
                style = typo.bodyLarge.copy(textAlign = TextAlign.Center),
                color = colors.text.copy(alpha = 0.8f),
            )
            Spacer(modifier = Modifier.height(Spacing.Md))
            Text(
                text = if (mapping == null) {
                    stringResource(R.string.onboarding_welcome_no_controller)
                } else {
                    stringResource(R.string.onboarding_welcome_press_thrice)
                },
                style = typo.bodyLarge.copy(textAlign = TextAlign.Center),
                color = colors.text,
            )
            if (mapping != null) {
                Spacer(modifier = Modifier.height(Spacing.Lg))
                ConfirmPressPips(confirmPresses = confirmPresses, onRunExpired = onRunExpired)
            }
        }
    }
}

/**
 * The run's progress, and the only place in Cannoli that animates anything beyond the selection pill
 * and artwork: a sanctioned exception, granted 2026-08-16, so the pips drain rather than vanish when
 * a run expires. Do not remove it while enforcing the no-animations rule.
 *
 * Filling stays instant. The run is over the moment the fade starts, so a press arriving during it
 * cancels the fade, restores full opacity and begins a fresh run rather than joining a disappearing
 * one. [pipsFading] is what the tail draws, held separately because the count is already back at
 * zero by then.
 */
@Composable
private fun ConfirmPressPips(confirmPresses: Int, onRunExpired: () -> Unit) {
    var pipsFading by remember { mutableIntStateOf(0) }
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(confirmPresses) {
        if (confirmPresses == 0) return@LaunchedEffect
        pipsFading = 0
        alpha.snapTo(1f)
        delay(CONFIRM_RUN_TIMEOUT_MS)
        pipsFading = confirmPresses
        onRunExpired()
    }

    // Leaving the step ends the run with it, so returning later never resumes a stale one.
    DisposableEffect(Unit) {
        onDispose { onRunExpired() }
    }

    // Keyed on the snapshot rather than the count, so expiring the run cannot cancel its own tail.
    LaunchedEffect(pipsFading) {
        if (pipsFading == 0) return@LaunchedEffect
        alpha.animateTo(0f, tween(durationMillis = CONFIRM_RUN_FADE_MS, easing = LinearEasing))
        pipsFading = 0
        alpha.snapTo(1f)
    }

    ProgressPips(
        total = CONFIRM_PRESSES_REQUIRED,
        filled = if (pipsFading > 0) pipsFading else confirmPresses,
        alpha = alpha.value,
    )
}
