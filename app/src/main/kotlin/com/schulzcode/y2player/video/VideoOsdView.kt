package com.schulzcode.y2player.video

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.os.SystemClock
import android.view.View
import com.schulzcode.y2player.ui.Y2Palette
import com.schulzcode.y2player.util.TimeFormat

/**
 * Overlay shown over a playing video: title and status message at the top,
 * play state, elapsed/remaining time and a progress bar at the bottom, and a
 * transient volume bar on the right while the wheel turns. Holding Centre adds
 * an options panel in the middle, and a running sleep timer shows top right.
 */
class VideoOsdView(context: Context, private val palette: Y2Palette) : View(context) {
    private val density = resources.displayMetrics.density
    private val padding = 14f * density
    private val bandHeight = 44f * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 15f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDDDDDD.toInt()
        textSize = 12.5f * density
    }
    private val rect = RectF()
    private val path = Path()

    private var title = ""
    private var message: String? = null
    private var positionMs = 0L
    private var durationMs = 0L
    private var playing = false
    private var volumePercent = -1
    private var volumeShownAt = 0L
    private var sleepLabel: String? = null
    private var menuItems: List<MenuItem>? = null
    private var menuSelected = 0

    data class MenuItem(val title: String, val value: String)

    val menuOpen: Boolean get() = menuItems != null

    fun setSleepLabel(value: String?) {
        if (value == sleepLabel) return
        sleepLabel = value
        invalidate()
    }

    fun setMenu(items: List<MenuItem>?, selected: Int = 0) {
        menuItems = items
        menuSelected = selected.coerceIn(0, ((items?.size ?: 1) - 1).coerceAtLeast(0))
        invalidate()
    }

    fun setTitle(value: String) { title = value; invalidate() }

    fun setMessage(value: String?) { message = value; invalidate() }

    fun hasResumeMessage(): Boolean = message?.startsWith("Resumed") == true

    fun setProgress(position: Long, duration: Long, playing: Boolean) {
        positionMs = position
        durationMs = duration
        this.playing = playing
        invalidate()
    }

    fun setVolume(percent: Int) {
        volumePercent = percent.coerceIn(0, 100)
        volumeShownAt = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // Top band: title and message.
        fill.color = SCRIM
        canvas.drawRect(0f, 0f, w, bandHeight, fill)
        val sleepWidth = sleepLabel?.let { small.measureText(it) + padding } ?: 0f
        sleepLabel?.let {
            canvas.drawText(it, w - padding - small.measureText(it), bandHeight * 0.45f, small)
        }
        val titleText = TextUtils.ellipsize(title, text, w - padding * 2 - sleepWidth, TextUtils.TruncateAt.END)
        canvas.drawText(titleText, 0, titleText.length, padding, bandHeight * 0.45f, text)
        message?.let {
            val line = TextUtils.ellipsize(it, small, w - padding * 2, TextUtils.TruncateAt.END)
            canvas.drawText(line, 0, line.length, padding, bandHeight * 0.85f, small)
        }

        // Bottom band: state, times, progress.
        val top = h - bandHeight
        fill.color = SCRIM
        canvas.drawRect(0f, top, w, h, fill)
        drawPlayState(canvas, padding + 7f * density, top + bandHeight * 0.42f)

        val elapsed = TimeFormat.duration(positionMs)
        val remaining = if (durationMs > 0) "-" + TimeFormat.duration((durationMs - positionMs).coerceAtLeast(0)) else ""
        val baseline = top + bandHeight * 0.42f - (small.ascent() + small.descent()) / 2f
        canvas.drawText(elapsed, padding + 24f * density, baseline, small)
        canvas.drawText(remaining, w - padding - small.measureText(remaining), baseline, small)

        val barTop = top + bandHeight * 0.74f
        val barHeight = 3f * density
        fill.color = 0x55FFFFFF
        rect.set(padding, barTop, w - padding, barTop + barHeight)
        canvas.drawRoundRect(rect, barHeight, barHeight, fill)
        if (durationMs > 0) {
            val fraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            fill.color = palette.accent
            rect.set(padding, barTop, padding + (w - padding * 2) * fraction, barTop + barHeight)
            canvas.drawRoundRect(rect, barHeight, barHeight, fill)
        }

        if (volumePercent >= 0 && SystemClock.uptimeMillis() - volumeShownAt < VOLUME_VISIBLE_MS) {
            drawVolume(canvas, w, h)
            postInvalidateDelayed(VOLUME_VISIBLE_MS)
        }
        menuItems?.let { drawMenu(canvas, it, w, h) }
    }

    private fun drawMenu(canvas: Canvas, items: List<MenuItem>, w: Float, h: Float) {
        val rowHeight = 36f * density
        val panelWidth = (w * 0.62f).coerceAtMost(320f * density)
        val panelHeight = rowHeight * items.size + padding
        val left = (w - panelWidth) / 2f
        val top = (h - panelHeight) / 2f
        fill.color = PANEL
        rect.set(left, top, left + panelWidth, top + panelHeight)
        canvas.drawRoundRect(rect, 12f * density, 12f * density, fill)
        items.forEachIndexed { index, item ->
            val rowTop = top + padding / 2f + rowHeight * index
            if (index == menuSelected) {
                fill.color = palette.accent
                rect.set(left + 6f * density, rowTop, left + panelWidth - 6f * density, rowTop + rowHeight)
                canvas.drawRoundRect(rect, 8f * density, 8f * density, fill)
            }
            val baseline = rowTop + rowHeight / 2f - (text.ascent() + text.descent()) / 2f
            canvas.drawText(item.title, left + padding, baseline, text)
            if (item.value.isNotEmpty()) {
                val valueWidth = panelWidth / 2f - padding
                val value = TextUtils.ellipsize(item.value, small, valueWidth, TextUtils.TruncateAt.END)
                val valueBaseline = rowTop + rowHeight / 2f - (small.ascent() + small.descent()) / 2f
                canvas.drawText(
                    value, 0, value.length,
                    left + panelWidth - padding - small.measureText(value, 0, value.length), valueBaseline, small
                )
            }
        }
    }

    private fun drawPlayState(canvas: Canvas, cx: Float, cy: Float) {
        fill.color = 0xFFFFFFFF.toInt()
        val s = 7f * density
        if (playing) {
            canvas.drawRect(cx - s * 0.8f, cy - s, cx - s * 0.25f, cy + s, fill)
            canvas.drawRect(cx + s * 0.25f, cy - s, cx + s * 0.8f, cy + s, fill)
        } else {
            path.reset()
            path.moveTo(cx - s * 0.7f, cy - s)
            path.lineTo(cx + s, cy)
            path.lineTo(cx - s * 0.7f, cy + s)
            path.close()
            canvas.drawPath(path, fill)
        }
    }

    private fun drawVolume(canvas: Canvas, w: Float, h: Float) {
        val barWidth = 8f * density
        val barHeight = h - bandHeight * 2 - padding * 2
        val left = w - padding - barWidth
        val top = bandHeight + padding
        fill.color = SCRIM
        rect.set(left - 8f * density, top - 8f * density, left + barWidth + 8f * density, top + barHeight + 8f * density)
        canvas.drawRoundRect(rect, 6f * density, 6f * density, fill)
        fill.color = 0x55FFFFFF
        rect.set(left, top, left + barWidth, top + barHeight)
        canvas.drawRoundRect(rect, barWidth / 2, barWidth / 2, fill)
        fill.color = palette.accent
        val filled = barHeight * volumePercent / 100f
        rect.set(left, top + barHeight - filled, left + barWidth, top + barHeight)
        canvas.drawRoundRect(rect, barWidth / 2, barWidth / 2, fill)
    }

    private companion object {
        val SCRIM: Int = 0xB3000000.toInt()
        val PANEL: Int = 0xE6202020.toInt()
        const val VOLUME_VISIBLE_MS = 1_500L
    }
}
