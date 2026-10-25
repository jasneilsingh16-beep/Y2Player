package com.schulzcode.y2player.skin

/** All coordinates are in a skin's logical viewport. Drawing and input share these bounds. */
data class SkinRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    fun contains(px: Float, py: Float) = px >= x && py >= y && px < x + width && py < y + height
}

data class SkinNode(
    val type: String,
    val bounds: SkinRect,
    val text: String = "",
    val color: String = "primaryText",
    val background: String = "surface",
    val size: Float = 16f,
    val radius: Float = 0f,
    val stroke: Float = 0f,
    val align: String = "left",
    val bold: Boolean = false,
    val whenState: String = "always",
    val action: String = "",
    val asset: String = "",
    val columns: Int = 1,
    val rowHeight: Float = 48f,
    val gap: Float = 4f,
    val segments: Int = 0,
    val children: List<SkinNode> = emptyList(),
    val lines: Int = 1,
    val font: String = "",
    val lineSpacing: Float = 1f,
    val overflow: String = "ellipsis",
    val verticalAlign: String = "center",
    val imageFit: String = "stretch",
    val imageX: Float = .5f,
    val imageY: Float = .5f,
    val opacity: Float = 1f,
    val borderColor: String = "",
    val borderWidth: Float = 0f,
    val gradient: SkinGradient? = null,
    val points: List<Pair<Float, Float>> = emptyList(),
    val closed: Boolean = false,
    val orientation: String = "horizontal",
    val seekable: Boolean = false,
    val layout: String = "absolute",
    val padding: Float = 0f,
    val anchor: String = "topLeft",
    val weight: Float = 0f,
    val listMode: String = "grid",
    val rowWidth: Float = 96f,
    val scrolling: String = "page",
    val keyStyle: SkinKeyStyle = SkinKeyStyle(),
    val states: Map<String, SkinVisual> = emptyMap(),
    val focus: SkinFocus? = null,
    val disabled: Boolean = false,
    val transitionMs: Int = 0,
    val marqueeSpeed: Float = 24f
)

data class Skin(
    val id: String,
    val name: String,
    val author: String,
    val width: Float,
    val height: Float,
    val colors: Map<String, Int>,
    val font: String,
    val screens: Map<String, List<SkinNode>>,
    val components: Map<String, List<SkinNode>>,
    val formatVersion: Int = 1,
    val navigation: Map<String, SkinNavigation> = emptyMap()
) {
    fun screen(code: String) = screens[code] ?: screens.getValue("default")
}

object SkinIds {
    const val CLASSIC = "classic"
    const val LIGHT = "classic-light"
    fun valid(id: String) = id.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))
}

data class SkinSummary(val id: String, val name: String, val author: String = "")

/** Pure state snapshot, so the reducer never depends on files or Android rendering. */
data class SkinCatalogState(
    val available: List<SkinSummary> = listOf(SkinSummary(SkinIds.LIGHT, "Porcelain"), SkinSummary(SkinIds.CLASSIC, "Classic Dark")),
    val errors: List<String> = emptyList(),
    val revision: Int = 0
) {
    fun name(id: String) = available.firstOrNull { it.id == id }?.name ?: "Classic (skin unavailable)"
}

data class SkinGradient(val endColor: String, val direction: String = "vertical")
data class SkinFocus(val id: String, val group: String = "main", val order: Int = 0,
    val previous: String = "", val next: String = "")
data class SkinNavigation(val initial: String = "", val wrap: Boolean = true)
data class SkinKeyStyle(val gap: Float = 1f, val radius: Float = 0f,
    val color: String = "primaryText", val background: String = "surface",
    val focusColor: String = "accent", val focusBackground: String = "focusSurface",
    val borderColor: String = "accent", val borderWidth: Float = 0f)
/** State overrides deliberately cannot change geometry or actions. */
data class SkinVisual(val color: String? = null, val background: String? = null,
    val opacity: Float? = null, val borderColor: String? = null, val borderWidth: Float? = null,
    val radius: Float? = null, val size: Float? = null, val bold: Boolean? = null) {
    fun apply(node: SkinNode) = node.copy(color = color ?: node.color, background = background ?: node.background,
        opacity = opacity ?: node.opacity, borderColor = borderColor ?: node.borderColor,
        borderWidth = borderWidth ?: node.borderWidth, radius = radius ?: node.radius,
        size = size ?: node.size, bold = bold ?: node.bold)
}
