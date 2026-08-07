package dev.cannoli.scorza.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

object LocaleOverride {
    private const val PREFS = "cannoli_locale"
    private const val KEY = "language"

    fun currentTag(base: Context): String =
        base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    fun persist(base: Context, tag: String) {
        base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
    }

    fun configurationFor(base: Configuration, tag: String): Configuration =
        Configuration(base).apply { setLocales(LocaleList.forLanguageTags(tag.ifEmpty { "en" })) }

    fun wrap(base: Context): Context {
        val tag = currentTag(base)
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }
}
