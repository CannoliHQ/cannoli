package dev.cannoli.igm

import android.os.Parcel
import android.os.Parcelable

// Still used by DelfinoLaunchParams, whose sender and receiver remain separate builds.
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
    // BCP-47 tag of the launcher's selected language, empty for the device default.
    val localeTag: String = "",
    // The ROM file's base name, NFC-normalized: the key every Cannoli side directory uses
    // (Cheats/<tag>/<romBaseName>/). Not gameTitle, which has (region) and [dump] tags stripped.
    val romBaseName: String = "",
    // The launcher's authoritative effective-hardcore decision for this launch, with global
    // hardcore and per-game force-softcore already folded in (LaunchManager.hardcoreInEffect). The
    // IGM gates its Save/Load State rows on this rather than on the live RetroArch cheevos setting,
    // which a stale per-game override file can clobber back to hardcore after launch.
    // Trailing field: a sender that predates it reads back false (rows shown) rather than shifting
    // every field before it.
    val hardcoreInEffect: Boolean = false,
    // Which in-game settings list to show: the curated one, or every RetroArch setting Cannoli
    // exposes. A launcher preference rather than part of the config the game launches with, so it
    // never reaches an override and no two games can disagree about it.
    // Trailing field: a sender that predates it reads back true, the launcher's own default.
    val curatedSettings: Boolean = true,
    // Ports whose pad the input DB marks built in, as of launch. RetroArch announces a configured
    // pad on every port; a handheld's own controls are not news, so these stay quiet. A launch-time
    // snapshot is the whole of it, since a built-in pad is present before the game starts and
    // anything arriving later is a real connection worth announcing.
    // Trailing field: a sender that predates it reads back empty, which announces everything, the
    // behaviour before this existed.
    val builtinPorts: List<Int> = emptyList(),
    /** Chords the launcher has bound, matched in this process because only it sees play input. */
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
        dest.writeIntArray(builtinPorts.toIntArray())
        dest.writeInt(shortcuts.size)
        for ((action, chord) in shortcuts) {
            dest.writeString(action.name)
            dest.writeIntArray(chord.toIntArray())
        }
    }

    companion object {
        /** Intent extra key carrying the parcelled params. */
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
                // The fields above default to false, so reading past a stale sender's parcel end
                // returns 0 and degrades correctly on its own. This one defaults to true, so it has
                // to ask whether the sender wrote it at all.
                val curatedSettings = if (p.dataAvail() > 0) p.readInt() != 0 else true
                val builtinPorts =
                    if (p.dataAvail() > 0) (p.createIntArray() ?: IntArray(0)).toList() else emptyList()
                val shortcuts = mutableMapOf<ShortcutAction, Set<Int>>()
                if (p.dataAvail() > 0) {
                    repeat(p.readInt()) {
                        val name = p.readString()
                        val chord = (p.createIntArray() ?: IntArray(0)).toSet()
                        // An action this build no longer has is skipped rather than failing the
                        // parcel, the same way the ini reader skips a name it does not know.
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
                    romBaseName, hardcoreInEffect, curatedSettings, builtinPorts, shortcuts,
                )
            }

            override fun newArray(size: Int) = arrayOfNulls<RicottaLaunchParams>(size)
        }
    }
}
