package dev.cannoli.scorza.input

enum class MappingSource {
    BUNDLED,
    RETROARCH_AUTOCONFIG,
    USER_WIZARD,
    /** No profile matched this pad, so it has no bindings until the wizard gives it some. */
    UNIDENTIFIED,
}
