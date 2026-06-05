package dev.cannoli.scorza.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object LoggingPrefs {
    enum class Category { ROM_SCAN, INPUT, SESSION, KITCHEN, STORAGE }

    var romScan: Boolean by mutableStateOf(false)
    var input: Boolean by mutableStateOf(false)
    var session: Boolean by mutableStateOf(false)
    var kitchen: Boolean by mutableStateOf(false)
    var storage: Boolean by mutableStateOf(false)

    fun isEnabled(category: Category): Boolean = when (category) {
        Category.ROM_SCAN -> romScan
        Category.INPUT -> input
        Category.SESSION -> session
        Category.KITCHEN -> kitchen
        Category.STORAGE -> storage
    }

    fun set(category: Category, enabled: Boolean) {
        when (category) {
            Category.ROM_SCAN -> romScan = enabled
            Category.INPUT -> input = enabled
            Category.SESSION -> session = enabled
            Category.KITCHEN -> kitchen = enabled
            Category.STORAGE -> storage = enabled
        }
    }
}
