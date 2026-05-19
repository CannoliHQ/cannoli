package dev.cannoli.scorza.input

interface DialogPrecedence {
    fun onUp(): Boolean = false
    fun onDown(): Boolean = false
    fun onLeft(): Boolean = false
    fun onRight(): Boolean = false
    fun onConfirm(): Boolean = false
    fun onBack(): Boolean = false
    fun onStart(): Boolean = false
    fun onSelect(): Boolean = false
    fun onSelectUp(): Boolean = false
    fun onNorth(): Boolean = false
    fun onWest(): Boolean = false
    fun onL1(): Boolean = false
    fun onR1(): Boolean = false
    fun onL2(): Boolean = false
    fun onR2(): Boolean = false
    fun cancelSelectHold() {}
}
