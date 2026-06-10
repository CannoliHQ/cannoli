package dev.cannoli.scorza.romm

import org.junit.Assert.assertEquals
import org.junit.Test

class RommUrlCandidatesTest {

    @Test fun `keeps an explicit scheme as-is and trims trailing slash`() {
        assertEquals(listOf("https://romm.example.com"), RommUrlCandidates.build("https://romm.example.com/"))
        assertEquals(listOf("http://10.0.0.5:8080"), RommUrlCandidates.build("http://10.0.0.5:8080"))
    }

    @Test fun `domain tries https first then http`() {
        assertEquals(listOf("https://romm.example.com", "http://romm.example.com"), RommUrlCandidates.build("romm.example.com"))
    }

    @Test fun `ip address tries http first then https`() {
        assertEquals(listOf("http://192.168.1.50:8080", "https://192.168.1.50:8080"), RommUrlCandidates.build("192.168.1.50:8080"))
    }

    @Test fun `localhost tries http first`() {
        assertEquals(listOf("http://localhost:8080", "https://localhost:8080"), RommUrlCandidates.build("localhost:8080"))
    }

    @Test fun `strips leading double slash`() {
        assertEquals(listOf("https://romm.example.com", "http://romm.example.com"), RommUrlCandidates.build("//romm.example.com"))
    }
}
