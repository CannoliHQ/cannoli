package dev.cannoli.scorza.i18n

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * MainActivity handles orientation changes itself, so nothing recreates the composition on rotation:
 * the only signal is AndroidComposeView reassigning LocalConfiguration, which these tests stand in
 * for. The configuration this provider hands downstream has to follow that, because screens read the
 * orientation off it to decide whether the portrait margin applies and how much edge padding a short
 * screen gets. Providing a value that is not derived from the live one leaves them pinned to the
 * orientation the launcher started up in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProvideLocalizedResourcesTest {

    @get:Rule val compose = createComposeRule()

    private fun configuration(orientation: Int, widthDp: Int, heightDp: Int) =
        Configuration(RuntimeEnvironment.getApplication().resources.configuration).apply {
            this.orientation = orientation
            screenWidthDp = widthDp
            screenHeightDp = heightDp
        }

    private val portrait = configuration(Configuration.ORIENTATION_PORTRAIT, 411, 891)
    private val landscape = configuration(Configuration.ORIENTATION_LANDSCAPE, 891, 411)

    private val provided = mutableStateOf(portrait, neverEqualPolicy())

    private var orientation = Configuration.ORIENTATION_UNDEFINED
    private var heightDp = 0
    private var language = ""

    private fun render(languageTag: String?) {
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides provided.value) {
                ProvideLocalizedResources(languageTag) {
                    orientation = LocalConfiguration.current.orientation
                    heightDp = LocalConfiguration.current.screenHeightDp
                    language = LocalConfiguration.current.locales[0].language
                }
            }
        }
        compose.waitForIdle()
    }

    private fun rotate() {
        provided.value = landscape
        compose.waitForIdle()
    }

    @Test
    fun `orientation follows a rotation`() {
        render(languageTag = "fr")
        assertEquals(Configuration.ORIENTATION_PORTRAIT, orientation)

        rotate()

        assertEquals(Configuration.ORIENTATION_LANDSCAPE, orientation)
    }

    @Test
    fun `screen size follows a rotation`() {
        render(languageTag = "fr")
        assertEquals(891, heightDp)

        rotate()

        assertEquals(411, heightDp)
    }

    @Test
    fun `rotation keeps the chosen language`() {
        render(languageTag = "fr")
        assertEquals("fr", language)

        rotate()

        assertEquals("fr", language)
    }

    @Test
    fun `orientation follows a rotation before settings load`() {
        render(languageTag = null)
        assertEquals(Configuration.ORIENTATION_PORTRAIT, orientation)

        rotate()

        assertEquals(Configuration.ORIENTATION_LANDSCAPE, orientation)
    }
}
