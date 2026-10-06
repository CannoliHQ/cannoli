package dev.cannoli.scorza.launcher

import dev.cannoli.igm.GuideOverlayContract
import org.junit.Assert.assertEquals
import org.junit.Test

class GuideOverlayContractTest {

    @Test fun serviceClassNamesTheServiceItStarts() {
        assertEquals(GuideOverlayService::class.java.name, GuideOverlayContract.SERVICE_CLASS)
    }
}
