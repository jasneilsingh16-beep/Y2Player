package com.schulzcode.y2player.skin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.Path
import android.graphics.LinearGradient
import android.graphics.Shader
import android.os.SystemClock
import kotlin.math.*
import com.schulzcode.y2player.core.model.PlaybackStatus
import com.schulzcode.y2player.core.state.*
import com.schulzcode.y2player.ui.Y2Icon
import com.schulzcode.y2player.ui.Y2IconPainter
import com.schulzcode.y2player.ui.Y2RowIcons
import com.schulzcode.y2player.ui.Y2RowState
import com.schulzcode.y2player.util.TimeFormat

/** A small scene renderer; no skin IDs or skin-specific layouts appear here. */
class SkinRenderer(private val dispatch: (AppAction) -> Unit) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val icons = Y2IconPainter(paint, 1f)
    private val rect = RectF()
    private data class Target(val bounds: SkinRect, val action: AppAction?, val key: String = "", val seek: SkinNode? = null, val groupAction: String = "", val rowContainer: Boolean = false)
    private val targets = mutableListOf<Target>()
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var state = AppState()
    private var rows: List<ScreenRow> = emptyList()
    private lateinit var loaded: LoadedSkin
    private var artwork: Bitmap? = null
    private var bindings: Map<String, String> = emptyMap()
    private val token = Regex("\\{([^{}]+)\\}")
    private var pressed: Target? = null

    private val focus = SkinFocusController()
    private val focusTargets = linkedMapOf<String, Target>()
    private var screenKey: Any? = null
    private var clockMs = 0L
    private var startedMs = 0L
    var needsAnimation = false
        private set
    var animationsEnabled = true
    private data class Transition(val from: SkinNode, val to: SkinNode, val start: Long)
    private val transitions = mutableMapOf<String, Transition>()
    private val seenTransitions = mutableSetOf<String>()
    private var drawingKey = ""
    private var contextFocused: Boolean? = null
    private var contextDisabled = false

    fun handleInput(action: AppAction): Boolean {
        if (!::loaded.isInitialized || navigation() == null || targets.isEmpty() || state.transientMessage != null) return false
        if (focus.editingRows) {
            if (action == AppAction.Back) return focus.leaveRows()
            return false
        }
        return when (action) {
            is AppAction.WheelMoved -> { focus.move(action.delta); true }
            AppAction.Confirm -> { focusTargets[focus.selected]?.let(::activate); true }
            else -> false
        }
    }
    private fun navigation() = loaded.definition.navigation[state.currentScreen.code]
        ?: loaded.definition.navigation["default"].takeIf { state.currentScreen.code !in loaded.definition.screens && state.currentScreen !is Screen.Search }
    private fun activate(target: Target) {
        if (target.action == AppAction.Back && state.transientMessage == null && focus.leaveRows()) return
        if (target.rowContainer) { focus.enterRows(); return }
        when (target.groupAction) {
            "nextGroup" -> focus.moveGroup(1)
            "previousGroup" -> focus.moveGroup(-1)
            else -> target.action?.let(dispatch)
        }
    }

    fun invalidateInput() { targets.clear(); pressed = null }
    fun press(x: Float, y: Float) { pressed = target(x, y); pressed?.key?.takeIf { it.isNotEmpty() }?.let(focus::select) }
    fun cancel() { pressed = null }
    fun release(x: Float, y: Float) {
        val target = target(x, y)
        if (target != null && target.key == pressed?.key && target.bounds == pressed?.bounds && target.action == pressed?.action) {
            val seek = target.seek
            if (seek != null) dispatch(AppAction.SeekFraction(SkinLayout.seekFraction(seek.bounds, seek.orientation, (x - offsetX) / scale, (y - offsetY) / scale)))
            else activate(target)
        }
        pressed = null
    }
    private fun target(x: Float, y: Float) = targets.lastOrNull { it.bounds.contains((x - offsetX) / scale, (y - offsetY) / scale) }

    fun draw(canvas: Canvas, width: Int, height: Int, skin: LoadedSkin, state: AppState, rows: List<ScreenRow>, artwork: Bitmap?, systemVolumePercent: Int? = null) {
        val key = Triple(skin, state.screenStack.map { it.screen }, state.skins.revision)
        clockMs = SystemClock.uptimeMillis()
        if (key != screenKey) {
            screenKey = key; focus.reset(); pressed = null; transitions.clear(); startedMs = clockMs
        }
        needsAnimation = false
        seenTransitions.clear()
        this.loaded = skin
        this.state = state
        this.rows = rows
        this.artwork = artwork
        val definition = skin.definition
        scale = minOf(width / definition.width, height / definition.height)
        if (scale <= 0f) return
        offsetX = (width - definition.width * scale) / 2f
        offsetY = (height - definition.height * scale) / 2f
        val track = state.playback.currentTrackId?.let(state.library.byId::get)
        bindings = mapOf(
            "empty.message" to when {
                state.currentScreen is Screen.Search -> if ((state.currentScreen as Screen.Search).query.isBlank()) "Use the keyboard below to search" else "No results. Try another word."
                state.library.isScanning -> "Scanning music…"
                !state.device.internalStorageAvailable && !state.device.removableStorageAvailable -> "Music storage unavailable"
                else -> "No items here. Press Back to return."
            },
            "playback.details" to if (state.preferences.extraTrackInfo && track != null) listOfNotNull(track.year?.toString(), track.genre).joinToString(" · ") else "",
            "screen.title" to ScreenContent.title(state), "track.title" to (track?.title ?: "Nothing playing"),
            "track.artist" to (track?.displayArtist ?: "Select a track"), "track.album" to (track?.displayAlbum ?: ""),
            "playback.elapsed" to TimeFormat.duration(state.playback.positionMs), "playback.duration" to TimeFormat.duration(state.playback.durationMs),
            "playback.status" to state.playback.status.name, "battery" to (state.device.batteryPercent?.let { "$it%" } ?: "--"),
            "clock" to com.schulzcode.y2player.core.state.ClockPresentation.time(state.clock, state.preferences.use24HourClock),
            "position" to if (rows.isEmpty()) "" else "${state.selectedIndex + 1} / ${rows.size}",
            "search.query" to ((state.currentScreen as? Screen.Search)?.query ?: ""),
            "fm.frequency" to "${state.fm.frequencyLabel} MHz", "fm.status" to (state.fm.message ?: if (state.fm.powered) "FM ON" else "FM OFF"),
            "volume" to (if (state.preferences.volumeMode == com.schulzcode.y2player.playback.VolumeMode.PERCEPTUAL)
                com.schulzcode.y2player.playback.VolumeCurve.percentForLevel(state.preferences.volumeLevel).toString() else systemVolumePercent?.toString() ?: "--"), "volume.mode" to state.preferences.volumeMode.name,
            "shuffle" to if (state.playback.shuffleEnabled) "ON" else "OFF", "repeat" to state.playback.repeatMode.name,
            "track.codec" to track?.codec.orEmpty(), "track.sampleRate" to (track?.sampleRate?.toString() ?: ""),
            "track.bitDepth" to (track?.bitDepth?.toString() ?: ""), "track.bitrate" to (track?.bitrate?.toString() ?: ""),
            "track.channels" to (track?.channels?.toString() ?: ""), "device.charging" to state.device.charging.toString(),
            "device.model" to state.device.deviceModel, "device.storageAvailable" to (state.device.internalStorageAvailable || state.device.removableStorageAvailable).toString(),
            "display.brightness" to state.display.brightnessPercent.toString(),
            "message" to (state.transientMessage ?: ""), "alphabet" to (state.alphabetScrub?.label ?: "")
        )
        targets.clear()
        focusTargets.clear()
        navigation()?.let { nav ->
            val candidates = mutableListOf<SkinFocus>()
            fun collect(nodes: List<SkinNode>, parentVisible: Boolean = true) { nodes.forEach { node ->
                contextFocused = node.focus?.let { it.id == focus.selected }
                contextDisabled = node.disabled
                val show = parentVisible && !node.disabled && node.opacity > 0f && (node.whenState in setOf("focused", "unfocused", "pressed") || visible(node.whenState, null, -1))
                if (show) node.focus?.let(candidates::add)
                collect(node.children, show)
            } }
            collect(skin.definition.screen(state.currentScreen.code))
            focus.update(candidates, nav)
        }
        canvas.drawColor(color("background"))
        val save = canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        canvas.clipRect(0f, 0f, definition.width, definition.height)
        val root = SkinNode("group", SkinRect(0f, 0f, definition.width, definition.height), children = definition.screen(state.currentScreen.code))
        drawNodes(canvas, SkinLayout.children(root), 0f, 0f, root.bounds, null, -1)
        canvas.restoreToCount(save)
        navigation()?.let { nav ->
            val surviving = mutableListOf<SkinFocus>()
            fun collect(nodes: List<SkinNode>) { nodes.forEach { node -> node.focus?.takeIf { it.id in focusTargets }?.let(surviving::add); collect(node.children) } }
            collect(definition.screen(state.currentScreen.code))
            val previous = focus.selected
            focus.update(surviving, nav)
            if (previous != focus.selected && surviving.isNotEmpty()) needsAnimation = animationsEnabled
        }
        transitions.keys.retainAll(seenTransitions)
    }

    private fun visible(condition: String, row: ScreenRow?, index: Int): Boolean = when (condition) {
        "focused" -> contextFocused ?: (index >= 0 && index == state.selectedIndex && ((state.currentScreen as? Screen.Search)?.resultsFocused != false))
        "unfocused" -> !visible("focused", row, index)
        "pressed" -> pressed?.key == drawingKey
        "disabled" -> contextDisabled || (row is ScreenRow.TrackRow && !row.track.available)
        "enabled" -> !contextDisabled && !(row is ScreenRow.TrackRow && !row.track.available)
        "charging" -> state.device.charging
        "active" -> row != null && Y2RowState.isActive(row, state)
        "unavailable" -> row is ScreenRow.TrackRow && !row.track.available
        "playing" -> state.playback.status == PlaybackStatus.PLAYING
        "paused" -> state.playback.status != PlaybackStatus.PLAYING
        "hasTrack" -> state.playback.currentTrackId != null
        "noTrack" -> state.playback.currentTrackId == null
        "emptyRows" -> rows.isEmpty()
        "message" -> !state.transientMessage.isNullOrEmpty()
        "alphabet" -> state.alphabetScrub != null
        else -> true
    }

    private fun drawNodes(canvas: Canvas, nodes: List<SkinNode>, ox: Float, oy: Float, clip: SkinRect, row: ScreenRow?, index: Int, path: String = "screen", inheritedDisabled: Boolean = false, inheritedFocus: Boolean? = null) {
        for ((slot, original) in nodes.withIndex()) {
            drawingKey = original.focus?.id ?: "$path/$slot:$index"
            contextFocused = original.focus?.let { it.id == focus.selected } ?: inheritedFocus
            contextDisabled = inheritedDisabled || original.disabled
            if (!visible(original.whenState, row, index)) continue
            var node = original
            for (condition in listOf("normal", "paused", "playing", "active", "focused", "pressed", "disabled")) {
                if (condition == "normal" || visible(condition, row, index)) node = node.states[condition]?.apply(node) ?: node
            }
            node = transition(drawingKey, node)
            if (node.opacity <= 0f) continue
            val localKey = drawingKey
            val localFocus = contextFocused
            val localDisabled = contextDisabled
            val b = node.bounds.let { SkinRect(it.x + ox, it.y + oy, it.width, it.height) }
            val clipped = intersect(b, clip) ?: continue
            val save = canvas.save()
            canvas.clipRect(clipped.x, clipped.y, clipped.x + clipped.width, clipped.y + clipped.height)
            val alphaSave = if (node.opacity < 1f) canvas.saveLayerAlpha(clipped.x, clipped.y, clipped.x + clipped.width, clipped.y + clipped.height, (node.opacity * 255).toInt(), Canvas.ALL_SAVE_FLAG) else -1
            paint.reset()
            paint.isAntiAlias = true; paint.isFilterBitmap = true
            paint.style = Paint.Style.FILL
            val face = loaded.fonts[node.font] ?: loaded.font
            paint.typeface = if (node.bold) Typeface.create(face, Typeface.BOLD) else face
            paint.textSize = node.size
            paint.color = color(node.color)
            paint.textAlign = Paint.Align.LEFT
            rect.set(b.x, b.y, b.x + b.width, b.y + b.height)
            if (!localDisabled) {
                val target = Target(clipped, action(node.action), localKey,
                    if (node.type == "progress" && node.seekable) node.copy(bounds = b) else null,
                    node.action.takeIf { it == "nextGroup" || it == "previousGroup" }.orEmpty(), node.type == "rows")
                if (target.action != null || target.seek != null || target.groupAction.isNotEmpty()) targets += target
                node.focus?.let { focusTargets[it.id] = target }
            }
            node.gradient?.let { g -> paint.shader = LinearGradient(b.x, b.y,
                if (g.direction == "horizontal") b.x + b.width else b.x,
                if (g.direction == "vertical") b.y + b.height else b.y,
                color(node.color), color(g.endColor), Shader.TileMode.CLAMP) }
            when (node.type) {
                "rect" -> {
                    paint.style = if (node.stroke > 0f) Paint.Style.STROKE else Paint.Style.FILL
                    paint.strokeWidth = node.stroke
                    if (node.stroke > 0f) rect.inset(node.stroke / 2, node.stroke / 2)
                    canvas.drawRoundRect(rect, node.radius, node.radius, paint)
                }
                "circle" -> { paint.style = if (node.stroke > 0) Paint.Style.STROKE else Paint.Style.FILL; paint.strokeWidth = node.stroke; rect.inset(node.stroke / 2, node.stroke / 2); canvas.drawOval(rect, paint) }
                "line" -> { paint.strokeWidth = node.stroke.coerceAtLeast(1f); canvas.drawLine(b.x, b.y, b.x + b.width, b.y + b.height, paint) }
                "path" -> {
                    val shape = Path()
                    node.points.forEachIndexed { i, point -> if (i == 0) shape.moveTo(b.x + point.first * b.width, b.y + point.second * b.height) else shape.lineTo(b.x + point.first * b.width, b.y + point.second * b.height) }
                    if (node.closed) shape.close()
                    paint.style = if (node.stroke > 0 || !node.closed) Paint.Style.STROKE else Paint.Style.FILL
                    paint.strokeWidth = node.stroke.coerceAtLeast(1f)
                    canvas.drawPath(shape, paint)
                }
                "text" -> drawText(canvas, expand(node.text, row, index), b, node.align, node.lines, node)
                "image", "artwork" -> {
                    val bitmap = if (node.type == "artwork") artwork else loaded.images[node.asset]
                    if (node.radius > 0) { val mask = Path(); mask.addRoundRect(rect, node.radius, node.radius, Path.Direction.CW); canvas.clipPath(mask) }
                    paint.shader = null; paint.color = color(node.background); canvas.drawRect(rect, paint)
                    if (bitmap != null) {
                        val dest = SkinLayout.imageBounds(b, bitmap.width, bitmap.height, node.imageFit, node.imageX, node.imageY)
                        canvas.drawBitmap(bitmap, null, RectF(dest.x, dest.y, dest.x + dest.width, dest.y + dest.height), paint)
                    }
                }
                "icon" -> {
                    val icon = if (row != null && node.asset.isEmpty()) Y2RowIcons.forRow(row, state.currentScreen, state.playback.currentTrackId, Y2RowState.isActive(row, state))
                        else Y2Icon.entries.firstOrNull { it.name.equals(node.asset, true) } ?: Y2Icon.INFO
                    icons.draw(canvas, icon, b.x + b.width / 2, b.y + b.height / 2, minOf(b.width, b.height), color(node.color))
                }
                "progress" -> drawProgress(canvas, node, b)
                "rows" -> {
                    val rowNode = node.copy(bounds = b)
                    val first = SkinLayout.firstVisible(rowNode, state.selectedIndex)
                    for (slot in 0 until minOf(SkinLayout.capacity(rowNode), rows.size - first)) {
                        val cell = SkinLayout.cell(rowNode, slot)
                        val cellClip = intersect(cell, clipped) ?: continue
                        val rowIndex = first + slot
                        if (!localDisabled) targets += Target(cellClip, if (rowIndex == state.selectedIndex) AppAction.Confirm else AppAction.SelectIndex(rowIndex), "$localKey/row:$rowIndex")
                        drawNodes(canvas, SkinLayout.children(node.copy(bounds = cell)), cell.x, cell.y, cellClip, rows[rowIndex], rowIndex, "$localKey/row:$rowIndex", localDisabled)
                    }
                }
                "keyboard" -> drawKeyboard(canvas, b, clipped, node, localDisabled)
                "group" -> drawNodes(canvas, SkinLayout.children(node), b.x, b.y, clipped, row, index, localKey, localDisabled, localFocus)
                "component" -> drawNodes(canvas, SkinLayout.children(node.copy(children = loaded.definition.components.getValue(node.asset))), b.x, b.y, clipped, row, index, localKey, localDisabled, localFocus)
            }
            paint.shader = null
            if (node.borderWidth > 0) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = node.borderWidth; paint.color = color(node.borderColor)
                rect.set(b.x, b.y, b.x + b.width, b.y + b.height); rect.inset(node.borderWidth / 2, node.borderWidth / 2)
                if (node.type == "circle") canvas.drawOval(rect, paint) else canvas.drawRoundRect(rect, node.radius, node.radius, paint)
            }
            if (alphaSave >= 0) canvas.restoreToCount(alphaSave)
            canvas.restoreToCount(save)
        }
    }

    private fun drawKeyboard(canvas: Canvas, b: SkinRect, clip: SkinRect, node: SkinNode, disabled: Boolean) {
        val screen = state.currentScreen as? Screen.Search ?: return
        val h = b.height / SearchKeyboard.rows.size
        SearchKeyboard.rows.forEachIndexed { rowIndex, keys ->
            val w = b.width / keys.size
            keys.forEachIndexed { column, key ->
                val bounds = SkinRect(b.x + column * w, b.y + rowIndex * h, w, h)
                val focused = !screen.resultsFocused && screen.keyboardRow == rowIndex && screen.keyboardColumn == column
                val style = node.keyStyle
                paint.shader = null; paint.style = Paint.Style.FILL
                paint.color = color(if (focused) style.focusBackground else style.background)
                val gap = minOf(style.gap, w / 4, h / 4)
                rect.set(bounds.x + gap, bounds.y + gap, bounds.x + w - gap, bounds.y + h - gap)
                canvas.drawRoundRect(rect, style.radius, style.radius, paint)
                if (style.borderWidth > 0) {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = style.borderWidth; paint.color = color(style.borderColor)
                    rect.inset(style.borderWidth / 2, style.borderWidth / 2)
                    canvas.drawRoundRect(rect, style.radius, style.radius, paint); paint.style = Paint.Style.FILL
                }
                paint.color = color(if (focused) style.focusColor else style.color)
                drawText(canvas, SearchKeyboard.label(key), bounds, "center")
                if (!disabled) intersect(bounds, clip)?.let { targets += Target(it, AppAction.PressSearchKey(key), "keyboard:$rowIndex:$column") }
            }
        }
    }

    private fun drawText(canvas: Canvas, source: String, b: SkinRect, align: String, maxLines: Int = 1, node: SkinNode? = null) {
        val lineHeight = paint.fontSpacing * (node?.lineSpacing ?: 1f)
        if (node?.overflow == "marquee" && paint.measureText(source) > b.width) {
            val travel = paint.measureText(source) - b.width
            val elapsed = ((clockMs - startedMs).coerceAtLeast(0) / 1000f)
            val duration = travel / node.marqueeSpeed
            val phase = elapsed % (duration * 2 + 2)
            val offset = when { phase < 1 -> 0f; phase < 1 + duration -> (phase - 1) * node.marqueeSpeed; phase < 2 + duration -> travel; else -> travel - (phase - 2 - duration) * node.marqueeSpeed }
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(source, b.x - if (animationsEnabled) offset else 0f, b.y + (b.height - paint.fontSpacing) / 2 - paint.ascent(), paint)
            needsAnimation = needsAnimation || animationsEnabled
            return
        }
        val count = minOf(maxLines, (b.height / lineHeight).toInt().coerceAtLeast(1))
        val lines = SkinTextLayout.lines(if (loaded.definition.formatVersion == 1) source.replace('\n', ' ') else source, b.width, count, node?.overflow != "clip", paint::measureText)
        paint.textAlign = when (align) { "center" -> Paint.Align.CENTER; "right" -> Paint.Align.RIGHT; else -> Paint.Align.LEFT }
        val x = when (align) { "center" -> b.x + b.width / 2; "right" -> b.x + b.width; else -> b.x }
        val free = b.height - lines.size * lineHeight
        val baseline = b.y + when (node?.verticalAlign) { "top" -> 0f; "bottom" -> free; else -> free / 2 } - paint.ascent()
        lines.forEachIndexed { index, text -> canvas.drawText(text, x, baseline + index * lineHeight, paint) }
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawProgress(canvas: Canvas, node: SkinNode, b: SkinRect) {
        val fraction = if (state.playback.durationMs > 0) (state.playback.positionMs.toFloat() / state.playback.durationMs).coerceIn(0f, 1f) else 0f
        paint.shader = null
        rect.set(b.x, b.y, b.x + b.width, b.y + b.height)
        if (node.orientation == "circular") {
            val diameter = minOf(b.width, b.height)
            rect.set(b.x + (b.width - diameter) / 2, b.y + (b.height - diameter) / 2,
                b.x + (b.width + diameter) / 2, b.y + (b.height + diameter) / 2)
            val stroke = node.stroke.coerceAtLeast(4f).coerceAtMost(diameter / 2)
            rect.inset(stroke / 2, stroke / 2)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke
            paint.color = color(node.background); canvas.drawOval(rect, paint)
            paint.color = color(node.color); canvas.drawArc(rect, -90f, 360f * fraction, false, paint)
            paint.style = Paint.Style.FILL
            return
        }
        paint.color = color(node.background); canvas.drawRoundRect(rect, node.radius, node.radius, paint)
        paint.color = color(node.color)
        val vertical = node.orientation == "vertical"
        if (node.segments == 0) {
            if (vertical) rect.top = b.y + b.height * (1 - fraction) else rect.right = b.x + b.width * fraction
            canvas.drawRoundRect(rect, node.radius, node.radius, paint)
        } else {
            val length = if (vertical) b.height else b.width
            val gap = minOf(node.gap, length / node.segments / 2)
            val size = (length - gap * (node.segments - 1)) / node.segments
            repeat((fraction * node.segments).toInt()) { slot ->
                val start = slot * (size + gap)
                if (vertical) canvas.drawRect(b.x, b.y + b.height - start - size, b.x + b.width, b.y + b.height - start, paint)
                else canvas.drawRect(b.x + start, b.y, b.x + start + size, b.y + b.height, paint)
            }
        }
    }

    private fun transition(key: String, node: SkinNode): SkinNode {
        if (node.transitionMs == 0 || !animationsEnabled) return node
        seenTransitions += key
        val old = transitions[key]
        val entry = if (old == null) Transition(node, node, clockMs) else if (old.to != node) Transition(interpolate(old), node, clockMs) else old
        transitions[key] = entry
        if (entry.from != entry.to && clockMs - entry.start < node.transitionMs) needsAnimation = true
        return interpolate(entry)
    }
    private fun interpolate(t: Transition): SkinNode {
        val amount = ((clockMs - t.start).toFloat() / t.to.transitionMs.coerceAtLeast(1)).coerceIn(0f, 1f)
        fun number(a: Float, b: Float) = a + (b - a) * amount
        fun blend(a: String, b: String): String {
            val from = color(a); val to = color(b)
            var out = 0
            for (shift in listOf(0, 8, 16, 24)) out = out or (number(((from ushr shift) and 255).toFloat(), ((to ushr shift) and 255).toFloat()).toInt() shl shift)
            return "#" + java.lang.Long.toHexString(out.toLong() and 0xffffffffL).padStart(8, '0')
        }
        return t.to.copy(color = blend(t.from.color, t.to.color), background = blend(t.from.background, t.to.background),
            opacity = number(t.from.opacity, t.to.opacity), radius = number(t.from.radius, t.to.radius),
            borderWidth = number(t.from.borderWidth, t.to.borderWidth), borderColor = blend(t.from.borderColor, t.to.borderColor))
    }

    private fun expand(text: String, row: ScreenRow?, index: Int) = token.replace(text) { match ->
        when (val key = match.groupValues[1]) {
            "row.title" -> row?.title ?: ""
            "row.subtitle" -> row?.subtitle ?: ""
            "row.number" -> (index + 1).toString()
            "row.trailing" -> (row as? ScreenRow.TrackRow)?.track?.durationMs?.let(TimeFormat::duration) ?: ""
            else -> bindings[key].orEmpty()
        }
    }
    private fun color(name: String) = loaded.definition.colors[name] ?: if (name.startsWith('#')) SkinParser.parseColor(name) else 0xffffffff.toInt()
    private fun action(name: String): AppAction? = when (name) {
        "confirm" -> AppAction.Confirm; "back" -> AppAction.Back; "home" -> AppAction.NavigateHome
        "nowPlaying" -> AppAction.ShowNowPlaying; "playPause" -> AppAction.PlayPause
        "next" -> AppAction.MediaNext; "previous" -> AppAction.MediaPrevious
        "left" -> AppAction.Left; "right" -> AppAction.Right
        "volumeUp", "volumeDown", "shuffle", "repeat" -> AppAction.SkinCommand(name)
        else -> null
    }
    private fun intersect(a: SkinRect, b: SkinRect): SkinRect? {
        val x = maxOf(a.x, b.x); val y = maxOf(a.y, b.y)
        val right = minOf(a.x + a.width, b.x + b.width); val bottom = minOf(a.y + a.height, b.y + b.height)
        return if (right > x && bottom > y) SkinRect(x, y, right - x, bottom - y) else null
    }
}
