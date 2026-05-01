package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.DeviceMapping
import dev.cannoli.scorza.input.v2.GlyphStyle
import dev.cannoli.scorza.input.v2.HatDirection
import dev.cannoli.scorza.input.v2.InputBinding
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.ELLIPSIS
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.Radius
import dev.cannoli.ui.theme.Spacing

private val PROTECTED_CANONICALS = setOf(
    CanonicalButton.BTN_UP, CanonicalButton.BTN_DOWN,
    CanonicalButton.BTN_LEFT, CanonicalButton.BTN_RIGHT,
    CanonicalButton.BTN_SOUTH, CanonicalButton.BTN_EAST,
)

@Composable
fun EditButtonsScreen(
    screen: LauncherScreen.EditButtons,
    mapping: DeviceMapping?,
    modifier: Modifier = Modifier,
    backgroundImagePath: String? = null,
    backgroundTint: Int = 0,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 8.dp,
    buttonStyle: ButtonStyle = ButtonStyle(),
    listenTimeoutMs: Int = 5000,
) {
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    val colors = LocalCannoliColors.current
    val isListening = screen.listeningCanonical != null

    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(screenPadding)
        ) {
            if (mapping == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = footerReservation())
                ) {
                    ScreenTitle(
                        text = stringResource(R.string.controllers_edit_buttons),
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                    )
                    Spacer(modifier = Modifier.height(Spacing.Sm))
                    Box(modifier = Modifier.padding(start = 14.dp)) {
                        Text(
                            text = stringResource(R.string.controllers_mapping_not_found),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                color = colors.text.copy(alpha = 0.6f),
                            )
                        )
                    }
                }
                BottomBar(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    leftItems = listOf(buttonStyle.back to stringResource(R.string.label_back)),
                    rightItems = emptyList()
                )
                return@ScreenBackground
            }

            val entries = CanonicalButton.entries.toList()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = footerReservation())
            ) {
                ScreenTitle(
                    text = mapping.displayName,
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                )
                Spacer(modifier = Modifier.height(Spacing.Sm))
                List(
                    items = entries,
                    selectedIndex = screen.selectedIndex.coerceIn(0, entries.size - 1),
                    itemHeight = itemHeight,
                    scrollTarget = screen.scrollTarget,
                ) { _, canonical, isSelected ->
                    val rowListening = screen.listeningCanonical == canonical
                    val bindings = mapping.bindings[canonical].orEmpty()
                    val value = when {
                        rowListening -> ELLIPSIS
                        bindings.isEmpty() -> stringResource(R.string.controllers_not_bound)
                        else -> formatBindings(bindings)
                    }
                    PillRowKeyValue(
                        label = friendlyCanonicalLabel(canonical, mapping.glyphStyle),
                        value = value,
                        isSelected = isSelected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding,
                    )
                }
            }

            val selectedCanonical = entries.getOrNull(screen.selectedIndex.coerceIn(0, entries.size - 1))
            val canUnmap = !isListening && selectedCanonical != null
                && selectedCanonical !in PROTECTED_CANONICALS
                && !mapping.bindings[selectedCanonical].isNullOrEmpty()
            val bottomLeft = if (isListening) emptyList()
                else listOf(buttonStyle.back to stringResource(R.string.label_back))
            val bottomRight = if (isListening) {
                emptyList()
            } else {
                buildList {
                    if (canUnmap) add(buttonStyle.north to stringResource(R.string.label_unmap))
                    add(buttonStyle.confirm to stringResource(R.string.label_remap))
                }
            }

            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = bottomLeft,
                rightItems = bottomRight,
            )
        }

        if (isListening) {
            val listening = screen.listeningCanonical
            val remaining = (listenTimeoutMs - screen.countdownMs).coerceAtLeast(0)
            val progress = if (listenTimeoutMs > 0) {
                (remaining.toFloat() / listenTimeoutMs.toFloat()).coerceIn(0f, 1f)
            } else 0f

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                ) {
                    val targetLabel2 = listening?.let { friendlyCanonicalLabel(it, mapping?.glyphStyle ?: GlyphStyle.PLUMBER) }.orEmpty()
                    Text(
                        text = targetLabel2,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 24.sp,
                            color = colors.text,
                        ),
                    )
                    Spacer(modifier = Modifier.height(Spacing.Sm))
                    Text(
                        text = stringResource(R.string.press_button_prompt),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 16.sp,
                            color = colors.text.copy(alpha = 0.6f),
                        ),
                    )
                    Spacer(modifier = Modifier.height(Spacing.Lg))
                    Box(
                        modifier = Modifier
                            .widthIn(max = 280.dp).fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(Radius.Sm))
                            .background(colors.text.copy(alpha = 0.2f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .height(8.dp)
                                .clip(RoundedCornerShape(Radius.Sm))
                                .background(colors.highlight),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun friendlyCanonicalLabel(button: CanonicalButton, style: GlyphStyle): String {
    val face = when (button) {
        CanonicalButton.BTN_SOUTH -> R.string.canonical_south to when (style) {
            GlyphStyle.PLUMBER -> R.string.glyph_plumber_south
            GlyphStyle.REDMOND -> R.string.glyph_redmond_south
            GlyphStyle.SHAPES -> R.string.glyph_shapes_south
        }
        CanonicalButton.BTN_EAST -> R.string.canonical_east to when (style) {
            GlyphStyle.PLUMBER -> R.string.glyph_plumber_east
            GlyphStyle.REDMOND -> R.string.glyph_redmond_east
            GlyphStyle.SHAPES -> R.string.glyph_shapes_east
        }
        CanonicalButton.BTN_WEST -> R.string.canonical_west to when (style) {
            GlyphStyle.PLUMBER -> R.string.glyph_plumber_west
            GlyphStyle.REDMOND -> R.string.glyph_redmond_west
            GlyphStyle.SHAPES -> R.string.glyph_shapes_west
        }
        CanonicalButton.BTN_NORTH -> R.string.canonical_north to when (style) {
            GlyphStyle.PLUMBER -> R.string.glyph_plumber_north
            GlyphStyle.REDMOND -> R.string.glyph_redmond_north
            GlyphStyle.SHAPES -> R.string.glyph_shapes_north
        }
        else -> null
    }
    if (face != null) {
        val (cardinalRes, glyphRes) = face
        return stringResource(R.string.canonical_face_with_glyph, stringResource(cardinalRes), stringResource(glyphRes))
    }
    val res = when (button) {
        CanonicalButton.BTN_UP -> R.string.canonical_dpad_up
        CanonicalButton.BTN_DOWN -> R.string.canonical_dpad_down
        CanonicalButton.BTN_LEFT -> R.string.canonical_dpad_left
        CanonicalButton.BTN_RIGHT -> R.string.canonical_dpad_right
        CanonicalButton.BTN_L -> R.string.canonical_l1
        CanonicalButton.BTN_R -> R.string.canonical_r1
        CanonicalButton.BTN_L2 -> R.string.canonical_l2
        CanonicalButton.BTN_R2 -> R.string.canonical_r2
        CanonicalButton.BTN_L3 -> R.string.canonical_l3
        CanonicalButton.BTN_R3 -> R.string.canonical_r3
        CanonicalButton.BTN_START -> R.string.canonical_start
        CanonicalButton.BTN_SELECT -> R.string.canonical_select
        CanonicalButton.BTN_MENU -> R.string.canonical_menu
        else -> return button.name
    }
    return stringResource(res)
}

@Composable
private fun formatBindings(bindings: List<InputBinding>): String =
    bindings.map { formatBinding(it) }.joinToString(" / ")

@Composable
private fun formatBinding(b: InputBinding): String = when (b) {
    is InputBinding.Button -> stringResource(R.string.binding_key, b.keyCode)
    is InputBinding.Hat -> stringResource(R.string.binding_hat, hatArrow(b.direction))
    is InputBinding.Axis -> if (b.activeMax < 0)
        stringResource(R.string.binding_axis_neg, b.axis)
    else
        stringResource(R.string.binding_axis_pos, b.axis)
}

@Composable
private fun hatArrow(direction: HatDirection): String {
    val res = when (direction) {
        HatDirection.UP -> R.string.hat_up
        HatDirection.DOWN -> R.string.hat_down
        HatDirection.LEFT -> R.string.hat_left
        HatDirection.RIGHT -> R.string.hat_right
    }
    return stringResource(res)
}
