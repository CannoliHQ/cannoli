package dev.cannoli.scorza.input.autoconfig

/**
 * Extra device names one physical pad reports. Android merges a pad's HID nodes into a single
 * InputDevice named after whichever node enumerated first, so the same controller can come back as
 * "GameSir-Pocket 1" or "GameSir-Pocket 1 Keyboard" across reconnects.
 */
object DeviceAliases {

    const val KEY = "cannoli_device_aliases"

    fun parse(value: String): List<String> =
        value.split('|').map { it.trim() }.filter { it.isNotEmpty() }

    fun format(aliases: List<String>): String = aliases.joinToString("|")
}
