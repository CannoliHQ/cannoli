package dev.cannoli.scorza.launcher

import dev.cannoli.igm.GuideOverlayContract
import org.junit.Assert.assertEquals
import org.junit.Test

class GuideOverlayContractTest {

    // The IGM's host cannot see the service class, so the contract names it by string. Only the app
    // module can see both sides, and a rename that drifts here fails silently on device.
    @Test fun serviceClassNamesTheServiceItStarts() {
        assertEquals(GuideOverlayService::class.java.name, GuideOverlayContract.SERVICE_CLASS)
    }
}
