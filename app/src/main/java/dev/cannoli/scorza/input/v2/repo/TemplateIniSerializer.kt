package dev.cannoli.scorza.input.v2.repo

import dev.cannoli.scorza.input.v2.AnalogRole
import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.DeviceMatchRule
import dev.cannoli.scorza.input.v2.DeviceTemplate
import dev.cannoli.scorza.input.v2.GlyphStyle
import dev.cannoli.scorza.input.v2.HatDirection
import dev.cannoli.scorza.input.v2.InputBinding
import dev.cannoli.scorza.input.v2.TemplateSource
import dev.cannoli.scorza.util.IniParser

object TemplateIniSerializer {

    fun toIni(template: DeviceTemplate): String {
        val sb = StringBuilder()

        sb.appendLine("[meta]")
        sb.appendLine("display_name=${template.displayName}")
        sb.appendLine("source=${template.source.name}")
        sb.appendLine("user_edited=${template.userEdited}")
        sb.appendLine()

        sb.appendLine("[match]")
        template.match.name?.let { sb.appendLine("name=$it") }
        template.match.vendorId?.let { sb.appendLine("vendor_id=$it") }
        template.match.productId?.let { sb.appendLine("product_id=$it") }
        template.match.androidBuildModel?.let { sb.appendLine("android_build_model=$it") }
        template.match.sourceMask?.let { sb.appendLine("source_mask=$it") }
        sb.appendLine()

        sb.appendLine("[menu]")
        sb.appendLine("confirm=${template.menuConfirm.name}")
        sb.appendLine("back=${template.menuBack.name}")
        sb.appendLine()

        sb.appendLine("[glyph]")
        sb.appendLine("style=${template.glyphStyle.name}")
        sb.appendLine()

        sb.appendLine("[behavior]")
        sb.appendLine("exclude_from_gameplay=${template.excludeFromGameplay}")
        template.defaultControllerTypeId?.let { sb.appendLine("default_controller_type=$it") }
        sb.appendLine()

        for ((button, bindings) in template.bindings) {
            sb.appendLine("[binding.${button.name}]")
            bindings.forEachIndexed { index, binding ->
                sb.appendLine("$index=${encodeBinding(binding)}")
            }
            sb.appendLine()
        }

        return sb.toString()
    }

    fun fromIni(id: String, ini: String): DeviceTemplate {
        val data = IniParser.parse(ini)

        val meta = data.getSection("meta")
        val match = data.getSection("match")
        val menu = data.getSection("menu")
        val glyph = data.getSection("glyph")
        val behavior = data.getSection("behavior")

        val bindings = mutableMapOf<CanonicalButton, List<InputBinding>>()
        for ((sectionName, entries) in data.sections) {
            if (!sectionName.startsWith("binding.")) continue
            val canonicalName = sectionName.removePrefix("binding.")
            val canonical = runCatching { CanonicalButton.valueOf(canonicalName) }.getOrNull()
                ?: continue
            val ordered = entries.entries
                .mapNotNull { (key, value) ->
                    val idx = key.toIntOrNull() ?: return@mapNotNull null
                    val parsed = decodeBinding(value) ?: return@mapNotNull null
                    idx to parsed
                }
                .sortedBy { it.first }
                .map { it.second }
            if (ordered.isNotEmpty()) bindings[canonical] = ordered
        }

        return DeviceTemplate(
            id = id,
            displayName = meta["display_name"] ?: id,
            match = DeviceMatchRule(
                name = match["name"],
                vendorId = match["vendor_id"]?.toIntOrNull(),
                productId = match["product_id"]?.toIntOrNull(),
                androidBuildModel = match["android_build_model"],
                sourceMask = match["source_mask"]?.toIntOrNull(),
            ),
            bindings = bindings,
            menuConfirm = menu["confirm"]
                ?.let { runCatching { CanonicalButton.valueOf(it) }.getOrNull() }
                ?: CanonicalButton.BTN_EAST,
            menuBack = menu["back"]
                ?.let { runCatching { CanonicalButton.valueOf(it) }.getOrNull() }
                ?: CanonicalButton.BTN_SOUTH,
            glyphStyle = glyph["style"]
                ?.let { runCatching { GlyphStyle.valueOf(it) }.getOrNull() }
                ?: GlyphStyle.GENERIC,
            excludeFromGameplay = behavior["exclude_from_gameplay"]?.toBoolean() ?: false,
            defaultControllerTypeId = behavior["default_controller_type"]?.toIntOrNull(),
            source = meta["source"]
                ?.let { runCatching { TemplateSource.valueOf(it) }.getOrNull() }
                ?: TemplateSource.USER_WIZARD,
            userEdited = meta["user_edited"]?.toBoolean() ?: false,
        )
    }

    private fun encodeBinding(b: InputBinding): String = when (b) {
        is InputBinding.Button -> "button:${b.keyCode}"
        is InputBinding.Hat ->
            "hat:axis=${b.axis},direction=${b.direction.name},threshold=${b.threshold}"
        is InputBinding.Axis ->
            "axis:axis=${b.axis},resting=${b.restingValue},active_min=${b.activeMin}," +
                "active_max=${b.activeMax},threshold=${b.digitalThreshold}," +
                "invert=${b.invert},role=${b.analogRole.name}"
    }

    private fun decodeBinding(raw: String): InputBinding? {
        val colon = raw.indexOf(':')
        if (colon <= 0) return null
        val kind = raw.substring(0, colon)
        val rest = raw.substring(colon + 1)
        return when (kind) {
            "button" -> rest.toIntOrNull()?.let { InputBinding.Button(it) }
            "hat" -> {
                val map = rest.toKvMap()
                val axis = map["axis"]?.toIntOrNull() ?: return null
                val direction = map["direction"]
                    ?.let { runCatching { HatDirection.valueOf(it) }.getOrNull() }
                    ?: return null
                val threshold = map["threshold"]?.toFloatOrNull() ?: 0.5f
                InputBinding.Hat(axis, direction, threshold)
            }
            "axis" -> {
                val map = rest.toKvMap()
                val axis = map["axis"]?.toIntOrNull() ?: return null
                val resting = map["resting"]?.toFloatOrNull() ?: return null
                val activeMin = map["active_min"]?.toFloatOrNull() ?: return null
                val activeMax = map["active_max"]?.toFloatOrNull() ?: return null
                val threshold = map["threshold"]?.toFloatOrNull() ?: 0.5f
                val invert = map["invert"]?.toBoolean() ?: false
                val role = map["role"]
                    ?.let { runCatching { AnalogRole.valueOf(it) }.getOrNull() }
                    ?: AnalogRole.DIGITAL_BUTTON
                InputBinding.Axis(axis, resting, activeMin, activeMax, threshold, invert, role)
            }
            else -> null
        }
    }

    private fun String.toKvMap(): Map<String, String> =
        split(",").mapNotNull {
            val eq = it.indexOf('=')
            if (eq <= 0) null else it.substring(0, eq).trim() to it.substring(eq + 1).trim()
        }.toMap()
}
