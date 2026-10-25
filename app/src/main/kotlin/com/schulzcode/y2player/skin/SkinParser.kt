package com.schulzcode.y2player.skin

import org.json.JSONArray
import org.json.JSONObject

/** Versioned, bounded declarative input. Unknown fields are errors, not silently ignored typos. */
object SkinParser {
    const val MAX_BYTES = 256 * 1024
    val types = setOf("rect", "text", "image", "artwork", "progress", "rows", "group", "component", "keyboard", "icon", "circle", "line", "path")
    val actions = setOf("", "confirm", "back", "home", "nowPlaying", "playPause", "next", "previous", "left", "right", "volumeUp", "volumeDown", "shuffle", "repeat", "nextGroup", "previousGroup")
    val bindings = setOf("screen.title", "track.title", "track.artist", "track.album", "playback.elapsed", "playback.duration", "playback.status", "battery", "position", "row.title", "row.subtitle", "row.number", "row.trailing", "search.query", "fm.frequency", "fm.status", "message", "alphabet", "empty.message", "playback.details", "volume", "volume.mode", "shuffle", "repeat", "track.codec", "track.sampleRate", "track.bitDepth", "track.bitrate", "track.channels", "device.charging", "device.model", "device.storageAvailable", "display.brightness", "clock")
    val conditions = setOf("always", "focused", "unfocused", "active", "unavailable", "playing", "paused", "hasTrack", "noTrack", "message", "alphabet", "emptyRows", "pressed", "disabled", "enabled", "charging")
    val visualFields = setOf("color", "background", "opacity", "borderColor", "borderWidth", "radius", "size", "bold")
    val v1Fields = setOf("type", "bounds", "text", "color", "background", "size", "radius", "stroke", "align", "bold", "when", "action", "asset", "columns", "rowHeight", "gap", "segments", "children", "lines")
    val v2Fields = v1Fields + setOf("font", "lineSpacing", "overflow", "verticalAlign", "imageFit", "imageX", "imageY", "opacity", "borderColor", "borderWidth", "gradient", "points", "closed", "orientation", "seekable", "layout", "padding", "anchor", "weight", "listMode", "rowWidth", "scrolling", "keyStyle", "states", "focus", "disabled", "transitionMs", "marqueeSpeed")
    private val token = Regex("\\{([^{}]+)\\}")

