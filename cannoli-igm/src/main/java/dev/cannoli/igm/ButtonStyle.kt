package dev.cannoli.igm

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
