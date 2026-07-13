package dev.cannoli.scorza.libretro

// An unbound HOLD_FF is stored as an empty set, and containsAll(emptySet()) is always true,
// so a chord cleared or removed while fast forward is held must release it explicitly.
fun shouldReleaseHoldFf(holdChord: Set<Int>?, pressedKeys: Set<Int>): Boolean =
    holdChord.isNullOrEmpty() || !pressedKeys.containsAll(holdChord)
