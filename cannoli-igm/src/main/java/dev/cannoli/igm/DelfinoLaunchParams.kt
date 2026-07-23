package dev.cannoli.igm

import android.content.Intent
import android.os.Parcel
import android.os.Parcelable

const val DELFINO_PROTOCOL_VERSION = 1

data class DelfinoLaunchParams(
    val romPath: String,
    val cannoliRoot: String,
    val savesDir: String?,
    val saveStatesDir: String?,
    val biosDir: String?,
    val userDir: String?,
    val gameTitle: String,
    val platformTag: String,
    val igmTriggerKeycodes: List<Int>,
    val colors: IgmColors?,
    val displaySettings: IgmDisplaySettings?,
    val inputMapping: IgmInputMapping?,
) : Parcelable {

    fun resolvedSavesDir(): String = savesDir ?: "$cannoliRoot/Saves"

    fun resolvedSaveStatesDir(): String = saveStatesDir ?: "$cannoliRoot/Save States"

    override fun describeContents() = 0

    fun writeToIntent(intent: Intent) {
        intent.putExtra(EXTRA_PROTOCOL, DELFINO_PROTOCOL_VERSION)
        intent.putExtra(EXTRA, this)
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(DELFINO_PROTOCOL_VERSION)
        dest.writeString(romPath)
        dest.writeString(cannoliRoot)
        dest.writeString(savesDir)
        dest.writeString(saveStatesDir)
        dest.writeString(biosDir)
        dest.writeString(userDir)
        dest.writeString(gameTitle)
        dest.writeString(platformTag)
        dest.writeIntArray(igmTriggerKeycodes.toIntArray())
        dest.writeParcelable(colors, flags)
        dest.writeParcelable(displaySettings, flags)
        dest.writeParcelable(inputMapping, flags)
    }

    companion object {
        const val EXTRA = "DELFINO_PARAMS"
        const val EXTRA_PROTOCOL = "DELFINO_PROTOCOL"

        fun readFromIntent(intent: Intent): DelfinoLaunchParams? {
            if (intent.getIntExtra(EXTRA_PROTOCOL, -1) != DELFINO_PROTOCOL_VERSION) return null
            @Suppress("DEPRECATION")
            return intent.getParcelableExtra(EXTRA)
        }

        @JvmField
        val CREATOR = object : Parcelable.Creator<DelfinoLaunchParams> {
            override fun createFromParcel(p: Parcel): DelfinoLaunchParams {
                val version = p.readInt()
                if (version != DELFINO_PROTOCOL_VERSION) {
                    throw ProtocolMismatchException(version, DELFINO_PROTOCOL_VERSION)
                }
                val romPath = p.readString()!!
                val cannoliRoot = p.readString()!!
                val savesDir = p.readString()
                val saveStatesDir = p.readString()
                val biosDir = p.readString()
                val userDir = p.readString()
                val gameTitle = p.readString()!!
                val platformTag = p.readString()!!
                val igmTriggerKeycodes = (p.createIntArray() ?: IntArray(0)).toList()
                @Suppress("DEPRECATION")
                val colors = p.readParcelable<IgmColors>(IgmColors::class.java.classLoader)
                @Suppress("DEPRECATION")
                val displaySettings =
                    p.readParcelable<IgmDisplaySettings>(IgmDisplaySettings::class.java.classLoader)
                @Suppress("DEPRECATION")
                val inputMapping =
                    p.readParcelable<IgmInputMapping>(IgmInputMapping::class.java.classLoader)
                return DelfinoLaunchParams(
                    romPath, cannoliRoot, savesDir, saveStatesDir, biosDir, userDir,
                    gameTitle, platformTag, igmTriggerKeycodes, colors, displaySettings, inputMapping,
                )
            }

            override fun newArray(size: Int) = arrayOfNulls<DelfinoLaunchParams>(size)
        }
    }
}