    fun parse(source: String): Skin {
        require(source.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "skin.json exceeds 256 KiB" }
        var nesting = 0
        var quoted = false
        var escaped = false
        source.forEach { ch ->
            if (quoted) {
                if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
            } else when (ch) {
                '"' -> quoted = true
                '{', '[' -> { nesting++; require(nesting <= 64) { "JSON nesting exceeds 64" } }
                '}', ']' -> nesting--
            }
        }
        val raw = JSONObject(source)
        val version = raw.getInt("formatVersion")
        require(version in 1..2) { "Unsupported formatVersion $version; update Y2Player" }
        fields(raw, setOf("formatVersion", "id", "name", "author", "viewport", "colors", "font", "screens", "components") + if (version == 2) setOf("styles", "navigation") else emptySet(), "skin")
        val json = if (version == 2) SkinCompiler.compile(raw) else raw
        val id = json.getString("id")
        require(SkinIds.valid(id) && id !in setOf(SkinIds.CLASSIC, SkinIds.LIGHT)) { "Invalid or reserved skin id" }
        val name = json.getString("name").also { require(it.isNotBlank() && it.length <= 64) { "Invalid skin name" } }
        val viewport = json.getJSONArray("viewport")
        require(viewport.length() == 2)
        val width = finite(viewport.getDouble(0), 240f, 1920f)
        val height = finite(viewport.getDouble(1), 240f, 1920f)
        val colorsJson = json.getJSONObject("colors")
        val colors = colorsJson.keys().asSequence().associateWith { parseColor(colorsJson.getString(it)) }
        require(colors.keys.containsAll(setOf("background", "surface", "primaryText", "secondaryText", "accent", "focusSurface", "warning"))) { "Missing required palette colors" }
        var count = 0
        fun nodes(array: JSONArray, depth: Int): List<SkinNode> {
            require(depth <= 12) { "Layout nesting exceeds 12" }
            return (0 until array.length()).map { index ->
                require(++count <= if (version == 1) 512 else 4096) { "Compiled layout exceeds node limit" }
                val n = array.getJSONObject(index)
                fields(n, if (version == 1) v1Fields else v2Fields, "node")
                if (version == 2) {
                    for (key in setOf("size", "radius", "stroke", "rowHeight", "gap", "lineSpacing", "imageX", "imageY", "opacity", "borderWidth", "padding", "weight", "rowWidth", "marqueeSpeed"))
                        if (n.has(key)) require(n.get(key) is Number) { "$key must be a number" }
                    for (key in setOf("columns", "segments", "lines", "transitionMs")) if (n.has(key)) {
                        val value = n.get(key); require(value is Number && value.toDouble() == value.toInt().toDouble()) { "$key must be an integer" }
                    }
                    for (key in setOf("bold", "closed", "seekable", "disabled")) if (n.has(key)) require(n.get(key) is Boolean) { "$key must be a boolean" }
                    for (key in setOf("gradient", "keyStyle", "states", "focus")) if (n.has(key)) require(n.get(key) is JSONObject) { "$key must be an object" }
                    for (key in setOf("points", "children")) if (n.has(key)) require(n.get(key) is JSONArray) { "$key must be an array" }
                }
                val type = n.getString("type").also { require(it in types && (version == 2 || it !in setOf("circle", "line", "path"))) { "Unknown node type: $it" } }
                val b = n.getJSONArray("bounds")
                require(b.length() == 4) { "bounds must be [x, y, width, height]" }
                val bounds = SkinRect(finite(b.getDouble(0), 0f, 1920f), finite(b.getDouble(1), 0f, 1920f), finite(b.getDouble(2), 1f, 1920f), finite(b.getDouble(3), 1f, 1920f))
                fun number(key: String, default: Double, min: Float, max: Float) = finite(if (n.has(key)) n.getDouble(key) else default, min, max)
                fun color(key: String, default: String) = n.optString(key, default).also { require(it in colors || it.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) { "Unknown color: $it" } }
                val text = n.optString("text").also { value ->
                    require(value.length <= 1024)
                    token.findAll(value).forEach { require(it.groupValues[1] in bindings) { "Unknown binding: ${it.value}" } }
                }
                val columns = n.optInt("columns", 1).also { require(it in 1..8) }
                val rowHeight = number("rowHeight", 48.0, 20f, 1920f)
                val gap = number("gap", 4.0, 0f, 64f)
                if (type == "rows") require(rowHeight <= bounds.height && bounds.width > gap * (columns - 1)) { "Rows do not fit their bounds" }
                fun enum(key: String, default: String, allowed: Set<String>) = n.optString(key, default).also { require(it in allowed) { "Invalid $key: $it" } }
                fun visual(obj: JSONObject): SkinVisual {
                    fields(obj, visualFields, "state")
                    fun col(key: String) = if (obj.has(key)) obj.getString(key).also { require(it in colors || it.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) { "Unknown color: $it" } } else null
                    fun num(key: String, min: Float, max: Float) = if (obj.has(key)) finite(obj.getDouble(key), min, max) else null
                    return SkinVisual(col("color"), col("background"), num("opacity", 0f, 1f), col("borderColor"), num("borderWidth", 0f, 16f), num("radius", 0f, 256f), num("size", 6f, 128f), if (obj.has("bold")) obj.getBoolean("bold") else null)
                }
                val states = n.optJSONObject("states")?.let { obj -> obj.keys().asSequence().associateWith { key ->
                    require(key in setOf("normal", "focused", "pressed", "active", "disabled", "playing", "paused")) { "Unknown visual state: $key" }
                    visual(obj.getJSONObject(key))
                } }.orEmpty()
                val focus = n.optJSONObject("focus")?.let { f ->
                    fields(f, setOf("id", "group", "order", "previous", "next"), "focus")
                    fun id(key: String, default: String) = f.optString(key, default).also { require(it.isEmpty() || SkinIds.valid(it)) { "Invalid focus $key" } }
                    SkinFocus(id("id", "").also { require(it.isNotEmpty()) }, id("group", "main"), f.optInt("order", 0), id("previous", ""), id("next", ""))
                }
                val gradient = n.optJSONObject("gradient")?.let { g ->
                    fields(g, setOf("endColor", "direction"), "gradient")
                    val end = g.getString("endColor").also { require(it in colors || it.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) }
                    SkinGradient(end, g.optString("direction", "vertical").also { require(it in setOf("horizontal", "vertical")) })
                }
                val keys = n.optJSONObject("keyStyle")?.let { k ->
                    fields(k, setOf("gap", "radius", "color", "background", "focusColor", "focusBackground", "borderColor", "borderWidth"), "keyStyle")
                    fun kc(key: String, default: String) = k.optString(key, default).also { require(it in colors || it.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) }
                    SkinKeyStyle(finite(k.optDouble("gap", 1.0), 0f, 12f), finite(k.optDouble("radius", 0.0), 0f, 32f), kc("color", "primaryText"), kc("background", "surface"), kc("focusColor", "accent"), kc("focusBackground", "focusSurface"), kc("borderColor", "accent"), finite(k.optDouble("borderWidth", 0.0), 0f, 8f))
                } ?: SkinKeyStyle()
                val points = n.optJSONArray("points")?.let { a ->
                    require(a.length() in 2..128) { "Paths need 2–128 points" }
                    (0 until a.length()).map { i -> a.getJSONArray(i).let { xy -> require(xy.length() == 2); finite(xy.getDouble(0), 0f, 1f) to finite(xy.getDouble(1), 0f, 1f) } }
                }.orEmpty()
                require(type != "path" || points.size >= 2) { "Path requires points" }
                val listMode = enum("listMode", "grid", setOf("grid", "horizontal", "carousel", "radial"))
                val rowWidth = number("rowWidth", 96.0, 20f, 1920f)
                require(type != "rows" || listMode == "grid" || rowWidth <= bounds.width) { "Row width exceeds viewport" }
                val nodeFont = n.optString("font").also { require(it.isEmpty() || it in setOf("sans", "sans-serif", "serif", "monospace") || safeAsset(it)) }
                SkinNode(type, bounds, text, color("color", "primaryText"), color("background", "surface"),
                    number("size", 16.0, 6f, 128f), number("radius", 0.0, 0f, 256f), number("stroke", 0.0, 0f, 16f),
                    n.optString("align", "left").also { require(it in setOf("left", "center", "right")) }, n.optBoolean("bold", false),
                    n.optString("when", "always").also { require(it in conditions) { "Unknown condition: $it" } },
                    n.optString("action").also { require(it in actions) { "Unknown action: $it" } },
                    n.optString("asset"), columns, rowHeight, gap,
                    n.optInt("segments", 0).also { require(it in 0..128) }, nodes(n.optJSONArray("children") ?: JSONArray(), depth + 1),
                    n.optInt("lines", 1).also { require(it in 1..12) },
                    font = nodeFont, lineSpacing = number("lineSpacing", 1.0, .5f, 3f),
                    overflow = enum("overflow", "ellipsis", setOf("ellipsis", "clip", "marquee")),
                    verticalAlign = enum("verticalAlign", "center", setOf("top", "center", "bottom")),
                    imageFit = enum("imageFit", "stretch", setOf("stretch", "fit", "crop")),
                    imageX = number("imageX", .5, 0f, 1f), imageY = number("imageY", .5, 0f, 1f),
                    opacity = number("opacity", 1.0, 0f, 1f), borderColor = color("borderColor", "primaryText"), borderWidth = number("borderWidth", 0.0, 0f, 16f),
                    gradient = gradient, points = points, closed = n.optBoolean("closed", false),
                    orientation = enum("orientation", "horizontal", setOf("horizontal", "vertical", "circular")), seekable = n.optBoolean("seekable", false),
                    layout = enum("layout", "absolute", setOf("absolute", "row", "column", "grid", "stack")), padding = number("padding", 0.0, 0f, 256f),
                    anchor = enum("anchor", "topLeft", setOf("topLeft", "topRight", "bottomLeft", "bottomRight", "center", "stretch")), weight = number("weight", 0.0, 0f, 100f),
                    listMode = listMode, rowWidth = rowWidth, scrolling = enum("scrolling", "page", setOf("page", "follow", "center")),
                    keyStyle = keys, states = states, focus = focus, disabled = n.optBoolean("disabled", false),
                    transitionMs = n.optInt("transitionMs", 0).also { require(it in 0..500) }, marqueeSpeed = number("marqueeSpeed", 24.0, 8f, 80f))
            }
        }
        fun templates(obj: JSONObject) = obj.keys().asSequence().associateWith { nodes(obj.getJSONArray(it), 0) }
        val screens = templates(json.getJSONObject("screens"))
        val components = templates(json.optJSONObject("components") ?: JSONObject())
        require("default" in screens) { "Missing default screen" }
        var expanded = 0
        fun validate(list: List<SkinNode>, stack: Set<String>, depth: Int = 0, multiplier: Int = 1, inRows: Boolean = false) {
            require(depth <= 16) { "Expanded nesting exceeds 16" }
            list.forEach { n ->
                expanded += multiplier
                require(expanded <= 4096) { "Expanded layout exceeds 4096 nodes" }
                if (n.type == "component") {
                    require(n.asset !in stack) { "Component cycle: ${n.asset}" }
                    validate(components[n.asset] ?: error("Unknown component: ${n.asset}"), stack + n.asset, depth + 1, multiplier, inRows)
                }
                if (n.type == "image") require(safeAsset(n.asset)) { "Invalid image path" }
                require(n.type != "rows" || !inRows) { "Nested rows are unsupported" }
                val children = if (version == 2) {
                    if (n.type == "rows") {
                        require(n.rowHeight <= n.bounds.height && (n.listMode != "grid" || n.bounds.width > n.gap * (n.columns - 1))) { "Resized rows do not fit their container" }
                        require(n.listMode == "grid" || n.rowWidth <= n.bounds.width) { "Resized horizontal/radial rows do not fit" }
                        SkinLayout.children(n.copy(bounds = SkinLayout.cell(n, 0)))
                    } else SkinLayout.children(n)
                } else n.children
                validate(children, stack, depth + 1, if (n.type == "rows") multiplier * SkinLayout.capacity(n) else multiplier, inRows || n.type == "rows")
            }
        }
        screens.values.forEach {
            expanded = 0
            validate(if (version == 2) SkinLayout.children(SkinNode("group", SkinRect(0f, 0f, width, height), children = it)) else it, emptySet())
        }
        components.forEach { (key, value) -> expanded = 0; validate(value, setOf(key)) }
        val font = json.optString("font", "sans")
        require(font in setOf("sans", "sans-serif", "serif", "monospace") || safeAsset(font)) { "Invalid font" }
        val navigation = json.optJSONObject("navigation")?.let { nav -> nav.keys().asSequence().associateWith { screen ->
            require(screen in screens) { "Navigation refers to missing screen: $screen" }
            require(screen != "search") { "Search keeps its native keyboard navigation" }
            val obj = nav.getJSONObject(screen)
            fields(obj, setOf("initial", "wrap"), "navigation")
            SkinNavigation(obj.optString("initial"), obj.optBoolean("wrap", true))
        } }.orEmpty()
        screens.forEach { (screen, list) ->
            val focusNodes = mutableListOf<SkinFocus>()
            fun collect(nodes: List<SkinNode>, repeated: Boolean = false) { nodes.forEach { node ->
                node.focus?.let { require(!repeated) { "Focus IDs cannot be repeated inside rows" }; require(node.action.isNotEmpty() || node.type == "rows") { "Focus needs an action or rows" }; focusNodes += it }
                collect(node.children, repeated || node.type == "rows")
            } }
            collect(list)
            require(focusNodes.map { it.id }.distinct().size == focusNodes.size) { "Duplicate focus ID on $screen" }
            val ids = focusNodes.map { it.id }.toSet()
            focusNodes.forEach { f -> require((f.next.isEmpty() || f.next in ids) && (f.previous.isEmpty() || f.previous in ids)) { "Unknown focus neighbor" } }
            navigation[screen]?.let { nav -> require(ids.isNotEmpty()); require(nav.initial.isEmpty() || nav.initial in ids) { "Unknown initial focus" } }
        }
        return Skin(id, name, json.optString("author").take(64), width, height, colors, font, screens, components, version, navigation)
    }

    fun safeAsset(path: String) = path.isNotEmpty() && path.length <= 128 && !path.startsWith('/') && !path.contains('\\') && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    fun parseColor(value: String): Int {
        require(value.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) { "Invalid color: $value" }
        return (value.substring(1).toLong(16) or if (value.length == 7) 0xff000000L else 0L).toInt()
    }
    private fun finite(value: Double, min: Float, max: Float): Float {
        require(value.isFinite() && value >= min && value <= max) { "Number $value outside $min..$max" }
        return value.toFloat()
    }
    internal fun checkFields(json: JSONObject, allowed: Set<String>, context: String) = fields(json, allowed, context)
    private fun fields(json: JSONObject, allowed: Set<String>, context: String) {
        json.keys().asSequence().forEach { require(it in allowed) { "Unknown $context field: $it" } }
    }
}
