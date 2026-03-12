package dev.cannoli.scorza.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.ui.theme.LocalCannoliColors
import kotlinx.coroutines.delay

@Composable
fun ScreenTitle(
    text: String,
    fontSize: TextUnit,
    lineHeight: TextUnit
) {
    val scrollState = rememberScrollState()

    LaunchedEffect(text) {
        scrollState.scrollTo(0)
        delay(800)
        while (true) {
            val max = scrollState.maxValue
            if (max <= 0) break
            val duration = (max * 4).coerceIn(500, 8000)
            scrollState.animateScrollTo(
                max,
                animationSpec = tween(durationMillis = duration, easing = LinearEasing)
            )
            delay(800)
            scrollState.animateScrollTo(
                0,
                animationSpec = tween(durationMillis = duration, easing = LinearEasing)
            )
            delay(800)
        }
    }

    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(
            fontSize = fontSize * 1.1f,
            lineHeight = lineHeight * 1.1f
        ),
        color = LocalCannoliColors.current.text,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .padding(start = pillInternalH)
            .horizontalScroll(scrollState)
    )
}
