package dev.cannoli.igm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliTypography
import kotlinx.coroutines.delay
import java.io.File

private const val SCROLL_SPEED = 14f
private const val FRAME_MS = 16L

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

    ScreenBackground(backgroundImagePath = null, backgroundAlpha = 1f) {
        Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
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
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var fd by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
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

    DisposableEffect(filePath) {
        val file = File(filePath)
        if (file.exists()) {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            fd = pfd
            renderer = PdfRenderer(pfd)
        }
        onDispose {
            bitmap?.recycle()
            bitmap = null
            renderer?.close()
            fd?.close()
        }
    }

    LaunchedEffect(page, scale, renderer) {
        val r = renderer ?: return@LaunchedEffect
        if (page < 0 || page >= r.pageCount) return@LaunchedEffect
        val pdfPage = r.openPage(page)
        val renderScale = scale + 1
        val bmp = Bitmap.createBitmap(
            (pdfPage.width * renderScale).toInt(), (pdfPage.height * renderScale).toInt(), Bitmap.Config.ARGB_8888
        )
        bmp.eraseColor(android.graphics.Color.WHITE)
        pdfPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        pdfPage.close()
        val old = bitmap
        bitmap = bmp
        old?.recycle()
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
        bitmap?.let { bmp ->
            if (scale <= 1f) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .horizontalScroll(hScrollState)
                        .verticalScroll(scrollState)
                        .requiredWidth(maxWidth * scale),
                    contentScale = ContentScale.FillWidth
                )
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
