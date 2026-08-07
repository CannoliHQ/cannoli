package dev.cannoli.scorza.i18n

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * Hands the launcher's chosen language to everything below it. [languageTag] is null until settings
 * have loaded, which leaves the platform context and configuration in place.
 */
@Composable
fun ProvideLocalizedResources(languageTag: String?, content: @Composable () -> Unit) {
    val baseContext = LocalContext.current
    val localizedContext = if (languageTag != null) {
        remember(languageTag, baseContext) { localeContext(baseContext, languageTag) }
    } else {
        baseContext
    }
    val baseConfiguration = LocalConfiguration.current
    val localizedConfiguration = if (languageTag != null) {
        LocaleOverride.configurationFor(baseConfiguration, languageTag)
    } else {
        baseConfiguration
    }
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedConfiguration,
        content = content,
    )
}

private fun localeContext(base: Context, tag: String): Context =
    base.createConfigurationContext(LocaleOverride.configurationFor(base.resources.configuration, tag))
