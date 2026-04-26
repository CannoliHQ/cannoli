package dev.cannoli.scorza.input.screen

import dev.cannoli.scorza.input.ScreenInputHandler
import dev.cannoli.scorza.navigation.NavigationController

class ScrollListInputHandler(
    private val nav: NavigationController,
    private val itemCount: () -> Int,
    private val selectedIndex: () -> Int,
    private val onMove: (newIndex: Int) -> Unit,
    private val onConfirm: () -> Unit,
    private val onBack: () -> Unit,
    private val onStart: (() -> Unit)? = null,
) : ScreenInputHandler {

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
