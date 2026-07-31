package dev.cannoli.scorza.i18n

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocaleOverrideTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    @Test fun `wrap with empty tag returns base unchanged`() {
        LocaleOverride.persist(ctx(), "")
        val base = ctx()
        assertSame(base, LocaleOverride.wrap(base))
    }

    @Test fun `wrap applies the persisted locale`() {
        LocaleOverride.persist(ctx(), "de-DE")
        val wrapped = LocaleOverride.wrap(ctx())
        assertEquals("de-DE", wrapped.resources.configuration.locales[0].toLanguageTag())
    }

    @Test fun `persist and currentTag round trip`() {
        LocaleOverride.persist(ctx(), "ja-JP")
        assertEquals("ja-JP", LocaleOverride.currentTag(ctx()))
    }
}
