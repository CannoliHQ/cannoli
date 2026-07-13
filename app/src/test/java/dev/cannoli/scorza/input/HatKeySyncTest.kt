package dev.cannoli.scorza.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class HatKeySyncTest {

    private class Recorder {
        val downs = mutableListOf<Int>()
        val ups = mutableListOf<Int>()
    }

    private fun HatKeySync.feed(rec: Recorder, deviceId: Int, hatX: Float, hatY: Float) =
        sync(deviceId, hatX, hatY, { rec.downs.add(it) }, { rec.ups.add(it) })

    @Test
    fun `hat directions map to dpad keycodes`() {
        val rec = Recorder()
        val sync = HatKeySync()

        sync.feed(rec, 1, -1f, 0f)
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_LEFT), rec.downs)

        sync.feed(rec, 1, 0f, 0f)
        sync.feed(rec, 1, 1f, 0f)
        assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT, rec.downs.last())

        sync.feed(rec, 1, 0f, 0f)
        sync.feed(rec, 1, 0f, -1f)
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, rec.downs.last())

        sync.feed(rec, 1, 0f, 0f)
        sync.feed(rec, 1, 0f, 1f)
        assertEquals(KeyEvent.KEYCODE_DPAD_DOWN, rec.downs.last())
    }

    @Test
    fun `centering the hat releases the direction`() {
        val rec = Recorder()
        val sync = HatKeySync()

        sync.feed(rec, 1, 0f, -1f)
        sync.feed(rec, 1, 0f, 0f)

        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_UP), rec.downs)
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_UP), rec.ups)
    }

    @Test
    fun `holding a direction does not repeat the press`() {
        val rec = Recorder()
        val sync = HatKeySync()

        repeat(5) { sync.feed(rec, 1, 0f, -1f) }

        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_UP), rec.downs)
        assertEquals(emptyList<Int>(), rec.ups)
    }

    @Test
    fun `value inside the hysteresis band holds the press`() {
        val rec = Recorder()
        val sync = HatKeySync()

        sync.feed(rec, 1, 0f, -1f)
        sync.feed(rec, 1, 0f, -0.4f)

        assertEquals(emptyList<Int>(), rec.ups)

        sync.feed(rec, 1, 0f, -0.1f)
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_UP), rec.ups)
    }

    @Test
    fun `diagonals press both directions`() {
        val rec = Recorder()
        val sync = HatKeySync()

        sync.feed(rec, 1, 1f, -1f)

        assertEquals(
            setOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP),
            rec.downs.toSet(),
        )
    }

    @Test
    fun `devices hold independent hat state`() {
        val rec = Recorder()
        val sync = HatKeySync()

        sync.feed(rec, 1, 0f, -1f)
        sync.feed(rec, 2, 0f, -1f)

        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP), rec.downs)

        sync.feed(rec, 1, 0f, 0f)
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_UP), rec.ups)
    }

    @Test
    fun `releaseAll releases every held direction for the device`() {
        val rec = Recorder()
        val sync = HatKeySync()

        sync.feed(rec, 1, 1f, -1f)
        sync.releaseAll(1) { rec.ups.add(it) }

        assertEquals(
            setOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP),
            rec.ups.toSet(),
        )
    }

    @Test
    fun `reset drops held state so a still-held direction presses again`() {
        val rec = Recorder()
        val sync = HatKeySync()

        sync.feed(rec, 1, 0f, -1f)
        sync.reset()
        sync.feed(rec, 1, 0f, -1f)

        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP), rec.downs)
        assertEquals(emptyList<Int>(), rec.ups)
    }

    @Test
    fun `dpad keycodes map back to canonical directions`() {
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, HatKeys.keyCodeFor(CanonicalButton.BTN_UP))
        assertEquals(KeyEvent.KEYCODE_DPAD_DOWN, HatKeys.keyCodeFor(CanonicalButton.BTN_DOWN))
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, HatKeys.keyCodeFor(CanonicalButton.BTN_LEFT))
        assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT, HatKeys.keyCodeFor(CanonicalButton.BTN_RIGHT))
        assertEquals(null, HatKeys.keyCodeFor(CanonicalButton.BTN_SOUTH))
    }
}
