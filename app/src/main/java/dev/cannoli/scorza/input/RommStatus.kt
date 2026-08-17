package dev.cannoli.scorza.input

import dev.cannoli.ui.components.RommStatus

fun rommStatusFrom(isConfigured: Boolean, host: String, serverVersion: String?): RommStatus? =
    if (isConfigured) RommStatus(host, serverVersion != null) else null
