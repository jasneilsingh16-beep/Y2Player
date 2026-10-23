package com.schulzcode.y2player.ui

import com.schulzcode.y2player.core.state.AppState
import com.schulzcode.y2player.core.state.Screen
import com.schulzcode.y2player.core.state.ScreenRow

/**
 * One row's artwork lookup. Library rows resolve from their track; rows outside
 * the library (video folders) carry a path whose folder holds cover.jpg or
 * poster.jpg, so the same loader and cache serve both.
 */
internal data class VisibleRowArtworkRequest(
    val rowIndex: Int,
    val trackId: Long,
    val path: String,
    val modifiedAt: Long
)

internal object VisibleRowArtworkPlanner {
    fun requests(
        state: AppState,
        rows: List<ScreenRow>,
        visibleStart: Int,
        visibleCount: Int
    ): List<VisibleRowArtworkRequest> {
        if (visibleCount <= 0 || rows.isEmpty()) return emptyList()
        val start = visibleStart.coerceIn(0, rows.size)
        val end = (start + visibleCount).coerceAtMost(rows.size)
        return buildList(end - start) {
            for (index in start until end) {
                val row = rows[index]
                row.artworkPath?.let { path ->
                    if (path.isNotBlank()) add(VisibleRowArtworkRequest(index, NO_TRACK, path, 0L))
                    continue
                }
                if (row is ScreenRow.TrackRow && state.currentScreen is Screen.AlbumSongs) continue
                val track = row.artworkTrackId?.let(state.library.byId::get) ?: continue
                if (track.available && track.absolutePath.isNotBlank()) {
                    add(VisibleRowArtworkRequest(index, track.id, track.absolutePath, track.modifiedAt))
                }
            }
        }
    }

    const val NO_TRACK = -1L
}
