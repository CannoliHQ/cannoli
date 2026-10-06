package dev.cannoli.scorza.romm

object RommCapabilities {

    fun isSupported(version: String?): Boolean = atLeastFloor(version) == true

    fun isKnownUnsupported(version: String?): Boolean = atLeastFloor(version) == false

    private fun atLeastFloor(version: String?): Boolean? {
        if (version.isNullOrEmpty()) return null
        val core = version.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        val parts = core.split('.')
        if (parts.size < 3) return null

        val nums = IntArray(3)
        var prerelease = false
        for (i in 0..2) {
            val digits = parts[i].takeWhile(Char::isDigit)
            nums[i] = digits.toIntOrNull() ?: return null
            if (digits.length != parts[i].length) prerelease = true
        }
        val (major, minor, patch) = nums.toList()

        if (major != 5) return major > 5
        if (minor != 0) return minor > 0
        if (patch > 0) return true
        return !prerelease
    }
}
