package dev.cannoli.igm

import android.os.Parcel
import android.os.Parcelable

/** Bumped whenever the launch contract changes. Sender and receiver must match. */
const val RICOTTA_PROTOCOL_VERSION = 1

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
) : Parcelable {
    override fun describeContents() = 0

    fun writeToIntent(intent: android.content.Intent) {
        intent.putExtra(EXTRA_PROTOCOL, RICOTTA_PROTOCOL_VERSION)
        intent.putExtra(EXTRA, this)
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        // protocolVersion is FIELD #1 and is always this build's version.
        dest.writeInt(RICOTTA_PROTOCOL_VERSION)
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
    }

    companion object {
        /** Intent extra key carrying the parcelled params. */
        const val EXTRA = "RICOTTA_PARAMS"

        /** Intent extra key carrying the plain-int protocol version for fast mismatch detection. */
        const val EXTRA_PROTOCOL = "RICOTTA_PROTOCOL"

        fun readFromIntent(intent: android.content.Intent): RicottaLaunchParams? {
            if (intent.getIntExtra(EXTRA_PROTOCOL, -1) != RICOTTA_PROTOCOL_VERSION) return null
            @Suppress("DEPRECATION")
            return intent.getParcelableExtra(EXTRA)
        }

        @JvmField
        val CREATOR = object : Parcelable.Creator<RicottaLaunchParams> {
            override fun createFromParcel(p: Parcel): RicottaLaunchParams {
                val version = p.readInt()
                if (version != RICOTTA_PROTOCOL_VERSION) {
                    throw ProtocolMismatchException(version, RICOTTA_PROTOCOL_VERSION)
                }
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
                return RicottaLaunchParams(
                    coreId, romPath, configFilePath, gameTitle, stateBasePath,
                    cannoliRoot, platformTag, platformName, igmTriggerKeycodes, quitOnFocusLoss,
                    preferredRefreshRate, colors, displaySettings, inputMapping,
                )
            }

            override fun newArray(size: Int) = arrayOfNulls<RicottaLaunchParams>(size)
        }
    }
}
