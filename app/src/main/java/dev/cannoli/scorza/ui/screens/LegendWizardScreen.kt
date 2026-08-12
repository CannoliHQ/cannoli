package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.input.legend.WizardStep
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.listTitleSpacing
import dev.cannoli.ui.components.screenInsets
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.Spacing

// First-pass literals: the app module's own strings.xml carries nothing but app_name, and every
// real launcher string lives in cannoli-ui's strings.xml, which is off-limits for this task.
private const val TITLE = "Set up your controller"
private const val PROMPT_SOUTH = "Press the bottom face button"
private const val PROMPT_PRIMARY = "Press the button labeled A"
private const val PROMPT_PRIMARY_HINT = "(or the cross on PlayStation)"
private const val PROMPT_MENU = "Press the button you'll use to open the menu"
private const val PROMPT_MENU_HINT = "(something you won't need in games)"

@Composable
fun LegendWizardScreen(
    step: WizardStep,
    modifier: Modifier = Modifier,
    backgroundImagePath: String? = null,
    backgroundTint: Int = 0,
) {
    val colors = LocalCannoliColors.current

    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(modifier = modifier.fillMaxSize().padding(screenInsets())) {
            Column(modifier = Modifier.fillMaxSize()) {
                ScreenTitle(text = TITLE, fontSize = 22.sp, lineHeight = 32.sp)
                Spacer(modifier = Modifier.height(listTitleSpacing()))
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                    ) {
                        when (step) {
                            WizardStep.PressSouth -> {
                                FaceDiamond(
                                    highlightColor = colors.highlight,
                                    dimColor = colors.text.copy(alpha = 0.25f),
                                )
                                Spacer(modifier = Modifier.height(Spacing.Lg))
                                PromptText(PROMPT_SOUTH, colors.text)
                            }
                            WizardStep.PressPrimary -> {
                                PromptText(PROMPT_PRIMARY, colors.text)
                                Spacer(modifier = Modifier.height(Spacing.Sm))
                                PromptHint(PROMPT_PRIMARY_HINT, colors.text)
                            }
                            WizardStep.PressMenu -> {
                                PromptText(PROMPT_MENU, colors.text)
                                Spacer(modifier = Modifier.height(Spacing.Sm))
                                PromptHint(PROMPT_MENU_HINT, colors.text)
                            }
                            WizardStep.Done -> {}
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptText(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 24.sp, color = color),
    )
}

@Composable
private fun PromptHint(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, color = color.copy(alpha = 0.6f)),
    )
}

// A style-neutral diamond: four dots at N/E/S/W with only the bottom one lit, since the pad's
// actual button glyphs are unknown until the wizard classifies it.
@Composable
private fun FaceDiamond(highlightColor: Color, dimColor: Color) {
    val dotSize = 20.dp
    val spacing = 14.dp
    Box(
        modifier = Modifier.size(dotSize * 3 + spacing * 2),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.align(Alignment.TopCenter).size(dotSize).background(dimColor, CircleShape))
        Box(modifier = Modifier.align(Alignment.CenterEnd).size(dotSize).background(dimColor, CircleShape))
        Box(modifier = Modifier.align(Alignment.CenterStart).size(dotSize).background(dimColor, CircleShape))
        Box(modifier = Modifier.align(Alignment.BottomCenter).size(dotSize).background(highlightColor, CircleShape))
    }
}
