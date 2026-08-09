package dev.cannoli.scorza.achievements

import android.content.Context
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.BuildConfig
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.RaTokenState
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
            // that rejects it flips the row to invalid. A 200 that is not the API's JSON is the
            // same kind of non-answer: a proxy or portal reply, not a rejection.
            val state = when {
                res == null || res.code !in 200..299 -> RaTokenState.UNREACHABLE
                else -> when (RaConnectClient.successFlag(res.body)) {
                    true -> RaTokenState.VALID
                    false -> RaTokenState.INVALID
                    null -> RaTokenState.UNREACHABLE
                }
            }
            withContext(Dispatchers.Main) {
                val ds = nav.dialogState.value
                if (ds is DialogState.RAAccount) nav.dialogState.value = ds.copy(tokenState = state)
            }
        }
    }

    private fun apply(result: RaConnectClient.LoginResult) {
        // Backing out of the progress dialog abandons the wait, not the request, which cannot be
        // recalled anyway. A login that already succeeded is still kept, so a correct password is
        // never typed for nothing, but no dialog is written over whatever the user opened instead.
        val stillWaiting = nav.dialogState.value is DialogState.RALoggingIn
        when (result) {
            is RaConnectClient.LoginResult.Success -> {
                settings.raUsername = result.username
                settings.raToken = result.token
                settings.raPassword = ""
                if (!stillWaiting) return
                nav.dialogState.value = DialogState.RAAccount(
                    username = result.username,
                    score = result.score,
                    tokenState = RaTokenState.VALID,
                    hardcore = settings.raHardcore,
                )
            }
            is RaConnectClient.LoginResult.InvalidCredentials ->
                if (stillWaiting) fail(dev.cannoli.scorza.R.string.ra_login_invalid)
            RaConnectClient.LoginResult.NetworkError ->
                if (stillWaiting) fail(dev.cannoli.scorza.R.string.ra_login_network)
        }
    }

    private fun fail(messageRes: Int) {
        nav.dialogState.value =
            DialogState.RALoggingIn(message = context.getString(messageRes), failed = true)
    }
}
