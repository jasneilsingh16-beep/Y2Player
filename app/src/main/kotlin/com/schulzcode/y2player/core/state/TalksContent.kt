package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.AudiobookIdentity
import com.schulzcode.y2player.core.model.NaturalTextOrder
import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.util.TimeFormat

/**
 * Talks & Podcasts. Every folder that holds recordings is one podcast. The talks
 * folders themselves (Talks, Sikhi Talks, NDEs, Plum Village) are not a level of
 * their own: the first folder inside them (a speaker such as Jvala Singh) is, and
 * opens on all of its podcasts however deep they sit. Whatever is under way comes
 * first. Episodes before the one in progress count as played.
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

    /** A speaker or collection: every podcast inside one folder. */
    data class Folder(val path: String, val name: String, val podcasts: List<AudiobookEntry>) {
        val inProgressCount: Int get() = podcasts.count { it.inProgress }
        val updatedAt: Long get() = podcasts.maxOf { it.updatedAt }
    }

    /**
     * The speaker folder a podcast belongs to: the first folder below the run of
     * talks folders, as "volume|path". Null when the podcast sits straight inside
     * a talks folder, or was found by its genre tag alone.
     */
    fun groupOf(folderKey: String): String? {
        val segments = AudiobookIdentity.pathOf(folderKey).split('/')
        var index = segments.indexOfFirst(AudiobookIdentity::isRootSegment)
        if (index < 0) return null
        while (index + 1 < segments.lastIndex && AudiobookIdentity.isRootSegment(segments[index + 1])) index += 1
        val group = index + 1
        if (group >= segments.lastIndex) return null
        return AudiobookIdentity.volumeOf(folderKey) + "|" + segments.subList(0, group + 1).joinToString("/")
    }

    /** The top level, or one speaker's podcasts. */
    fun rows(state: AppState, folderPath: String? = null): List<ScreenRow> {
        val entries = ScreenContent.audiobookEntries(state)
        if (folderPath != null) {
            val inside = entries.filter { it.folderKey.startsWith("$folderPath/") || it.folderKey == folderPath }
            return ordered(inside.map { it to it.updatedAt }).map { seriesRow(state, it) }
        }
        val groups = entries.groupBy { groupOf(it.folderKey) }
        val items: List<Pair<Any, Long>> = groups.flatMap { (group, podcasts) ->
            when {
                group == null -> podcasts.map { it to it.updatedAt }
                podcasts.size == 1 -> listOf(podcasts.single() to podcasts.single().updatedAt)
                else -> Folder(group, nameOf(group), podcasts).let { listOf(it to it.updatedAt) }
            }
        }
        return ordered(items).map { item ->
            when (item) {
                is Folder -> folderRow(item)
                is AudiobookEntry -> seriesRow(state, item, speaker = groupOf(item.folderKey)?.let(::nameOf))
                else -> error("unexpected")
            }
        }
    }

    /** What is under way first, most recent first; then everything else by name. */
    private fun <T : Any> ordered(items: List<Pair<T, Long>>): List<T> = items.sortedWith { first, second ->
        val recent = compareValues(second.second, first.second)
        if (recent != 0) recent
        else NaturalTextOrder.compare(nameOf(first.first).lowercase(), nameOf(second.first).lowercase())
    }.map { it.first }

    private fun nameOf(item: Any): String = when (item) {
        is AudiobookEntry -> item.name
        is Folder -> item.name
        is String -> item.substringAfterLast('/').substringAfter('|')
        else -> ""
    }

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

    fun seriesRow(state: AppState, entry: AudiobookEntry, speaker: String? = null): ScreenRow.Action = ScreenRow.Action(
        entry.name,
        listOfNotNull(speaker?.takeUnless { it.equals(entry.name, ignoreCase = true) }, seriesDetail(entry)).joinToString(" · "),
        SERIES_KEY + entry.folderKey,
        artworkTrackId = entry.currentChapterId ?: entry.chapterIds.firstOrNull(),
        // A ring only once something has been heard; unstarted podcasts give their length instead.
        progress = if (entry.inProgress) seriesProgress(state, entry) else null
    )

    /** "7 of 348 played", or "348 episodes · 9 h" before it is started. */
    fun seriesDetail(entry: AudiobookEntry): String {
        val parts = ArrayList<String>(2)
        if (entry.chapterCount == 1) {
            parts += if (entry.inProgress) "In progress" else (duration(entry.totalDurationMs) ?: "1 episode")
        } else if (entry.inProgress || entry.playedCount > 0) {
            parts += "${entry.playedCount} of ${entry.chapterCount} played"
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
                val playing = entry.inProgress && index == entry.startIndex
                // Played means heard to the end; nothing before it is assumed.
                val played = !playing && ScreenContent.episodeKey(episode) in state.library.playedEpisodes
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
