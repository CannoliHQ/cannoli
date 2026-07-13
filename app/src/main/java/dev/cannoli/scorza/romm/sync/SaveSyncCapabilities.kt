package dev.cannoli.scorza.romm.sync

object SaveSyncCapabilities {

    fun supportsSaveSync(version: String?): Boolean {
        if (version.isNullOrEmpty()) return false
        val core = version.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        val parts = core.split('.')
        if (parts.size < 3) return false

        val nums = IntArray(3)
        var prerelease = false
        for (i in 0..2) {
            val digits = parts[i].takeWhile(Char::isDigit)
            nums[i] = digits.toIntOrNull() ?: return false
            if (digits.length != parts[i].length) prerelease = true
        }
        val (major, minor, patch) = nums.toList()

        if (major != 4) return major > 4
        if (minor != 9) return minor > 9
        if (patch > 0) return true
        // Pre-releases of 4.9.0 itself predate the sync API, so they sort below the floor.
        return !prerelease
    }
}
