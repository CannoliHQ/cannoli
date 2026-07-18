package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.model.Rom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LaunchState @Inject constructor() {
    @Volatile var launching: Boolean = false
    @Volatile var lastLaunched: Rom? = null

    private val _gameActive = MutableStateFlow(false)
    val gameActive: StateFlow<Boolean> = _gameActive

    fun markGameStarted() {
        _gameActive.value = true
    }

    fun markGameEnded() {
        _gameActive.value = false
    }
}
