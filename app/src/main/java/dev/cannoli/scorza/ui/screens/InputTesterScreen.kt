package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cannoli.igm.ui.theme.ErrorHighlight
import dev.cannoli.igm.ui.theme.Spacing
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import dev.cannoli.igm.ui.theme.LocalCannoliColors
import dev.cannoli.igm.ButtonStyle
import dev.cannoli.scorza.R
import dev.cannoli.scorza.ui.components.ControllerDiagram
import dev.cannoli.scorza.ui.components.DiagramInput
import dev.cannoli.scorza.ui.components.FaceLabels
import dev.cannoli.scorza.ui.viewmodel.EventLogEntry
import dev.cannoli.scorza.ui.viewmodel.InputTesterViewModel

@Composable
fun InputTesterScreen(
    viewModel: InputTesterViewModel,
    buttonStyle: ButtonStyle,
    onExit: () -> Unit,
) {
    val uiState by viewModel.state.collectAsState()
    val colors = LocalCannoliColors.current

    DisposableEffect(Unit) {
        onDispose { viewModel.reset() }
    }

    LaunchedEffect(uiState.exitRequested) {
        if (uiState.exitRequested) onExit()
    }

    val faceLabels = remember(buttonStyle.labelSet) {
        val ls = buttonStyle.labelSet
        FaceLabels(top = ls.north, bottom = ls.south, left = ls.west, right = ls.east)
    }

    val activePortState = uiState.portStates[uiState.activePort]
    val diagramInput = DiagramInput(
        pressed = activePortState?.pressedButtons ?: emptySet(),
        leftStick = activePortState?.leftStick ?: androidx.compose.ui.geometry.Offset.Zero,
        rightStick = activePortState?.rightStick ?: androidx.compose.ui.geometry.Offset.Zero,
        leftTrigger = activePortState?.leftTrigger ?: 0f,
        rightTrigger = activePortState?.rightTrigger ?: 0f,
    )

    val noneText = stringResource(R.string.input_tester_none)
    val deviceInfo = uiState.lastEventDevice
        ?: uiState.connectedPorts.firstOrNull { it.port == uiState.activePort }
        ?: uiState.connectedPorts.firstOrNull()
    val deviceLabel = deviceInfo?.let {
        stringResource(R.string.input_tester_device_format, it.port + 1, it.name, it.deviceId)
    } ?: noneText
    val profileLabel = stringResource(
        R.string.input_tester_profile_label,
        uiState.selectedProfile.ifEmpty { noneText },
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(20.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Header(
                activeDevice = deviceLabel,
                profileLabel = profileLabel,
                highlight = colors.highlight,
                text = colors.text,
                highlightText = colors.highlightText,
            )
            Spacer(Modifier.height(Spacing.Sm))
            Row(modifier = Modifier.fillMaxWidth().weight(1f, fill = true)) {
                Box(modifier = Modifier.fillMaxHeight().weight(1f, fill = true)) {
                    ControllerDiagram(
                        input = diagramInput,
                        faceLabels = faceLabels,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (uiState.eventLog.isNotEmpty()) {
                    Spacer(Modifier.width(12.dp))
                    EventLog(
                        entries = uiState.eventLog,
                        textColor = colors.text,
                    )
                }
            }
            Spacer(Modifier.height(Spacing.Sm))
            Text(
                text = stringResource(R.string.input_tester_cycle_hint),
                color = colors.text.copy(alpha = 0.6f),
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Text(
                text = stringResource(R.string.input_tester_exit_hint),
                color = colors.text.copy(alpha = 0.6f),
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Header(
    activeDevice: String,
    profileLabel: String,
    highlight: Color,
    text: Color,
    highlightText: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(highlight)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(text = activeDevice, color = highlightText, fontSize = 14.sp)
        }
        Spacer(Modifier.fillMaxWidth().weight(1f, fill = true))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.08f))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(text = profileLabel, color = text, fontSize = 14.sp)
        }
    }
}

@Composable
private fun EventLog(
    entries: List<EventLogEntry>,
    textColor: Color,
) {
    Box(
        modifier = Modifier
            .width(160.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column {
            entries.forEach { e ->
                val color = if (e.resolvedButton == null) ErrorHighlight else textColor
                val arrow = if (e.isDown) "↓" else "↑"
                Text(
                    text = "$arrow ${e.keyName} (${e.keyCode})",
                    color = color,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
