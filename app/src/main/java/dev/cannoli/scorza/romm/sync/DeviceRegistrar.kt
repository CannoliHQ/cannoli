package dev.cannoli.scorza.romm.sync

import android.os.Build
import dev.cannoli.scorza.BuildConfig
import dev.cannoli.scorza.romm.RommClient
import dev.cannoli.scorza.settings.SettingsRepository

class DeviceRegistrar(
    private val settings: SettingsRepository,
    private val client: RommClient,
) {
    fun deviceId(): String? = settings.rommDeviceId

    fun isRegistered(): Boolean = !settings.rommDeviceId.isNullOrEmpty()

    fun defaultDeviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    fun register(deviceName: String): String {
        val response = client.registerDevice(
            DeviceRegisterPayload(name = deviceName, clientVersion = BuildConfig.VERSION_NAME),
        )
        settings.rommDeviceId = response.deviceId
        settings.rommDeviceName = deviceName
        settings.rommDeviceClientVersion = BuildConfig.VERSION_NAME
        return response.deviceId
    }

    fun clear() {
        settings.rommDeviceId = null
        settings.rommDeviceName = null
        settings.rommDeviceClientVersion = null
    }
}
