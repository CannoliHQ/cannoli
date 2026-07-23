package dev.cannoli.scorza.romm

import kotlinx.serialization.json.Json

internal val rommJson: Json = Json {
    ignoreUnknownKeys = true
}
