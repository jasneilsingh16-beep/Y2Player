package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.AudiobookIdentity
import com.schulzcode.y2player.core.model.NaturalTextOrder
import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.util.TimeFormat

/**
 * Talks & Podcasts mirrors the card. Every folder that holds recordings is one
 * podcast; the folders above it (Sikhi Talks › Jvala Singh) open like folders.
 * Episodes before the one in progress count as played.
 */
internal object TalksContent {
    const val SERIES_KEY = "audiobook:"
    const val COLLECTION_KEY = "talk_collection:"
    const val RESUME_KEY = "talk_resume:"
    const val EPISODE_KEY = "talk_episode:"
    const val RESTART_KEY = "talk_restart:"
    const val CLEAR_KEY = "talk_clear:"
    const val CONTINUE_TALK_KEY = "continue_talk:"
    const val CONTINUE_VIDEO_KEY = "continue_video:"

    /** A folder of podcasts: everything below [path], however deep. */
    data class Folder(val path: String, val name: String, val podcasts: List<AudiobookEntry>) {
        val inProgressCount: Int get() = podcasts.count { it.inProgress }
    }

    /**
     * One folder's contents, or the top level. The top level opens straight into
     * the talks folder when there is only one ("Talks"), and keeps going while a
     * folder holds nothing but one other folder.
     */
    fun rows(state: AppState, folderPath: String? = null): List<ScreenRow> {
        val entries = ScreenContent.audiobookEntries(state)
        if (folderPath != null) return listing(state, entries, folderPath)
        val roots = entries.map { AudiobookIdentity.rootOf(it.folderKey) }
        // Podcasts found by their genre tag alone sit at the top, beside the folders.
        val loose = entries.indices.filter { roots[it] == null }.map { seriesRow(state, entries[it]) }
        val folders = roots.filterNotNull().distinct()
        if (folders.size != 1) {
            val rows = folders.map { root ->
                val podcasts = entries.filterIndexed { index, _ -> roots[index] == root }
                if (podcasts.size == 1) seriesRow(state, podcasts.single()) else folderRow(Folder(root, nameOf(root), podcasts))
            }
            return sorted(rows + loose)
        }
        var rows = listing(state, entries, folders.single())
        while (rows.size == 1 && rows.single().key.startsWith(COLLECTION_KEY)) {
            rows = listing(state, entries, rows.single().key.removePrefix(COLLECTION_KEY))
        }
        return sorted(rows + loose)
    }

    private fun listing(state: AppState, entries: List<AudiobookEntry>, path: String): List<ScreenRow.Action> {
        val prefix = "$path/"
        val own = entries.filter { it.folderKey == path }.map { seriesRow(state, it) }
        val children = entries.filter { it.folderKey.startsWith(prefix) }
            .groupBy { it.folderKey.substring(prefix.length).substringBefore('/') }
            .map { (child, podcasts) ->
                // A folder holding a single podcast opens straight to its episodes.
                if (podcasts.size == 1) seriesRow(state, podcasts.single())
                else folderRow(Folder(prefix + child, child, podcasts))
            }
        return own + sorted(children)
    }

    private fun sorted(rows: List<ScreenRow.Action>): List<ScreenRow.Action> = rows.sortedWith { first, second ->
        NaturalTextOrder.compare(first.title.lowercase(), second.title.lowercase())
    }

    private fun nameOf(path: String): String = path.substringAfterLast('/').substringAfter('|')

    private fun folderRow(folder: Folder): ScreenRow.Action {
        val count = folder.podcasts.size
        val active = folder.inProgressCount
        val detail = if (active == 0) "$count podcasts" else "$count podcasts · $active in progress"
        val first = folder.podcasts.firstOrNull { it.inProgress } ?: folder.podcasts.first()
        return ScreenRow.Action(
            folder.name, detail, COLLECTION_KEY + folder.path,
            artworkTrackId = first.currentChapterId ?: first.chapterIds.firstOrNull()
        )
    }

    fun seriesRow(state: AppState, entry: AudiobookEntry): ScreenRow.Action = ScreenRow.Action(
        entry.name,
        seriesDetail(entry),
        SERIES_KEY + entry.folderKey,
        artworkTrackId = entry.currentChapterId ?: entry.chapterIds.firstOrNull(),
        progress = seriesProgress(state, entry)
    )

    /** "7 of 348 played", or "348 episodes · 9 h" before it is started. */
    fun seriesDetail(entry: AudiobookEntry): String {
        val parts = ArrayList<String>(2)
        if (entry.chapterCount == 1) {
            parts += if (entry.inProgress) "In progress" else (duration(entry.totalDurationMs) ?: "1 episode")
        } else if (entry.inProgress) {
            parts += "${entry.startIndex} of ${entry.chapterCount} played"
        } else {
            parts += listOfNotNull("${entry.chapterCount} episodes", duration(entry.totalDurationMs)).joinToString(" · ")
        }
        return parts.joinToString(" · ")
    }

    private fun seriesProgress(state: AppState, entry: AudiobookEntry): RowProgress {
        if (!entry.inProgress) return RowProgress.UNPLAYED
        if (entry.totalDurationMs > 0L) {
            return RowProgress((entry.listenedMs.toFloat() / entry.totalDurationMs).coerceIn(.03f, 1f))
        }
        val episode = entry.currentChapterId?.let(state.library.byId::get)
        val within = episode?.durationMs?.takeIf { it > 0 }?.let { entry.positionMs.toFloat() / it } ?: 0f
        return RowProgress(((entry.startIndex + within) / entry.chapterCount).coerceIn(.03f, 1f))
    }

