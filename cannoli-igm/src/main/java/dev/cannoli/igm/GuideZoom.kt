package dev.cannoli.igm

// Single source of truth for the guide viewer's zoom levels, shared by GuideScreen (rendering) and
// every host's zoom-cycle handler (IGMController, the launcher's GuideInputHandler, LibretroActivity).
// pdfScales and txtFontSizes are index-parallel; textZoom is a 1-based index into them.
object GuideZoom {
    val pdfScales = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f)
    val txtFontSizes = listOf(14, 15, 16, 17, 18, 24)
    val levels = pdfScales.size
}
