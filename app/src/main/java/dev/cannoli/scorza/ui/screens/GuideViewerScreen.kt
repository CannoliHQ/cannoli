package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.cannoli.igm.GuideScreen
import dev.cannoli.igm.GuideType
import dev.cannoli.igm.guideHelpHint

@Composable
fun GuideViewerScreen(
    filePath: String,
    guideType: GuideType,
    page: Int,
    textZoom: Int,
    initialScrollY: Int,
    initialScrollX: Int,
    scrollDir: Int,
    scrollXDir: Int,
    pageJump: Int,
    pageJumpDir: Int,
    pageCount: Int,
    onScrollPosChanged: (y: Int, x: Int) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        GuideScreen(
            filePath = filePath,
            guideType = guideType,
            page = page,
            initialScrollY = initialScrollY,
            initialScrollX = initialScrollX,
            scrollDir = scrollDir,
            scrollXDir = scrollXDir,
            pageJump = pageJump,
            pageJumpDir = pageJumpDir,
            pageCount = pageCount,
            textZoom = textZoom,
            onScrollPosChanged = onScrollPosChanged,
            helpHint = guideHelpHint(),
        )
    }
}