    /** A series page: carry on, then every episode with its state, then start over. */
    fun episodeRows(state: AppState, folderKey: String): List<ScreenRow> {
        val entry = ScreenContent.audiobookEntry(state, folderKey) ?: return emptyList()
        val byId = state.library.byId
        val episodes = entry.chapterIds.mapNotNull(byId::get)
        if (episodes.isEmpty()) return emptyList()
        return buildList {
            val current = episodes.getOrNull(entry.startIndex)
            if (entry.inProgress && current != null) {
                add(ScreenRow.Action(
                    "Continue · ${current.title}",
                    listOfNotNull(
                        if (entry.chapterCount > 1) "Episode ${entry.startIndex + 1}" else null,
                        timeLeft(current, entry.positionMs)
                    ).joinToString(" · "),
                    RESUME_KEY + folderKey,
                    progress = RowProgress(fraction(current, entry.positionMs))
                ))
            } else {
                add(ScreenRow.Action(
                    if (entry.chapterCount == 1) "Play" else "Play from Episode 1",
                    listOfNotNull(
                        if (entry.chapterCount > 1) "${entry.chapterCount} episodes" else null,
                        duration(entry.totalDurationMs)
                    ).joinToString(" · ").ifEmpty { null },
                    RESUME_KEY + folderKey
                ))
            }
            episodes.forEachIndexed { index, episode ->
                val played = entry.inProgress && index < entry.startIndex
                val playing = entry.inProgress && index == entry.startIndex
                add(ScreenRow.Action(
                    episode.title,
                    when {
                        played -> "Played"
                        playing -> timeLeft(episode, entry.positionMs) ?: "In progress"
                        else -> episode.durationMs.takeIf { it > 0 }?.let { duration(it) } ?: episode.displayArtist
                    },
                    EPISODE_KEY + episode.id,
                    progress = when {
                        played -> RowProgress.PLAYED
                        playing -> RowProgress(fraction(episode, entry.positionMs).coerceAtLeast(.03f))
                        else -> RowProgress.UNPLAYED
                    },
                    leading = (index + 1).toString()
                ))
            }
            if (entry.inProgress) {
                add(ScreenRow.Action("Start Over", "From the first episode", RESTART_KEY + folderKey))
                add(ScreenRow.Action("Mark All Unplayed", "Forget where you are in this series", CLEAR_KEY + folderKey))
            }
        }
    }

    /** Everything part-way: talks and videos, most recent first. */
    fun continueItems(state: AppState): List<ContinueItem> {
        val talks = ScreenContent.audiobookEntries(state).filter { it.inProgress }.mapNotNull { entry ->
            val episode = entry.currentChapterId?.let(state.library.byId::get) ?: return@mapNotNull null
            ContinueItem.Talk(entry, episode, entry.updatedAt)
        }
        val videos = state.videos.inProgress.map { ContinueItem.Video(it, it.updatedAt) }
        return (talks + videos).sortedByDescending { it.updatedAt }
    }

    fun continueRows(state: AppState): List<ScreenRow> = continueItems(state).map { item ->
        when (item) {
            is ContinueItem.Talk -> ScreenRow.Action(
                item.episode.title,
                listOfNotNull(
                    item.entry.name.takeUnless { it == item.episode.title },
                    if (item.entry.chapterCount > 1) "Ep ${item.entry.startIndex + 1}" else null,
                    timeLeft(item.episode, item.entry.positionMs)
                ).joinToString(" · "),
                CONTINUE_TALK_KEY + item.entry.folderKey,
                artworkTrackId = item.episode.id,
                progress = RowProgress(fraction(item.episode, item.entry.positionMs).coerceAtLeast(.03f))
            )
            is ContinueItem.Video -> ScreenRow.Action(
                item.video.title,
                listOfNotNull(item.video.folderName, videoLeft(item.video)).joinToString(" · "),
                CONTINUE_VIDEO_KEY + item.video.path,
                artworkPath = item.video.artworkPath,
                progress = RowProgress(item.video.fraction.coerceAtLeast(.03f))
            )
        }
    }

    fun fraction(track: Track, positionMs: Long): Float =
        if (track.durationMs <= 0L) 0f else (positionMs.toFloat() / track.durationMs).coerceIn(0f, 1f)

    /** "18 min left", rounded up so a few seconds still reads as a minute. */
    fun timeLeft(track: Track, positionMs: Long): String? {
        if (track.durationMs <= 0L) return null
        return leftLabel((track.durationMs - positionMs).coerceAtLeast(0L))
    }

    fun videoLeft(video: VideoProgress): String? =
        if (video.durationMs <= 0L) "at ${TimeFormat.duration(video.positionMs)}"
        else leftLabel((video.durationMs - video.positionMs).coerceAtLeast(0L))

    fun leftLabel(remainingMs: Long): String {
        val minutes = (remainingMs + 59_999L) / 60_000L
        return if (minutes < 60) "$minutes min left" else "${minutes / 60} h ${minutes % 60} min left"
    }

    fun duration(totalMs: Long): String? {
        if (totalMs <= 0L) return null
        val minutes = totalMs / 60_000
        return when {
            minutes < 1 -> "1 min"
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0L -> "${minutes / 60} h"
            minutes < 600 -> "${minutes / 60} h ${minutes % 60} min"
            else -> "${minutes / 60} h"
        }
    }
}

internal sealed interface ContinueItem {
    val updatedAt: Long
    data class Talk(val entry: AudiobookEntry, val episode: Track, override val updatedAt: Long) : ContinueItem
    data class Video(val video: VideoProgress, override val updatedAt: Long) : ContinueItem
}

/** Which bani of the Nitnem playlist is due at a given hour. */
internal object NitnemSchedule {
    fun due(hour: Int): String = when (hour) {
        in 2..11 -> "Japji"
        in 12..20 -> "Rehras"
        else -> "Sohila"
    }
}
