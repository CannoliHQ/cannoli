package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.model.Rom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LaunchState @Inject constructor() {
    @Volatile var launching: Boolean = false
    @Volatile var lastLaunched: Rom? = null
}
