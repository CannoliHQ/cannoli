package dev.cannoli.scorza.sigil

/**
 * The platform's own id for a game, read out of the rom, or null when this build cannot read one.
 *
 * The library loads into a flag rather than an init block, for the reason RaHasher records: an init
 * block throws at whatever first touches the object, outside any try this object owns.
 *
 * The shim answers with seven strings whether it succeeded or not, status first, so a failure keeps
 * its reason for the log instead of collapsing into a bare null.
 */
object SigilNative {
    val isAvailable: Boolean = try {
        System.loadLibrary("sigil_jni")
        true
    } catch (_: Throwable) {
        false
    }

    fun extract(path: String, platform: SigilPlatform): Result {
        if (!isAvailable) return Result.Unavailable
        val fields = try {
            nativeExtract(path, platform.ordinal)
        } catch (_: Throwable) {
            null
        } ?: return Result.Failed("no result")

        if (fields.size != FIELD_COUNT) return Result.Failed("malformed result")
        if (fields[0] != "OK") return Result.Failed(fields[0])

        val usage = enumValueOrNull<SaveUsage>(fields[4]) ?: return Result.Failed("unknown usage ${fields[4]}")
        val source = enumValueOrNull<IdSource>(fields[5]) ?: return Result.Failed("unknown source ${fields[5]}")
        return Result.Extracted(
            GameId(
                titleId = fields[1],
                saveId = fields[2],
                rawSerial = fields[3],
                usage = usage,
                source = source,
                experimental = fields[6] == "1",
            )
        )
    }

    sealed interface Result {
        data class Extracted(val id: GameId) : Result
        data class Failed(val reason: String) : Result
        data object Unavailable : Result
    }

    private const val FIELD_COUNT = 7

    private inline fun <reified T : Enum<T>> enumValueOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }

    private external fun nativeExtract(path: String, platform: Int): Array<String>?
}
