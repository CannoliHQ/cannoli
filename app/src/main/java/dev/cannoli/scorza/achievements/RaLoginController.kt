package dev.cannoli.scorza.achievements

import android.content.Context
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.BuildConfig
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.screens.DialogState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private fun defaultClient() = RaConnectClient(userAgent = "Cannoli/${BuildConfig.VERSION_NAME}")

@ActivityScoped
class RaLoginController(
    private val nav: NavigationController,
    private val ioScope: CoroutineScope,
    private val context: Context,
    private val settings: SettingsRepository,
    private val clientProvider: () -> RaConnectClient = ::defaultClient,
) {

    // Dagger cannot bind the clientProvider lambda, so injection goes through a constructor that
    // omits it and the tests use the primary one to swap in a client aimed at a local server.
    @Inject constructor(
        nav: NavigationController,
        @IoScope ioScope: CoroutineScope,
        @ActivityContext context: Context,
        settings: SettingsRepository,
    ) : this(nav, ioScope, context, settings, ::defaultClient)

    fun login(username: String, password: String) {
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) {
            fail(dev.cannoli.scorza.R.string.ra_login_missing_credentials)
            return
        }
        nav.dialogState.value = DialogState.RALoggingIn(
            message = context.getString(dev.cannoli.scorza.R.string.ra_login_progress),
        )
        ioScope.launch {
            val result = runCatching { clientProvider().loginWithPassword(user, password) }
                .getOrDefault(RaConnectClient.LoginResult.NetworkError)
            withContext(Dispatchers.Main) { apply(result) }
        }
    }

    /** Opens the post-login account menu and confirms the stored token still works. */
    fun openAccountMenu() {
        nav.dialogState.value = DialogState.RAAccount(
            username = settings.raUsername,
            hardcore = settings.raHardcore,
        )
        val user = settings.raUsername
        val token = settings.raToken
        if (user.isEmpty() || token.isEmpty()) return
        ioScope.launch {
            val res = runCatching { clientProvider().validateToken(user, token) }.getOrNull()
            // A network failure is not evidence the token is bad, so only a reachable server
            // that rejects it flips the row to invalid.
            val valid = when {
                res == null || res.code !in 200..299 -> null
                else -> res.body.contains("\"Success\":true")
            }
            withContext(Dispatchers.Main) {
                val ds = nav.dialogState.value
                if (ds is DialogState.RAAccount) nav.dialogState.value = ds.copy(tokenValid = valid)
            }
        }
    }

    private fun apply(result: RaConnectClient.LoginResult) {
        when (result) {
            is RaConnectClient.LoginResult.Success -> {
                settings.raUsername = result.username
                settings.raToken = result.token
                settings.raPassword = ""
                nav.dialogState.value = DialogState.RAAccount(
                    username = result.username,
                    score = result.score,
                    tokenValid = true,
                    hardcore = settings.raHardcore,
                )
            }
            is RaConnectClient.LoginResult.InvalidCredentials ->
                fail(dev.cannoli.scorza.R.string.ra_login_invalid)
            RaConnectClient.LoginResult.NetworkError ->
                fail(dev.cannoli.scorza.R.string.ra_login_network)
        }
    }

    private fun fail(messageRes: Int) {
        nav.dialogState.value =
            DialogState.RALoggingIn(message = context.getString(messageRes), failed = true)
    }
}
