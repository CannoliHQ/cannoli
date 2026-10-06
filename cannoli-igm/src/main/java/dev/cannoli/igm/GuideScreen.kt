package dev.cannoli.igm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.ui.MENU_GLYPH
import androidx.compose.foundation.layout.RowScope
import dev.cannoli.ui.components.OsdPillStyle
import dev.cannoli.ui.components.OsdPillText
import dev.cannoli.ui.theme.LocalMenuGlyph
import dev.cannoli.ui.theme.glyphs
import dev.cannoli.ui.components.HelpEntry
import dev.cannoli.ui.components.HelpGlyph
import dev.cannoli.ui.components.HelpGroup
import dev.cannoli.ui.components.OsdController
import dev.cannoli.ui.components.OsdHost
import dev.cannoli.ui.components.OsdPosition
import dev.cannoli.ui.components.ScreenBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.io.File

private const val SCROLL_SPEED = 14f
private const val FRAME_MS = 16L

private const val TILE_OVERSCAN = 256

private const val DEFAULT_PAGE_ASPECT = 1.4f

private data class TileKey(val page: Int, val contentWidth: Int, val contentHeight: Int)

private const val HINT_MS = 3000L
private const val STATE_MS = 1500L

@Composable
fun guideHelpHint(): String = "$MENU_GLYPH ${stringResource(dev.cannoli.ui.R.string.label_help)}"

@Composable
fun RowScope.GuideOsdText(message: String, helpHint: String?) {
    val menu = LocalMenuGlyph.current.glyphs().joinToString(" + ")
    val text = if (message == helpHint && message.startsWith(MENU_GLYPH)) {
        menu + message.removePrefix(MENU_GLYPH)
    } else {
        message
    }
    OsdPillText(text, OsdPillStyle.Text.fontSize)
}

fun guideHelpGroups(guideType: GuideType): List<HelpGroup> {
    val shoulders = if (guideType == GuideType.PDF) {
        dev.cannoli.ui.R.string.guide_help_page
    } else {
        dev.cannoli.ui.R.string.guide_help_jump
    }
    val view = buildList {
        add(HelpEntry(listOf(HelpGlyph.NORTH), dev.cannoli.ui.R.string.guide_help_zoom_in))
        add(HelpEntry(listOf(HelpGlyph.WEST), dev.cannoli.ui.R.string.guide_help_zoom_out))
        if (guideType != GuideType.TXT) {
            add(HelpEntry(listOf(HelpGlyph.DPAD), dev.cannoli.ui.R.string.guide_help_pan))
        }
        add(HelpEntry(listOf(HelpGlyph.BACK), dev.cannoli.ui.R.string.guide_help_close))
    }
    return listOf(
        HelpGroup(
            dev.cannoli.ui.R.string.guide_help_group_read,
            listOf(
                HelpEntry(listOf(HelpGlyph.DPAD), dev.cannoli.ui.R.string.guide_help_scroll),
                HelpEntry(listOf(HelpGlyph.L1, HelpGlyph.R1), shoulders),
            )
        ),
        HelpGroup(dev.cannoli.ui.R.string.guide_help_group_view, view),
    )
}

