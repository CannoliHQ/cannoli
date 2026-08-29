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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.io.File

private const val SCROLL_SPEED = 14f
private const val FRAME_MS = 16L

// Rendered past each edge of the viewport so a small pan lands inside the tile already on screen
// instead of waiting on pdfium.
private const val TILE_OVERSCAN = 256

// Only until the real page is measured, and only ever used for one frame.
private const val DEFAULT_PAGE_ASPECT = 1.4f

/** What a tile was rendered for, so one made for another page or zoom is never mistaken for current. */
private data class TileKey(val page: Int, val contentWidth: Int, val contentHeight: Int)

// Deep enough to cover a bottom bar at the largest text size the user can pick, since the guide
// screen never sees the bar its host draws over it.
private val LEGEND_SCRIM_HEIGHT = 96.dp
private val COUNTER_SCRIM_HEIGHT = 48.dp

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
    pageLabel: String = "%d / %d"
) {
    val typo = LocalCannoliTypography.current
    val colors = LocalCannoliColors.current
    val zoomIndex = (textZoom - 1).coerceIn(0, GuideZoom.pdfScales.lastIndex)

    // A PDF page goes edge to edge; text still wants its margin.
    val inset = if (guideType == GuideType.PDF) 0.dp else 12.dp

    // A PDF or an image is someone else's page, usually white, and the colour the user picked for
    // Cannoli's own screens has no business tinting it or the space around it. Text is Cannoli
    // drawing the guide itself, so that stays on the theme.
    //
    // The neutral is chosen against the legend rather than against the page: the host draws that
    // legend in the theme's text colour and never tells this screen, so whatever the scrim is has
    // to be the side that colour reads on.
    val document = guideType != GuideType.TXT
    val neutral = if (colors.text.luminance() > 0.5f) Color.Black else Color.White
    val ground = if (document) neutral else null

    ScreenBackground(backgroundImagePath = null, backgroundAlpha = 1f, backgroundColor = ground) {
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

            // A page can be any brightness, and white is the common one, so the host's legend and
            // the page counter would otherwise sit as theme-coloured text on a white page and
            // disappear. These fade the page out behind them instead of boxing them in, in the
            // neutral rather than the theme colour: the job is to back the legend, not decorate.
            if (document) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(LEGEND_SCRIM_HEIGHT)
                        .background(
                            Brush.verticalGradient(listOf(Color.Transparent, neutral))
                        )
                )
                if (guideType == GuideType.PDF && pageCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(COUNTER_SCRIM_HEIGHT)
                            .background(
                                Brush.verticalGradient(listOf(neutral, Color.Transparent))
                            )
                    )
                }
            }

            if (guideType == GuideType.PDF && pageCount > 0) {
                Text(
                    text = String.format(pageLabel, page + 1, pageCount),
                    style = typo.labelSmall.copy(color = colors.text.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            }
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
        // The width always follows the zoom and the height follows the page, so even at zoom 1 the
        // page spans the screen and is read by scrolling down it. Fitting the whole page instead
        // would letterbox a portrait page against a landscape screen, which is most of a handheld.
        val contentW = (viewportW * scale).toInt()
        val contentH = (contentW * aspect).toInt()

        LaunchedEffect(renderer, page, contentW, contentH, viewportW, viewportH) {
            val key = TileKey(page, contentW, contentH)
            snapshotFlow { scrollState.value to hScrollState.value }.collectLatest { (y, x) ->
                val r = renderer ?: return@collectLatest
                if (contentW <= 0 || contentH <= 0) return@collectLatest
                if (page < 0 || page >= r.pageCount) return@collectLatest
                val current = tile
                // A tile drawn for another page or another zoom can still span the viewport, so the
                // rect alone would keep a stale one on screen. It has to match what it was made for.
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
    val colors = LocalCannoliColors.current
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
                color = colors.text,
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
    // PDF is the only type with a page model, and a zoomed PDF already pans horizontally through
    // horizontalScroll, so paging there would fight it.
    val pageable = guideType == GuideType.PDF &&
        GuideGestures.horizontalGesture(guideType, textZoom) == HorizontalGesture.PAGE

    return this
        .pointerInput(Unit) {
            detectTapGestures(onTap = { currentOnTap() })
        }
        .pointerInput(Unit) {
            // Claimed on the Initial pass and only for two or more pointers, so the inner
            // verticalScroll never sees a pinch and single-finger drags fall through to it.
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
                // detectHorizontalDragGestures waits for horizontal slop, so a vertical drag is
                // never claimed here and reaches verticalScroll intact.
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
