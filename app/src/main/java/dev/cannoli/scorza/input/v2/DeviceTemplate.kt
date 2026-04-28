package dev.cannoli.scorza.input.v2

data class DeviceTemplate(
    val id: String,
    val displayName: String,
    val match: DeviceMatchRule,
    val bindings: Map<CanonicalButton, List<InputBinding>>,
    val menuConfirm: CanonicalButton = CanonicalButton.BTN_EAST,
    val menuBack: CanonicalButton = CanonicalButton.BTN_SOUTH,
    val glyphStyle: GlyphStyle = GlyphStyle.PLUMBER,
    val excludeFromGameplay: Boolean = false,
    val defaultControllerTypeId: Int? = null,
    val source: TemplateSource,
    val userEdited: Boolean = false,
)
