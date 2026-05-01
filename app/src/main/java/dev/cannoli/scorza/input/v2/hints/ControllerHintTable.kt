package dev.cannoli.scorza.input.v2.hints

import android.content.Context
import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.GlyphStyle
import org.json.JSONObject

data class ControllerHint(
    val menuConfirm: CanonicalButton,
    val glyphStyle: GlyphStyle,
)

class ControllerHintTable internal constructor(
    private val vidPidEntries: List<VidPidEntry>,
    private val buildModelEntries: List<BuildModelEntry>,
    private val default: ControllerHint,
) {
    internal data class VidPidEntry(
        val vendorId: Int,
        val productId: Int?,
        val hint: ControllerHint,
    )

    internal data class BuildModelEntry(
        val modelPrefix: String,
        val hint: ControllerHint,
    )

    /**
     * Look up a hint by VID/PID only. Returns null if no VID-based entry matches; never falls
     * back to Build.MODEL or the default.
     */
    fun lookupVidPid(vendorId: Int, productId: Int): ControllerHint? {
        if (vendorId == 0 || productId == 0) return null
        val exact = vidPidEntries.firstOrNull {
            it.vendorId == vendorId && it.productId != null && it.productId == productId
        }
        if (exact != null) return exact.hint
        return vidPidEntries.firstOrNull {
            it.vendorId == vendorId && it.productId == null
        }?.hint
    }

    fun lookup(vendorId: Int, productId: Int, buildModel: String): ControllerHint {
        val exact = vidPidEntries.firstOrNull {
            it.vendorId == vendorId && it.productId != null && it.productId == productId
        }
        if (exact != null) {
            dev.cannoli.scorza.util.DebugLog.write("[hints] vid+pid hit vid=$vendorId pid=$productId -> confirm=${exact.hint.menuConfirm} glyph=${exact.hint.glyphStyle}")
            return exact.hint
        }

        val vidOnly = vidPidEntries.firstOrNull {
            it.vendorId == vendorId && it.productId == null
        }
        if (vidOnly != null) {
            dev.cannoli.scorza.util.DebugLog.write("[hints] vid hit vid=$vendorId -> confirm=${vidOnly.hint.menuConfirm} glyph=${vidOnly.hint.glyphStyle}")
            return vidOnly.hint
        }

        val byModel = buildModelEntries.firstOrNull {
            buildModel.startsWith(it.modelPrefix, ignoreCase = true)
        }
        if (byModel != null) {
            dev.cannoli.scorza.util.DebugLog.write("[hints] model prefix '${byModel.modelPrefix}' hit Build.MODEL='$buildModel' -> confirm=${byModel.hint.menuConfirm} glyph=${byModel.hint.glyphStyle}")
            return byModel.hint
        }

        dev.cannoli.scorza.util.DebugLog.write("[hints] no match for vid=$vendorId pid=$productId Build.MODEL='$buildModel' -> default confirm=${default.menuConfirm} glyph=${default.glyphStyle}")
        return default
    }

    companion object {
        private const val ASSET_PATH = "input/controller_hints.json"

        fun fromAssets(context: Context): ControllerHintTable {
            val text = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
            return fromJson(text)
        }

        fun fromJson(text: String): ControllerHintTable {
            val root = JSONObject(text)
            val vidPid = mutableListOf<VidPidEntry>()
            root.optJSONArray("vid_pid")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    vidPid += VidPidEntry(
                        vendorId = obj.getInt("vendor_id"),
                        productId = if (obj.has("product_id")) obj.getInt("product_id") else null,
                        hint = parseHint(obj),
                    )
                }
            }
            val byModel = mutableListOf<BuildModelEntry>()
            root.optJSONArray("build_model")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    byModel += BuildModelEntry(
                        modelPrefix = obj.getString("model_prefix"),
                        hint = parseHint(obj),
                    )
                }
            }
            val defaultObj = root.getJSONObject("default")
            return ControllerHintTable(
                vidPidEntries = vidPid,
                buildModelEntries = byModel,
                default = parseHint(defaultObj),
            )
        }

        private fun parseHint(obj: JSONObject): ControllerHint = ControllerHint(
            menuConfirm = CanonicalButton.valueOf(obj.getString("menuConfirm")),
            glyphStyle = GlyphStyle.valueOf(obj.getString("glyphStyle")),
        )
    }
}
