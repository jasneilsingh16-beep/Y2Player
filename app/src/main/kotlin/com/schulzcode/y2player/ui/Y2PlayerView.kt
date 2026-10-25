package com.schulzcode.y2player.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import com.schulzcode.y2player.artwork.AlbumArtworkLoader
import com.schulzcode.y2player.core.model.AudioCodecLabels
import com.schulzcode.y2player.core.model.PauseReason
import com.schulzcode.y2player.core.model.PlaybackStatus
import com.schulzcode.y2player.core.model.RepeatMode
import com.schulzcode.y2player.core.model.SleepTimerMode
import com.schulzcode.y2player.core.state.AppAction
import com.schulzcode.y2player.core.state.AppState
import com.schulzcode.y2player.core.state.ClockState
import com.schulzcode.y2player.core.state.HomePane
import com.schulzcode.y2player.fm.FmState
import com.schulzcode.y2player.core.state.Screen
import com.schulzcode.y2player.core.state.ScreenContent
import com.schulzcode.y2player.core.state.ScreenRow
import com.schulzcode.y2player.core.state.SearchKeyboard
import com.schulzcode.y2player.core.state.SleepTimerPresentation
import com.schulzcode.y2player.core.state.isCardMenu
import com.schulzcode.y2player.core.state.isProgressOnlyUpdate
import com.schulzcode.y2player.util.TimeFormat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@SuppressLint("ViewConstructor")
@Suppress("DEPRECATION")
class Y2PlayerView(
    context: Context,
    private val dispatch: (AppAction) -> Unit,
    private val artworkLoader: AlbumArtworkLoader
) : View(context) {
    private val density = resources.displayMetrics.density.coerceAtLeast(1f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans", Typeface.NORMAL)
        style = Paint.Style.FILL
    }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans", Typeface.BOLD)
        style = Paint.Style.FILL
    }
    private val iconPainter = Y2IconPainter(paint, density)
    private val accessibilityManager =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    private val reusableRect = Rect()
    private val reusableRectF = RectF()
    private val textScroller = SelectedTextScroller(MARQUEE_SPEED_DP_PER_SECOND * density)
    private val nowTextScrollers = Array(3) {
        SelectedTextScroller(MARQUEE_SPEED_DP_PER_SECOND * density)
    }
    private var nowScrollTrackId: Long? = null
    private var textAnimationsVisible = false
    private var attached = false
    private var textScrollCallbackScheduled = false
    private val textScrollRunnable = object : Runnable {
        override fun run() {
            textScrollCallbackScheduled = false
            val delay = advanceActiveTextScrollers(SystemClock.uptimeMillis())
            if (delay == SelectedTextScroller.NO_CALLBACK) return
            invalidateTextScrollTarget()
            scheduleTextScroll(delay)
        }
    }
    private var sleepTimerCountdownCallbackScheduled = false
    private val sleepTimerCountdownRunnable = object : Runnable {
        override fun run() {
            sleepTimerCountdownCallbackScheduled = false
            if (!shouldRefreshSleepTimerCountdown()) return
            refreshSleepTimerCountdownRows()
            updateSleepTimerCountdownActivity(refreshRows = false)
        }
    }

    private var state: AppState = AppState()

    private var palette: Y2Palette = Y2Palette.DARK
    private val skinAudioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    private var skinVolumeReadMs = 0L
    private var skinVolumePercent: Int? = null
    private val skinRenderer = com.schulzcode.y2player.skin.SkinRenderer(dispatch)
    private fun activeSkin() = if (state.safeMode) null else com.schulzcode.y2player.skin.SkinRepository.skins[state.preferences.skinId]


    private fun applyPalette(lightTheme: Boolean, bedtime: Boolean): Boolean {
        val next = Y2Palette.of(lightTheme, bedtime)
        if (next == palette) return false
        palette = next
        return true
    }
    private var rows = rowsForState(state)
    private var artwork: Bitmap? = null
    private var artworkPath: String? = null
    private var artworkIdentity: Triple<Long, Long, Long>? = null
    private var detailArtwork: Bitmap? = null
    private var detailArtworkPath: String? = null
    private var visibleStart = 0
    private var rowHeight = Y2UiTheme.ROW_HEIGHT_DP * density
    /** Every row of the list keeps one height, so the wheel moves evenly. */
    private var listTwoLine = false
    private var listArtColumn = false
    private var listLeadingColumn = false
    private val cachedProgress = arrayOfNulls<com.schulzcode.y2player.core.state.RowProgress>(MAX_VISIBLE_ROWS)
    private val cachedLeading = arrayOfNulls<String>(MAX_VISIBLE_ROWS)
    private val listLeadingIcons = false
    private val reusablePath = android.graphics.Path()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val headerHeight = Y2UiTheme.HEADER_HEIGHT_DP * density
    private val detailHeaderHeight = Y2UiTheme.DETAIL_HEADER_HEIGHT_DP * density
    // No footer: hints and the mini player are gone, and Home leads with Now Playing.
    private val footerHeight = 0f
    private var pendingTouchIndex: Int? = null
    private var pendingTouchActive = false

    private val cachedTitles = arrayOfNulls<String>(MAX_VISIBLE_ROWS)
    private val cachedSubtitles = arrayOfNulls<String>(MAX_VISIBLE_ROWS)
    private val cachedIcons = Array(MAX_VISIBLE_ROWS) { Y2Icon.INFO }
    private val cachedTrailingIcons = arrayOfNulls<Y2Icon>(MAX_VISIBLE_ROWS)
    private val cachedTrailingText = arrayOfNulls<String>(MAX_VISIBLE_ROWS)
    private val cachedTrailingAccent = BooleanArray(MAX_VISIBLE_ROWS)
    private val cachedLeadingIcons = BooleanArray(MAX_VISIBLE_ROWS)
    private val cachedTrackNumbers = arrayOfNulls<String>(MAX_VISIBLE_ROWS)
    private val cachedTitleWidths = FloatArray(MAX_VISIBLE_ROWS)
    private val cachedTitleAvailableWidths = FloatArray(MAX_VISIBLE_ROWS)
    private val cachedActive = BooleanArray(MAX_VISIBLE_ROWS)
    private val cachedUnavailable = BooleanArray(MAX_VISIBLE_ROWS)
    private val visibleRowArtworks = HashMap<Int, VisibleRowArtworkSlot>(MAX_VISIBLE_ROWS)
    private var visibleRowArtworkGeneration = 0L
    private var cachedRowStart = -1
    private var cachedRowEnd = -1
    private var cachedHeaderTitle = "Y2 Player"
    private var cachedRoute = Y2UiLogic.routePresentation(state.playback.outputRoute)
    private var cachedHeaderRouteIcon: RouteIcon? = null
    private var cachedBatteryText = "--"
    private var cachedBatteryPercent: Int? = null
    private var cachedNowTitle = "Nothing playing"
    private var cachedNowTitleFull = "Nothing playing"
    private var cachedNowTitleWidth = 0f
    private var cachedNowArtist = "Select a track"
    private var cachedNowArtistFull = "Select a track"
    private var cachedNowArtistWidth = 0f
    private var cachedNowAlbum = ""
    private var cachedNowAlbumFull = ""
    private var cachedNowAlbumWidth = 0f
    private var cachedNowGenre = ""
    private var cachedTechnicalLine = ""
    private var cachedNowSecondary = ""
    private var cachedNowSecondaryWarning = false
    private var cachedStatusTag = ""
    private var cachedDacLabel = "Balanced"
    private var cachedElapsed = "0:00"
    private var cachedDuration = "0:00"
    private var cachedRemaining = "−0:00"
    private var cachedNowSource = ""
    private var cachedNowTitleLines: List<String> = emptyList()
    private var cachedNowArtistLines: List<String> = emptyList()
    private var nowPlayingPane: HomePane.NowPlaying? = null
    private var cachedMessageSource: String? = null
    private var cachedMessageTitle = ""
    private var cachedMessageBody = ""
    private var cachedNowFavorite = false
    private var cachedPaneTitle = ""
    private var cachedPaneArtist = ""
    private var cachedHomeStats = ""
    private var cachedHomeHint = ""
    private var cachedScanProgressFraction: Float? = null
    private var cachedScanProgressPhase = 0f
    private var cachedFmFrequency = ""
    private var cachedFmStatus = ""
    private var cachedFmSignal = ""
    private var cachedFmHint = ""
    private var cachedFmFraction = 0f
    private var cachedDetailTitle = ""
    private var cachedDetailTitle2 = ""
    private var cachedDetailSubtitle = ""

    init {
        isFocusable = true
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updatePresentationCache()
    }

    fun setTextAnimationsVisible(visible: Boolean) {
        if (textAnimationsVisible == visible) return
        textAnimationsVisible = visible
        if (activeSkin() != null) invalidate()
        updateTextScrollActivity()
        updateSleepTimerCountdownActivity()
    }

    fun onNavigationInput() {
        val now = SystemClock.uptimeMillis()
        val hasTarget = if (isNowPlayingSurface()) {
            var found = false
            for (scroller in nowTextScrollers) {
                if (scroller.hasTarget) scroller.restart(now)
                found = found || scroller.hasTarget
            }
            found
        } else {
            if (textScroller.hasTarget) textScroller.restart(now)
            textScroller.hasTarget
        }
        if (!hasTarget) return
        rescheduleTextScroll()
        invalidateTextScrollTarget()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        updateTextScrollActivity()
        updateSleepTimerCountdownActivity()
        requestVisibleRowArtworks()
    }

    override fun onDetachedFromWindow() {
        attached = false
        cancelVisibleRowArtworkRequests()
        stopTextScrollCallbacks()
        stopSleepTimerCountdownCallback()
        setTextScrollersActive(false, SystemClock.uptimeMillis())
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateTextScrollActivity()
        updateSleepTimerCountdownActivity()
        if (visibility == VISIBLE) requestVisibleRowArtworks() else cancelVisibleRowArtworkRequests()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateTextScrollActivity()
        updateSleepTimerCountdownActivity()
        if (visibility == VISIBLE) requestVisibleRowArtworks() else cancelVisibleRowArtworkRequests()
    }

    fun render(newState: AppState) {
        val oldState = state
        // A card menu floats over a still image of the screen it was opened from.
        if (newState.currentScreen.isCardMenu() && !oldState.currentScreen.isCardMenu()) captureCardBackdrop()
        if (!newState.currentScreen.isCardMenu()) cardBackdrop = null
        val light = newState.preferences.skinId == "classic-light"
        val themeChanged = applyPalette(light, light && ScreenContent.isBedtime(newState)) ||
            oldState.preferences.skinId != newState.preferences.skinId || oldState.skins.revision != newState.skins.revision
        val progressOnly = !themeChanged && isProgressOnlyUpdate(oldState.playback, newState.playback) &&
            oldState.copy(playback = newState.playback) == newState
        val sameScreenPath = oldState.screenStack.size == newState.screenStack.size &&
            oldState.screenStack.indices.all { oldState.screenStack[it].screen == newState.screenStack[it].screen }
        val selectionOnly = !themeChanged && sameScreenPath &&
            oldState.copy(
                screenStack = newState.screenStack,
                alphabetScrub = newState.alphabetScrub
            ) == newState

        if (!progressOnly) skinRenderer.invalidateInput()
        state = newState
        updateSleepTimerCountdownActivity(refreshRows = false)
        if (activeSkin() != null) {
            stopTextScrollCallbacks()
            if (!progressOnly) {
                rows = rowsForState(newState)
                updateListLayout()
                requestArtwork()
                updatePresentationCache()
            }
            updateContentDescription(newState)
            invalidate()
            return
        }
        if (selectionOnly) {
            if (ensureSelectionVisible()) requestVisibleRowArtworks()
            if (isSplitHome()) updateHomePane()
            updateContentDescription(newState)
            invalidate(0, headerHeight.toInt(), width, height)
            return
        }
        if (progressOnly) {
            cachedElapsed = TimeFormat.duration(newState.playback.positionMs)
            cachedDuration = TimeFormat.duration(newState.playback.durationMs)
            cachedRemaining = remainingLabel(newState.playback.positionMs, newState.playback.durationMs)
            when (newState.currentScreen) {
                Screen.NowPlaying -> invalidate(0, headerHeight.toInt(), width, (height - footerHeight).toInt())
                Screen.MainMenu -> if (isSplitHome() && newState.playback.currentTrackId != null) {
                    invalidate(rowAreaRight().toInt(), headerHeight.toInt(), width, (height - footerHeight).toInt())
                } else if (newState.playback.currentTrackId != null) {
                    invalidate(0, (height - footerHeight).toInt(), width, height)
                }
                Screen.NowPlayingOptions, Screen.QueueManagement, is Screen.QueueOptions -> {
                    if (oldState.playback.sleepTimerRemainingMs != newState.playback.sleepTimerRemainingMs) {
                        rows = rowsForState(newState)
                        updateListLayout()
                        invalidateRowCache()
                    }
                    invalidate()
                }
                else -> if (newState.playback.currentTrackId != null) {
                    invalidate(0, (height - footerHeight).toInt(), width, height)
                }
            }
            return
        }

        rows = rowsForState(newState)
        updateListLayout()
        requestArtwork()
        updatePresentationCache()
        invalidateRowCache()
        ensureSelectionVisible()
        requestVisibleRowArtworks()
        updateHomePane()
        updateContentDescription(newState)
        invalidate()
    }

    fun trimMemory() {
        artwork = null
        artworkPath = null
        artworkIdentity = null
        detailArtwork = null
        detailArtworkPath = null
        cancelVisibleRowArtworkRequests()
        artworkLoader.trimMemory()
        invalidate()
    }

    fun release() {
        stopTextScrollCallbacks()
        textScroller.clear()
        for (scroller in nowTextScrollers) scroller.clear()
        nowScrollTrackId = null
        artwork = null
        artworkPath = null
        artworkIdentity = null
        detailArtwork = null
        detailArtworkPath = null
        cancelVisibleRowArtworkRequests()
        stopSleepTimerCountdownCallback()
        cardBackdrop = null
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateListLayout()
        invalidateRowCache()
        updatePresentationCache()
        ensureSelectionVisible()
        requestVisibleRowArtworks()
    }

    private fun updateTextScrollActivity() {
        if (activeSkin() != null) { stopTextScrollCallbacks(); return }
        val active = attached && textAnimationsVisible &&
            windowVisibility == VISIBLE && visibility == VISIBLE
        val changed = setTextScrollersActive(active, SystemClock.uptimeMillis())
        stopTextScrollCallbacks()
        if (active) rescheduleTextScroll()
        if (changed) invalidateTextScrollTarget()
    }

    private fun rowsForState(value: AppState): List<ScreenRow> {
        if (!SleepTimerPresentation.showsCountdown(value.currentScreen)) return ScreenContent.rows(value)
        val remaining = SleepTimerPresentation.remainingMs(value.playback, SystemClock.elapsedRealtime())
            ?: return ScreenContent.rows(value)
        return ScreenContent.rows(value.copy(
            playback = value.playback.copy(sleepTimerRemainingMs = remaining)
        ))
    }

    private fun shouldRefreshSleepTimerCountdown(): Boolean {
        val uiVisible = attached && textAnimationsVisible &&
            windowVisibility == VISIBLE && visibility == VISIBLE
        return SleepTimerPresentation.shouldRefresh(
            state.currentScreen,
            state.playback.sleepTimerMode,
            state.playback.sleepTimerDeadlineElapsedMs,
            uiVisible
        )
    }

    private fun updateSleepTimerCountdownActivity(refreshRows: Boolean = true) {
        if (!shouldRefreshSleepTimerCountdown()) {
            stopSleepTimerCountdownCallback()
            return
        }
        if (refreshRows) refreshSleepTimerCountdownRows()
        if (sleepTimerCountdownCallbackScheduled) return
        sleepTimerCountdownCallbackScheduled = true
        postDelayed(sleepTimerCountdownRunnable, SLEEP_TIMER_REFRESH_MS)
    }

    private fun refreshSleepTimerCountdownRows() {
        val updated = rowsForState(state)
        if (updated == rows) {
            // The status bar countdown and Now Playing's Sleep tile tick even when no row does.
            if (isNowPlayingSurface()) invalidate() else invalidate(0, 0, width, headerHeight.toInt())
            return
        }
        rows = updated
        updateListLayout()
        invalidateRowCache()
        updateContentDescription(state)
        invalidate()
    }

    private fun stopSleepTimerCountdownCallback() {
        if (!sleepTimerCountdownCallbackScheduled) return
        removeCallbacks(sleepTimerCountdownRunnable)
        sleepTimerCountdownCallbackScheduled = false
    }

    private fun rescheduleTextScroll() {
        stopTextScrollCallbacks()
        val delay = advanceActiveTextScrollers(SystemClock.uptimeMillis())
        if (delay != SelectedTextScroller.NO_CALLBACK) scheduleTextScroll(delay)
    }

    private fun setTextScrollersActive(active: Boolean, nowMs: Long): Boolean {
        var changed = textScroller.setActive(active, nowMs)
        for (scroller in nowTextScrollers) changed = scroller.setActive(active, nowMs) || changed
        return changed
    }

    private fun advanceActiveTextScrollers(nowMs: Long): Long {
        if (!isNowPlayingSurface()) return textScroller.advance(nowMs)
        var delay = SelectedTextScroller.NO_CALLBACK
        for (scroller in nowTextScrollers) {
            delay = earlierScrollDelay(delay, scroller.advance(nowMs))
        }
        return delay
    }

    private fun earlierScrollDelay(first: Long, second: Long): Long = when {
        first == SelectedTextScroller.NO_CALLBACK -> second
        second == SelectedTextScroller.NO_CALLBACK -> first
        else -> minOf(first, second)
    }

    private fun scheduleTextScroll(delayMs: Long) {
        if (textScrollCallbackScheduled) return
        textScrollCallbackScheduled = true
        if (delayMs == SelectedTextScroller.NEXT_FRAME) {
            // Ten updates per second remain readable on the 480x360 panel without
            // making the single Canvas view compete with audio decoding for CPU.
            postDelayed(textScrollRunnable, MARQUEE_FRAME_MS)
        } else {
            postDelayed(textScrollRunnable, delayMs.coerceAtLeast(1L))
        }
    }

    private fun stopTextScrollCallbacks() {
        if (!textScrollCallbackScheduled) return
        removeCallbacks(textScrollRunnable)
        textScrollCallbackScheduled = false
    }

    private fun invalidateTextScrollTarget() {
        if (activeSkin() != null) { invalidate(); return }
        if (isNowPlayingSurface()) {
            invalidate(0, headerHeight.toInt(), width, (height - footerHeight).toInt())
            return
        }
        when (textScroller.kind) {
            SelectedTextScroller.TARGET_LIST -> {
                val index = textScroller.index
                if (index in cachedRowStart until cachedRowEnd) {
                    val top = rowAreaTop() + (index - visibleStart) * rowHeight
                    invalidate(0, top.toInt(), rowAreaRight().toInt(), (top + rowHeight).toInt())
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        activeSkin()?.let {
            val now = SystemClock.uptimeMillis()
            if (now - skinVolumeReadMs >= 1000L) {
                skinVolumeReadMs = now
                skinVolumePercent = runCatching {
                    100 * skinAudioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) /
                        skinAudioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                }.getOrNull()
            }
            skinRenderer.animationsEnabled = attached && textAnimationsVisible
            skinRenderer.draw(canvas, width, height, it, state, rows, artwork, skinVolumePercent)
            if (skinRenderer.needsAnimation && attached && textAnimationsVisible) postInvalidateDelayed(33L)
            return
        }
        if (state.currentScreen.isCardMenu()) {
            drawCardMenu(canvas)
            drawVolumeOverlay(canvas)
            return
        }
        canvas.drawColor(palette.background)
        drawHeader(canvas)
        if (state.currentScreen == Screen.FmRadio) {
            drawFmRadio(canvas)
        } else if (isNowPlayingSurface()) {
            drawNowPlaying(canvas)
        } else {
            if (hasDetailHeader()) drawDetailHeader(canvas)
            if (state.currentScreen is Screen.Search) drawSearchQuery(canvas)
            drawScreenNote(canvas)
            drawRows(canvas)
            if (state.currentScreen is Screen.Search) drawSearchKeyboard(canvas)
        }
        if (isSplitHome()) drawHomePane(canvas)
        drawStatusMessage(canvas)
        drawAlphabetScrubOverlay(canvas)
        drawVolumeOverlay(canvas)
    }

    private fun isNowPlayingSurface(): Boolean = state.currentScreen == Screen.NowPlaying

    private fun isSplitHome(): Boolean = state.currentScreen == Screen.MainMenu && width > height

    private fun rowAreaRight(): Float = if (isSplitHome()) HOME_LIST_WIDTH_DP * density else width.toFloat()

    private fun hasDetailHeader(): Boolean = state.currentScreen is Screen.AlbumSongs || state.currentScreen is Screen.ArtistSongs

    private fun rowAreaTop(): Float = headerHeight + when {
        state.currentScreen is Screen.Search -> SEARCH_QUERY_HEIGHT_DP * density
        hasDetailHeader() -> detailHeaderHeight
        cachedScreenNote != null -> SCREEN_NOTE_HEIGHT_DP * density
        else -> 4f * density
    }

    /** A line under the title that explains the screen, where it needs one. */
    private var cachedScreenNote: String? = null

    private fun drawScreenNote(canvas: Canvas) {
        val note = cachedScreenNote ?: return
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
        paint.color = palette.secondaryText
        canvas.drawText(note, (Y2UiTheme.EDGE_DP + Y2UiTheme.ROW_PADDING_DP) * density, headerHeight + 20f * density, paint)
    }

    private fun rowAreaBottom(): Float =
        (if (state.currentScreen is Screen.Search) height.toFloat() - SEARCH_KEYBOARD_HEIGHT_DP * density
        else height - footerHeight) - if (cachedMessageSource != null && cachedMessageTitle != "Status") 66f * density else 0f

    fun handleSkinInput(action: AppAction): Boolean {
        if (activeSkin() == null) return false
        val consumed = skinRenderer.handleInput(action)
        if (consumed) invalidate()
        return consumed
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (activeSkin() != null) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> skinRenderer.press(event.x, event.y)
                MotionEvent.ACTION_UP -> skinRenderer.release(event.x, event.y)
                MotionEvent.ACTION_CANCEL -> skinRenderer.cancel()
            }
            invalidate()
            return true
        }
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                pendingTouchActive = true
                pendingTouchIndex = touchIndex(event.x, event.y)
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                pendingTouchActive = false
                pendingTouchIndex = null
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!pendingTouchActive) true else {
                    pendingTouchActive = false
                    val searchKey = searchKeyAt(event.x, event.y)
                    if (searchKey != null) {
                        pendingTouchIndex = null
                        dispatch(AppAction.PressSearchKey(searchKey))
                        return true
                    }
                    if (state.currentScreen.isCardMenu()) {
                        pendingTouchIndex = null
                        performClick()
                        return true
                    }
                    if (isSplitHome() && event.x >= rowAreaRight() && event.y >= headerHeight && event.y < height - footerHeight) {
                        pendingTouchIndex = null
                        dispatch(AppAction.ShowNowPlaying)
                        return true
                    }
                    if (state.currentScreen != Screen.NowPlaying) {
                        pendingTouchIndex = touchIndex(event.x, event.y)
                        if (pendingTouchIndex == null) return true
                    }
                    performClick()
                }
            }
            else -> true
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (state.currentScreen == Screen.NowPlaying || state.currentScreen.isCardMenu()) {
            dispatch(AppAction.Confirm)
            pendingTouchActive = false
            pendingTouchIndex = null
            return true
        }
        val index = pendingTouchIndex ?: state.selectedIndex
        pendingTouchIndex = null
        if (index !in rows.indices) return false
        if (index == state.selectedIndex) dispatch(AppAction.Confirm) else dispatch(AppAction.SelectIndex(index))
        return true
    }

    private fun touchIndex(x: Float, y: Float): Int? {
        val rowsTop = rowAreaTop()
        if (y < rowsTop || y >= rowAreaBottom()) return null
        if (x >= rowAreaRight()) return null
        return (visibleStart + ((y - rowsTop) / rowHeight).toInt()).takeIf { it in rows.indices }
    }

    private fun searchKeyAt(x: Float, y: Float): String? {
        if (state.currentScreen !is Screen.Search) return null
        val top = searchKeyboardTop()
        if (y < top || y >= height || x !in 0f..width.toFloat()) return null
        val rowHeight = (height - top) / SearchKeyboard.rows.size
        val row = ((y - top) / rowHeight).toInt().coerceIn(0, SearchKeyboard.rows.lastIndex)
        val keys = SearchKeyboard.rows[row]
        val keyWidth = width.toFloat() / keys.size
        return keys[(x / keyWidth).toInt().coerceIn(0, keys.lastIndex)]
    }

    private fun drawSearchQuery(canvas: Canvas) {
        val screen = state.currentScreen as? Screen.Search ?: return
        val top = headerHeight
        val bottom = rowAreaTop()
        paint.style = Paint.Style.FILL
        paint.color = palette.surface
        canvas.drawRect(0f, top, width.toFloat(), bottom, paint)
        boldPaint.textAlign = Paint.Align.LEFT
        boldPaint.textSize = Y2UiTheme.BODY_SP * density
        boldPaint.color = if (screen.query.isEmpty()) palette.secondaryText else palette.primaryText
        val query = screen.query.ifEmpty { "Type to search this device…" }
        canvas.drawText(ellipsize(query, width - 86f * density, boldPaint), 12f * density, top + 25f * density, boldPaint)
        paint.textAlign = Paint.Align.RIGHT
        paint.textSize = Y2UiTheme.META_SP * density
        paint.color = palette.secondaryText
        canvas.drawText(if (screen.query.isEmpty()) "" else rows.size.toString(), width - 12f * density, top + 24f * density, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun searchKeyboardTop(): Float = height - SEARCH_KEYBOARD_HEIGHT_DP * density

    private fun drawSearchKeyboard(canvas: Canvas) {
        val screen = state.currentScreen as? Screen.Search ?: return
        val top = searchKeyboardTop()
        val keyboardHeight = height - top
        val keyHeight = keyboardHeight / SearchKeyboard.rows.size
        paint.style = Paint.Style.FILL
        paint.color = palette.surface
        canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), paint)
        SearchKeyboard.rows.forEachIndexed { rowIndex, keys ->
            val keyWidth = width.toFloat() / keys.size
            keys.forEachIndexed { columnIndex, key ->
                val left = columnIndex * keyWidth + 2f * density
                val keyTop = top + rowIndex * keyHeight + 2f * density
                val right = (columnIndex + 1) * keyWidth - 2f * density
                val bottom = keyTop + keyHeight - 4f * density
                val focused = !screen.resultsFocused && rowIndex == screen.keyboardRow && columnIndex == screen.keyboardColumn
                paint.color = if (focused) palette.accent else palette.background
                reusableRectF.set(left, keyTop, right, bottom)
                canvas.drawRoundRect(reusableRectF, 4f * density, 4f * density, paint)
                boldPaint.textAlign = Paint.Align.CENTER
                boldPaint.textSize = if (rowIndex == SearchKeyboard.rows.lastIndex) 10f * density else 13f * density
                boldPaint.color = if (focused) palette.background else palette.primaryText
                canvas.drawText(
                    SearchKeyboard.label(key),
                    (left + right) * .5f,
                    keyTop + keyHeight * .5f + 4f * density,
                    boldPaint
                )
            }
        }
        boldPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawHeader(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = palette.background
        canvas.drawRect(0f, 0f, width.toFloat(), headerHeight, paint)
        paint.color = palette.divider
        canvas.drawRect(0f, headerHeight - density, width.toFloat(), headerHeight, paint)

        val baseline = headerHeight * .5f + 6f * density
        val hasBack = state.screenStack.size > 1
        val titleLeft = if (hasBack) 30f * density else 16f * density
        if (hasBack) {
            val save = canvas.save()
            canvas.rotate(180f, 17f * density, headerHeight * .5f)
            iconPainter.draw(canvas, Y2Icon.CHEVRON, 17f * density, headerHeight * .5f, 16f * density, palette.primaryText)
            canvas.restoreToCount(save)
        }

        boldPaint.color = palette.primaryText
        boldPaint.textSize = Y2UiTheme.SCREEN_TITLE_SP * density
        boldPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(cachedHeaderTitle, titleLeft, baseline, boldPaint)

        if (state.safeMode || state.library.isScanning) {
            val markerX = titleLeft + boldPaint.measureText(cachedHeaderTitle) + 9f * density
            paint.style = Paint.Style.FILL
            if (state.safeMode) {
                paint.textAlign = Paint.Align.LEFT
                paint.textSize = Y2UiTheme.BADGE_SP * density
                paint.color = palette.warning
                canvas.drawText("SAFE", markerX, baseline - density, paint)
            } else {
                paint.color = palette.accent
                canvas.drawCircle(markerX + 3f * density, headerHeight * .5f, 3f * density, paint)
            }
        }

        // Right to left: battery, its percentage, the clock, the sleep timer.
        var right = width - 12f * density
        right = drawBattery(canvas, right, headerHeight * .5f)
        cachedHeaderRouteIcon?.let { icon ->
            val color = if (cachedRoute.warning) palette.warning else palette.secondaryText
            iconPainter.draw(canvas, routeIcon(icon), right - 7f * density, headerHeight * .5f, 14f * density, color)
            right -= 24f * density
        }
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.RIGHT
        paint.textSize = Y2UiTheme.STATUS_SP * density
        if (state.preferences.showStatusClock) {
            paint.color = palette.primaryText
            val clock = statusClockText()
            canvas.drawText(clock, right, baseline - density, paint)
            right -= paint.measureText(clock) + 10f * density
        }
        statusSleepText()?.let { label ->
            paint.color = palette.accent
            canvas.drawText(label, right, baseline - density, paint)
            val textWidth = paint.measureText(label)
            iconPainter.draw(canvas, Y2Icon.MOON, right - textWidth - 9f * density, headerHeight * .5f, 13f * density, palette.accent)
        }
        paint.textAlign = Paint.Align.LEFT
    }

    /** "23m", "1h 5m", or "End" for a timer that waits for the track, book or queue to finish. */
    private fun statusSleepText(): String? {
        val playback = state.playback
        return when (playback.sleepTimerMode) {
            SleepTimerMode.OFF -> null
            SleepTimerMode.MINUTES -> SleepTimerPresentation.remainingMs(playback, SystemClock.elapsedRealtime())
                ?.let { SleepTimerPresentation.statusLabel(it) }
            else -> "End"
        }
    }

    private var statusClockMinute = Long.MIN_VALUE
    private var statusClock24Hour = false
    private var statusClockZone = ""
    private var statusClockLabel = ""

    // Read from the system at draw time so it is never a minute behind the tick;
    // formatted once per minute.
    private fun statusClockText(): String {
        val now = System.currentTimeMillis()
        val zone = java.util.TimeZone.getDefault().id
        val minute = now / 60_000L
        val use24 = state.preferences.use24HourClock
        if (minute != statusClockMinute || use24 != statusClock24Hour || zone != statusClockZone) {
            statusClockMinute = minute
            statusClock24Hour = use24
            statusClockZone = zone
            statusClockLabel = com.schulzcode.y2player.core.state.ClockPresentation.time(ClockState(now, zone), use24)
        }
        return statusClockLabel
    }

    // The body is filled in proportion to the reading, so 100% is a full battery.
    private fun drawBattery(canvas: Canvas, rightEdge: Float, centerY: Float): Float {
        val percent = cachedBatteryPercent
        val color = when {
            state.device.charging -> palette.success
            (percent ?: 100) <= 15 -> palette.warning
            else -> palette.primaryText
        }
        val nubWidth = 2f * density
        val bodyWidth = 22f * density
        val bodyHeight = 11f * density
        val bodyRight = rightEdge - nubWidth
        val bodyLeft = bodyRight - bodyWidth
        val stroke = 1.5f * density
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = color
        reusableRectF.set(bodyLeft + stroke * .5f, centerY - bodyHeight * .5f + stroke * .5f,
            bodyRight - stroke * .5f, centerY + bodyHeight * .5f - stroke * .5f)
        canvas.drawRoundRect(reusableRectF, 3f * density, 3f * density, paint)
        paint.style = Paint.Style.FILL
        reusableRectF.set(bodyRight, centerY - 2.5f * density, bodyRight + nubWidth, centerY + 2.5f * density)
        canvas.drawRoundRect(reusableRectF, density, density, paint)
        val inset = stroke + density
        val innerWidth = bodyWidth - inset * 2
        val fill = (percent ?: 0).coerceIn(0, 100)
        if (fill > 0) {
            reusableRectF.set(bodyLeft + inset, centerY - bodyHeight * .5f + inset,
                bodyLeft + inset + innerWidth * fill / 100f, centerY + bodyHeight * .5f - inset)
            canvas.drawRoundRect(reusableRectF, density, density, paint)
        }
        var left = bodyLeft
        if (cachedBatteryText.isNotEmpty()) {
            paint.textAlign = Paint.Align.RIGHT
            paint.textSize = Y2UiTheme.BADGE_SP * density
            paint.color = if (color == palette.primaryText) palette.secondaryText else color
            canvas.drawText(cachedBatteryText, bodyLeft - 5f * density, centerY + 4.5f * density, paint)
            left = bodyLeft - 5f * density - paint.measureText(cachedBatteryText)
            paint.textAlign = Paint.Align.LEFT
        }
        return left - 10f * density
    }

    private fun drawRows(canvas: Canvas) {
        if (rows.isEmpty()) {
            drawEmptyState(canvas)
            return
        }
        refreshVisibleRowCache()
        prepareListTextScrollTarget()
        val rowsRight = rowAreaRight()
        val rowsTop = rowAreaTop()
        val saveCount = canvas.save()
        canvas.clipRect(0f, rowsTop, rowsRight, rowAreaBottom())
        val surfaceRight = rowSurfaceRight()
        val textLeft = rowTextLeft()
        for (index in cachedRowStart until cachedRowEnd) {
            val slot = index - cachedRowStart
            val top = rowsTop + (index - visibleStart) * rowHeight
            val bottom = top + rowHeight
            val focused = index == state.selectedIndex &&
                ((state.currentScreen as? Screen.Search)?.resultsFocused != false)
            val active = cachedActive[slot]
            val unavailable = cachedUnavailable[slot]
            if (focused) drawFocus(canvas, top, bottom)
            val titleColor = when {
                focused -> palette.onFocus
                unavailable -> palette.mutedText
                active && rows[index] is ScreenRow.TrackRow -> palette.accent
                else -> palette.primaryText
            }
            val secondaryColor = if (focused) palette.onFocusSecondary else palette.secondaryText
            val iconColor = if (focused) palette.onFocus else palette.accent

            if (listArtColumn) {
                val artSize = ROW_ARTWORK_SIZE_DP * density
                val artLeft = (Y2UiTheme.EDGE_DP + 6f) * density
                val artTop = top + (rowHeight - artSize) * .5f
                val rowArtwork = visibleRowArtworks[index]?.bitmap
                val trackNumber = cachedTrackNumbers[slot]
                when {
                    rowArtwork != null -> drawRowArtwork(canvas, artLeft, artTop, artSize, rowArtwork)
                    trackNumber != null -> {
                        boldPaint.textAlign = Paint.Align.CENTER
                        boldPaint.textSize = Y2UiTheme.ROW_DETAIL_SP * density
                        boldPaint.color = if (focused) palette.onFocusSecondary else palette.mutedText
                        canvas.drawText(trackNumber, artLeft + artSize * .5f, top + rowHeight * .5f + 5f * density, boldPaint)
                        boldPaint.textAlign = Paint.Align.LEFT
                    }
                    // An action in a list of covers, such as Shuffle, is a plain blue icon.
                    rows[index] is ScreenRow.Action && rows[index].artworkTrackId == null && rows[index].artworkPath == null ->
                        iconPainter.draw(canvas, cachedIcons[slot], artLeft + artSize * .5f, artTop + artSize * .5f,
                            artSize * .55f, if (focused) palette.onFocus else palette.accent)
                    else -> {
                        reusableRectF.set(artLeft, artTop, artLeft + artSize, artTop + artSize)
                        paint.style = Paint.Style.FILL
                        paint.color = if (focused) colorAtAlpha(palette.onFocus, .18f) else palette.surfaceRaised
                        canvas.drawRoundRect(reusableRectF, 6f * density, 6f * density, paint)
                        iconPainter.draw(canvas, cachedIcons[slot], artLeft + artSize * .5f, artTop + artSize * .5f,
                            artSize * .5f, if (focused) palette.onFocus else palette.mutedText)
                    }
                }
            } else if (listLeadingColumn) {
                cachedLeading[slot]?.let { number ->
                    paint.style = Paint.Style.FILL
                    paint.textAlign = Paint.Align.RIGHT
                    paint.textSize = Y2UiTheme.ROW_DETAIL_SP * density
                    paint.color = if (focused) palette.onFocusSecondary else palette.mutedText
                    canvas.drawText(number, (Y2UiTheme.EDGE_DP + 36f) * density, top + rowHeight * .5f + 5f * density, paint)
                    paint.textAlign = Paint.Align.LEFT
                }
            } else if (cachedLeadingIcons[slot]) {
                iconPainter.draw(canvas, cachedIcons[slot], (Y2UiTheme.EDGE_DP + 21f) * density, top + rowHeight * .5f,
                    Y2UiTheme.ICON_SIZE_DP * density, iconColor)
            }

            val subtitle = cachedSubtitles[slot]
            val twoLine = listTwoLine && subtitle != null
            // Two-line lists keep every title bold, so a row without a second line still matches.
            val title = boldOrRegular(listTwoLine)
            title.textAlign = Paint.Align.LEFT
            title.textSize = (if (listTwoLine) Y2UiTheme.TWO_LINE_TITLE_SP else Y2UiTheme.ROW_TITLE_SP) * density
            title.color = titleColor
            val titleBaseline = if (twoLine) top + rowHeight * .5f - 3f * density else top + rowHeight * .5f + 6f * density
            val fullTitle = rows[index].title
            if (focused && textScroller.isTarget(
                    SelectedTextScroller.TARGET_LIST,
                    state.currentScreen,
                    index,
                    fullTitle
                ) && textScroller.drawsFullText
            ) {
                drawScrollingText(
                    canvas,
                    fullTitle,
                    textLeft,
                    textLeft + cachedTitleAvailableWidths[slot],
                    titleBaseline,
                    title,
                    textScroller.offsetPx
                )
            } else {
                canvas.drawText(cachedTitles[slot].orEmpty(), textLeft, titleBaseline, title)
            }
            if (twoLine) {
                paint.style = Paint.Style.FILL
                paint.textAlign = Paint.Align.LEFT
                paint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
                paint.color = if (unavailable && !focused) palette.mutedText else secondaryColor
                canvas.drawText(subtitle.orEmpty(), textLeft, top + rowHeight * .5f + 15f * density, paint)
            }

            var trailingRight = surfaceRight - 10f * density
            cachedProgress[slot]?.let { progress ->
                drawProgressRing(canvas, trailingRight - 11f * density, top + rowHeight * .5f, 10f * density, progress, focused)
                trailingRight -= 30f * density
            }
            cachedTrailingIcons[slot]?.let { trailingIcon ->
                val color = when {
                    focused -> palette.onFocus
                    trailingIcon == Y2Icon.CHEVRON -> palette.mutedText
                    else -> palette.accent
                }
                val size = if (trailingIcon == Y2Icon.CHEVRON) 12f else 16f
                iconPainter.draw(canvas, trailingIcon, trailingRight - 5f * density, top + rowHeight * .5f, size * density, color)
                trailingRight -= 20f * density
            }
            cachedTrailingText[slot]?.let { trailing ->
                paint.style = Paint.Style.FILL
                paint.textAlign = Paint.Align.RIGHT
                paint.textSize = Y2UiTheme.ROW_DETAIL_SP * density
                paint.color = when {
                    focused -> palette.onFocusSecondary
                    cachedTrailingAccent[slot] -> palette.accent
                    else -> palette.mutedText
                }
                canvas.drawText(trailing, trailingRight, top + rowHeight * .5f + 5f * density, paint)
                paint.textAlign = Paint.Align.LEFT
            }
        }
        canvas.restoreToCount(saveCount)
        drawScrollIndicator(canvas)
    }

    private fun boldOrRegular(bold: Boolean): Paint = if (bold) boldPaint else paint

    /** How far through: an arc, a tick once played, or an empty ring before it is started. */
    private fun drawProgressRing(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float,
        progress: com.schulzcode.y2player.core.state.RowProgress,
        focused: Boolean
    ) {
        val strong = if (focused) palette.onFocus else palette.accent
        if (progress.played) {
            paint.style = Paint.Style.FILL
            paint.color = if (focused) colorAtAlpha(palette.onFocus, .22f) else palette.activeSurface
            canvas.drawCircle(centerX, centerY, radius, paint)
            iconPainter.draw(canvas, Y2Icon.CHECK, centerX, centerY, radius * 1.15f, strong)
            paint.style = Paint.Style.FILL
            return
        }
        paint.style = Paint.Style.STROKE
        val started = progress.fraction > 0f
        paint.strokeWidth = (if (started) 2.5f else 1.6f) * density
        paint.color = when {
            focused -> colorAtAlpha(palette.onFocus, .35f)
            started -> palette.track
            else -> palette.scrollThumb
        }
        canvas.drawCircle(centerX, centerY, radius, paint)
        if (started) {
            paint.color = strong
            paint.strokeCap = Paint.Cap.ROUND
            reusableRectF.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
            canvas.drawArc(reusableRectF, -90f, 360f * progress.fraction.coerceIn(0f, 1f), false, paint)
            paint.strokeCap = Paint.Cap.BUTT
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawRowArtwork(canvas: Canvas, left: Float, top: Float, size: Float, bitmap: Bitmap) {
        reusableRect.set(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt())
        val save = canvas.save()
        reusablePath.reset()
        reusableRectF.set(left, top, left + size, top + size)
        reusablePath.addRoundRect(reusableRectF, 6f * density, 6f * density, android.graphics.Path.Direction.CW)
        canvas.clipPath(reusablePath)
        canvas.drawBitmap(bitmap, null, reusableRect, bitmapPaint)
        canvas.restoreToCount(save)
    }

    private fun prepareListTextScrollTarget() {
        var changed = false
        for (scroller in nowTextScrollers) changed = scroller.clear() || changed
        nowScrollTrackId = null
        val selected = state.selectedIndex
        val slot = selected - cachedRowStart
        val row = rows.getOrNull(selected)
        changed = (if (row == null || slot !in cachedTitles.indices) {
            textScroller.clear()
        } else {
            textScroller.setTarget(
                SelectedTextScroller.TARGET_LIST,
                state.currentScreen,
                selected,
                row.title,
                cachedTitleWidths[slot],
                cachedTitleAvailableWidths[slot],
                SystemClock.uptimeMillis()
            )
        }) || changed
        if (changed) rescheduleTextScroll()
    }

    private fun prepareNowPlayingTextScrollTarget() {
        val available = nowPlayingTextWidth()
        val now = SystemClock.uptimeMillis()
        var changed = textScroller.clear()
        val trackId = state.playback.currentTrackId
        if (nowScrollTrackId != trackId) {
            nowScrollTrackId = trackId
            for (scroller in nowTextScrollers) changed = scroller.clear() || changed
        }
        changed = nowPlayingTextScroller(SelectedTextScroller.TARGET_NOW_TITLE).setTarget(
            SelectedTextScroller.TARGET_NOW_TITLE, Screen.NowPlaying, 0,
            cachedNowTitleFull, cachedNowTitleWidth, available, now
        ) || changed
        changed = nowPlayingTextScroller(SelectedTextScroller.TARGET_NOW_ARTIST).setTarget(
            SelectedTextScroller.TARGET_NOW_ARTIST, Screen.NowPlaying, 0,
            cachedNowArtistFull, cachedNowArtistWidth, available, now
        ) || changed
        changed = nowPlayingTextScroller(SelectedTextScroller.TARGET_NOW_ALBUM).setTarget(
            SelectedTextScroller.TARGET_NOW_ALBUM, Screen.NowPlaying, 0,
            cachedNowAlbumFull, cachedNowAlbumWidth, available, now
        ) || changed
        if (changed) rescheduleTextScroll()
    }

    private fun nowPlayingTextScroller(kind: Int): SelectedTextScroller = when (kind) {
        in SelectedTextScroller.TARGET_NOW_TITLE..SelectedTextScroller.TARGET_NOW_ALBUM ->
            nowTextScrollers[kind - SelectedTextScroller.TARGET_NOW_TITLE]
        else -> error("Invalid Now Playing marquee target")
    }

    private fun isNowPlayingScrollTarget(kind: Int, text: String): Boolean {
        val scroller = nowPlayingTextScroller(kind)
        return scroller.drawsFullText && scroller.isTarget(kind, Screen.NowPlaying, 0, text)
    }

    private fun drawScrollingText(
        canvas: Canvas,
        text: String,
        clipLeft: Float,
        clipRight: Float,
        baseline: Float,
        targetPaint: Paint,
        offsetPx: Float
    ) {
        val alignment = targetPaint.textAlign
        val saveCount = canvas.save()
        canvas.clipRect(clipLeft, baseline - targetPaint.textSize, clipRight, baseline + 4f * density)
        targetPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(text, clipLeft - offsetPx, baseline, targetPaint)
        targetPaint.textAlign = alignment
        canvas.restoreToCount(saveCount)
    }

    private fun drawFocus(canvas: Canvas, top: Float, bottom: Float) {
        reusableRectF.set(
            Y2UiTheme.ROW_FOCUS_INSET_DP * density,
            top + Y2UiTheme.ROW_GAP_DP * .5f * density,
            rowSurfaceRight(),
            bottom - Y2UiTheme.ROW_GAP_DP * .5f * density
        )
        paint.style = Paint.Style.FILL
        paint.color = palette.focusFill
        canvas.drawRoundRect(reusableRectF, Y2UiTheme.ROW_RADIUS_DP * density, Y2UiTheme.ROW_RADIUS_DP * density, paint)
    }

    private fun rowSurfaceRight(): Float {
        val right = rowAreaRight() - Y2UiTheme.ROW_FOCUS_INSET_DP * density
        return if (hasScrollIndicator()) right - 4f * density else right
    }

    private fun rowTextLeft(): Float =
        if (listLeadingColumn) (Y2UiTheme.EDGE_DP + 46f) * density
        else if (listArtColumn) (Y2UiTheme.EDGE_DP + 6f + ROW_ARTWORK_SIZE_DP + 12f) * density
        else if (listLeadingIcons) (Y2UiTheme.EDGE_DP + 40f) * density
        else (Y2UiTheme.EDGE_DP + Y2UiTheme.ROW_PADDING_DP) * density

    private fun hasScrollIndicator(): Boolean = rows.size > visibleRowCount()

    private fun drawScrollIndicator(canvas: Canvas) {
        val visibleCount = visibleRowCount()
        if (!hasScrollIndicator()) return
        val right = rowAreaRight() - Y2UiTheme.SCROLLBAR_END_INSET_DP * density
        val left = right - Y2UiTheme.SCROLLBAR_WIDTH_DP * density
        val trackTop = rowAreaTop() + 4f * density
        val trackBottom = rowAreaBottom() - 4f * density
        val trackHeight = trackBottom - trackTop
        val radius = Y2UiTheme.SCROLLBAR_WIDTH_DP * .5f * density
        paint.style = Paint.Style.FILL
        paint.color = palette.track
        reusableRectF.set(left, trackTop, right, trackBottom)
        canvas.drawRoundRect(reusableRectF, radius, radius, paint)
        val thumbHeight = (trackHeight * visibleCount / rows.size.toFloat()).coerceAtLeast(24f * density)
        val maxStart = (rows.size - visibleCount).coerceAtLeast(1)
        val thumbTop = trackTop + (trackHeight - thumbHeight) * (visibleStart.toFloat() / maxStart)
        paint.color = palette.scrollThumb
        reusableRectF.set(left, thumbTop, right, thumbTop + thumbHeight)
        canvas.drawRoundRect(reusableRectF, radius, radius, paint)
    }

    private fun drawEmptyState(canvas: Canvas) {
        val search = state.currentScreen as? Screen.Search
        val storageAvailable = state.device.internalStorageAvailable || state.device.removableStorageAvailable
        val videos = state.currentScreen as? Screen.Videos
        val kind = Y2UiLogic.emptyState(
            videosScreen = videos != null,
            videosLoading = videos != null && state.videos.listing(videos.path) == null,
            scanning = state.library.isScanning,
            storageAvailable = storageAvailable,
            queueScreen = state.currentScreen == Screen.Queue,
            favoritesScreen = state.currentScreen == Screen.Favorites,
            playlistScreen = state.currentScreen is Screen.PlaylistTracks,
            recentScreen = state.currentScreen == Screen.RecentlyPlayed,
            audiobooksScreen = state.currentScreen == Screen.Audiobooks,
            drilldownScreen = state.currentScreen is Screen.AlbumSongs ||
                state.currentScreen is Screen.ArtistSongs ||
                state.currentScreen is Screen.ArtistAlbums ||
                state.currentScreen is Screen.AudiobookChapters
        )
        val title: String
        val detail: String
        val icon: Y2Icon
        if (search != null) {
            title = if (search.query.isBlank()) "Search your device" else "No search results"
            detail = if (search.query.isBlank()) "Use the keyboard below to begin" else "Try another word or spelling"
            icon = Y2Icon.SEARCH
        } else when (kind) {
            EmptyStateKind.SCANNING -> {
                title = "Scanning music"
                detail = "Building your library safely"
                icon = Y2Icon.PREPARING
            }
            EmptyStateKind.STORAGE_MISSING -> {
                title = "Music storage unavailable"
                detail = "Insert or remount the SD card"
                icon = Y2Icon.STORAGE
            }
            EmptyStateKind.EMPTY_LIST -> {
                title = "Nothing here"
                detail = "These files are no longer on the card"
                icon = Y2Icon.WARNING
            }
            EmptyStateKind.VIDEOS_LOADING -> {
                title = "Looking for videos"
                detail = "Reading the Videos folder"
                icon = Y2Icon.PREPARING
            }
            EmptyStateKind.EMPTY_VIDEOS -> {
                title = "No videos found"
                detail = "Put MP4 files inside a Videos folder"
                icon = Y2Icon.VIDEO
            }
            EmptyStateKind.EMPTY_AUDIOBOOKS -> {
                title = "No talks or podcasts yet"
                detail = "Put them in a Podcasts folder, or tag them Podcast"
                icon = Y2Icon.BOOK
            }
            EmptyStateKind.EMPTY_QUEUE -> {
                title = "Queue is empty"
                detail = "Choose a song to begin"
                icon = Y2Icon.QUEUE
            }
            EmptyStateKind.EMPTY_FAVORITES -> {
                title = "No favourites yet"
                detail = "Hold Centre on a song, then choose Favourite"
                icon = Y2Icon.FAVORITE
            }
            EmptyStateKind.EMPTY_RECENT -> {
                title = "Nothing played yet"
                detail = "Played tracks will appear here"
                icon = Y2Icon.RECENT
            }
            EmptyStateKind.EMPTY_PLAYLIST -> {
                title = "Playlist is empty"
                detail = "Hold Centre on a song, then Add to Playlist"
                icon = Y2Icon.PLAYLIST
            }
            EmptyStateKind.NO_MUSIC -> {
                title = "No music found"
                detail = "Add supported audio files"
                icon = Y2Icon.SONG
            }
        }
        val centerX = width * .5f
        val rowsTop = rowAreaTop()
        val centerY = rowsTop + (rowAreaBottom() - rowsTop) * .42f
        paint.style = Paint.Style.FILL
        paint.color = palette.surface
        canvas.drawCircle(centerX, centerY - 34f * density, 26f * density, paint)
        iconPainter.draw(canvas, icon, centerX, centerY - 34f * density, 26f * density,
            if (kind == EmptyStateKind.STORAGE_MISSING) palette.warning else palette.accent)
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.textSize = Y2UiTheme.SECTION_TITLE_SP * density
        boldPaint.color = palette.primaryText
        canvas.drawText(title, centerX, centerY + 14f * density, boldPaint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = Y2UiTheme.BODY_SP * density
        paint.color = palette.secondaryText
        canvas.drawText(ellipsize(detail, width - 34f * density, paint), centerX, centerY + 36f * density, paint)
        val emptyAction = if (search != null) EmptyStateAction.NONE else Y2UiLogic.emptyStateAction(kind)
        val action = when (emptyAction) {
            EmptyStateAction.OPEN_STORAGE -> "Centre opens Storage"
            EmptyStateAction.GO_BACK -> "Centre goes back"
            EmptyStateAction.NONE -> null
        }
        if (action != null) {
            boldPaint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
            val pillWidth = boldPaint.measureText(action) + 24f * density
            boldPaint.textAlign = Paint.Align.LEFT
            drawPill(canvas, centerX - pillWidth * .5f, centerY + 52f * density, action, chevron = false)
        }
        paint.textAlign = Paint.Align.LEFT
        boldPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawDetailHeader(canvas: Canvas) {
        val top = headerHeight
        val bottom = top + detailHeaderHeight
        paint.style = Paint.Style.FILL
        paint.color = palette.divider
        canvas.drawRect(16f * density, bottom - density, width - 16f * density, bottom, paint)

        val artSize = 60f * density
        val artLeft = 16f * density
        val artTop = top + 8f * density
        val fallback = if (state.currentScreen is Screen.ArtistSongs) Y2Icon.ARTIST else Y2Icon.ALBUM
        drawArtwork(canvas, artLeft, artTop, artSize, detailArtwork, fallback)

        val textLeft = artLeft + artSize + 12f * density
        boldPaint.textAlign = Paint.Align.LEFT
        boldPaint.textSize = Y2UiTheme.SECTION_TITLE_SP * density
        boldPaint.color = palette.primaryText
        canvas.drawText(cachedDetailTitle, textLeft, top + 25f * density, boldPaint)
        if (cachedDetailTitle2.isNotEmpty()) {
            canvas.drawText(cachedDetailTitle2, textLeft, top + 44f * density, boldPaint)
        }
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
        paint.color = palette.secondaryText
        canvas.drawText(cachedDetailSubtitle, textLeft, if (cachedDetailTitle2.isEmpty()) top + 51f * density else top + 66f * density, paint)
    }

    private fun drawNowPlaying(canvas: Canvas) {
        prepareNowPlayingTextScrollTarget()
        if (Y2UiLogic.playerLayout(width, height) == PlayerLayout.WIDE) drawNowPlayingWide(canvas) else drawNowPlayingTall(canvas)
    }

    private var cardBackdrop: Bitmap? = null
    private var cardStart = 0

    private fun captureCardBackdrop() {
        if (width <= 0 || height <= 0) return
        val bitmap = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565) }.getOrNull() ?: return
        draw(Canvas(bitmap))
        cardBackdrop = bitmap
    }

    private data class CardHeader(val title: String, val detail: String?, val trackId: Long?, val wrapDetail: Boolean)

    private fun cardHeader(): CardHeader? {
        fun track(id: Long?) = id?.let(state.library.byId::get)
        return when (val screen = state.currentScreen) {
            is Screen.TrackOptions -> track(screen.trackId)?.let { CardHeader(it.title, it.displayArtist, it.id, false) }
            Screen.NowPlayingOptions -> track(state.playback.currentTrackId)?.let { CardHeader(it.title, it.displayArtist, it.id, false) }
            is Screen.QueueOptions -> track(state.playback.queue.firstOrNull { it.id == screen.entryId }?.trackId)
                ?.let { CardHeader(it.title, it.displayArtist, it.id, false) }
            is Screen.CollectionOptions -> CardHeader(screen.title, ScreenContent.trackCountLabel(screen.trackIds.size), screen.trackIds.firstOrNull(), false)
            is Screen.AudiobookOptions -> ScreenContent.audiobookEntry(state, screen.folderKey)
                ?.let { CardHeader(it.name, null, it.currentChapterId ?: it.chapterIds.firstOrNull(), false) }
            Screen.QueueManagement -> CardHeader("Queue", null, null, false)
            is Screen.ConfirmAction -> (rows.firstOrNull() as? ScreenRow.Group)?.let { CardHeader(it.title, it.subtitle, null, true) }
            else -> null
        }
    }

    private fun drawCardMenu(canvas: Canvas) {
        val backdrop = cardBackdrop
        if (backdrop != null) canvas.drawBitmap(backdrop, 0f, 0f, null) else canvas.drawColor(palette.background)
        canvas.drawColor(colorAtAlpha(palette.background, .66f))

        val header = cardHeader()
        val offset = if (state.currentScreen is Screen.ConfirmAction && rows.firstOrNull() is ScreenRow.Group) 1 else 0
        val items = rows.drop(offset)
        val cardWidth = (width - 48f * density).coerceAtMost(356f * density)
        val left = (width - cardWidth) * .5f
        val right = left + cardWidth
        val pad = 10f * density
        val rowH = 40f * density

        paint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
        val detailLines = header?.detail?.let { if (header.wrapDetail) wrap(it, cardWidth - pad * 4, paint, 3) else listOf(it) }.orEmpty()
        val headerHeight = when {
            header == null -> 0f
            header.trackId != null -> 58f * density
            else -> (30f + 18f * detailLines.size + 8f) * density
        }
        val maxRows = ((height - 24f * density - headerHeight - pad * 2) / rowH).toInt().coerceAtLeast(1)
        val visible = items.size.coerceAtMost(maxRows)
        val selected = (state.selectedIndex - offset).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (selected < cardStart) cardStart = selected
        if (selected >= cardStart + visible) cardStart = selected - visible + 1
        cardStart = cardStart.coerceIn(0, (items.size - visible).coerceAtLeast(0))
        val cardHeight = headerHeight + visible * rowH + pad * 2
        val top = ((height - cardHeight) * .5f).coerceAtLeast(12f * density)

        // A soft shadow, then the card.
        paint.style = Paint.Style.FILL
        for (step in 3 downTo 1) {
            paint.color = colorAtAlpha(0xFF000000.toInt(), .035f * (4 - step))
            reusableRectF.set(left - step * 2f * density, top - step * density + 6f * density,
                right + step * 2f * density, top + cardHeight + step * 3f * density)
            canvas.drawRoundRect(reusableRectF, 18f * density, 18f * density, paint)
        }
        paint.color = palette.background
        reusableRectF.set(left, top, right, top + cardHeight)
        canvas.drawRoundRect(reusableRectF, 16f * density, 16f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = palette.divider
        canvas.drawRoundRect(reusableRectF, 16f * density, 16f * density, paint)
        paint.style = Paint.Style.FILL

        var y = top + pad
        if (header != null) {
            val textLeft: Float
            if (header.trackId != null) {
                val artSize = 38f * density
                val bitmap = if (header.trackId == state.playback.currentTrackId) artwork else paneArtwork(header.trackId, null)
                drawCover(canvas, left + pad * 1.6f, y + 4f * density, artSize, bitmap, 6f * density)
                textLeft = left + pad * 1.6f + artSize + 10f * density
                boldPaint.textAlign = Paint.Align.LEFT
                boldPaint.textSize = Y2UiTheme.BODY_SP * density
                boldPaint.color = palette.primaryText
                canvas.drawText(ellipsize(header.title, right - pad * 2 - textLeft, boldPaint), textLeft, y + 20f * density, boldPaint)
                header.detail?.let {
                    paint.textAlign = Paint.Align.LEFT
                    paint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
                    paint.color = palette.secondaryText
                    canvas.drawText(ellipsize(it, right - pad * 2 - textLeft, paint), textLeft, y + 38f * density, paint)
                }
            } else {
                textLeft = left + pad * 2
                boldPaint.textAlign = Paint.Align.LEFT
                boldPaint.textSize = Y2UiTheme.MINI_TITLE_SP * density
                boldPaint.color = palette.primaryText
                canvas.drawText(ellipsize(header.title, right - pad * 2 - textLeft, boldPaint), textLeft, y + 20f * density, boldPaint)
                paint.textAlign = Paint.Align.LEFT
                paint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
                paint.color = palette.secondaryText
                detailLines.forEachIndexed { index, line -> canvas.drawText(line, textLeft, y + (38f + 18f * index) * density, paint) }
            }
            y += headerHeight
            paint.color = palette.divider
            canvas.drawRect(left + pad, y - 5f * density, right - pad, y - 4f * density, paint)
        }

        for (index in cardStart until cardStart + visible) {
            val row = items.getOrNull(index) ?: break
            val rowTop = y + (index - cardStart) * rowH
            val focused = index == selected
            if (focused) {
                paint.style = Paint.Style.FILL
                paint.color = palette.focusFill
                reusableRectF.set(left + pad, rowTop + 1f * density, right - pad, rowTop + rowH - 1f * density)
                canvas.drawRoundRect(reusableRectF, 10f * density, 10f * density, paint)
            }
            val unavailable = row is ScreenRow.Group
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = Y2UiTheme.MINI_TITLE_SP * density
            paint.color = when {
                focused -> palette.onFocus
                unavailable -> palette.mutedText
                else -> palette.primaryText
            }
            var valueRight = right - pad * 2
            val active = isActive(row)
            val navigation = trailingIconFor(row, active) == Y2Icon.CHEVRON
            val value = row.subtitle?.takeUnless { navigation }
            if (navigation) {
                iconPainter.draw(canvas, Y2Icon.CHEVRON, valueRight - 4f * density, rowTop + rowH * .5f, 11f * density,
                    if (focused) palette.onFocus else palette.mutedText)
                valueRight -= 18f * density
            }
            val valueWidth = value?.let {
                paint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
                paint.measureText(it).coerceAtMost(cardWidth * .4f)
            } ?: 0f
            paint.style = Paint.Style.FILL
            paint.textSize = Y2UiTheme.MINI_TITLE_SP * density
            paint.color = when {
                focused -> palette.onFocus
                unavailable -> palette.mutedText
                else -> palette.primaryText
            }
            canvas.drawText(ellipsize(row.title, valueRight - valueWidth - 12f * density - (left + pad * 2), paint),
                left + pad * 2, rowTop + rowH * .5f + 5.5f * density, paint)
            if (value != null) {
                paint.textAlign = Paint.Align.RIGHT
                paint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
                paint.color = if (focused) palette.onFocusSecondary else palette.mutedText
                canvas.drawText(ellipsize(value, valueWidth, paint), valueRight, rowTop + rowH * .5f + 4.5f * density, paint)
                paint.textAlign = Paint.Align.LEFT
            }
        }
        if (items.size > visible) {
            // A short thumb on the card's edge shows there is more below.
            val trackTop = y
            val trackBottom = y + visible * rowH
            val thumb = ((trackBottom - trackTop) * visible / items.size).coerceAtLeast(16f * density)
            val thumbTop = trackTop + (trackBottom - trackTop - thumb) * cardStart / (items.size - visible).toFloat()
            paint.color = palette.scrollThumb
            reusableRectF.set(right - 5f * density, thumbTop, right - 2f * density, thumbTop + thumb)
            canvas.drawRoundRect(reusableRectF, 1.5f * density, 1.5f * density, paint)
        }
    }

    private fun colorAtAlpha(color: Int, fraction: Float): Int {
        val sourceAlpha = color ushr 24 and 0xFF
        val alpha = (sourceAlpha * fraction.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
        return color and 0x00FFFFFF or (alpha shl 24)
    }

    private fun drawNowPlayingWide(canvas: Canvas) {
        val margin = 16f * density
        val top = headerHeight + 12f * density
        val artSize = NOW_ART_DP * density
        drawCover(canvas, margin, top, artSize, artwork, 12f * density)

        val colLeft = margin + artSize + 16f * density
        val colRight = width - margin
        val colWidth = colRight - colLeft
        var y = top + 12f * density
        boldPaint.textAlign = Paint.Align.LEFT
        if (cachedNowSource.isNotEmpty()) {
            boldPaint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
            boldPaint.color = palette.accent
            canvas.drawText(cachedNowSource, colLeft, y, boldPaint)
            y += 26f * density
        } else {
            y += 12f * density
        }
        boldPaint.textSize = Y2UiTheme.NOW_TITLE_SP * density
        boldPaint.color = palette.primaryText
        cachedNowTitleLines.forEach { line ->
            canvas.drawText(line, colLeft, y, boldPaint)
            y += 27f * density
        }
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = Y2UiTheme.NOW_ARTIST_SP * density
        paint.color = palette.secondaryText
        cachedNowArtistLines.forEach { line ->
            canvas.drawText(line, colLeft, y - 4f * density, paint)
            y += 20f * density
        }

        // Up next, as a soft card along the bottom of the cover.
        nowPlayingPane?.let { pane ->
            val cardTop = top + artSize - 48f * density
            if (cardTop > y - 12f * density) {
                reusableRectF.set(colLeft, cardTop, colRight, top + artSize)
                paint.style = Paint.Style.FILL
                paint.color = palette.surface
                canvas.drawRoundRect(reusableRectF, 10f * density, 10f * density, paint)
                boldPaint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
                boldPaint.color = palette.mutedText
                canvas.drawText("Up next", colLeft + 10f * density, cardTop + 18f * density, boldPaint)
                boldPaint.textSize = Y2UiTheme.ROW_DETAIL_SP * density
                boldPaint.color = if (pane.upNextIsRepeat) palette.accent else palette.primaryText
                var textLeft = colLeft + 10f * density
                if (pane.upNextIsRepeat) {
                    iconPainter.draw(canvas, Y2Icon.REPEAT, textLeft + 7f * density, cardTop + 33f * density, 14f * density, palette.accent)
                    textLeft += 19f * density
                }
                canvas.drawText(ellipsize(pane.upNext, colRight - 10f * density - textLeft, boldPaint),
                    textLeft, cardTop + 38f * density, boldPaint)
            }
        }

        val barTop = top + artSize + 12f * density
        drawThinBar(canvas, margin, width - margin, barTop, Y2UiTheme.PROGRESS_HEIGHT_DP * density,
            Y2UiLogic.progressFraction(state.playback.positionMs, state.playback.durationMs), palette.primaryText)
        val timesY = barTop + 23f * density
        paint.style = Paint.Style.FILL
        paint.textSize = Y2UiTheme.ROW_DETAIL_SP * density
        paint.color = palette.secondaryText
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(cachedElapsed, margin, timesY, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(cachedRemaining, width - margin, timesY, paint)
        if (cachedStatusTag.isNotEmpty()) {
            boldPaint.textAlign = Paint.Align.CENTER
            boldPaint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
            boldPaint.color = if (state.playback.status == PlaybackStatus.ERROR) palette.warning else palette.accent
            canvas.drawText(cachedStatusTag, width * .5f, timesY, boldPaint)
        }
        drawNowPlayingTiles(canvas, margin, width - margin, height - 10f * density)
        paint.textAlign = Paint.Align.LEFT
        boldPaint.textAlign = Paint.Align.LEFT
    }

    /** Four large, labelled tiles; each says its state in words. */
    private fun drawNowPlayingTiles(canvas: Canvas, left: Float, right: Float, bottom: Float) {
        val tiles = rows.drop(1).take(4)
        if (tiles.isEmpty()) return
        val gap = 8f * density
        val tileHeight = 46f * density
        val tileWidth = (right - left - gap * (tiles.size - 1)) / tiles.size
        val top = bottom - tileHeight
        tiles.forEachIndexed { index, row ->
            val tileLeft = left + index * (tileWidth + gap)
            val focused = state.currentScreen == Screen.NowPlaying && state.selectedIndex == index + 1
            val on = isTileOn(row)
            reusableRectF.set(tileLeft, top, tileLeft + tileWidth, bottom)
            paint.style = Paint.Style.FILL
            paint.color = when {
                focused -> palette.focusFill
                on -> palette.focusSurface
                else -> palette.surfaceRaised
            }
            canvas.drawRoundRect(reusableRectF, 12f * density, 12f * density, paint)
            val labelColor = when {
                focused -> palette.onFocusSecondary
                on -> palette.accent
                else -> palette.secondaryText
            }
            val valueColor = when {
                focused -> palette.onFocus
                on -> palette.accent
                else -> palette.primaryText
            }
            val centerX = tileLeft + tileWidth * .5f
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
            paint.color = labelColor
            canvas.drawText(row.title, centerX, top + 19f * density, paint)
            boldPaint.textAlign = Paint.Align.CENTER
            boldPaint.textSize = Y2UiTheme.BODY_SP * density
            boldPaint.color = valueColor
            canvas.drawText(ellipsize(row.subtitle.orEmpty(), tileWidth - 8f * density, boldPaint), centerX, top + 37f * density, boldPaint)
        }
        paint.textAlign = Paint.Align.LEFT
        boldPaint.textAlign = Paint.Align.LEFT
    }

    private fun isTileOn(row: ScreenRow): Boolean {
        val key = (row as? ScreenRow.Action)?.key ?: return false
        return when {
            key == "shuffle" -> state.playback.shuffleEnabled
            key == "repeat" -> state.playback.repeatMode != RepeatMode.OFF
            key == "sleep_timer" -> state.playback.sleepTimerMode != SleepTimerMode.OFF
            key.startsWith("np_favorite:") -> cachedNowFavorite
            else -> false
        }
    }

    // Volume: a slim bar beside the side buttons, shown while it changes, then faded.
    private var volumeOverlayPercent = -1
    private var volumeOverlayShownAt = 0L

    fun showVolume(percent: Int) {
        volumeOverlayPercent = percent.coerceIn(0, 100)
        volumeOverlayShownAt = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    private fun drawVolumeOverlay(canvas: Canvas) {
        if (volumeOverlayPercent < 0) return
        val age = SystemClock.uptimeMillis() - volumeOverlayShownAt
        val alpha = when {
            age < VOLUME_HOLD_MS -> 1f
            age < VOLUME_HOLD_MS + VOLUME_FADE_MS -> 1f - (age - VOLUME_HOLD_MS).toFloat() / VOLUME_FADE_MS
            else -> 0f
        }
        if (alpha <= 0f) {
            volumeOverlayPercent = -1
            return
        }
        postInvalidateOnAnimation()
        val right = width - 5f * density
        val left = right - 8f * density
        val top = headerHeight + 12f * density
        val bottom = height - 12f * density
        paint.style = Paint.Style.FILL
        paint.color = colorAtAlpha(palette.background, .92f * alpha)
        reusableRectF.set(left - 3f * density, top - 3f * density, right + 3f * density, bottom + 3f * density)
        canvas.drawRoundRect(reusableRectF, 7f * density, 7f * density, paint)
        paint.color = colorAtAlpha(palette.track, alpha)
        reusableRectF.set(left, top, right, bottom)
        canvas.drawRoundRect(reusableRectF, 4f * density, 4f * density, paint)
        val fillTop = bottom - (bottom - top) * volumeOverlayPercent / 100f
        if (volumeOverlayPercent > 0) {
            paint.color = colorAtAlpha(palette.accent, alpha)
            reusableRectF.set(left, fillTop.coerceAtMost(bottom - 8f * density), right, bottom)
            canvas.drawRoundRect(reusableRectF, 4f * density, 4f * density, paint)
        }
    }

    private fun drawPlaybackControls(canvas: Canvas, left: Float, right: Float, centerY: Float) {
        val centerX = (left + right) * .5f
        val sideOffset = ((right - left) * .34f).coerceAtMost(70f * density)
        iconPainter.draw(canvas, Y2Icon.PREVIOUS, centerX - sideOffset, centerY, 24f * density, palette.primaryText)
        iconPainter.draw(canvas, Y2Icon.NEXT, centerX + sideOffset, centerY, 24f * density, palette.primaryText)

        paint.style = Paint.Style.FILL
        paint.color = palette.focusSurface
        canvas.drawCircle(centerX, centerY, 22f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Y2UiTheme.FOCUS_OUTLINE_DP * density
        paint.color = palette.accent
        canvas.drawCircle(centerX, centerY, 22f * density, paint)
        paint.style = Paint.Style.FILL
        val playbackIcon = when (state.playback.status) {
            PlaybackStatus.PLAYING -> Y2Icon.PAUSE
            PlaybackStatus.PREPARING -> Y2Icon.PREPARING
            PlaybackStatus.ERROR -> Y2Icon.WARNING
            else -> Y2Icon.PLAY
        }
        iconPainter.draw(
            canvas,
            playbackIcon,
            centerX,
            centerY,
            Y2UiTheme.PRIMARY_ICON_SIZE_DP * density,
            if (state.playback.status == PlaybackStatus.ERROR) palette.warning else palette.accent
        )
    }

    private fun drawHomePane(canvas: Canvas) {
        val left = rowAreaRight()
        val right = width.toFloat()
        val top = headerHeight
        val bottom = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = palette.surface
        canvas.drawRect(left, top, right, bottom, paint)
        val inner = left + 14f * density
        val innerRight = right - 14f * density
        when (val pane = homePane) {
            is HomePane.NowPlaying -> drawNowPlayingPane(canvas, pane, inner, innerRight, top + 14f * density, bottom - 12f * density)
            is HomePane.Continue -> drawContinuePane(canvas, pane, inner, innerRight, top + 14f * density, bottom - 12f * density)
            is HomePane.Collection -> drawCollectionPane(canvas, pane, inner, innerRight, top + 14f * density, bottom - 12f * density)
            null -> drawLibraryPane(canvas, inner, innerRight, top, bottom)
        }
        paint.textAlign = Paint.Align.LEFT
        boldPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawNowPlayingPane(canvas: Canvas, pane: HomePane.NowPlaying, left: Float, right: Float, top: Float, bottom: Float) {
        val artSize = 118f * density
        drawCover(canvas, left, top, artSize, artwork, 10f * density)
        var y = top + artSize + 22f * density
        boldPaint.textAlign = Paint.Align.LEFT
        boldPaint.textSize = Y2UiTheme.MINI_TITLE_SP * density
        boldPaint.color = palette.primaryText
        canvas.drawText(ellipsize(pane.title, right - left, boldPaint), left, y, boldPaint)
        y += 18f * density
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
        paint.color = palette.secondaryText
        canvas.drawText(ellipsize(pane.artist, right - left, paint), left, y, paint)
        y += 14f * density
        val remaining = "−" + TimeFormat.duration((state.playback.durationMs - state.playback.positionMs).coerceAtLeast(0L))
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(remaining, right, y + 6f * density, paint)
        val barRight = right - paint.measureText(remaining) - 8f * density
        drawThinBar(canvas, left, barRight, y, 4f * density,
            Y2UiLogic.progressFraction(state.playback.positionMs, state.playback.durationMs), palette.accent)
        drawUpNext(canvas, pane, left, right, bottom)
    }

    private fun drawUpNext(canvas: Canvas, pane: HomePane.NowPlaying, left: Float, right: Float, bottom: Float) {
        paint.style = Paint.Style.FILL
        paint.color = palette.divider
        canvas.drawRect(left, bottom - 42f * density, right, bottom - 41f * density, paint)
        boldPaint.textAlign = Paint.Align.LEFT
        boldPaint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
        boldPaint.color = palette.mutedText
        canvas.drawText("Up next", left, bottom - 24f * density, boldPaint)
        boldPaint.textSize = Y2UiTheme.ROW_DETAIL_SP * density
        boldPaint.color = if (pane.upNextIsRepeat) palette.accent else palette.primaryText
        var textLeft = left
        if (pane.upNextIsRepeat) {
            iconPainter.draw(canvas, Y2Icon.REPEAT, left + 7f * density, bottom - 6f * density, 14f * density, palette.accent)
            textLeft += 19f * density
        }
        canvas.drawText(ellipsize(pane.upNext, right - textLeft, boldPaint), textLeft, bottom - 2f * density, boldPaint)
    }

    private fun drawContinuePane(canvas: Canvas, pane: HomePane.Continue, left: Float, right: Float, top: Float, bottom: Float) {
        val first = pane.first
        val artSize = 72f * density
        drawCover(canvas, left, top, artSize, paneArtwork(first.artTrackId, first.artPath), 9f * density, Y2Icon.PLAY)
        val textLeft = left + artSize + 10f * density
        boldPaint.textAlign = Paint.Align.LEFT
        boldPaint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
        boldPaint.color = palette.accent
        canvas.drawText("Resume", textLeft, top + 12f * density, boldPaint)
        boldPaint.textSize = Y2UiTheme.MINI_TITLE_SP * density
        boldPaint.color = palette.primaryText
        val titleLines = wrap(first.title, right - textLeft, boldPaint, 2)
        var y = top + 32f * density
        titleLines.forEach { line -> canvas.drawText(line, textLeft, y, boldPaint); y += 18f * density }
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
        paint.color = palette.secondaryText
        canvas.drawText(ellipsize(first.detail, right - textLeft, paint), textLeft, y - 1f * density, paint)

        y = top + artSize + 16f * density
        first.left?.let { label ->
            val text = "$label left"
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(text, right, y + 5f * density, paint)
            drawThinBar(canvas, left, right - paint.measureText(text) - 8f * density, y, 4f * density, first.fraction, palette.accent)
            paint.textAlign = Paint.Align.LEFT
        }
        y += 18f * density
        drawPill(canvas, left, y, "All in progress", chevron = true)
        y += 40f * density
        if (pane.others.isEmpty()) return
        paint.color = palette.divider
        canvas.drawRect(left, y - 8f * density, right, y - 7f * density, paint)
        boldPaint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
        boldPaint.color = palette.mutedText
        canvas.drawText("Also in progress", left, y + 8f * density, boldPaint)
        y += 28f * density
        pane.others.forEach { item ->
            if (y > bottom) return
            paint.textAlign = Paint.Align.RIGHT
            paint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
            paint.color = palette.secondaryText
            val left2 = item.left.orEmpty()
            canvas.drawText(left2, right, y, paint)
            boldPaint.textSize = Y2UiTheme.ROW_DETAIL_SP * density
            boldPaint.color = palette.primaryText
            canvas.drawText(ellipsize(item.title, right - left - paint.measureText(left2) - 10f * density, boldPaint), left, y, boldPaint)
            drawThinBar(canvas, left, right, y + 6f * density, 3f * density, item.fraction, palette.mutedText)
            y += 30f * density
        }
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawCollectionPane(canvas: Canvas, pane: HomePane.Collection, left: Float, right: Float, top: Float, bottom: Float) {
        var y = top
        if (pane.artTrackIds.isNotEmpty()) {
            val size = 118f * density
            if (pane.artTrackIds.size >= 4) {
                val gap = 3f * density
                val cell = (size - gap) * .5f
                pane.artTrackIds.take(4).forEachIndexed { index, id ->
                    val x = left + (index % 2) * (cell + gap)
                    val cellTop = y + (index / 2) * (cell + gap)
                    drawCover(canvas, x, cellTop, cell, paneArtwork(id, null), 6f * density)
                }
            } else {
                drawCover(canvas, left, y, size, paneArtwork(pane.artTrackIds.first(), null), 10f * density)
            }
            y += size + 22f * density
        } else {
            y += 16f * density
        }
        boldPaint.textAlign = Paint.Align.LEFT
        boldPaint.textSize = Y2UiTheme.MINI_TITLE_SP * density
        boldPaint.color = palette.primaryText
        canvas.drawText(ellipsize(pane.title, right - left, boldPaint), left, y, boldPaint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
        paint.color = palette.secondaryText
        pane.detail?.let {
            y += 18f * density
            canvas.drawText(ellipsize(it, right - left, paint), left, y, paint)
        }
        val hintTop = if (pane.hint != null) bottom - 28f * density else bottom
        y += 8f * density
        pane.lines.forEach { line ->
            if (y + 22f * density > hintTop) return@forEach
            y += 20f * density
            val textPaint = if (line.emphasised) boldPaint else paint
            textPaint.textSize = Y2UiTheme.ROW_DETAIL_SP * density
            textPaint.color = if (line.emphasised) palette.accent else palette.primaryText
            val detail = line.detail
            paint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
            val detailWidth = if (detail == null) 0f else paint.measureText(detail).coerceAtMost((right - left) * .45f)
            canvas.drawText(ellipsize(line.text, right - left - detailWidth - 8f * density, textPaint), left, y, textPaint)
            if (detail != null) {
                paint.textAlign = Paint.Align.RIGHT
                paint.color = palette.mutedText
                canvas.drawText(ellipsize(detail, detailWidth, paint), right, y, paint)
                paint.textAlign = Paint.Align.LEFT
            }
        }
        pane.hint?.let { drawPill(canvas, left, hintTop, it, chevron = false, maxRight = right) }
    }

    private fun drawLibraryPane(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        val centerX = (left + right) * .5f
        val centerY = (top + bottom) * .5f
        iconPainter.draw(canvas, Y2Icon.SONG, centerX, centerY - 34f * density, 34f * density, palette.accent)
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.textSize = Y2UiTheme.BODY_SP * density
        boldPaint.color = palette.primaryText
        canvas.drawText(cachedHomeStats, centerX, centerY + 6f * density, boldPaint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = Y2UiTheme.NAV_LABEL_SP * density
        paint.color = palette.mutedText
        canvas.drawText(cachedHomeHint, centerX, centerY + 24f * density, paint)
        if (state.library.isScanning) {
            drawScanProgressBar(canvas, left, right, centerY + 36f * density)
        }
    }

    /** A rounded pill, white on the pane, holding a hint about what a key does. */
    private fun drawPill(canvas: Canvas, left: Float, top: Float, text: String, chevron: Boolean, maxRight: Float = width.toFloat()) {
        boldPaint.textAlign = Paint.Align.LEFT
        boldPaint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
        val padding = 12f * density
        val extra = if (chevron) 14f * density else 0f
        val label = ellipsize(text, maxRight - left - padding * 2 - extra, boldPaint)
        val pillRight = left + padding * 2 + boldPaint.measureText(label) + extra
        reusableRectF.set(left, top, pillRight, top + 26f * density)
        paint.style = Paint.Style.FILL
        paint.color = palette.background
        canvas.drawRoundRect(reusableRectF, 13f * density, 13f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = palette.divider
        canvas.drawRoundRect(reusableRectF, 13f * density, 13f * density, paint)
        paint.style = Paint.Style.FILL
        boldPaint.color = palette.accent
        canvas.drawText(label, left + padding, top + 17.5f * density, boldPaint)
        if (chevron) {
            iconPainter.draw(canvas, Y2Icon.CHEVRON, pillRight - padding - 3f * density, top + 13f * density, 10f * density, palette.accent)
            paint.style = Paint.Style.FILL
        }
    }

    private fun drawThinBar(canvas: Canvas, left: Float, right: Float, top: Float, height: Float, fraction: Float, color: Int) {
        if (right <= left) return
        paint.style = Paint.Style.FILL
        paint.color = palette.track
        reusableRectF.set(left, top, right, top + height)
        canvas.drawRoundRect(reusableRectF, height * .5f, height * .5f, paint)
        val fill = fraction.coerceIn(0f, 1f)
        if (fill <= 0f) return
        paint.color = color
        reusableRectF.set(left, top, left + (right - left) * fill, top + height)
        canvas.drawRoundRect(reusableRectF, height * .5f, height * .5f, paint)
    }

    /** Square cover with rounded corners; a quiet placeholder until the bitmap loads. */
    private fun drawCover(canvas: Canvas, left: Float, top: Float, size: Float, bitmap: Bitmap?, radius: Float, fallback: Y2Icon = Y2Icon.SONG) {
        reusableRectF.set(left, top, left + size, top + size)
        if (bitmap == null) {
            paint.style = Paint.Style.FILL
            paint.color = palette.surfaceRaised
            if (palette.surfaceRaised == palette.surface) paint.color = palette.divider
            canvas.drawRoundRect(reusableRectF, radius, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            paint.color = palette.divider
            canvas.drawRoundRect(reusableRectF, radius, radius, paint)
            iconPainter.draw(canvas, fallback, left + size * .5f, top + size * .5f, size * .32f, palette.mutedText)
            paint.style = Paint.Style.FILL
            return
        }
        val save = canvas.save()
        reusablePath.reset()
        reusablePath.addRoundRect(reusableRectF, radius, radius, android.graphics.Path.Direction.CW)
        canvas.clipPath(reusablePath)
        reusableRect.set(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt())
        canvas.drawBitmap(bitmap, null, reusableRect, bitmapPaint)
        canvas.restoreToCount(save)
    }

    /** Up to [maxLines] lines broken at spaces, the last one ellipsized. */
    private fun wrap(text: String, maxWidth: Float, target: Paint, maxLines: Int): List<String> {
        if (target.measureText(text) <= maxWidth || maxLines <= 1) return listOf(ellipsize(text, maxWidth, target))
        val lines = ArrayList<String>(maxLines)
        var rest = text
        while (rest.isNotEmpty() && lines.size < maxLines - 1) {
            val fit = Y2UiLogic.truncationEnd(rest.length, maxWidth, 0f) { end -> target.measureText(rest, 0, end) }
            if (fit >= rest.length) break
            val breakAt = rest.lastIndexOf(' ', fit).takeIf { it > 0 } ?: fit
            lines += rest.substring(0, breakAt).trimEnd()
            rest = rest.substring(breakAt).trimStart()
        }
        if (rest.isNotEmpty()) lines += ellipsize(rest, maxWidth, target)
        return lines
    }

    // Home's pane: what it shows and the covers it needs, kept in step with the focused row.
    private var homePane: HomePane? = null
    private val paneArtworks = HashMap<String, Bitmap?>()
    private var paneArtworkGeneration = 0L

    private fun paneArtwork(trackId: Long?, path: String?): Bitmap? {
        val key = paneArtworkKey(trackId, path) ?: return null
        return if (key == "t${state.playback.currentTrackId}") paneArtworks[key] ?: artwork else paneArtworks[key]
    }

    private fun paneArtworkKey(trackId: Long?, path: String?): String? = when {
        trackId != null -> "t$trackId"
        path != null -> "p$path"
        else -> null
    }

    private fun updateHomePane() {
        val pane = if (isSplitHome()) HomePane.of(state, rows.getOrNull(state.selectedIndex)) else null
        homePane = pane
        val wanted = LinkedHashMap<String, Pair<String, Long>>()
        fun want(trackId: Long?, path: String?) {
            val key = paneArtworkKey(trackId, path) ?: return
            val track = trackId?.let(state.library.byId::get)
            val source = track?.absolutePath ?: path ?: return
            wanted[key] = source to (track?.modifiedAt ?: 0L)
        }
        when (pane) {
            is HomePane.Continue -> want(pane.first.artTrackId, pane.first.artPath)
            is HomePane.Collection -> pane.artTrackIds.take(4).forEach { want(it, null) }
            else -> Unit
        }
        if (state.currentScreen.isCardMenu()) cardHeader()?.trackId?.let { want(it, null) }
        paneArtworks.keys.retainAll(wanted.keys)
        val generation = ++paneArtworkGeneration
        wanted.forEach { (key, source) ->
            if (paneArtworks.containsKey(key)) return@forEach
            paneArtworks[key] = null
            artworkLoader.load(source.first, source.second, state.library.tracksRevision, PANE_ARTWORK_SIZE_PX) { _, bitmap ->
                if (generation != paneArtworkGeneration && !paneArtworks.containsKey(key)) return@load
                if (!paneArtworks.containsKey(key)) return@load
                paneArtworks[key] = bitmap
                if (isSplitHome()) invalidate(rowAreaRight().toInt(), headerHeight.toInt(), width, height)
                else if (state.currentScreen.isCardMenu()) invalidate()
            }
        }
    }

    private fun drawNowPlayingTall(canvas: Canvas) {
        val centerX = width * .5f
        val artSize = (width * .46f).coerceIn(124f * density, 156f * density)
        val artTop = headerHeight + 10f * density
        drawArtwork(canvas, centerX - artSize * .5f, artTop, artSize)

        val titleTop = artTop + artSize + 9f * density
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.textSize = Y2UiTheme.NOW_TITLE_SP * density
        boldPaint.color = palette.primaryText
        if (isNowPlayingScrollTarget(SelectedTextScroller.TARGET_NOW_TITLE, cachedNowTitleFull)) {
            drawScrollingText(canvas, cachedNowTitleFull, 12f * density, width - 12f * density,
                titleTop + 19f * density, boldPaint,
                nowPlayingTextScroller(SelectedTextScroller.TARGET_NOW_TITLE).offsetPx)
        } else canvas.drawText(cachedNowTitle, centerX, titleTop + 19f * density, boldPaint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = Y2UiTheme.NOW_ARTIST_SP * density
        paint.color = palette.secondaryText
        if (isNowPlayingScrollTarget(SelectedTextScroller.TARGET_NOW_ARTIST, cachedNowArtistFull)) {
            drawScrollingText(canvas, cachedNowArtistFull, 12f * density, width - 12f * density,
                titleTop + 40f * density, paint,
                nowPlayingTextScroller(SelectedTextScroller.TARGET_NOW_ARTIST).offsetPx)
        } else canvas.drawText(cachedNowArtist, centerX, titleTop + 40f * density, paint)
        paint.textSize = Y2UiTheme.NOW_ALBUM_SP * density
        paint.color = palette.mutedText
        if (isNowPlayingScrollTarget(SelectedTextScroller.TARGET_NOW_ALBUM, cachedNowAlbumFull)) {
            drawScrollingText(canvas, cachedNowAlbumFull, 12f * density, width - 12f * density,
                titleTop + 58f * density, paint,
                nowPlayingTextScroller(SelectedTextScroller.TARGET_NOW_ALBUM).offsetPx)
        } else canvas.drawText(cachedNowAlbum, centerX, titleTop + 58f * density, paint)
        var metadataY = titleTop + 58f * density
        paint.textSize = Y2UiTheme.META_SP * density
        if (cachedNowGenre.isNotEmpty()) {
            metadataY += 17f * density
            paint.color = palette.mutedText
            canvas.drawText(cachedNowGenre, centerX, metadataY, paint)
        }
        if (cachedNowSecondary.isNotEmpty()) {
            metadataY += 17f * density
            paint.color = if (cachedNowSecondaryWarning) palette.warning else palette.mutedText
            canvas.drawText(cachedNowSecondary, centerX, metadataY, paint)
        }

        val barTop = (titleTop + 88f * density).coerceAtLeast(metadataY + 13f * density)
        drawProgressBar(canvas, 20f * density, width - 20f * density, barTop)
        paint.style = Paint.Style.FILL
        paint.textSize = Y2UiTheme.META_SP * density
        paint.color = palette.secondaryText
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(cachedElapsed, 20f * density, barTop + 21f * density, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(cachedDuration, width - 20f * density, barTop + 21f * density, paint)

        val controlY = barTop + 47f * density
        drawPlaybackControls(canvas, 20f * density, width - 20f * density, controlY)
        paint.textAlign = Paint.Align.LEFT
        boldPaint.textAlign = Paint.Align.LEFT
    }

    /**
     * Every string the FM screen shows is built here rather than in onDraw, so
     * a frame costs no formatting and no allocation.
     */
    private fun updateFmCache() {
        val fm = state.fm
        cachedFmFrequency = fm.frequencyLabel
        cachedFmFraction = fm.signalFraction
        cachedFmStatus = when {
            fm.message != null -> fm.message
            !fm.available -> "FM unavailable"
            fm.seeking -> "Seeking"
            !fm.powered -> "Off"
            fm.stereo -> "Stereo"
            else -> "Mono"
        }
        cachedFmSignal = if (fm.powered && fm.rssi != FmState.RSSI_UNKNOWN) "${fm.rssi} dBm" else ""
        cachedFmHint = if (fm.powered) "Wheel tunes · Skip seeks · Select stops"
        else "Select turns the radio on"
    }

    private fun drawFmRadio(canvas: Canvas) {
        val centerX = width * .5f
        val top = headerHeight + 26f * density

        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.textSize = 56f * density
        boldPaint.color = if (state.fm.powered) palette.primaryText else palette.mutedText
        canvas.drawText(cachedFmFrequency, centerX, top + 44f * density, boldPaint)

        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = Y2UiTheme.NOW_ARTIST_SP * density
        paint.color = palette.secondaryText
        canvas.drawText("MHz", centerX, top + 66f * density, paint)

        paint.textSize = Y2UiTheme.NOW_ALBUM_SP * density
        paint.color = if (state.fm.available) palette.secondaryText else palette.warning
        canvas.drawText(cachedFmStatus, centerX, top + 90f * density, paint)

        // Signal meter, drawn with the same geometry as the playback progress bar.
        val left = 20f * density
        val right = width - 20f * density
        val barTop = top + 104f * density
        val barHeight = 6f * density
        paint.color = palette.divider
        canvas.drawRect(left, barTop, right, barTop + barHeight, paint)
        if (cachedFmFraction > 0f) {
            paint.color = palette.accent
            canvas.drawRect(left, barTop, left + (right - left) * cachedFmFraction, barTop + barHeight, paint)
        }

        paint.textSize = Y2UiTheme.META_SP * density
        paint.color = palette.mutedText
        canvas.drawText(cachedFmSignal, centerX, barTop + 24f * density, paint)
        canvas.drawText(cachedFmHint, centerX, barTop + 44f * density, paint)

        paint.textAlign = Paint.Align.LEFT
        boldPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawArtwork(
        canvas: Canvas,
        left: Float,
        top: Float,
        size: Float,
        source: Bitmap? = artwork,
        fallbackIcon: Y2Icon = Y2Icon.SONG
    ) = drawCover(canvas, left, top, size, source, Y2UiTheme.PANEL_RADIUS_DP * density, fallbackIcon)

    private fun drawStateBadges(canvas: Canvas, startX: Float, centerY: Float, step: Float) {
        var x = startX
        if (state.playback.sleepTimerMode != SleepTimerMode.OFF) {
            iconPainter.draw(canvas, Y2Icon.TIMER, x, centerY, 14f * density, palette.accent)
            x += step
            // Minutes left beside the moon, so the timer can be read without opening it.
            SleepTimerPresentation.remainingMs(state.playback, SystemClock.elapsedRealtime())
                ?.takeIf { state.playback.sleepTimerMode == SleepTimerMode.MINUTES }
                ?.let { remaining ->
                    val label = SleepTimerPresentation.compact(remaining)
                    paint.style = Paint.Style.FILL
                    paint.textAlign = Paint.Align.LEFT
                    paint.textSize = Y2UiTheme.BADGE_SP * density
                    paint.color = palette.accent
                    canvas.drawText(label, x - step * .45f, centerY - (paint.ascent() + paint.descent()) * .5f, paint)
                    x += paint.measureText(label) + step * .2f
                }
        }
        if (cachedNowFavorite) {
            iconPainter.draw(canvas, Y2Icon.FAVORITE_FILLED, x, centerY, 14f * density, palette.accent)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawProgressBar(canvas: Canvas, left: Float, right: Float, top: Float) {
        val barHeight = Y2UiTheme.PROGRESS_HEIGHT_DP * density
        paint.style = Paint.Style.FILL
        reusableRectF.set(left, top, right, top + barHeight)
        paint.color = palette.divider
        canvas.drawRoundRect(reusableRectF, barHeight * .5f, barHeight * .5f, paint)
        val progress = Y2UiLogic.progressFraction(state.playback.positionMs, state.playback.durationMs)
        val progressRight = left + (right - left) * progress
        reusableRectF.set(left, top, progressRight.coerceAtLeast(left), top + barHeight)
        paint.color = palette.accent
        canvas.drawRoundRect(reusableRectF, barHeight * .5f, barHeight * .5f, paint)
        if (progress > 0f) {
            val knobRadius = 3f * density
            val knobX = progressRight.coerceIn(left + knobRadius, right - knobRadius)
            canvas.drawCircle(knobX, top + barHeight * .5f, knobRadius, paint)
        }
    }

    private fun drawScanProgressBar(canvas: Canvas, left: Float, right: Float, top: Float) {
        val barHeight = Y2UiTheme.PROGRESS_HEIGHT_DP * density
        paint.style = Paint.Style.FILL
        reusableRectF.set(left, top, right, top + barHeight)
        paint.color = palette.divider
        canvas.drawRoundRect(reusableRectF, barHeight * .5f, barHeight * .5f, paint)

        val width = right - left
        val fraction = cachedScanProgressFraction
        val fillLeft: Float
        val fillRight: Float
        if (fraction != null) {
            fillLeft = left
            fillRight = left + width * fraction
        } else {
            val segmentWidth = width * .28f
            fillLeft = left + (width - segmentWidth) * cachedScanProgressPhase
            fillRight = fillLeft + segmentWidth
        }
        reusableRectF.set(fillLeft, top, fillRight.coerceAtLeast(fillLeft), top + barHeight)
        paint.color = palette.accent
        canvas.drawRoundRect(reusableRectF, barHeight * .5f, barHeight * .5f, paint)
    }

    private fun drawStatusMessage(canvas: Canvas) {
        if (cachedMessageSource == null) return
        val warning = state.playback.pauseReason == PauseReason.OUTPUT_DISCONNECTED || state.playback.errorMessage != null
        if (cachedMessageTitle == "Status") {
            // A short confirmation: one dark pill near the bottom, like a HUD.
            boldPaint.textAlign = Paint.Align.CENTER
            boldPaint.textSize = Y2UiTheme.BODY_SP * density
            val text = ellipsize(cachedMessageBody, width - 64f * density, boldPaint)
            val pillWidth = boldPaint.measureText(text) + 36f * density
            val bottom = height - 14f * density
            reusableRectF.set((width - pillWidth) * .5f, bottom - 40f * density, (width + pillWidth) * .5f, bottom)
            paint.style = Paint.Style.FILL
            paint.color = colorAtAlpha(MESSAGE_HUD, .94f)
            canvas.drawRoundRect(reusableRectF, 20f * density, 20f * density, paint)
            boldPaint.color = 0xFFFFFFFF.toInt()
            canvas.drawText(text, width * .5f, bottom - 14.5f * density, boldPaint)
            boldPaint.textAlign = Paint.Align.LEFT
            return
        }
        val bottom = height - 12f * density
        val top = bottom - 58f * density
        reusableRectF.set(12f * density, top, width - 12f * density, bottom)
        paint.style = Paint.Style.FILL
        paint.color = colorAtAlpha(MESSAGE_HUD, .94f)
        canvas.drawRoundRect(reusableRectF, 14f * density, 14f * density, paint)
        iconPainter.draw(canvas, if (state.playback.pauseReason == PauseReason.OUTPUT_DISCONNECTED) Y2Icon.DISCONNECTED else Y2Icon.INFO,
            34f * density, top + 29f * density, 19f * density, if (warning) MESSAGE_WARNING else 0xFFFFFFFF.toInt())
        boldPaint.textAlign = Paint.Align.LEFT
        boldPaint.textSize = Y2UiTheme.BODY_SP * density
        boldPaint.color = 0xFFFFFFFF.toInt()
        canvas.drawText(cachedMessageTitle, 54f * density, top + 25f * density, boldPaint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = Y2UiTheme.META_SP * density
        paint.color = 0xFFD1D1D6.toInt()
        canvas.drawText(cachedMessageBody, 54f * density, top + 44f * density, paint)
    }

    private fun drawAlphabetScrubOverlay(canvas: Canvas) {
        val label = state.alphabetScrub?.label ?: return
        val centerX = rowAreaRight() * .5f
        val centerY = (rowAreaTop() + rowAreaBottom()) * .5f
        val halfSize = 49f * density
        reusableRectF.set(
            centerX - halfSize,
            centerY - halfSize,
            centerX + halfSize,
            centerY + halfSize
        )
        paint.style = Paint.Style.FILL
        paint.color = palette.surfaceRaised
        canvas.drawRoundRect(reusableRectF, 16f * density, 16f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = palette.accent
        canvas.drawRoundRect(reusableRectF, 16f * density, 16f * density, paint)
        paint.style = Paint.Style.FILL

        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.textSize = 54f * density
        boldPaint.color = palette.primaryText
        val baseline = centerY - (boldPaint.ascent() + boldPaint.descent()) * .5f
        canvas.drawText(label, centerX, baseline, boldPaint)
        boldPaint.textAlign = Paint.Align.LEFT
    }

    private fun updatePresentationCache() {
        updateFmCache()
        cachedRoute = Y2UiLogic.routePresentation(state.playback.outputRoute)
        cachedHeaderRouteIcon = when (cachedRoute.icon) {
            RouteIcon.BLUETOOTH -> RouteIcon.BLUETOOTH
            RouteIcon.DISCONNECTED -> RouteIcon.DISCONNECTED
            else -> null
        }
        cachedBatteryPercent = state.device.batteryPercent
        cachedBatteryText = state.device.batteryPercent?.let { "$it%" } ?: ""
        val track = state.playback.currentTrackId?.let(state.library.byId::get)
        cachedNowFavorite = track?.favorite == true
        val nowWidth = nowPlayingTextWidth()
        boldPaint.textSize = Y2UiTheme.NOW_TITLE_SP * density
        cachedNowTitleFull = track?.title ?: "Nothing playing"
        cachedNowTitleWidth = boldPaint.measureText(cachedNowTitleFull)
        cachedNowTitle = ellipsize(cachedNowTitleFull, nowWidth, boldPaint)
        val book = track?.audiobookFolderKey?.let { ScreenContent.audiobookEntry(state, it) }
        paint.textSize = Y2UiTheme.NOW_ARTIST_SP * density
        val artistText = when {
            track == null -> "Select a track"
            book != null -> Y2UiLogic.audiobookArtistLine(book.name, track.artist)
            else -> track.displayArtist
        }
        cachedNowArtistFull = artistText
        cachedNowArtistWidth = paint.measureText(cachedNowArtistFull)
        cachedNowArtist = ellipsize(cachedNowArtistFull, nowWidth, paint)
        paint.textSize = Y2UiTheme.NOW_ALBUM_SP * density
        val albumText = when {
            track == null -> ""
            book != null -> Y2UiLogic.audiobookChapterLine(
                book.chapterIds.indexOf(track.id).takeIf { it >= 0 }?.plus(1),
                book.chapterCount
            )
            else -> Y2UiLogic.albumLine(
                album = track.displayAlbum,
                year = track.year,
                includeYear = state.preferences.extraTrackInfo
            )
        }
        cachedNowAlbumFull = albumText
        cachedNowAlbumWidth = paint.measureText(cachedNowAlbumFull)
        cachedNowAlbum = ellipsize(cachedNowAlbumFull, nowWidth, paint)
        paint.textSize = Y2UiTheme.META_SP * density
        cachedNowGenre = if (track != null && state.preferences.extraTrackInfo) {
            ellipsize(Y2UiLogic.genreLine(track.genre), nowWidth, paint)
        } else ""
        cachedTechnicalLine = if (track == null) "" else Y2UiLogic.technicalLine(
            AudioCodecLabels.label(track.codec, track.extension),
            track.sampleRate,
            track.bitDepth,
            if (state.preferences.extraTrackInfo) track.bitrate else null
        )
        cachedDacLabel = Y2UiLogic.dacModeLabel(
            state.preferences.audioQualityMode,
            state.playback.dac.detected,
            state.playback.dac.hiFiRequestAccepted
        )
        val outputLost = cachedRoute.icon == RouteIcon.DISCONNECTED
        val dacWarning = cachedDacLabel.contains("unavailable") || cachedDacLabel.contains("fallback")
        cachedNowSecondaryWarning = outputLost || dacWarning
        val secondary = when {
            outputLost -> cachedRoute.label
            dacWarning -> cachedDacLabel
            else -> cachedTechnicalLine
        }
        paint.textSize = Y2UiTheme.META_SP * density
        cachedNowSecondary = ellipsize(secondary, nowWidth, paint)
        cachedStatusTag = when (state.playback.status) {
            PlaybackStatus.PAUSED -> "Paused"
            PlaybackStatus.PREPARING -> "Loading"
            PlaybackStatus.ERROR -> "Can't play this track"
            else -> ""
        }
        val paneTextWidth = (fallbackWidth() * .45f - 40f * density).coerceAtLeast(40f * density)
        boldPaint.textSize = Y2UiTheme.MINI_TITLE_SP * density
        cachedPaneTitle = if (track == null) "" else ellipsize(track.title, paneTextWidth, boldPaint)
        paint.textSize = Y2UiTheme.NAV_LABEL_SP * density
        cachedPaneArtist = if (track == null) "" else ellipsize(track.displayArtist, paneTextWidth, paint)
        val count = state.library.musicTracks.size
        val processed = state.library.scanProgress.processedFiles.coerceAtLeast(0)
        cachedScanProgressFraction = Y2UiLogic.scanProgressFraction(processed, count)
        cachedScanProgressPhase =
            (processed % SCAN_PROGRESS_PHASE_STEPS).toFloat() / SCAN_PROGRESS_PHASE_STEPS
        val stats = if (state.library.isScanning) {
            if (cachedScanProgressFraction != null) {
                String.format(java.util.Locale.US, "%,d / %,d", processed, count)
            } else {
                String.format(java.util.Locale.US, "%,d processed", processed)
            }
        } else when (count) {
            0 -> "No music yet"
            1 -> "1 song"
            else -> String.format(java.util.Locale.US, "%,d songs", count)
        }
        boldPaint.textSize = Y2UiTheme.BODY_SP * density
        cachedHomeStats = ellipsize(stats, paneTextWidth, boldPaint)
        val homeHint = when {
            state.library.isScanning -> "Scanning your music…"
            count == 0 -> "Add music, then rescan"
            else -> "Shuffle All to begin"
        }
        paint.textSize = Y2UiTheme.NAV_LABEL_SP * density
        cachedHomeHint = ellipsize(homeHint, paneTextWidth, paint)
        cachedElapsed = TimeFormat.duration(state.playback.positionMs)
        cachedDuration = TimeFormat.duration(state.playback.durationMs)
        cachedRemaining = remainingLabel(state.playback.positionMs, state.playback.durationMs)
        nowPlayingPane = HomePane.nowPlaying(state)
        val columnWidth = fallbackWidth() - (16f + NOW_ART_DP + 16f + 16f) * density
        boldPaint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
        val series = track?.audiobookFolderKey?.let { ScreenContent.audiobookEntry(state, it) }
        val sourceText = when {
            track == null -> ""
            series != null && series.chapterCount > 1 -> {
                val number = series.chapterIds.indexOf(track.id) + 1
                if (number > 0) "${series.name} · Episode $number of ${series.chapterCount}" else series.name
            }
            else -> state.playSource.orEmpty()
        }
        cachedNowSource = ellipsize(sourceText, columnWidth, boldPaint)
        boldPaint.textSize = Y2UiTheme.NOW_TITLE_SP * density
        cachedNowTitleLines = wrap(track?.title ?: "Nothing playing", columnWidth, boldPaint, 2)
        paint.textSize = Y2UiTheme.NOW_ARTIST_SP * density
        // A talk's series is already named above the title, so only the speaker follows it.
        val nowArtistText = when {
            track == null -> "Choose something to play"
            series != null && series.chapterCount > 1 -> track.artist?.trim()?.takeIf { it.isNotEmpty() } ?: series.name
            else -> HomePane.artistLine(state, track)
        }
        cachedNowArtistLines = wrap(nowArtistText, columnWidth, paint, if (cachedNowTitleLines.size > 1) 1 else 2)
        // Room for the battery and route icon, plus the clock when it is shown.
        val availableWidth = fallbackWidth() - (if (state.preferences.showStatusClock) 190f else 130f) * density
        boldPaint.textSize = Y2UiTheme.SCREEN_TITLE_SP * density
        val headerTitle = when (state.currentScreen) {
            Screen.NowPlayingOptions -> "Now Playing"
            else -> ScreenContent.title(state)
        }
        cachedHeaderTitle = ellipsize(headerTitle, availableWidth, boldPaint)
        paint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
        cachedScreenNote = ScreenContent.note(state)?.let { ellipsize(it, fallbackWidth() - 32f * density, paint) }
        updateDetailCache()
        updateMessageCache()
    }

    private fun updateDetailCache() {
        val screen = state.currentScreen
        if (screen !is Screen.AlbumSongs && screen !is Screen.ArtistSongs) {
            cachedDetailTitle = ""
            cachedDetailTitle2 = ""
            cachedDetailSubtitle = ""
            return
        }
        val title = when (screen) {
            is Screen.AlbumSongs -> screen.album
            is Screen.ArtistSongs -> screen.artist
            else -> ""
        }
        val maxWidth = (fallbackWidth() - 96f * density).coerceAtLeast(80f * density)
        boldPaint.textSize = Y2UiTheme.SECTION_TITLE_SP * density
        splitDetailTitle(title, maxWidth)
        var trackCount = 0
        var firstArtist: String? = null
        var multipleArtists = false
        val albums = if (screen is Screen.ArtistSongs) HashSet<String>() else null
        rows.forEach { row ->
            val track = (row as? ScreenRow.TrackRow)?.track ?: return@forEach
            trackCount += 1
            if (firstArtist == null) firstArtist = track.displayArtist
            else if (firstArtist != track.displayArtist) multipleArtists = true
            albums?.add(track.displayAlbum)
        }
        val detail = when (screen) {
            is Screen.AlbumSongs -> {
                val artist = if (multipleArtists) "Various artists" else firstArtist ?: "Unknown artist"
                "$artist · ${ScreenContent.trackCountLabel(trackCount)}"
            }
            is Screen.ArtistSongs -> {
                val albumCount = albums?.size ?: 0
                val albumLabel = if (albumCount == 1) "1 album" else "$albumCount albums"
                "$albumLabel · ${ScreenContent.trackCountLabel(trackCount)}"
            }
            else -> ""
        }
        paint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
        cachedDetailSubtitle = ellipsize(detail, maxWidth, paint)
    }

    private fun splitDetailTitle(title: String, maxWidth: Float) {
        if (title.isEmpty() || boldPaint.measureText(title) <= maxWidth) {
            cachedDetailTitle = title
            cachedDetailTitle2 = ""
            return
        }
        val fitEnd = Y2UiLogic.truncationEnd(title.length, maxWidth, 0f) { end -> boldPaint.measureText(title, 0, end) }
        val safeEnd = Y2UiLogic.safeTextBoundary(title, fitEnd).coerceAtLeast(1)
        val breakAt = title.lastIndexOf(' ', safeEnd - 1).takeIf { it > 0 } ?: safeEnd
        cachedDetailTitle = title.substring(0, breakAt).trimEnd()
        cachedDetailTitle2 = ellipsize(title.substring(breakAt).trimStart(), maxWidth, boldPaint)
    }

    private fun remainingLabel(positionMs: Long, durationMs: Long): String =
        "−" + TimeFormat.duration((durationMs - positionMs).coerceAtLeast(0L))

    private fun fallbackWidth(): Float = (width.takeIf { it > 0 } ?: (Y2UiTheme.TARGET_WIDTH_PX * density).toInt()).toFloat()

    private fun fallbackHeight(): Float = (height.takeIf { it > 0 } ?: (Y2UiTheme.TARGET_HEIGHT_PX * density).toInt()).toFloat()

    private fun nowPlayingTextWidth(): Float {
        val w = fallbackWidth()
        val h = fallbackHeight()
        return if (w > h) {
            val contentHeight = h - headerHeight - footerHeight
            val margin = 14f * density
            val artSize = (contentHeight - 2 * margin).coerceAtMost(232f * density)
            (w - margin * 2 - artSize - 18f * density).coerceAtLeast(60f * density)
        } else {
            w - 24f * density
        }
    }

    private fun updateMessageCache() {
        val message = state.transientMessage ?: state.library.errorMessage ?: state.playback.errorMessage
            ?: state.bluetooth.lastError ?: state.diagnostics.lastError
        cachedMessageSource = message
        if (message == null) {
            cachedMessageTitle = ""
            cachedMessageBody = ""
            return
        }
        when {
            state.playback.pauseReason == PauseReason.OUTPUT_DISCONNECTED -> {
                cachedMessageTitle = "Playback paused for safety"
                cachedMessageBody = "Private output disconnected; press Play to resume"
            }
            state.playback.errorMessage != null -> {
                cachedMessageTitle = "Playback issue"
                cachedMessageBody = friendlyMessage(message, "Choose another track or try again")
            }
            state.library.errorMessage != null -> {
                cachedMessageTitle = "Library needs attention"
                cachedMessageBody = friendlyMessage(message, "Check Storage and rescan when ready")
            }
            state.bluetooth.lastError != null -> {
                cachedMessageTitle = "Bluetooth issue"
                cachedMessageBody = friendlyMessage(message, "Check the device and try again")
            }
            else -> {
                cachedMessageTitle = "Status"
                cachedMessageBody = message
            }
        }
        paint.textSize = Y2UiTheme.META_SP * density
        cachedMessageBody = ellipsize(cachedMessageBody, fallbackWidth() - 74f * density, paint)
    }

    private fun friendlyMessage(message: String, fallback: String): String {
        if (message.length <= 72 && !message.contains("Exception") && !message.contains("java.")) return message
        return fallback
    }

    private fun requestArtwork() {
        val track = state.playback.currentTrackId?.let(state.library.byId::get)
        val path = track?.absolutePath
        val identity = track?.let { Triple(it.id, it.modifiedAt, state.library.tracksRevision) }
        if (path != artworkPath || identity != artworkIdentity) {
            artworkPath = path
            artworkIdentity = identity
            artwork = null
            if (track != null) {
                artworkLoader.load(
                    track.absolutePath,
                    track.modifiedAt,
                    state.library.tracksRevision,
                    SHARED_ARTWORK_SIZE_PX
                ) { loadedPath, bitmap ->
                    if (loadedPath == artworkPath && identity == artworkIdentity) {
                        artwork = bitmap
                        invalidate()
                    }
                }
            }
        }
        requestDetailArtwork()
    }

    private fun requestDetailArtwork() {
        val detailPath = if (state.currentScreen is Screen.AlbumSongs) {
            (rows.firstOrNull { it is ScreenRow.TrackRow } as? ScreenRow.TrackRow)?.track?.absolutePath
        } else null
        if (detailPath == detailArtworkPath) return
        detailArtworkPath = detailPath
        detailArtwork = null
        if (detailPath == null) return
        artworkLoader.load(detailPath, SHARED_ARTWORK_SIZE_PX) { loadedPath, bitmap ->
            if (loadedPath == detailArtworkPath) {
                detailArtwork = bitmap
                invalidate(0, headerHeight.toInt(), width, rowAreaTop().toInt())
            }
        }
    }

    private fun ensureSelectionVisible(): Boolean {
        if (rows.isEmpty()) {
            val changed = visibleStart != 0
            if (changed) invalidateRowCache()
            visibleStart = 0
            return changed
        }
        val nextStart = Y2UiLogic.firstVisibleRow(rows.size, state.selectedIndex, visibleStart, visibleRowCount())
        if (nextStart != visibleStart) {
            visibleStart = nextStart
            invalidateRowCache()
            return true
        }
        return false
    }

    private fun requestVisibleRowArtworks() {
        if (!attached || visibility != VISIBLE || windowVisibility != VISIBLE || width <= 0 || height <= 0) return
        val requests = VisibleRowArtworkPlanner.requests(state, rows, visibleStart, visibleRowCount())
        val generation = ++visibleRowArtworkGeneration
        val retained = HashMap<Int, VisibleRowArtworkSlot>(requests.size)
        val pending = ArrayList<VisibleRowArtworkLoad>(requests.size)
        requests.forEach { request ->
            val key = VisibleRowArtworkKey(
                trackId = request.trackId,
                path = request.path,
                modifiedAt = request.modifiedAt,
                libraryRevision = state.library.tracksRevision
            )
            val existing = visibleRowArtworks[request.rowIndex]
            if (existing?.key == key) {
                retained[request.rowIndex] = existing
                if (!existing.resolved) pending += VisibleRowArtworkLoad(request.rowIndex, key)
            } else {
                retained[request.rowIndex] = VisibleRowArtworkSlot(key)
                pending += VisibleRowArtworkLoad(request.rowIndex, key)
            }
        }
        visibleRowArtworks.clear()
        visibleRowArtworks.putAll(retained)
        loadNextVisibleRowArtwork(pending, 0, generation)
    }

    private fun loadNextVisibleRowArtwork(
        pending: List<VisibleRowArtworkLoad>,
        position: Int,
        generation: Long
    ) {
        if (generation != visibleRowArtworkGeneration || position >= pending.size) return
        val request = pending[position]
        if (visibleRowArtworks[request.rowIndex]?.key != request.key || !isRowVisible(request.rowIndex)) {
            loadNextVisibleRowArtwork(pending, position + 1, generation)
            return
        }
        artworkLoader.load(
            request.key.path,
            request.key.modifiedAt,
            request.key.libraryRevision,
            ROW_ARTWORK_SIZE_PX
        ) { loadedPath, bitmap ->
            val slot = visibleRowArtworks[request.rowIndex]
            if (generation == visibleRowArtworkGeneration && loadedPath == request.key.path &&
                slot?.key == request.key && isRowVisible(request.rowIndex)
            ) {
                slot.bitmap = bitmap
                slot.resolved = true
                invalidateRowArtwork(request.rowIndex)
            }
            loadNextVisibleRowArtwork(pending, position + 1, generation)
        }
    }

    private fun isRowVisible(index: Int): Boolean =
        index >= visibleStart && index < (visibleStart + visibleRowCount()).coerceAtMost(rows.size)

    private fun invalidateRowArtwork(index: Int) {
        if (!isRowVisible(index)) return
        val top = rowAreaTop() + (index - visibleStart) * rowHeight
        invalidate(0, top.toInt(), (50f * density).toInt(), (top + rowHeight).toInt())
    }

    private fun cancelVisibleRowArtworkRequests() {
        visibleRowArtworkGeneration += 1
        visibleRowArtworks.clear()
    }

    private fun visibleRowCount(): Int {
        val availableHeight = (rowAreaBottom() - rowAreaTop()).coerceAtLeast(0f)
        return Y2UiLogic.visibleRowCount(availableHeight, rowHeight).coerceAtMost(MAX_VISIBLE_ROWS)
    }

    /** Chooses one row shape for the whole list: one line with values on the right, or two lines. */
    private fun updateListLayout() {
        val home = isSplitHome()
        listLeadingColumn = !home && rows.any { (it as? ScreenRow.Action)?.leading != null }
        listArtColumn = !home && !listLeadingColumn &&
            rows.any { it is ScreenRow.TrackRow || it.artworkTrackId != null || it.artworkPath != null }
        listTwoLine = !home && (listArtColumn || rows.any { row ->
            val subtitle = row.subtitle ?: return@any false
            subtitle.length > TRAILING_SUBTITLE_MAX_CHARS && !isChoiceMarker(subtitle)
        })
        rowHeight = when {
            home -> Y2UiTheme.HOME_ROW_HEIGHT_DP
            listTwoLine -> Y2UiTheme.TWO_LINE_ROW_HEIGHT_DP
            else -> Y2UiTheme.ROW_HEIGHT_DP
        } * density
    }

    private fun isChoiceMarker(subtitle: String): Boolean = subtitle in CHOICE_MARKERS

    private fun isSwitchWord(subtitle: String): Boolean =
        subtitle == "On" || subtitle == "Off" || subtitle.startsWith("On ·") || subtitle.startsWith("Off ·") ||
            subtitle == "Shown" || subtitle == "Hidden" || subtitle == "Yes" || subtitle == "No"

    private fun refreshVisibleRowCache() {
        val end = (visibleStart + visibleRowCount()).coerceAtMost(rows.size)
        if (cachedRowStart == visibleStart && cachedRowEnd == end) return
        cachedRowStart = visibleStart
        cachedRowEnd = end
        val home = isSplitHome()
        val textLeft = rowTextLeft()
        val surfaceRight = rowSurfaceRight()
        for (slot in cachedTitles.indices) {
            if (visibleStart + slot < end) {
                val row = rows[visibleStart + slot]
                val active = isActive(row)
                val navigation = trailingIconFor(row, active) == Y2Icon.CHEVRON
                val rawSubtitle = if (home) null else rowSubtitle(row)
                // A chosen option gets a tick; a switch already says On or Off in words.
                val choiceMarked = (active || rawSubtitle?.let(::isChoiceMarker) == true) && !navigation && row !is ScreenRow.TrackRow &&
                    (row as? ScreenRow.Action)?.progress == null && rawSubtitle?.let(::isSwitchWord) != true
                val subtitle = rawSubtitle?.takeUnless { choiceMarked && isChoiceMarker(it) }
                val progress = (row as? ScreenRow.Action)?.progress
                val trailingIcon = when {
                    progress != null -> null
                    row is ScreenRow.TrackRow -> if (active) Y2Icon.PLAYING else null
                    navigation && !home -> Y2Icon.CHEVRON
                    choiceMarked -> Y2Icon.CHECK
                    else -> null
                }
                val trailingText = if (!listTwoLine && subtitle != null) subtitle else null
                paint.textSize = Y2UiTheme.ROW_DETAIL_SP * density
                val trailingIconWidth = if (trailingIcon != null || progress != null) 28f * density else 0f
                val trailingTextWidth = trailingText?.let {
                    paint.measureText(it).coerceAtMost((surfaceRight - textLeft) * .48f) + 12f * density
                } ?: 0f
                val maxWidth = (surfaceRight - 10f * density - trailingIconWidth - trailingTextWidth - textLeft)
                    .coerceAtLeast(36f * density)
                val titlePaint = boldOrRegular(listTwoLine)
                titlePaint.textSize = (if (listTwoLine) Y2UiTheme.TWO_LINE_TITLE_SP else Y2UiTheme.ROW_TITLE_SP) * density
                cachedTitleWidths[slot] = titlePaint.measureText(row.title)
                cachedTitleAvailableWidths[slot] = maxWidth
                cachedTitles[slot] = ellipsize(row.title, maxWidth, titlePaint)
                paint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
                cachedSubtitles[slot] = subtitle?.let {
                    ellipsize(it, surfaceRight - 10f * density - trailingIconWidth - textLeft, paint)
                }
                paint.textSize = Y2UiTheme.ROW_DETAIL_SP * density
                cachedTrailingText[slot] = trailingText?.let { ellipsize(it, (surfaceRight - textLeft) * .48f, paint) }
                cachedTrailingAccent[slot] = trailingText != null && choiceMarked
                cachedIcons[slot] = iconFor(row, active)
                cachedLeadingIcons[slot] = false
                cachedTrailingIcons[slot] = trailingIcon
                cachedProgress[slot] = progress
                cachedLeading[slot] = (row as? ScreenRow.Action)?.leading
                cachedTrackNumbers[slot] = if (state.currentScreen is Screen.AlbumSongs && row is ScreenRow.TrackRow) {
                    (row.track.trackNumber ?: (visibleStart + slot)).takeIf { it > 0 }?.toString()
                } else null
                cachedActive[slot] = active
                cachedUnavailable[slot] = isUnavailable(row)
            } else {
                cachedTitles[slot] = null
                cachedSubtitles[slot] = null
                cachedIcons[slot] = Y2Icon.INFO
                cachedTrailingText[slot] = null
                cachedTrailingAccent[slot] = false
                cachedLeadingIcons[slot] = false
                cachedTrailingIcons[slot] = null
                cachedProgress[slot] = null
                cachedLeading[slot] = null
                cachedTrackNumbers[slot] = null
                cachedTitleWidths[slot] = 0f
                cachedTitleAvailableWidths[slot] = 0f
                cachedActive[slot] = false
                cachedUnavailable[slot] = false
            }
        }
    }

    private fun invalidateRowCache() {
        cachedRowStart = -1
        cachedRowEnd = -1
    }

    private fun iconFor(row: ScreenRow, active: Boolean): Y2Icon =
        Y2RowIcons.forRow(row, state.currentScreen, state.playback.currentTrackId, active)

    private fun rowSubtitle(row: ScreenRow): String? = when {
        row !is ScreenRow.TrackRow -> row.subtitle
        state.currentScreen == Screen.Favorites -> row.track.displayArtist
        state.currentScreen is Screen.AlbumSongs -> null
        state.currentScreen is Screen.ArtistSongs -> row.track.displayAlbum
        (state.currentScreen as? Screen.FacetTracks)?.artist != null -> row.track.displayAlbum
        else -> row.subtitle
    }

    private fun trailingIconFor(row: ScreenRow, active: Boolean): Y2Icon? {
        val action = row as? ScreenRow.Action ?: return null
        val key = action.key
        // A row that opens a screen shows where it goes. CHECK is reserved for the
        // selected value of a setting, so navigation wins over the active mark.
        if (key in NAVIGATION_KEYS || key.startsWith("storage:") || key.startsWith("playlist:") ||
            key.startsWith("audiobook:") || key.startsWith("audiobook_chapters:") ||
            key.startsWith(ScreenContent.VIDEO_FOLDER_KEY) || key.startsWith(ScreenContent.VIDEO_VOLUME_KEY) ||
            key.startsWith("track_playlist") || key.startsWith("track_browse") || key.startsWith("track_details") ||
            key.startsWith("track_album") || key.startsWith("track_artist") || key.startsWith("np_playlist") ||
            key.startsWith("np_track_options")
        ) return Y2Icon.CHEVRON
        return if (active) Y2Icon.CHECK else null
    }

    private fun isActive(row: ScreenRow): Boolean = Y2RowState.isActive(row, state)

    private fun isUnavailable(row: ScreenRow): Boolean {
        if (row is ScreenRow.TrackRow) return !row.track.available
        val subtitle = row.subtitle ?: return false
        return subtitle.contains("unavailable", ignoreCase = true) ||
            subtitle.contains("not mounted", ignoreCase = true) ||
            subtitle.contains("unsupported", ignoreCase = true)
    }

    private fun routeIcon(icon: RouteIcon): Y2Icon = when (icon) {
        RouteIcon.HEADPHONES -> Y2Icon.HEADPHONES
        RouteIcon.BLUETOOTH -> Y2Icon.BLUETOOTH
        RouteIcon.SPEAKER -> Y2Icon.SPEAKER
        RouteIcon.DISCONNECTED -> Y2Icon.DISCONNECTED
        RouteIcon.UNKNOWN -> Y2Icon.UNKNOWN
    }

    private fun ellipsize(text: String, maxWidth: Float, targetPaint: Paint): String {
        val safeWidth = maxWidth.coerceAtLeast(0f)
        if (text.isEmpty() || targetPaint.measureText(text) <= safeWidth) return text
        val suffix = "…"
        val suffixWidth = targetPaint.measureText(suffix)
        if (suffixWidth > safeWidth) return ""
        val measuredEnd = Y2UiLogic.truncationEnd(text.length, safeWidth, suffixWidth) { prefix ->
            targetPaint.measureText(text, 0, prefix)
        }
        val end = Y2UiLogic.safeTextBoundary(text, measuredEnd)
        return text.substring(0, end) + suffix
    }

    private fun updateContentDescription(value: AppState) {
        if (accessibilityManager?.isEnabled != true) return
        contentDescription = buildContentDescription(value)
    }

    private fun buildContentDescription(value: AppState): String {
        val selected = rows.getOrNull(value.selectedIndex)
        return buildString {
            append(ScreenContent.title(value))
            selected?.let {
                append(", selected ${it.title}")
                it.subtitle?.let { subtitle -> append(", $subtitle") }
            }
            if (value.currentScreen == Screen.NowPlaying) {
                append(", shuffle ${Y2UiLogic.shuffleStatusLabel(value.playback.shuffleEnabled)}")
                append(", repeat ${Y2UiLogic.repeatStatusLabel(value.playback.repeatMode)}")
            }
            append(", output ${cachedRoute.label}")
        }
    }

    companion object {
        private const val MAX_VISIBLE_ROWS = 12
        private const val SCAN_PROGRESS_PHASE_STEPS = 200
        private const val MARQUEE_SPEED_DP_PER_SECOND = 28f
        private const val MARQUEE_FRAME_MS = 100L
        private const val SLEEP_TIMER_REFRESH_MS = 1_000L
        private const val ROW_ARTWORK_SIZE_DP = 38f
        private const val TRAILING_SUBTITLE_MAX_CHARS = 24
        private const val HOME_LIST_WIDTH_DP = 252f
        private val CHOICE_MARKERS = setOf("Active", "Selected", "Current")
        private const val ROW_ARTWORK_SIZE_PX = 64
        private const val NOW_ART_DP = 184f
        private const val VOLUME_HOLD_MS = 1_000L
        private const val MESSAGE_HUD = 0xFF1C1C1E.toInt()
        private const val MESSAGE_WARNING = 0xFFFF9F0A.toInt()
        private const val VOLUME_FADE_MS = 300L
        private const val RADIAL_OPEN_MS = 160f
        private const val RADIAL_CLOSE_MS = 120f
        private const val SEARCH_QUERY_HEIGHT_DP = 36f
        private const val SCREEN_NOTE_HEIGHT_DP = 30f
        // Four 29 dp key rows leave room for two 58 dp search results on the
        // physical 480x360 panel instead of reducing discovery to one row.
        private const val SEARCH_KEYBOARD_HEIGHT_DP = 116f

        private const val BATTERY_TEXT_REFERENCE = "100%"
        const val SHARED_ARTWORK_SIZE_PX = 256
        private const val PANE_ARTWORK_SIZE_PX = 128
        private val NAVIGATION_KEYS = setOf(
            "now_playing", "main_sleep_timer", "date_time", "theme", "bedtime", "continue", "clock_set_time", "clock_set_date", "clock_time_zone",
            "music", "audiobooks", "videos", "search", "songs", "albums", "artists", "playlists",
            "folders", "favorites", "recent", "audio", "settings", "output", "sort", "bluetooth",
            "sound_effects", "reset", "extra_track_info",
            "display", "controls", "storage", "system", "diagnostics", "android_settings", "about",
            "interface", "library_settings", "sound", "balance", "brightness", "timeout", "queue",
            "queue_management", "playback_transitions", "playback_seeking", "playback_volume",
            "playback_interruptions", "equalizer", "sound_dynamics", "output_information", "artist_all_songs"
        )
    }

    private data class VisibleRowArtworkKey(
        val trackId: Long,
        val path: String,
        val modifiedAt: Long,
        val libraryRevision: Long
    )

    private data class VisibleRowArtworkSlot(
        val key: VisibleRowArtworkKey,
        var bitmap: Bitmap? = null,
        var resolved: Boolean = false
    )

    private data class VisibleRowArtworkLoad(
        val rowIndex: Int,
        val key: VisibleRowArtworkKey
    )
}
