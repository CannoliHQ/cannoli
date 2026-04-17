package dev.cannoli.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.cannoli.ui.theme.LocalCannoliColors

data class FaceLabels(val top: String, val bottom: String, val left: String, val right: String)

data class DiagramInput(
    val pressed: Set<String>,
    val leftStick: Offset,
    val rightStick: Offset,
    val leftTrigger: Float,
    val rightTrigger: Float,
)

@Composable
fun ControllerDiagram(
    input: DiagramInput,
    faceLabels: FaceLabels,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCannoliColors.current
    val textMeasurer = rememberTextMeasurer()

    BoxWithConstraints(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val unit = minOf(size.width / 80f, size.height / 44f)
            val originX = size.width / 2f - 50f * unit
            val originY = size.height / 2f - 27.5f * unit
            fun p(x: Float, y: Float) = Offset(originX + x * unit, originY + y * unit)

            val body = colors.text.copy(alpha = 0.12f)
            val outline = colors.text.copy(alpha = 0.5f)
            val highlight = colors.highlight
            val idle = colors.text.copy(alpha = 0.25f)

            drawDiagram(
                p = ::p,
                unit = unit,
                input = input,
                labels = faceLabels,
                body = body,
                outline = outline,
                highlight = highlight,
                idle = idle,
                textColor = colors.text,
                pressedTextColor = colors.highlightText,
                textMeasurer = textMeasurer,
            )
        }
    }
}

private fun DrawScope.drawDiagram(
    p: (Float, Float) -> Offset,
    unit: Float,
    input: DiagramInput,
    labels: FaceLabels,
    body: Color,
    outline: Color,
    highlight: Color,
    idle: Color,
    textColor: Color,
    pressedTextColor: Color,
    textMeasurer: TextMeasurer,
) {
    fun pressed(name: String) = name in input.pressed
    fun fill(name: String) = if (pressed(name)) highlight else idle
    fun label(name: String) = if (pressed(name)) pressedTextColor else textColor

    drawRoundRect(
        color = body,
        topLeft = p(10f, 12f),
        size = Size(80f * unit, 36f * unit),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f * unit, 8f * unit),
    )
    drawShoulderPill(p(14f, 8f), unit, "L1", fill("btn_l"), outline, label("btn_l"), textMeasurer)
    drawShoulderPill(p(26f, 8f), unit, "L2", fill("btn_l2"), outline, label("btn_l2"), textMeasurer)
    drawShoulderPill(p(64f, 8f), unit, "R2", fill("btn_r2"), outline, label("btn_r2"), textMeasurer)
    drawShoulderPill(p(76f, 8f), unit, "R1", fill("btn_r"), outline, label("btn_r"), textMeasurer)

    val dpadCenter = p(22f, 26f)
    drawDpad(dpadCenter, unit, input, idle, highlight, outline)

    val faceCenter = p(78f, 26f)
    drawFaceButtons(faceCenter, unit, input, labels, idle, highlight, outline, textColor, pressedTextColor, textMeasurer)

    drawCenterPill(p(35f, 22f), unit, "SELECT", fill("btn_select"), outline, label("btn_select"), textMeasurer)
    drawCenterPill(p(46f, 22f), unit, "MENU", fill("btn_menu"), outline, label("btn_menu"), textMeasurer)
    drawCenterPill(p(57f, 22f), unit, "START", fill("btn_start"), outline, label("btn_start"), textMeasurer)

    drawStick(p(36f, 44f), unit, input.leftStick, pressed("btn_l3"), idle, highlight, outline)
    drawStick(p(64f, 44f), unit, input.rightStick, pressed("btn_r3"), idle, highlight, outline)
}

private fun DrawScope.drawShoulderPill(
    topLeft: Offset, unit: Float, label: String,
    fill: Color, outline: Color, textColor: Color, tm: TextMeasurer,
) {
    val size = Size(10f * unit, 4f * unit)
    drawRoundRect(
        color = fill,
        topLeft = topLeft,
        size = size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * unit, 2f * unit),
    )
    drawRoundRect(
        color = outline,
        topLeft = topLeft,
        size = size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * unit, 2f * unit),
        style = Stroke(width = unit * 0.3f),
    )
    drawCenteredText(tm, label, Offset(topLeft.x + size.width / 2f, topLeft.y + size.height / 2f), textColor, (unit * 1.6f).sp)
}

private fun DrawScope.drawCenterPill(
    topLeft: Offset, unit: Float, label: String,
    fill: Color, outline: Color, textColor: Color, tm: TextMeasurer,
) {
    val size = Size(10f * unit, 3.5f * unit)
    drawRoundRect(
        color = fill, topLeft = topLeft, size = size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.75f * unit, 1.75f * unit),
    )
    drawRoundRect(
        color = outline, topLeft = topLeft, size = size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.75f * unit, 1.75f * unit),
        style = Stroke(width = unit * 0.25f),
    )
    drawCenteredText(tm, label, Offset(topLeft.x + size.width / 2f, topLeft.y + size.height / 2f), textColor, (unit * 1.0f).sp)
}