@Composable
fun GuideScreen(
    filePath: String,
    guideType: GuideType,
    page: Int,
    initialScrollY: Int,
    initialScrollX: Int,
    scrollDir: Int,
    scrollXDir: Int,
    pageJump: Int,
    pageJumpDir: Int,
    pageCount: Int,
    textZoom: Int,
    onScrollPosChanged: (y: Int, x: Int) -> Unit,
    onZoomLevelChanged: (Int) -> Unit = {},
    onPageStep: (Int) -> Unit = {},
    onTapped: () -> Unit = {},
    pageLabel: String = "%d / %d",
    helpHint: String? = null,
) {
    val zoomIndex = (textZoom - 1).coerceIn(0, GuideZoom.pdfScales.lastIndex)

    val inset = if (guideType == GuideType.PDF) 0.dp else 12.dp

    val osd = remember { OsdController(defaultDurationMs = HINT_MS, defaultPosition = OsdPosition.TopEnd) }

    LaunchedEffect(filePath) { helpHint?.let { osd.show(it) } }

    var shownPage by remember(filePath) { mutableIntStateOf(page) }
    LaunchedEffect(page) {
        if (page != shownPage) {
            shownPage = page
            if (pageCount > 0) osd.show(String.format(pageLabel, page + 1, pageCount), durationMs = STATE_MS)
        }
    }

    ScreenBackground(backgroundImagePath = null, backgroundAlpha = 1f, backgroundColor = Color.Black) {
        Box(modifier = Modifier.fillMaxSize().padding(inset)) {
            when (guideType) {
                GuideType.PDF -> PdfContent(
                    filePath = filePath,
                    page = page,
                    scale = GuideZoom.pdfScales[zoomIndex],
                    textZoom = textZoom,
                    initialScrollY = initialScrollY,
                    initialScrollX = initialScrollX,
                    scrollDir = scrollDir,
                    scrollXDir = scrollXDir,
                    onScrollPosChanged = onScrollPosChanged,
                    onZoomLevelChanged = onZoomLevelChanged,
                    onPageStep = onPageStep,
                    onTapped = onTapped,
                )
                GuideType.TXT -> TxtContent(
                    filePath = filePath,
                    initialScrollY = initialScrollY,
                    scrollDir = scrollDir,
                    pageJump = pageJump,
                    pageJumpDir = pageJumpDir,
                    fontSize = GuideZoom.txtFontSizes[zoomIndex],
                    textZoom = textZoom,
                    onScrollPosChanged = onScrollPosChanged,
                    onZoomLevelChanged = onZoomLevelChanged,
                    onPageStep = onPageStep,
                    onTapped = onTapped,
                )
                GuideType.IMAGE -> ImageContent(
                    filePath = filePath,
                    initialScrollY = initialScrollY,
                    initialScrollX = initialScrollX,
                    scrollDir = scrollDir,
                    scrollXDir = scrollXDir,
                    pageJump = pageJump,
                    pageJumpDir = pageJumpDir,
                    scale = GuideZoom.pdfScales[zoomIndex],
                    textZoom = textZoom,
                    onScrollPosChanged = onScrollPosChanged,
                    onZoomLevelChanged = onZoomLevelChanged,
                    onPageStep = onPageStep,
                    onTapped = onTapped,
                )
            }

            OsdHost(osd) { message -> GuideOsdText(message, helpHint) }
        }
    }
}

