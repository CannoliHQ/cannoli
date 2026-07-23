package dev.cannoli.scorza.romm

import dev.cannoli.scorza.settings.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class PairingState {
    data object Idle : PairingState()
    data object Connecting : PairingState()
    data class WaitingApproval(val userCode: String, val verificationUrl: String) : PairingState()
    data object Success : PairingState()
    data class Failed(val reason: PairingFailure) : PairingState()
}

enum class PairingFailure { SERVER_TOO_OLD, UNREACHABLE, DENIED, EXPIRED, RATE_LIMITED, FAILED }

class RommDevicePairing(
    private val client: RommClient,
    private val store: RommConnectionStore,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val deviceIdentifier: () -> String,
    private val deviceName: () -> String,
    private val clientVersion: () -> String,
) {
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state

    private var job: Job? = null

    fun start(host: String) {
        job?.cancel()
        _state.value = PairingState.Connecting
        job = scope.launch(io) {
            val base = client.resolveBaseUrl(host)
            ensureActive()
            if (base == null) {
                _state.value = PairingState.Failed(PairingFailure.UNREACHABLE)
                return@launch
            }
            store.host = base
            val version = client.serverVersion()
            ensureActive()
            if (RommCapabilities.isKnownUnsupported(version)) {
                _state.value = PairingState.Failed(PairingFailure.SERVER_TOO_OLD)
                return@launch
            }
            val init = try {
                client.deviceAuthInit(
                    DeviceAuthInitPayload(
                        clientDeviceIdentifier = deviceIdentifier(),
                        name = deviceName(),
                        client = "Cannoli",
                        platform = "android",
                        clientVersion = clientVersion(),
                        requestedScopes = REQUESTED_SCOPES,
                    )
                )
            } catch (e: RommException) {
                ensureActive()
                _state.value = PairingState.Failed(
                    when (e.statusCode) {
                        // A pre-5.0 server has no device auth endpoints at all.
                        404 -> PairingFailure.SERVER_TOO_OLD
                        429 -> PairingFailure.RATE_LIMITED
                        null -> PairingFailure.UNREACHABLE
                        else -> PairingFailure.FAILED
                    }
                )
                return@launch
            }
            ensureActive()
            _state.value = PairingState.WaitingApproval(init.userCode, base + init.verificationPathComplete)
            poll(init.deviceCode, init.interval, init.expiresIn)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = PairingState.Idle
    }

    private suspend fun poll(deviceCode: String, initialInterval: Int, expiresIn: Int) {
        var intervalSeconds = initialInterval.coerceAtLeast(1)
        var waitedSeconds = 0
        while (waitedSeconds < expiresIn) {
            delay(intervalSeconds * 1000L)
            waitedSeconds += intervalSeconds
            val result = runCatching { client.deviceAuthToken(deviceCode) }
            currentCoroutineContext().ensureActive()
            result.onSuccess { dto ->
                store.token = dto.accessToken
                settings.rommDeviceId = dto.deviceId
                settings.rommDeviceName = deviceName()
                settings.rommDeviceClientVersion = clientVersion()
                _state.value = PairingState.Success
                return
            }
            val detail = (result.exceptionOrNull() as? RommException)
                ?.takeIf { it.statusCode == 400 }?.message.orEmpty()
            when {
                detail.contains("authorization_pending") -> {}
                detail.contains("slow_down") -> intervalSeconds += SLOW_DOWN_STEP_SECONDS
                detail.contains("access_denied") -> {
                    _state.value = PairingState.Failed(PairingFailure.DENIED)
                    return
                }
                detail.contains("expired_token") -> {
                    _state.value = PairingState.Failed(PairingFailure.EXPIRED)
                    return
                }
                // Anything else is transient; keep polling until the code expires.
                else -> {}
            }
        }
        _state.value = PairingState.Failed(PairingFailure.EXPIRED)
    }

    private companion object {
        const val SLOW_DOWN_STEP_SECONDS = 5
        val REQUESTED_SCOPES = listOf(
            "me.read", "platforms.read", "roms.read", "collections.read", "firmware.read",
            "assets.read", "assets.write", "devices.read", "devices.write",
        )
    }
}