private fun DrawScope.drawDpad(
    center: Offset, unit: Float, input: DiagramInput,
    idle: Color, highlight: Color, outline: Color,
) {
    val armLen = 4f * unit
    val thickness = 3f * unit
    val hub = thickness
    fun arm(name: String, dirX: Int, dirY: Int) {
        val pressed = name in input.pressed
        val color = if (pressed) highlight else idle
        val tl: Offset
        val sz: Size
        if (dirX != 0) {
            val x = center.x + dirX * (hub / 2f) + (if (dirX < 0) -armLen else 0f)
            val y = center.y - thickness / 2f
            tl = Offset(x, y)
            sz = Size(armLen, thickness)
        } else {
            val x = center.x - thickness / 2f
            val y = center.y + dirY * (hub / 2f) + (if (dirY < 0) -armLen else 0f)
            tl = Offset(x, y)
            sz = Size(thickness, armLen)
        }
        drawRect(color = color, topLeft = tl, size = sz)
    }
    arm("btn_left", -1, 0)
    arm("btn_right", 1, 0)
    arm("btn_up", 0, -1)
    arm("btn_down", 0, 1)
    drawRect(
        color = idle,
        topLeft = Offset(center.x - hub / 2f, center.y - hub / 2f),
        size = Size(hub, hub),
    )
}

private fun DrawScope.drawFaceButtons(
    center: Offset, unit: Float, input: DiagramInput, labels: FaceLabels,
    idle: Color, highlight: Color, outline: Color,
    textColor: Color, pressedTextColor: Color, tm: TextMeasurer,
) {
    val r = 3f * unit
    val offset = 5f * unit
    fun face(name: String, label: String, dx: Float, dy: Float) {
        val pressed = name in input.pressed
        val c = Offset(center.x + dx, center.y + dy)
        val labelColor = if (pressed) pressedTextColor else textColor
        drawCircle(color = if (pressed) highlight else idle, radius = r, center = c)
        drawCircle(color = outline, radius = r, center = c, style = Stroke(width = unit * 0.3f))
        drawFaceLabel(label, c, unit, labelColor, tm)
    }
    face("btn_north", labels.top,    0f, -offset)
    face("btn_south", labels.bottom, 0f,  offset)
    face("btn_west",  labels.left,  -offset, 0f)
    face("btn_east",  labels.right,  offset, 0f)
}

private fun DrawScope.drawFaceLabel(
    label: String,
    center: Offset,
    unit: Float,
    color: Color,
    tm: TextMeasurer,
) {
    val glyphRadius = unit * 1.3f
    val stroke = Stroke(width = unit * 0.35f)
    when (label) {
        "△" -> {
            val path = Path().apply {
                moveTo(center.x, center.y - glyphRadius)
                lineTo(center.x - glyphRadius * 0.866f, center.y + glyphRadius * 0.5f)
                lineTo(center.x + glyphRadius * 0.866f, center.y + glyphRadius * 0.5f)
                close()
            }
            drawPath(path, color = color, style = stroke)
        }
        "○" -> {
            drawCircle(color = color, radius = glyphRadius, center = center, style = stroke)
        }
        "✕" -> {
            val d = glyphRadius * 0.85f
            drawLine(color, Offset(center.x - d, center.y - d), Offset(center.x + d, center.y + d), strokeWidth = unit * 0.35f)
            drawLine(color, Offset(center.x + d, center.y - d), Offset(center.x - d, center.y + d), strokeWidth = unit * 0.35f)
        }
        "□" -> {
            val s = glyphRadius * 1.5f
            drawRect(
                color = color,
                topLeft = Offset(center.x - s / 2f, center.y - s / 2f),
                size = Size(s, s),
                style = stroke,
            )
        }
        else -> drawCenteredText(tm, label, center, color, (unit * 1.6f).sp)
    }
}

private fun DrawScope.drawStick(
    center: Offset, unit: Float, value: Offset, clicked: Boolean,
    idle: Color, highlight: Color, outline: Color,
) {
    val r = 5f * unit
    drawCircle(color = if (clicked) highlight else idle, radius = r, center = center)
    drawCircle(color = outline, radius = r, center = center, style = Stroke(width = unit * 0.3f))
    val dotCenter = Offset(
        center.x + value.x.coerceIn(-1f, 1f) * (r - unit * 0.8f),
        center.y + value.y.coerceIn(-1f, 1f) * (r - unit * 0.8f),
    )
    drawCircle(color = highlight, radius = unit * 0.9f, center = dotCenter)
}

private fun DrawScope.drawCenteredText(
    tm: TextMeasurer, text: String, center: Offset, color: Color, size: TextUnit,
) {
    val layout = tm.measure(text = text, style = TextStyle(color = color, fontSize = size))
    val topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f)
    drawText(layout, color = color, topLeft = topLeft)
}
