package dev.cannoli.scorza.romm.download

import dev.cannoli.scorza.download.DownloadItem
import dev.cannoli.scorza.download.DownloadKind
import dev.cannoli.scorza.romm.RommFirmware
import dev.cannoli.scorza.romm.RommGame

/**
 * Builds the queue's generic item from a RomM one.
 *
 * The key, the name and the size used to be computed properties on a RomM-shaped item. They belong
 * to the queue now, so they are filled in here: one place that knows a RomM transfer is identified
 * by kind and id, rather than every call site repeating it.
 */
fun rommItem(game: RommGame, tag: String, kind: DownloadKind = DownloadKind.ROM) = DownloadItem(
    key = "${kind.name}-${game.id}",
    displayName = game.name,
    kind = kind,
    sizeBytes = game.sizeBytes,
    tag = tag,
    payload = RommPayload(rommId = game.id, game = game),
)

fun firmwareItem(firmware: RommFirmware, tag: String) = DownloadItem(
    key = "${DownloadKind.FIRMWARE.name}-${firmware.id}",
    displayName = firmware.fileName,
    kind = DownloadKind.FIRMWARE,
    sizeBytes = firmware.sizeBytes,
    tag = tag,
    payload = RommPayload(rommId = firmware.id, firmware = firmware),
)
