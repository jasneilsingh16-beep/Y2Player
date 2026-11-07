package com.schulzcode.y2player.gurbani

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import android.view.View
import com.schulzcode.y2player.ui.Y2Fonts

/**
 * The Shabad page, as on the Phase 2 board: each tuk in Gurmukhi with its English
 * beneath, the one in focus on a pale blue card, the Ang, raag and author at the
 * top, and where the English comes from at the foot. The wheel moves the focus a
 * tuk at a time; headings sit centred and small between them.
 *
 * Only the tuks on screen are laid out, so a whole bani of two thousand lines
 * opens at once.
 */
class ShabadView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density.coerceAtLeast(1f)
    private val fonts = Y2Fonts(context.assets)
    private val gurmukhi = load(context, "gurmukhi_medium.ttf")
    private val gurmukhiFocus = load(context, "gurmukhi_semibold.ttf")

    private fun text(face: Typeface, sp: Float, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = face
        textSize = sp * density
        this.color = color
        hinting = Paint.HINTING_OFF
    }

    private val tukPaint = text(gurmukhi, 19f, INK_SOFT)
    private val tukFocusPaint = text(gurmukhiFocus, 21f, INK)
    private val headingPaint = text(gurmukhi, 16f, MUTED)
    private val englishPaint = text(fonts.regular, 14f, SECONDARY)
    private val englishFocusPaint = text(fonts.regular, 15f, FOCUS_ENGLISH)
    private val englishNoSourcePaint = text(fonts.regular, 14f, MUTED).apply { textSkewX = -.2f }
    private val headingEnglishPaint = text(fonts.medium, 13f, MUTED)
    private val titlePaint = text(fonts.semibold, 17f, INK)
    private val headerPaint = text(fonts.medium, 13f, SECONDARY)
    private val footerPaint = text(fonts.regular, 12f, FOOTER)
    private val bannerPaint = text(fonts.semibold, 13f, WARNING_INK)
    private val menuPaint = text(fonts.medium, 17f, INK)
    private val menuFocusPaint = text(fonts.medium, 17f, 0xFFFFFFFF.toInt())
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** Choices over the page (confirm, another shabad, none), or null. */
    var menu: List<String>? = null
        private set
    var menuFocus = 0
        private set

    private var passage: GurbaniText.Passage? = null
    private var attribution = ""
    private var title = "Shabad"
    private var message: String? = null
    var focus = 0
        private set
    private val blocks = LruCache<Long, Block>(BLOCK_CACHE)

    private class Block(val gurmukhi: StaticLayout, val english: StaticLayout?, val heading: Boolean, val gap: Int) {
        val height: Int get() = gurmukhi.height + (english?.height ?: 0) + if (english != null) gap else 0
    }

    fun show(value: GurbaniText.Passage, english: String) {
        passage = value
        attribution = english
        message = null
        menu = null
        title = when (value.kind) {
            GurbaniText.Kind.SHABAD -> "Shabad"
            GurbaniText.Kind.ANGS -> value.label ?: "Sehaj Paath"
            GurbaniText.Kind.BANI -> value.lines.firstOrNull { it.kind == 'B' }?.english?.substringBefore(" · ")?.takeIf { it.isNotBlank() }
                ?: "Bani"
        }
        blocks.evictAll()
        focus = value.focus.coerceIn(0, value.lines.lastIndex)
        invalidate()
    }

    fun openMenu(items: List<String>) {
        menu = items
        menuFocus = 0
        invalidate()
    }

    fun moveMenu(delta: Int) {
        val items = menu ?: return
        menuFocus = (menuFocus + delta).coerceIn(0, items.lastIndex)
        invalidate()
    }

    fun closeMenu() {
        menu = null
        invalidate()
    }

    fun showMessage(text: String) {
        menu = null
        passage = null
        message = text
        title = "Shabad"
        invalidate()
    }

    /** Moves the focus by [delta] tuks, passing over headings. */
    fun move(delta: Int) {
        val lines = passage?.lines ?: return
        var next = (focus + delta).coerceIn(0, lines.lastIndex)
        while (lines[next].isHeading && next + delta in lines.indices) next += delta
        if (next != focus) {
            focus = next
            invalidate()
        }
    }

    fun focusOn(index: Int) {
        val lines = passage?.lines ?: return
        val target = index.coerceIn(0, lines.lastIndex)
        if (target != focus) {
            focus = target
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        blocks.evictAll()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(BACKGROUND)
        val header = HEADER_DP * density
        val footer = if (passage != null) FOOTER_DP * density else 0f
        drawHeader(canvas, header)
        val lines = passage?.lines
        if (lines == null) {
            message?.let { drawMessage(canvas, it, header) }
            return
        }
        val banner = if (passage?.confident == false) BANNER_DP * density else 0f
        canvas.save()
        canvas.clipRect(0f, header + banner, width.toFloat(), height - footer)
        val left = PAD_DP * density
        // The tuk before the focus shows above it, so the eye keeps its place.
        var y = header + banner + TOP_DP * density
        if (focus > 0) {
            val previous = block(lines, focus - 1)
            y += minOf(previous.height.toFloat(), PREVIOUS_MAX_DP * density) + blockGap
            drawBlock(canvas, previous, left, y - blockGap - previous.height)
        }
        val current = block(lines, focus)
        if (!lines[focus].isHeading) {
            rect.set(left - cardPadX, y - cardPadY, width - left + cardPadX - scrollGutter, y + current.height + cardPadY)
            fill.color = FOCUS_FILL
            canvas.drawRoundRect(rect, 10f * density, 10f * density, fill)
        }
        drawBlock(canvas, current, left, y)
        y += current.height + cardPadY + blockGap
        var index = focus + 1
        while (index < lines.size && y < height - footer) {
            val next = block(lines, index)
            drawBlock(canvas, next, left, y)
            y += next.height + blockGap
            index++
        }
        canvas.restore()
        drawScroll(canvas, header + banner, height - footer, lines.size)
        drawFooter(canvas, footer, lines[focus])
        if (banner > 0f) drawBanner(canvas, header, banner)
        menu?.let { drawMenu(canvas, it) }
    }

    /** Says plainly that this shabad is a guess, and how to settle it. */
    private fun drawBanner(canvas: Canvas, top: Float, height: Float) {
        val value = passage ?: return
        fill.color = WARNING_FILL
        canvas.drawRect(0f, top, width.toFloat(), top + height, fill)
        val which = if (value.candidates.size > 1) " · suggestion ${value.candidate + 1} of ${value.candidates.size}" else ""
        val text = "Not confirmed: may be the wrong shabad$which · Centre to check"
        val shown = TextUtils.ellipsize(text, bannerPaint, width - 2 * PAD_DP * density, TextUtils.TruncateAt.END).toString()
        canvas.drawText(shown, PAD_DP * density, top + height * .5f + bannerPaint.textSize * .35f, bannerPaint)
    }

    private fun drawMenu(canvas: Canvas, items: List<String>) {
        fill.color = SCRIM
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        val row = MENU_ROW_DP * density
        val pad = 10f * density
        val cardHeight = items.size * row + 2 * pad
        val left = 24f * density
        val right = width - 24f * density
        val top = (height - cardHeight) / 2f
        fill.color = BACKGROUND
        rect.set(left, top, right, top + cardHeight)
        canvas.drawRoundRect(rect, 16f * density, 16f * density, fill)
        items.forEachIndexed { index, item ->
            val y = top + pad + index * row
            val focused = index == menuFocus
            if (focused) {
                fill.color = ACCENT
                rect.set(left + pad, y, right - pad, y + row)
                canvas.drawRoundRect(rect, 6f * density, 6f * density, fill)
            }
            val paint = if (focused) menuFocusPaint else menuPaint
            val shown = TextUtils.ellipsize(item, paint, right - left - 4 * pad, TextUtils.TruncateAt.END).toString()
            canvas.drawText(shown, left + 2 * pad, y + row * .5f + paint.textSize * .35f, paint)
        }
    }

    private fun drawHeader(canvas: Canvas, header: Float) {
        val baseline = header * .5f + titlePaint.textSize * .35f
        // Back chevron, as on every other screen.
        fill.color = INK
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = 2f * density
        fill.strokeCap = Paint.Cap.ROUND
        val cx = 16f * density
        val cy = header * .5f
        canvas.drawLine(cx + 3f * density, cy - 7f * density, cx - 3f * density, cy, fill)
        canvas.drawLine(cx - 3f * density, cy, cx + 3f * density, cy + 7f * density, fill)
        fill.style = Paint.Style.FILL
        canvas.drawText(title, 30f * density, baseline, titlePaint)
        val line = passage?.lines?.getOrNull(focus)
        if (line != null) {
            val where = listOfNotNull("Ang ${line.ang}", line.section.removePrefix("Raag ").takeIf { it.isNotBlank() },
                line.author.takeIf { it.isNotBlank() }).joinToString(" · ")
            val room = width - 30f * density - titlePaint.measureText(title) - 24f * density
            val shown = TextUtils.ellipsize(where, headerPaint, room, TextUtils.TruncateAt.END).toString()
            canvas.drawText(shown, width - 12f * density - headerPaint.measureText(shown), baseline, headerPaint)
        }
        fill.color = DIVIDER
        canvas.drawRect(0f, header - 1f, width.toFloat(), header, fill)
    }

    private fun drawFooter(canvas: Canvas, footer: Float, line: GurbaniText.Line) {
        val top = height - footer
        fill.color = BACKGROUND
        canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), fill)
        fill.color = DIVIDER_SOFT
        canvas.drawRect(PAD_DP * density, top, width - PAD_DP * density, top + 1f, fill)
        val note = if (line.noSource) "English for this tuk has no Sahib Singh arth behind it" else "English: $attribution"
        val shown = TextUtils.ellipsize(note, footerPaint, width - 2 * PAD_DP * density, TextUtils.TruncateAt.END).toString()
        canvas.drawText(shown, PAD_DP * density, top + footer * .5f + footerPaint.textSize * .35f, footerPaint)
    }

    private fun drawScroll(canvas: Canvas, top: Float, bottom: Float, count: Int) {
        if (count < 2) return
        val track = bottom - top - 12f * density
        val thumb = maxOf(24f * density, track / count * 6)
        val y = top + 6f * density + (track - thumb) * focus / (count - 1)
        fill.color = SCROLL
        rect.set(width - 7f * density, y, width - 3f * density, y + thumb)
        canvas.drawRoundRect(rect, 2f * density, 2f * density, fill)
    }

    private fun drawMessage(canvas: Canvas, text: String, header: Float) {
        val layout = StaticLayout(text, englishFocusPaint, (width - 2 * PAD_DP * density).toInt(),
            Layout.Alignment.ALIGN_NORMAL, 1.2f, 0f, false)
        canvas.save()
        canvas.translate(PAD_DP * density, header + 24f * density)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawBlock(canvas: Canvas, block: Block, left: Float, top: Float) {
        canvas.save()
        val x = if (block.heading) (width - scrollGutter - block.gurmukhi.width) / 2f else left
        canvas.translate(x, top)
        block.gurmukhi.draw(canvas)
        block.english?.let {
            canvas.translate(0f, (block.gurmukhi.height + block.gap).toFloat())
            it.draw(canvas)
        }
        canvas.restore()
    }

    private fun block(lines: List<GurbaniText.Line>, index: Int): Block {
        val focused = index == focus
        val key = index.toLong() * 2 + if (focused) 1 else 0
        blocks.get(key)?.let { return it }
        val line = lines[index]
        val width = (this.width - 2 * PAD_DP * density - scrollGutter).toInt().coerceAtLeast(1)
        val heading = line.isHeading
        val g = StaticLayout(
            line.gurmukhi,
            when { heading -> headingPaint; focused -> tukFocusPaint; else -> tukPaint },
            width, if (heading) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL, 1f, 2f * density, false
        )
        val englishText = line.english.takeIf { it.isNotBlank() }
        val e = englishText?.let {
            StaticLayout(
                it,
                when { heading -> headingEnglishPaint; line.noSource -> englishNoSourcePaint; focused -> englishFocusPaint; else -> englishPaint },
                width, if (heading) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL, 1f, 1f * density, false
            )
        }
        return Block(g, e, heading, gap).also { blocks.put(key, it) }
    }

    private val gap get() = (3f * density).toInt()
    private val blockGap get() = 12f * density
    private val cardPadX get() = 10f * density
    private val cardPadY get() = 8f * density
    private val scrollGutter get() = 6f * density

    companion object {
        private const val HEADER_DP = 34f
        private const val FOOTER_DP = 24f
        private const val PAD_DP = 20f
        private const val TOP_DP = 14f
        private const val PREVIOUS_MAX_DP = 54f
        private const val BLOCK_CACHE = 48
        private const val BANNER_DP = 26f
        private const val MENU_ROW_DP = 44f
        private const val WARNING_FILL = 0xFFFBF1DC.toInt()
        private const val WARNING_INK = 0xFF8A5A00.toInt()
        private const val ACCENT = 0xFF0A64D6.toInt()
        private const val SCRIM = 0x66000000

        private const val BACKGROUND = 0xFFFFFFFF.toInt()
        private const val INK = 0xFF111114.toInt()
        private const val INK_SOFT = 0xFF3A3A42.toInt()
        private const val SECONDARY = 0xFF5A5A63.toInt()
        private const val MUTED = 0xFF6E6E78.toInt()
        private const val FOOTER = 0xFF6E6E78.toInt()
        private const val FOCUS_FILL = 0xFFEEF4FD.toInt()
        private const val FOCUS_ENGLISH = 0xFF1F2A3A.toInt()
        private const val DIVIDER = 0xFFE5E5EA.toInt()
        private const val DIVIDER_SOFT = 0xFFF0F0F3.toInt()
        private const val SCROLL = 0xFFAEAEB2.toInt()

        private fun load(context: Context, name: String): Typeface =
            runCatching { Typeface.createFromAsset(context.assets, "fonts/$name") }.getOrDefault(Typeface.DEFAULT)
    }
}
