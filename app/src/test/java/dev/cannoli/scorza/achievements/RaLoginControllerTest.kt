package dev.cannoli.scorza.achievements

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.components.raTokenStatusRes
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.RaTokenState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaLoginControllerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val nav = NavigationController()

    private fun settings(): SettingsRepository {
        File(tmp.root, "Config").mkdirs()
        File(tmp.root, "Config/settings.json").writeText("{}")
        return SettingsRepository(ApplicationProvider.getApplicationContext<Context>()).apply {
            sdCardRoot = tmp.root.absolutePath
            raUsername = "bob"
            raPassword = "hunter2"
            raToken = ""
        }
    }

    private fun controller(settings: SettingsRepository, server: MockWebServer?) = RaLoginController(
        nav = nav,
        ioScope = CoroutineScope(Dispatchers.Unconfined),
        context = ApplicationProvider.getApplicationContext(),
        settings = settings,
        clientProvider = {
            RaConnectClient(
                baseUrlProvider = { server?.url("/")?.toString()?.trimEnd('/') ?: "http://127.0.0.1:1" },
                clientProvider = { OkHttpClient() },
                userAgent = "Cannoli/test",
            )
        },
    )

    // The controller lands its result on Dispatchers.Main, and Robolectric's main looper stays
    // paused until it is idled.
    private fun drainMain() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun `a successful login stores the token and clears the password`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"Success":true,"User":"Bob","Token":"abc123","Score":42}"""))
        server.start()
        val s = settings()

        controller(s, server).login("bob", "hunter2")
        drainMain()

        assertEquals("abc123", s.raToken)
        assertEquals("Bob", s.raUsername)
        assertEquals("", s.raPassword)
        assertEquals(DialogState.None, nav.dialogState.value)
        val screen = nav.currentScreen
        assertTrue(screen is LauncherScreen.RetroAchievements)
        assertEquals("Bob", (screen as LauncherScreen.RetroAchievements).username)
        assertEquals(RaTokenState.VALID, screen.tokenState)
        server.shutdown()
    }

    @Test fun `an unreachable server leaves the token unverified, not checking`() {
        val s = settings().apply { raToken = "abc123" }

        controller(s, null).openAccountMenu()
        drainMain()

        val screen = nav.currentScreen as LauncherScreen.RetroAchievements
        assertEquals(RaTokenState.UNREACHABLE, screen.tokenState)
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Offline, not verified", context.getString(raTokenStatusRes(screen.tokenState)))
    }

    @Test fun `invalid only comes from a reachable server rejecting the token`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody("""{"Success":false}"""))
        server.start()
        val s = settings().apply { raToken = "abc123" }
        val c = controller(s, server)

        c.openAccountMenu()
        drainMain()
        assertEquals(
            RaTokenState.UNREACHABLE,
            (nav.currentScreen as LauncherScreen.RetroAchievements).tokenState,
        )

        c.openAccountMenu()
        drainMain()
        assertEquals(
            RaTokenState.INVALID,
            (nav.currentScreen as LauncherScreen.RetroAchievements).tokenState,
        )

        server.shutdown()
    }

    @Test fun `a reachable server accepting the token marks it valid`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"Success":true,"User":"Bob"}"""))
        server.start()
        val s = settings().apply { raToken = "abc123" }

        controller(s, server).openAccountMenu()
        drainMain()

        assertEquals(RaTokenState.VALID, (nav.currentScreen as LauncherScreen.RetroAchievements).tokenState)
        server.shutdown()
    }

    @Test fun `invalid credentials leave the token empty and report the failure`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"Success":false,"Error":"nope"}"""))
        server.start()
        val s = settings()

        controller(s, server).login("bob", "wrong")
        drainMain()

        assertEquals("", s.raToken)
        val ds = nav.dialogState.value
        assertTrue(ds is DialogState.RALoggingIn)
        assertTrue((ds as DialogState.RALoggingIn).failed)
        assertEquals("Invalid username or password", ds.message)
        server.shutdown()
    }

    @Test fun `a network failure gets its own wording`() {
        val s = settings()

        controller(s, null).login("bob", "hunter2")
        drainMain()

        assertEquals("", s.raToken)
        val ds = nav.dialogState.value as DialogState.RALoggingIn
        assertTrue(ds.failed)
        assertEquals("Could not reach RetroAchievements. Check your connection.", ds.message)
    }

    @Test fun `a 200 that is not the api's json is unreachable, not invalid`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("<html>captive portal</html>"))
        server.start()
        val s = settings().apply { raToken = "abc123" }

        controller(s, server).openAccountMenu()
        drainMain()

        assertEquals(
            RaTokenState.UNREACHABLE,
            (nav.currentScreen as LauncherScreen.RetroAchievements).tokenState,
        )
        server.shutdown()
    }

    @Test fun `backing out of the progress dialog keeps the login but shows no dialog`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"Success":true,"User":"Bob","Token":"abc123"}"""))
        server.start()
        val s = settings()

        controller(s, server).login("bob", "hunter2")
        // The answer lands on the main looper, which Robolectric holds until drainMain. Backing
        // out before then is the race: the user is somewhere else when the result arrives.
        nav.dialogState.value = DialogState.None
        drainMain()

        assertEquals(DialogState.None, nav.dialogState.value)
        assertEquals("abc123", s.raToken)
        server.shutdown()
    }

    @Test fun `a failure that lands after backing out shows no dialog either`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"Success":false,"Error":"nope"}"""))
        server.start()
        val s = settings()

        controller(s, server).login("bob", "wrong")
        nav.dialogState.value = DialogState.None
        drainMain()

        assertEquals(DialogState.None, nav.dialogState.value)
        server.shutdown()
    }

    @Test fun `an empty username never hits the network`() {
        val s = settings().apply { raUsername = "" }

        controller(s, null).login("", "hunter2")
        drainMain()

        val ds = nav.dialogState.value as DialogState.RALoggingIn
        assertTrue(ds.failed)
        assertEquals("Enter a username and password first", ds.message)
    }
}
