package dev.cannoli.scorza.romm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RommConnectionStoreTest {

    private fun newStore() = RommConnectionStore(ApplicationProvider.getApplicationContext<Context>())

    @Test fun `defaults are empty and not configured`() {
        val store = newStore()
        assertEquals("", store.host)
        assertFalse(store.allowSelfSigned)
        assertNull(store.token)
        assertFalse(store.isConfigured)
        assertEquals(RommArtType.DEFAULT, store.artType)
    }

    @Test fun `host is trimmed of trailing slash and persists`() {
        newStore().apply { host = "https://romm.example.com/" }
        assertEquals("https://romm.example.com", newStore().host)
    }

    @Test fun `token and self-signed flag persist, isConfigured needs host plus token`() {
        val a = newStore()
        a.host = "https://r.example"
        a.allowSelfSigned = true
        a.token = "tok-1"
        val b = newStore()
        assertEquals("tok-1", b.token)
        assertTrue(b.allowSelfSigned)
        assertTrue(b.isConfigured)
    }

    @Test fun `clear removes token but keeps host`() {
        val a = newStore()
        a.host = "https://r.example"
        a.token = "tok-1"
        a.clearToken()
        val b = newStore()
        assertEquals("https://r.example", b.host)
        assertNull(b.token)
        assertFalse(b.isConfigured)
    }

    @Test fun `disconnect clears token username and version but keeps host`() {
        val a = newStore()
        a.host = "https://r.example"; a.token = "tok"; a.username = "btk"; a.serverVersion = "4.7.2"
        a.disconnect()
        val b = newStore()
        assertEquals("https://r.example", b.host)
        assertNull(b.token); assertNull(b.username); assertNull(b.serverVersion)
    }
}
