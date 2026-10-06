package dev.cannoli.scorza.romm.download

import dev.cannoli.scorza.download.DownloadItem
import dev.cannoli.scorza.download.DownloadKind
import dev.cannoli.scorza.romm.RommFile
import dev.cannoli.scorza.romm.RommFirmware
import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.scorza.romm.RommHacks
import java.io.File

fun rommItem(game: RommGame, tag: String, kind: DownloadKind = DownloadKind.ROM) = DownloadItem(
    key = "${kind.name}-${game.id}",
    displayName = game.name,
    kind = kind,
    sizeBytes = game.sizeBytes,
    tag = tag,
    payload = RommPayload(rommId = game.id, game = game),
)

fun rommFileItem(game: RommGame, file: RommFile, tag: String) = DownloadItem(
    key = "${DownloadKind.ROM.name}-${game.id}-${file.id}",
    displayName = if (RommHacks.isHack(file)) File(file.fileName).nameWithoutExtension else game.name,
    kind = DownloadKind.ROM,
    sizeBytes = file.sizeBytes,
    tag = tag,
    payload = RommPayload(rommId = game.id, game = RommHacks.asSingleFile(game, file), file = file),
)

fun firmwareItem(firmware: RommFirmware, tag: String) = DownloadItem(
    key = "${DownloadKind.FIRMWARE.name}-${firmware.id}",
    displayName = firmware.fileName,
    kind = DownloadKind.FIRMWARE,
    sizeBytes = firmware.sizeBytes,
    tag = tag,
    payload = RommPayload(rommId = firmware.id, firmware = firmware),
)

fun rommPickedItem(game: RommGame, tag: String, hackFile: RommFile? = null): DownloadItem {
    val one = hackFile ?: RommHacks.baseDownloadFile(game)
    return if (one != null) rommFileItem(game, one, tag) else rommItem(game, tag)
}
