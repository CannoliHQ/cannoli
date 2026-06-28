package dev.cannoli.scorza.romm.sync

object SaveSyncCapabilities {

    fun supportsSaveSync(version: String?): Boolean {
        if (version.isNullOrEmpty()) return false
        // Only pure numeric semver (no pre-release suffixes) is accepted.
        val parts = version.split(".").takeIf { it.size == 3 } ?: return false
        val nums = parts.map { it.toIntOrNull() ?: return false }
        val (major, minor, patch) = nums
        return major > 4 || (major == 4 && (minor > 9 || (minor == 9 && patch >= 0)))
    }
}
