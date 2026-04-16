package dev.cannoli.igm

const val START_GLYPH = "\uDB81\uDC0A"
const val ELLIPSIS = "..."

data class ButtonStyle(
    val labelSet: ButtonLabelSet = ButtonLabelSet.PLUMBER,
    val confirmButton: ConfirmButton = ConfirmButton.EAST,
) {
    val south: String get() = labelSet.south
    val east: String get() = labelSet.east
    val west: String get() = labelSet.west
    val north: String get() = labelSet.north

    val confirm: String get() = when (confirmButton) {
        ConfirmButton.SOUTH -> labelSet.south
        ConfirmButton.EAST -> labelSet.east
    }
    val back: String get() = when (confirmButton) {
        ConfirmButton.SOUTH -> labelSet.east
        ConfirmButton.EAST -> labelSet.south
    }
}
