package dev.cannoli.scorza.input

data class DeviceMapping(
    val id: String,
    val displayName: String,
    val match: DeviceMatchRule,
    val bindings: Map<CanonicalButton, List<InputBinding>>,
    val menuConfirm: CanonicalButton = CanonicalButton.BTN_EAST,
    val menuBack: CanonicalButton = CanonicalButton.BTN_SOUTH,
    val glyphStyle: GlyphStyle = GlyphStyle.PLUMBER,
    val excludeFromGameplay: Boolean = false,
    val defaultControllerTypeId: Int? = null,
    val source: MappingSource,
    val userEdited: Boolean = false,
    // Lines from the cfg this mapping was imported from that the model does not own, kept in file
    // order so rewriting the file does not strip what RetroArch still reads from it.
    val unmodeledLines: List<String> = emptyList(),
)