@Composable
private fun PdfContent(
    filePath: String, page: Int, scale: Float, textZoom: Int,
    initialScrollY: Int, initialScrollX: Int,
    scrollDir: Int, scrollXDir: Int,
    onScrollPosChanged: (Int, Int) -> Unit,
    onZoomLevelChanged: (Int) -> Unit,
    onPageStep: (Int) -> Unit,
    onTapped: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var renderer by remember { mutableStateOf<PdfTileRenderer?>(null) }
    var aspect by remember { mutableFloatStateOf(DEFAULT_PAGE_ASPECT) }
    var tile by remember { mutableStateOf<Bitmap?>(null) }
    var tileX by remember { mutableIntStateOf(0) }
    var tileY by remember { mutableIntStateOf(0) }
    var tileKey by remember { mutableStateOf<TileKey?>(null) }
    val scrollState = remember(initialScrollY) { ScrollState(initialScrollY) }
    val hScrollState = remember(initialScrollX) { ScrollState(initialScrollX) }

    LaunchedEffect(scrollDir) {
        while (scrollDir != 0) {
            scrollState.dispatchRawDelta(scrollDir * SCROLL_SPEED)
            delay(FRAME_MS)
        }
    }
    LaunchedEffect(scrollXDir) {
        while (scrollXDir != 0) {
            hScrollState.dispatchRawDelta(scrollXDir * SCROLL_SPEED)
            delay(FRAME_MS)
        }
    }
    val currentOnScrollPosChanged by rememberUpdatedState(onScrollPosChanged)
    LaunchedEffect(scrollState, hScrollState) {
        snapshotFlow { scrollState.value to hScrollState.value }
            .collect { (y, x) -> currentOnScrollPosChanged(y, x) }
    }

    LaunchedEffect(filePath) {
        val file = File(filePath)
        renderer = if (file.exists()) PdfTileRenderer.open(context, file) else null
    }

    DisposableEffect(filePath) {
        onDispose {
            tile = null
            renderer?.close()
            renderer = null
        }
    }

    LaunchedEffect(renderer, page) {
        val r = renderer ?: return@LaunchedEffect
        if (page < 0 || page >= r.pageCount) return@LaunchedEffect
        aspect = runCatching { r.aspectOf(page) }.getOrDefault(DEFAULT_PAGE_ASPECT)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .guideGestures(
                guideType = GuideType.PDF,
                textZoom = textZoom,
                onZoomLevelChanged = onZoomLevelChanged,
                onPageStep = onPageStep,
                onTapped = onTapped,
            )
    ) {
        val viewportW = with(density) { maxWidth.roundToPx() }
        val viewportH = with(density) { maxHeight.roundToPx() }
        val contentW = (viewportW * scale).toInt()
        val contentH = (contentW * aspect).toInt()

        LaunchedEffect(renderer, page, contentW, contentH, viewportW, viewportH) {
            val key = TileKey(page, contentW, contentH)
            snapshotFlow { scrollState.value to hScrollState.value }.collectLatest { (y, x) ->
                val r = renderer ?: return@collectLatest
                if (contentW <= 0 || contentH <= 0) return@collectLatest
                if (page < 0 || page >= r.pageCount) return@collectLatest
                val current = tile
                val covers = current != null && tileKey == key &&
                    x >= tileX && y >= tileY &&
                    x + viewportW <= tileX + current.width &&
                    y + viewportH <= tileY + current.height
                if (covers) return@collectLatest

                val wanted = minOf(contentW, viewportW + TILE_OVERSCAN * 2)
                val tallWanted = minOf(contentH, viewportH + TILE_OVERSCAN * 2)
                val originX = (x - TILE_OVERSCAN).coerceIn(0, maxOf(0, contentW - wanted))
                val originY = (y - TILE_OVERSCAN).coerceIn(0, maxOf(0, contentH - tallWanted))
                val next = runCatching {
                    r.renderTile(page, contentW, contentH, originX, originY, wanted, tallWanted)
                }.getOrNull() ?: return@collectLatest

                // The outgoing tile is dropped rather than recycled: a pan swaps tiles often, and a
                // frame still drawing the old one would take a recycled bitmap and crash.
                tileX = originX
                tileY = originY
                tileKey = key
                tile = next
            }
        }

        Box(
            modifier = Modifier
                .horizontalScroll(hScrollState)
                .verticalScroll(scrollState)
        ) {
            Box(
                modifier = Modifier.size(
                    with(density) { contentW.toDp() },
                    with(density) { contentH.toDp() },
                )
            ) {
                tile?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .offset { IntOffset(tileX, tileY) }
                            .size(
                                with(density) { bmp.width.toDp() },
                                with(density) { bmp.height.toDp() },
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun TxtContent(
    filePath: String, initialScrollY: Int, scrollDir: Int,
    pageJump: Int, pageJumpDir: Int,
    fontSize: Int, textZoom: Int, onScrollPosChanged: (Int, Int) -> Unit,
    onZoomLevelChanged: (Int) -> Unit,
    onPageStep: (Int) -> Unit,
    onTapped: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val scrollState = remember(initialScrollY) { ScrollState(initialScrollY) }
    var viewportHeight by remember { mutableStateOf(0) }

    LaunchedEffect(scrollDir) {
        while (scrollDir != 0) {
            scrollState.dispatchRawDelta(scrollDir * SCROLL_SPEED)
            delay(FRAME_MS)
        }
    }
    LaunchedEffect(pageJump) {
        if (pageJump > 0 && viewportHeight > 0) {
            scrollState.animateScrollTo(
                (scrollState.value + pageJumpDir * viewportHeight).coerceIn(0, scrollState.maxValue)
            )
        }
    }
    val currentOnScrollPosChanged by rememberUpdatedState(onScrollPosChanged)
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value }.collect { currentOnScrollPosChanged(it, 0) }
    }

    LaunchedEffect(filePath) {
        val file = File(filePath)
        if (file.exists()) text = file.readText()
    }

    if (text.isNotEmpty()) {
        Text(
            text = text,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize.sp,
                color = Color.White,
                lineHeight = (fontSize * 1.5).sp
            ),
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { viewportHeight = it.size.height }
                .guideGestures(
                    guideType = GuideType.TXT,
                    textZoom = textZoom,
                    onZoomLevelChanged = onZoomLevelChanged,
                    onPageStep = onPageStep,
                    onTapped = onTapped,
                )
                .verticalScroll(scrollState)
        )
    }
}

@Composable
private fun ImageContent(
    filePath: String, initialScrollY: Int, initialScrollX: Int,
    scrollDir: Int, scrollXDir: Int,
    pageJump: Int, pageJumpDir: Int,
    scale: Float, textZoom: Int, onScrollPosChanged: (Int, Int) -> Unit,
    onZoomLevelChanged: (Int) -> Unit,
    onPageStep: (Int) -> Unit,
    onTapped: () -> Unit
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val scrollState = remember(initialScrollY) { ScrollState(initialScrollY) }
    val hScrollState = remember(initialScrollX) { ScrollState(initialScrollX) }
    var viewportHeight by remember { mutableStateOf(0) }

    LaunchedEffect(scrollDir) {
        while (scrollDir != 0) {
            scrollState.dispatchRawDelta(scrollDir * SCROLL_SPEED)
            delay(FRAME_MS)
        }
    }
    LaunchedEffect(scrollXDir) {
        while (scrollXDir != 0) {
            hScrollState.dispatchRawDelta(scrollXDir * SCROLL_SPEED)
            delay(FRAME_MS)
        }
    }
    LaunchedEffect(pageJump) {
        if (pageJump > 0 && viewportHeight > 0) {
            scrollState.animateScrollTo(
                (scrollState.value + pageJumpDir * viewportHeight).coerceIn(0, scrollState.maxValue)
            )
        }
    }
    val currentOnScrollPosChanged by rememberUpdatedState(onScrollPosChanged)
    LaunchedEffect(scrollState, hScrollState) {
        snapshotFlow { scrollState.value to hScrollState.value }
            .collect { (y, x) -> currentOnScrollPosChanged(y, x) }
    }

    DisposableEffect(filePath) {
        val file = File(filePath)
        if (file.exists()) bitmap = BitmapFactory.decodeFile(file.absolutePath)
        onDispose { bitmap?.recycle(); bitmap = null }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .guideGestures(
                guideType = GuideType.IMAGE,
                textZoom = textZoom,
                onZoomLevelChanged = onZoomLevelChanged,
                onPageStep = onPageStep,
                onTapped = onTapped,
            )
    ) {
        bitmap?.let { bmp ->
            if (scale <= 1f) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { viewportHeight = it.size.height }
                        .verticalScroll(scrollState),
                    contentScale = ContentScale.FillWidth
                )
            } else {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .horizontalScroll(hScrollState)
                        .verticalScroll(scrollState)
                        .requiredWidth(maxWidth * scale)
                        .onGloballyPositioned { viewportHeight = it.size.height },
                    contentScale = ContentScale.FillWidth
                )
            }
        }
    }
}

