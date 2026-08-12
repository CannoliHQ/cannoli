package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Test

class GuideGesturesTest {

    @Test
    fun zoomedNonTextPansHorizontally() {
        assertEquals(
            HorizontalGesture.PAN,
            GuideGestures.horizontalGesture(GuideType.PDF, textZoom = 2)
        )
        assertEquals(
            HorizontalGesture.PAN,
            GuideGestures.horizontalGesture(GuideType.IMAGE, textZoom = 6)
        )
    }

    @Test
    fun unzoomedPages() {
        assertEquals(
            HorizontalGesture.PAGE,
            GuideGestures.horizontalGesture(GuideType.PDF, textZoom = 1)
        )
    }

    @Test
    fun textAlwaysPagesEvenWhenZoomed() {
        assertEquals(
            HorizontalGesture.PAGE,
            GuideGestures.horizontalGesture(GuideType.TXT, textZoom = 4)
        )
    }

    @Test
    fun dragLeftAdvancesAPage() {
        assertEquals(1, GuideGestures.pageStep(-200f))
    }

    @Test
    fun dragRightGoesBackAPage() {
        assertEquals(-1, GuideGestures.pageStep(200f))
    }

    @Test
    fun shortDragDoesNotPage() {
        assertEquals(0, GuideGestures.pageStep(30f))
        assertEquals(0, GuideGestures.pageStep(-30f))
    }

    @Test
    fun pinchOutZoomsInAndPinchInZoomsOut() {
        assertEquals(1, GuideGestures.zoomStep(1.4f))
        assertEquals(-1, GuideGestures.zoomStep(0.7f))
        assertEquals(0, GuideGestures.zoomStep(1.0f))
    }

    @Test
    fun zoomClampsInsteadOfWrapping() {
        assertEquals(GuideZoom.levels, GuideGestures.nextZoom(GuideZoom.levels, 1))
        assertEquals(1, GuideGestures.nextZoom(1, -1))
        assertEquals(3, GuideGestures.nextZoom(2, 1))
    }
}
