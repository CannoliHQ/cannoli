package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.cannoli.igm.GuideScreen
import dev.cannoli.igm.GuideType
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.components.BottomBar

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
    buttonStyle: ButtonStyle,
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
        )
        BottomBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            leftItems = listOf(buttonStyle.back to stringResource(dev.cannoli.ui.R.string.label_back)),
            rightItems = listOf(buttonStyle.north to stringResource(dev.cannoli.ui.R.string.guide_zoom)),
        )
    }
}
