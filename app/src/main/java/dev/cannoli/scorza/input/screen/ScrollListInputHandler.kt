package dev.cannoli.scorza.input.screen

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
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
        val page = nav.currentPageSize.coerceAtLeast(1)
        val count = itemCount()
        if (count == 0) return
        val newIdx = (selectedIndex() - page).coerceAtLeast(0)
        onMove(newIdx)
        nav.currentFirstVisible = newIdx
    }

    override fun onRight() {
        val page = nav.currentPageSize.coerceAtLeast(1)
        val count = itemCount()
        if (count == 0) return
        val newIdx = (selectedIndex() + page).coerceAtMost(count - 1)
        onMove(newIdx)
        nav.currentFirstVisible = newIdx
    }

    override fun onConfirm() = onConfirm.invoke()
    override fun onBack() = onBack.invoke()
    override fun onStart() = onStart?.invoke() ?: Unit
}
