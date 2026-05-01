package dev.cannoli.scorza.util

object LoggingPrefs {
    enum class Category { FILE_SCANNER, ROM_SCAN, INPUT, SESSION }

    @Volatile var fileScanner: Boolean = false
    @Volatile var romScan: Boolean = false
    @Volatile var input: Boolean = false
    @Volatile var session: Boolean = false

    fun isEnabled(category: Category): Boolean = when (category) {
        Category.FILE_SCANNER -> fileScanner
        Category.ROM_SCAN -> romScan
        Category.INPUT -> input
        Category.SESSION -> session
    }

    fun set(category: Category, enabled: Boolean) {
        when (category) {
            Category.FILE_SCANNER -> fileScanner = enabled
            Category.ROM_SCAN -> romScan = enabled
            Category.INPUT -> input = enabled
            Category.SESSION -> session = enabled
        }
    }
}
