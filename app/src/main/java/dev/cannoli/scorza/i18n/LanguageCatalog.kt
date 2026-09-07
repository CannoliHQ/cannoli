package dev.cannoli.scorza.i18n

data class LanguageOption(
    val tag: String,
    val nativeName: String,
    val coverageSample: String?,
)

object LanguageCatalog {
    // Only languages translated enough to be usable are offered here. es-419 is missing on
    // purpose rather than by oversight: crowdin.yml collects it into values-b+es+419, but not
    // one string has been translated yet, so offering it would render as English. Add it when
    // the translations land.
    val ALL: List<LanguageOption> = listOf(
        LanguageOption("en", "English", null),
        LanguageOption("zh-CN", "简体中文", "简体中文"),
        LanguageOption("fr-FR", "Français", null),
        LanguageOption("de-DE", "Deutsch", null),
        LanguageOption("el-GR", "Ελληνικά", "Ελληνικά"),
        LanguageOption("it-IT", "Italiano", null),
        LanguageOption("ja-JP", "日本語", "日本語"),
        LanguageOption("pt-BR", "Português (Brasil)", null),
        LanguageOption("pt-PT", "Português (Portugal)", null),
        LanguageOption("es-ES", "Español (España)", null),
        LanguageOption("uk-UA", "Українська", "Українська"),
    )

    fun byTag(tag: String): LanguageOption? = ALL.firstOrNull { it.tag == tag }
}
