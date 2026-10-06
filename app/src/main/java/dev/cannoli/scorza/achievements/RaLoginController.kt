package dev.cannoli.scorza.achievements

import android.content.Context
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.RaTokenState
import dev.cannoli.scorza.util.ErrorLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private fun defaultClient() = RaConnectClient(log = ErrorLog::write)

@ActivityScoped
class RaLoginController(
    private val nav: NavigationController,
    private val ioScope: CoroutineScope,
    private val context: Context,
    private val settings: SettingsRepository,
    private val clientProvider: () -> RaConnectClient = ::defaultClient,
) {

    @Inject constructor(
        nav: NavigationController,
        @IoScope ioScope: CoroutineScope,
        @ActivityContext context: Context,
        settings: SettingsRepository,
    ) : this(nav, ioScope, context, settings, ::defaultClient)

    fun login(username: String, password: String) {
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) {
            fail(dev.cannoli.scorza.R.string.achievos_login_missing_credentials)
            return
        }
        nav.dialogState.value = DialogState.RALoggingIn(
            message = context.getString(dev.cannoli.scorza.R.string.achievos_login_progress),
        )
        ioScope.launch {
            val result = runCatching { clientProvider().loginWithPassword(user, password) }
                .getOrDefault(RaConnectClient.LoginResult.NetworkError)
            withContext(Dispatchers.Main) { apply(result) }
        }
    }

    fun openAccountMenu() {
        nav.push(
            LauncherScreen.RetroAchievements(
                username = settings.raUsername,
                hardcore = settings.raHardcore,
            )
        )
        val user = settings.raUsername
        val token = settings.raToken
        if (user.isEmpty() || token.isEmpty()) return
        ioScope.launch {
            val res = runCatching { clientProvider().validateToken(user, token) }.getOrNull()
            val state = when {
                res == null || res.code !in 200..299 -> RaTokenState.UNREACHABLE
                else -> when (RaConnectClient.successFlag(res.body)) {
                    true -> RaTokenState.VALID
                    false -> RaTokenState.INVALID
                    null -> RaTokenState.UNREACHABLE
                }
            }
            withContext(Dispatchers.Main) {
                val idx = nav.screenStack.indexOfLast { it is LauncherScreen.RetroAchievements }
                if (idx >= 0) {
                    val screen = nav.screenStack[idx] as LauncherScreen.RetroAchievements
                    nav.screenStack[idx] = screen.copy(tokenState = state)
                }
            }
        }
    }

    private fun apply(result: RaConnectClient.LoginResult) {
        val stillWaiting = nav.dialogState.value is DialogState.RALoggingIn
        when (result) {
            is RaConnectClient.LoginResult.Success -> {
                settings.raUsername = result.username
                settings.raToken = result.token
                settings.raPassword = ""
                if (!stillWaiting) return
                nav.dialogState.value = DialogState.None
                nav.push(
                    LauncherScreen.RetroAchievements(
                        username = result.username,
                        tokenState = RaTokenState.VALID,
                        hardcore = settings.raHardcore,
                    )
                )
            }
            is RaConnectClient.LoginResult.InvalidCredentials ->
                if (stillWaiting) fail(dev.cannoli.scorza.R.string.achievos_login_invalid)
            RaConnectClient.LoginResult.NetworkError ->
                if (stillWaiting) fail(dev.cannoli.scorza.R.string.achievos_login_network)
        }
    }

    private fun fail(messageRes: Int) {
        nav.dialogState.value =
            DialogState.RALoggingIn(message = context.getString(messageRes), failed = true)
    }
}
