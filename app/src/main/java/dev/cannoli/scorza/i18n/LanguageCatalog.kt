package dev.cannoli.scorza.i18n

data class LanguageOption(
    val tag: String,
    val nativeName: String,
    val coverageSample: String?,
)

object LanguageCatalog {
    // Only languages translated enough to be usable are offered here. Add a
    // language once it crosses a usable completion bar on Crowdin.
    val ALL: List<LanguageOption> = listOf(
        LanguageOption("en", "English", null),
        LanguageOption("fr-FR", "Français", null),
        LanguageOption("pt-PT", "Português (Portugal)", null),
        LanguageOption("uk-UA", "Українська", "Українська"),
        LanguageOption("zh-CN", "简体中文", "简体中文"),
    )

    fun byTag(tag: String): LanguageOption? = ALL.firstOrNull { it.tag == tag }
}
