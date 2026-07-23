package dev.cannoli.igm

import android.os.Parcel
import android.os.Parcelable

/**
 * The slice of a Cannoli device mapping the IGM needs to translate raw Android keycodes
 * to actions. Only button bindings are carried; the dpad arrives as standard DPAD keycodes
 * (Android converts the controller hat) and the IGM uses no analog input.
 */
data class IgmInputMapping(
    val buttonKeycodes: Map<CanonicalButton, List<Int>>,
    val menuConfirm: CanonicalButton,
    val menuBack: CanonicalButton,
) : Parcelable {
    override fun describeContents() = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(buttonKeycodes.size)
        for ((button, codes) in buttonKeycodes) {
            dest.writeString(button.name)
            dest.writeIntArray(codes.toIntArray())
        }
        dest.writeString(menuConfirm.name)
        dest.writeString(menuBack.name)
    }

    companion object CREATOR : Parcelable.Creator<IgmInputMapping> {
        override fun createFromParcel(p: Parcel): IgmInputMapping {
            val count = p.readInt()
            val map = LinkedHashMap<CanonicalButton, List<Int>>(count)
            repeat(count) {
                val button = CanonicalButton.valueOf(p.readString()!!)
                val codes = (p.createIntArray() ?: IntArray(0)).toList()
                map[button] = codes
            }
            val confirm = CanonicalButton.valueOf(p.readString()!!)
            val back = CanonicalButton.valueOf(p.readString()!!)
            return IgmInputMapping(map, confirm, back)
        }

        override fun newArray(size: Int) = arrayOfNulls<IgmInputMapping>(size)
    }
}
