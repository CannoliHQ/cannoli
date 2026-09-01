package dev.cannoli.igm

/**
 * What the in-game menu needs from the emulator it is running inside.
 *
 * Every command here is queued onto RetroArch's own thread rather than performed, so nothing
 * returns a result and the menu learns what happened from a callback. Reading what is on disk is
 * not part of this: [dev.cannoli.core.SaveSlotStore] owns the save slot files.
 */
interface RetroArchBridge {

    fun reset()
    fun quit()

    /** Queues a write of the slot. It has not happened when this returns. */
    fun saveState(slot: Int)
    fun loadState(slot: Int)

    /** Puts back what the last save overwrote, and what the last load replaced. */
    fun undoSaveState()
    fun undoLoadState()

    /** Flips RetroArch's on-screen frame counter. */
    fun toggleShowFps() {}

    /**
     * Fast forward, as its two hotkeys mean it: [toggleFastForward] latches, [setFastForwardHeld]
     * runs only while held.
     *
     * RetroArch has no command for either. They are hotkeys it polls per frame, so these reach it
     * through a flag the poll reads rather than a queued command, which is what keeps its own
     * rules about pausing, netplay and audio muting in force.
     */
    fun toggleFastForward() {}

    fun setFastForwardHeld(held: Boolean) {}

    /**
     * Rewind, which only ever runs while held.
     *
     * Does nothing unless RetroArch's rewind buffer is on, which is what [rewindEnabled] answers:
     * the buffer costs real memory and is off by default, so a shortcut pressed without it should
     * say so rather than appear broken.
     */
    fun setRewindHeld(held: Boolean) {}

    val rewindEnabled: Boolean get() = false

    /**
     * Throws away the rewind history, for when the game has moved somewhere that history does not
     * lead back to. Does nothing when rewind is off, since then there is none.
     */
    fun resetRewindBuffer() {}

    /**
     * Switches between the shader this platform is set to and none.
     *
     * Reads the stored tier rather than keeping its own idea of what is applied, so the shortcut
     * and the settings tree cannot disagree about what the platform's shader is.
     */
    fun toggleShader() {}

    /** What [toggleShader] would turn on, or null when this game has no shader configured. */
    fun shaderToRestore(): String? = null

    /** RetroArch writes the auto slot itself while shutting down when this is on. */
    val savesOnQuit: Boolean

    /**
     * Makes RetroArch write the auto slot on the way out even when the user's setting says not to.
     *
     * Used by Save and Quit, which promises a save in its name. Done by turning RetroArch's own
     * shutdown save on rather than queueing a write: a queued save races the quit that follows it,
     * while the shutdown path is the one that already waits for the state and its thumbnail.
     */
    fun forceSaveOnQuit() {}

    /**
     * False when this session launched into hardcore, where RetroArch refuses to load a state.
     * Decided once at launch from the config, so pausing hardcore mid-session does not bring the
     * rows back until the next launch.
     *
     * RetroArch blocks only loading and still allows saving; both rows go anyway, because a save
     * that cannot be loaded in-mode is clutter. That is a deliberate divergence from RetroArch's
     * own quick menu, which keeps both.
     *
     * Not [hardcoreActive]: this is the launch-time latch, that is the live rc_client state. They
     * disagree after any pause, where the live flag goes false while this one holds.
     */
    val savestatesAllowed: Boolean get() = true

    val supportsAchievements: Boolean
    fun getAchievements(): List<AchievementInfo> = emptyList()

    fun getDiskCount(): Int
    fun getDiskIndex(): Int
    fun setDiskIndex(index: Int)

    fun openNativeMenu()
    fun setOnNativeMenuClosed(callback: () -> Unit)

    fun settingsProvider(): IgmSettingsProvider? = null

    /** [frameWidth, frameHeight, aspectNumerator, aspectDenominator], or null before the core loads. */
    fun coreGeometry(): IntArray? = null

    fun applyViewport(x: Int, y: Int, w: Int, h: Int): Boolean = false

    /** Hands the aspect index and integer-scale setting back to whatever the scaling row had set. */
    fun clearViewport(restoreAspectIdx: Int, restoreIntegerScale: Boolean): Boolean = false

    /** The scaling row owns these two; the fit reads them rather than overriding them. */
    fun raAspectIndex(): Int = 22

    fun raIntegerScale(): Boolean = false

    /** The value RetroArch's aspect ratio LUT holds for the current index, or 0 when unavailable. */
    fun raAspectValue(): Float = 0f

    /**
     * One row of RetroArch's live cheat list. [index] is the index every toggle must use: it is
     * observed by reading RetroArch back after a load, never inferred from the .cht file's order.
     */
    data class CheatRow(
        val index: Int,
        val desc: String,
        val code: String,
        val enabled: Boolean,
        val supported: Boolean,
    )

    /** Queues: drop the current list, load this file alone, clear every state, apply. */
    fun loadCheatFile(path: String) {}

    fun toggleCheat(index: Int) {}

    fun applyCheats() {}

    /** Fires on the main thread once a queued [loadCheatFile] has run. */
    fun setOnCheatsLoaded(callback: (List<CheatRow>) -> Unit) {}

    /**
     * Enabling a cheat pauses hardcore achievements, so the menu warns first when this is on.
     *
     * Not [savestatesAllowed]: this is the live rc_client state, that is the launch-time latch.
     * They disagree after any pause, where this goes false while the latch holds, so a new gate
     * has to say which of the two it means.
     */
    val hardcoreActive: Boolean get() = false
}
