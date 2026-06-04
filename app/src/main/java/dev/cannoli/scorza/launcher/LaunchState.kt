package dev.cannoli.scorza.launcher

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LaunchState @Inject constructor() {
    @Volatile var launching: Boolean = false
}