@Composable
private fun Modifier.guideGestures(
    guideType: GuideType,
    textZoom: Int,
    onZoomLevelChanged: (Int) -> Unit,
    onPageStep: (Int) -> Unit,
    onTapped: () -> Unit,
): Modifier {
    val currentZoom by rememberUpdatedState(textZoom)
    val currentOnZoom by rememberUpdatedState(onZoomLevelChanged)
    val currentOnPage by rememberUpdatedState(onPageStep)
    val currentOnTap by rememberUpdatedState(onTapped)
    val pageable = guideType == GuideType.PDF &&
        GuideGestures.horizontalGesture(guideType, textZoom) == HorizontalGesture.PAGE

    return this
        .pointerInput(Unit) {
            detectTapGestures(onTap = { currentOnTap() })
        }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    var event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.size < 2) continue

                    var scale = 1f
                    do {
                        scale *= event.calculateZoom()
                        event.changes.forEach { it.consume() }
                        val step = GuideGestures.zoomStep(scale)
                        if (step != 0) {
                            scale = 1f
                            currentOnZoom(GuideGestures.nextZoom(currentZoom, step))
                        }
                        event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed } && event.changes.size >= 2)
                }
            }
        }
        .then(
            if (!pageable) Modifier else Modifier.pointerInput(Unit) {
                var dragX = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragX = 0f },
                    onDragEnd = { dragX = 0f },
                    onDragCancel = { dragX = 0f },
                    onHorizontalDrag = { change, amount ->
                        dragX += amount
                        val step = GuideGestures.pageStep(dragX)
                        if (step != 0) {
                            dragX = 0f
                            change.consume()
                            currentOnPage(step)
                        }
                    },
                )
            }
        )
}
