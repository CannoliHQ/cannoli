package dev.cannoli.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.delay

class OsdController(
    val defaultDurationMs: Long = 3000L,
    val defaultPosition: OsdPosition = OsdPosition.TopCenter,
) {
    private val _request = mutableStateOf<Request?>(null)
    val request: State<Request?> = _request

    data class Request(
        val message: String,
        val position: OsdPosition,
        val durationMs: Long,
        val nonce: Int,
    )

    private var counter = 0

    fun show(
        message: String,
        position: OsdPosition = defaultPosition,
        durationMs: Long = defaultDurationMs,
    ) {
        counter += 1
        _request.value = Request(message, position, durationMs, counter)
    }

    fun clear() {
        _request.value = null
    }
}

@Composable
fun BoxScope.OsdHost(controller: OsdController) =
    OsdHost(controller) { message -> OsdPillText(message, OsdPillStyle.Text.fontSize) }

@Composable
fun BoxScope.OsdHost(
    controller: OsdController,
    content: @Composable androidx.compose.foundation.layout.RowScope.(message: String) -> Unit,
) {
    val current = controller.request.value
    LaunchedEffect(current) {
        if (current != null) {
            delay(current.durationMs)
            if (controller.request.value?.nonce == current.nonce) controller.clear()
        }
    }
    if (current != null) OsdPill(position = current.position) { content(current.message) }
}
