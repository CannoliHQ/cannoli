package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import dev.cannoli.ui.BULLET
import dev.cannoli.ui.CIRCLE_EMPTY
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.Spacing

/**
 * How far through a run of presses or a run of questions the user is. Shared by first run's welcome
 * step and the controller wizard so both read the same. [alpha] applies to the filled pips only,
 * for the welcome step's sanctioned fade; everything else leaves it alone.
 */
@Composable
fun ProgressPips(total: Int, filled: Int, modifier: Modifier = Modifier, alpha: Float = 1f) {
    val typo = LocalCannoliTypography.current
    val colors = LocalCannoliColors.current
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
        repeat(total) { index ->
            val isFilled = index < filled
            Text(
                text = if (isFilled) BULLET else CIRCLE_EMPTY,
                style = typo.bodyLarge,
                color = if (isFilled) colors.highlight else colors.text.copy(alpha = 0.4f),
                modifier = if (isFilled) Modifier.alpha(alpha) else Modifier,
            )
        }
    }
}
