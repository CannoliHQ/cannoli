package dev.cannoli.scorza.input.screen

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dev.cannoli.scorza.input.PageJump
import dev.cannoli.scorza.input.ScreenInputHandler
import dev.cannoli.scorza.navigation.NavigationController

class ScrollListInputHandler @AssistedInject constructor(
    private val nav: NavigationController,
    @Assisted("itemCount") val itemCount: () -> Int,
    @Assisted("selectedIndex") val selectedIndex: () -> Int,
    @Assisted("onMove") val onMove: (newIndex: Int) -> Unit,
    @Assisted("onConfirm") val onConfirm: () -> Unit,
    @Assisted("onBack") val onBack: () -> Unit,
    @Assisted("onStart") val onStart: (() -> Unit)?,
    @Assisted("onWest") val onWest: (() -> Unit)?,
    @Assisted("onNorth") val onNorth: (() -> Unit)?,
    @Assisted("onLeft") val onLeftOverride: (() -> Unit)?,
    @Assisted("onRight") val onRightOverride: (() -> Unit)?,
    @Assisted("onSelect") val onSelectOverride: (() -> Unit)?,
) : ScreenInputHandler {

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("itemCount") itemCount: () -> Int,
            @Assisted("selectedIndex") selectedIndex: () -> Int,
            @Assisted("onMove") onMove: (newIndex: Int) -> Unit,
            @Assisted("onConfirm") onConfirm: () -> Unit,
            @Assisted("onBack") onBack: () -> Unit,
            @Assisted("onStart") onStart: (() -> Unit)?,
            @Assisted("onWest") onWest: (() -> Unit)? = null,
            @Assisted("onNorth") onNorth: (() -> Unit)? = null,
            @Assisted("onLeft") onLeft: (() -> Unit)? = null,
            @Assisted("onRight") onRight: (() -> Unit)? = null,
            @Assisted("onSelect") onSelect: (() -> Unit)? = null,
        ): ScrollListInputHandler
    }

    private fun wrap(delta: Int): Int {
        val count = itemCount()
        if (count == 0) return 0
        return (selectedIndex() + delta).mod(count)
    }

    override fun onUp() = onMove(wrap(-1))
    override fun onDown() = onMove(wrap(1))

    override fun onLeft() {
        if (onLeftOverride != null) {
            onLeftOverride.invoke()
            return
        }
        val newIdx = PageJump.compute(-1, itemCount(), selectedIndex(), nav.activeListState)
        if (newIdx != selectedIndex()) onMove(newIdx)
    }

    override fun onRight() {
        if (onRightOverride != null) {
            onRightOverride.invoke()
            return
        }
        val newIdx = PageJump.compute(1, itemCount(), selectedIndex(), nav.activeListState)
        if (newIdx != selectedIndex()) onMove(newIdx)
    }

    override fun onConfirm() = onConfirm.invoke()
    override fun onBack() = onBack.invoke()
    override fun onStart() = onStart?.invoke() ?: Unit
    override fun onWest() = onWest?.invoke() ?: Unit
    override fun onNorth() = onNorth?.invoke() ?: Unit
    override fun onSelect() = onSelectOverride?.invoke() ?: Unit
}
