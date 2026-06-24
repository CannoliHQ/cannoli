package dev.cannoli.scorza.romm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RommConnectionStoreCollectionsTest {

    private fun newStore() = RommConnectionStore(ApplicationProvider.getApplicationContext<Context>())

    @Test fun `defaults enable only user collections`() {
        assertEquals(setOf(RommCollectionGroup.USER), newStore().enabledCollectionGroups())
    }

    @Test fun `enabling virtual adds it to the set`() {
        val store = newStore()
        store.showVirtualCollections = true
        assertEquals(setOf(RommCollectionGroup.USER, RommCollectionGroup.VIRTUAL), store.enabledCollectionGroups())
    }

    @Test fun `disabling user with virtual enabled leaves only virtual`() {
        val store = newStore()
        store.showVirtualCollections = true
        store.showUserCollections = false
        assertEquals(setOf(RommCollectionGroup.VIRTUAL), store.enabledCollectionGroups())
    }

    @Test fun `flags persist across store instances`() {
        newStore().apply {
            showUserCollections = false
            showVirtualCollections = true
            showSmartCollections = true
        }
        assertEquals(
            setOf(RommCollectionGroup.VIRTUAL, RommCollectionGroup.SMART),
            newStore().enabledCollectionGroups()
        )
    }
}
