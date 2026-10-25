package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.PlaybackSnapshot
import com.schulzcode.y2player.core.model.SleepTimerMode

internal object SleepTimerPresentation {
    /** Preset lengths offered in the Sleep Timer list, in minutes. */
    val PRESET_MINUTES = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)

    fun remainingMs(playback: PlaybackSnapshot, nowElapsedMs: Long): Long? =
        playback.sleepTimerDeadlineElapsedMs
            ?.let { (it - nowElapsedMs).coerceAtLeast(0L) }
            ?: playback.sleepTimerRemainingMs?.coerceAtLeast(0L)

    /** "14:59", or "1:29:59" once an hour or more is left. */
    fun countdown(remainingMs: Long): String {
        val value = remainingMs.coerceAtLeast(0L)
        val seconds = value / 1_000L + if (value % 1_000L == 0L) 0L else 1L
        val ss = (seconds % 60L).toString().padStart(2, '0')
        if (seconds < 3_600L) return "${seconds / 60L}:$ss"
        return "${seconds / 3_600L}:${(seconds / 60L % 60L).toString().padStart(2, '0')}:$ss"
    }

    /** Whole minutes left, rounded up, for the Now Playing badge: "24 min". */
    fun compact(remainingMs: Long): String {
        val minutes = (remainingMs.coerceAtLeast(0L) + 59_999L) / 60_000L
        return "$minutes min"
    }

    fun label(mode: SleepTimerMode, remainingMs: Long?): String =
        if (mode == SleepTimerMode.MINUTES && remainingMs != null) countdown(remainingMs) else mode.label

    /** "30 minutes", "1 hour", "1 hour 30 minutes". */
    fun durationLabel(minutes: Int): String {
        val hours = minutes / 60
        val rest = minutes % 60
        val hourPart = when (hours) { 0 -> null; 1 -> "1 hour"; else -> "$hours hours" }
        val minutePart = when (rest) { 0 -> null; 1 -> "1 minute"; else -> "$rest minutes" }
        return listOfNotNull(hourPart, minutePart).joinToString(" ")
    }

    /** Screens whose rows carry a ticking countdown. */
    fun showsCountdown(screen: Screen): Boolean = screen == Screen.NowPlayingOptions || screen == Screen.MainMenu

    fun shouldRefresh(
        screen: Screen,
        mode: SleepTimerMode,
        deadlineElapsedMs: Long?,
        uiVisible: Boolean
    ): Boolean = uiVisible && showsCountdown(screen) &&
        mode == SleepTimerMode.MINUTES && deadlineElapsedMs != null
}
