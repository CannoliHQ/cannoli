package dev.cannoli.igm

import android.os.Parcel
import android.os.Parcelable

class ProtocolMismatchException(val found: Int, val expected: Int) : RuntimeException(
    "Ricotta launch protocol mismatch: parcel=$found, app=$expected",
)

data class IgmColors(
    val highlight: String?,
    val text: String?,
    val highlightText: String?,
    val accent: String?,
    val title: String?,
) : Parcelable {
    override fun describeContents() = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(highlight)
        dest.writeString(text)
        dest.writeString(highlightText)
        dest.writeString(accent)
        dest.writeString(title)
    }

    companion object CREATOR : Parcelable.Creator<IgmColors> {
        override fun createFromParcel(p: Parcel) = IgmColors(
            p.readString(), p.readString(), p.readString(), p.readString(), p.readString(),
        )

        override fun newArray(size: Int) = arrayOfNulls<IgmColors>(size)
    }
}

data class RicottaLaunchParams(
    val coreId: String,
    val romPath: String,
    val configFilePath: String?,
    val gameTitle: String,
    val stateBasePath: String,
    val cannoliRoot: String,
    val platformTag: String,
    val platformName: String,
    val igmTriggerKeycodes: List<Int>,
    val quitOnFocusLoss: Boolean,
    val preferredRefreshRate: Int?,
    val colors: IgmColors?,
    val displaySettings: IgmDisplaySettings,
    val inputMapping: IgmInputMapping? = null,
    val localeTag: String = "",
    val romBaseName: String = "",
    val hardcoreInEffect: Boolean = false,
    val curatedSettings: Boolean = true,
    val shortcuts: Map<ShortcutAction, Set<Int>> = emptyMap(),
) : Parcelable {
    override fun describeContents() = 0

    fun writeToIntent(intent: android.content.Intent) {
        intent.putExtra(EXTRA, this)
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(coreId)
        dest.writeString(romPath)
        dest.writeString(configFilePath)
        dest.writeString(gameTitle)
        dest.writeString(stateBasePath)
        dest.writeString(cannoliRoot)
        dest.writeString(platformTag)
        dest.writeString(platformName)
        dest.writeIntArray(igmTriggerKeycodes.toIntArray())
        dest.writeInt(if (quitOnFocusLoss) 1 else 0)
        dest.writeInt(if (preferredRefreshRate != null) 1 else 0)
        if (preferredRefreshRate != null) dest.writeInt(preferredRefreshRate)
        dest.writeParcelable(colors, flags)
        dest.writeParcelable(displaySettings, flags)
        dest.writeParcelable(inputMapping, flags)
        dest.writeString(localeTag)
        dest.writeString(romBaseName)
        dest.writeInt(if (hardcoreInEffect) 1 else 0)
        dest.writeInt(if (curatedSettings) 1 else 0)
        dest.writeInt(shortcuts.size)
        for ((action, chord) in shortcuts) {
            dest.writeString(action.name)
            dest.writeIntArray(chord.toIntArray())
        }
    }

    companion object {
        const val EXTRA = "RICOTTA_PARAMS"

        fun readFromIntent(intent: android.content.Intent): RicottaLaunchParams? {
            @Suppress("DEPRECATION")
            return intent.getParcelableExtra(EXTRA)
        }

        @JvmField
        val CREATOR = object : Parcelable.Creator<RicottaLaunchParams> {
            override fun createFromParcel(p: Parcel): RicottaLaunchParams {
                val coreId = p.readString()!!
                val romPath = p.readString()!!
                val configFilePath = p.readString()
                val gameTitle = p.readString()!!
                val stateBasePath = p.readString()!!
                val cannoliRoot = p.readString()!!
                val platformTag = p.readString()!!
                val platformName = p.readString()!!
                val igmTriggerKeycodes = (p.createIntArray() ?: IntArray(0)).toList()
                val quitOnFocusLoss = p.readInt() != 0
                val hasRefresh = p.readInt() != 0
                val preferredRefreshRate = if (hasRefresh) p.readInt() else null
                @Suppress("DEPRECATION")
                val colors = p.readParcelable<IgmColors>(IgmColors::class.java.classLoader)
                @Suppress("DEPRECATION")
                val displaySettings = p.readParcelable<IgmDisplaySettings>(IgmDisplaySettings::class.java.classLoader)!!
                @Suppress("DEPRECATION")
                val inputMapping = p.readParcelable<IgmInputMapping>(IgmInputMapping::class.java.classLoader)
                val localeTag = p.readString().orEmpty()
                val romBaseName = p.readString().orEmpty()
                val hardcoreInEffect = p.readInt() != 0
                val curatedSettings = if (p.dataAvail() > 0) p.readInt() != 0 else true
                val shortcuts = mutableMapOf<ShortcutAction, Set<Int>>()
                if (p.dataAvail() > 0) {
                    repeat(p.readInt()) {
                        val name = p.readString()
                        val chord = (p.createIntArray() ?: IntArray(0)).toSet()
                        val action = name?.let {
                            runCatching { ShortcutAction.valueOf(it) }.getOrNull()
                        }
                        if (action != null && chord.isNotEmpty()) shortcuts[action] = chord
                    }
                }
                return RicottaLaunchParams(
                    coreId, romPath, configFilePath, gameTitle, stateBasePath,
                    cannoliRoot, platformTag, platformName, igmTriggerKeycodes, quitOnFocusLoss,
                    preferredRefreshRate, colors, displaySettings, inputMapping, localeTag,
                    romBaseName, hardcoreInEffect, curatedSettings, shortcuts,
                )
            }

            override fun newArray(size: Int) = arrayOfNulls<RicottaLaunchParams>(size)
        }
    }
}
