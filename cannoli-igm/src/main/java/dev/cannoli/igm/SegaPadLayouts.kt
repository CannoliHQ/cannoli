package dev.cannoli.igm

import dev.cannoli.ui.ButtonLabelSet

private class SegaLayout(
    val a: RemapButton,
    val b: RemapButton,
    val c: RemapButton,
    val x: RemapButton,
    val y: RemapButton,
    val z: RemapButton,
    val l: RemapButton? = null,
    val r: RemapButton? = null,
)

object SegaPadLayouts {

    private val GENESIS = SegaLayout(
        a = RemapButton.WEST, b = RemapButton.SOUTH, c = RemapButton.EAST,
        x = RemapButton.L, y = RemapButton.NORTH, z = RemapButton.R,
    )
    private val BEETLE_SATURN = SegaLayout(
        a = RemapButton.SOUTH, b = RemapButton.EAST, c = RemapButton.R,
        x = RemapButton.WEST, y = RemapButton.NORTH, z = RemapButton.L,
        l = RemapButton.L2, r = RemapButton.R2,
    )
    private val YABASANSHIRO = SegaLayout(
        a = RemapButton.SOUTH, b = RemapButton.EAST, c = RemapButton.L,
        x = RemapButton.WEST, y = RemapButton.NORTH, z = RemapButton.R,
        l = RemapButton.L2, r = RemapButton.R2,
    )

    private val BY_CORE = mapOf(
        "genesis_plus_gx_libretro" to GENESIS,
        "genesis_plus_gx_wide_libretro" to GENESIS,
        "picodrive_libretro" to GENESIS,
        "mednafen_saturn_libretro" to BEETLE_SATURN,
        "yabasanshiro_libretro" to YABASANSHIRO,
    )

    fun routed(coreId: String, labelSet: ButtonLabelSet): Map<Int, Int> {
        if (labelSet != ButtonLabelSet.HEDGEHOG_6) return emptyMap()
        val layout = BY_CORE[coreId] ?: return emptyMap()
        val l = layout.l?.id ?: ButtonRemap.UNBOUND
        val r = layout.r?.id ?: ButtonRemap.UNBOUND
        return mapOf(
            RemapButton.EAST.id to layout.a.id,
            RemapButton.SOUTH.id to layout.b.id,
            RemapButton.L3.id to layout.c.id,
            RemapButton.NORTH.id to layout.x.id,
            RemapButton.WEST.id to layout.y.id,
            RemapButton.R3.id to layout.z.id,
            RemapButton.L.id to l,
            RemapButton.L2.id to l,
            RemapButton.R.id to r,
            RemapButton.R2.id to r,
        )
    }
}
