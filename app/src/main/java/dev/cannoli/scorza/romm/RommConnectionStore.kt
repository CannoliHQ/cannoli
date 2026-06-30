package dev.cannoli.scorza.romm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class RommArtType { NONE, DEFAULT, BOX2D, BOX3D, MIX, TITLE, SCREENSHOT, MARQUEE }

@Singleton
class RommConnectionStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("cannoli_romm", Context.MODE_PRIVATE)
    private val creds = context.getSharedPreferences("cannoli_credentials", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) { prefs.edit().putString(KEY_HOST, value.trim().trimEnd('/')).apply() }

    var allowSelfSigned: Boolean
        get() = prefs.getBoolean(KEY_SELF_SIGNED, false)
        set(value) { prefs.edit().putBoolean(KEY_SELF_SIGNED, value).apply() }

    var showUserCollections: Boolean
        get() = prefs.getBoolean(KEY_COLL_USER, true)
        set(value) { prefs.edit().putBoolean(KEY_COLL_USER, value).apply() }
    var showVirtualCollections: Boolean
        get() = prefs.getBoolean(KEY_COLL_VIRTUAL, false)
        set(value) { prefs.edit().putBoolean(KEY_COLL_VIRTUAL, value).apply() }
    var showSmartCollections: Boolean
        get() = prefs.getBoolean(KEY_COLL_SMART, false)
        set(value) { prefs.edit().putBoolean(KEY_COLL_SMART, value).apply() }

    fun enabledCollectionGroups(): Set<RommCollectionGroup> = buildSet {
        if (showUserCollections) add(RommCollectionGroup.USER)
        if (showVirtualCollections) add(RommCollectionGroup.VIRTUAL)
        if (showSmartCollections) add(RommCollectionGroup.SMART)
    }

    private val _artTypeFlow = MutableStateFlow(
        runCatching { RommArtType.valueOf(prefs.getString(KEY_ART_TYPE, null) ?: "") }
            .getOrDefault(RommArtType.DEFAULT)
    )

    var artType: RommArtType
        get() = _artTypeFlow.value
        set(value) { prefs.edit().putString(KEY_ART_TYPE, value.name).apply(); _artTypeFlow.value = value }
    val artTypeFlow: StateFlow<RommArtType> = _artTypeFlow

    var token: String?
        get() = creds.getString(KEY_TOKEN, null)?.ifEmpty { null }
        set(value) {
            creds.edit().run {
                if (value.isNullOrEmpty()) remove(KEY_TOKEN) else putString(KEY_TOKEN, value)
                apply()
            }
        }

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)?.ifEmpty { null }
        set(value) { prefs.edit().run { if (value.isNullOrEmpty()) remove(KEY_USERNAME) else putString(KEY_USERNAME, value); apply() } }

    var serverVersion: String?
        get() = prefs.getString(KEY_SERVER_VERSION, null)?.ifEmpty { null }
        set(value) { prefs.edit().run { if (value.isNullOrEmpty()) remove(KEY_SERVER_VERSION) else putString(KEY_SERVER_VERSION, value); apply() } }

    val isConfigured: Boolean get() = host.isNotEmpty() && !token.isNullOrEmpty()

    fun clearToken() { creds.edit().remove(KEY_TOKEN).apply() }

    fun disconnect() {
        clearToken()
        username = null
        serverVersion = null
    }

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_SELF_SIGNED = "allow_self_signed"
        const val KEY_ART_TYPE = "art_type"
        const val KEY_TOKEN = "romm_token"
        const val KEY_USERNAME = "username"
        const val KEY_SERVER_VERSION = "server_version"
        const val KEY_COLL_USER = "coll_user"
        const val KEY_COLL_VIRTUAL = "coll_virtual"
        const val KEY_COLL_SMART = "coll_smart"
    }
}
