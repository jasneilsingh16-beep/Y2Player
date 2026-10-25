package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.AudiobookIdentity
import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.util.TimeFormat

/**
 * Talks & Podcasts. Each folder of spoken recordings is a series; a folder that
 * holds two or more series (Basics of Sikhi, Khoj Gurbani) is a collection and
 * opens into them. Episodes before the one in progress count as played.
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

    /** Folders that hold everything and so never read as a collection. */
    private val CONTAINER_NAMES = setOf("music", "media", "audio", "mp3", "downloads", "download")

    data class Collection(val path: String, val name: String, val series: List<AudiobookEntry>) {
        val updatedAt: Long get() = series.maxOf { it.updatedAt }
        val inProgressCount: Int get() = series.count { it.inProgress }
    }

    /** The collection folder of a series, when its parent holds other series too. */
    private fun parentPath(entry: AudiobookEntry): String? {
        val path = AudiobookIdentity.volumeOf(entry.folderKey) + "|" + AudiobookIdentity.pathOf(entry.folderKey)
        val slash = path.lastIndexOf('/')
        if (slash <= path.indexOf('|')) return null
        val parent = path.substring(0, slash)
        val name = parent.substringAfterLast('/').substringAfter('|')
        if (AudiobookIdentity.isRootSegment(name) || name.lowercase() in CONTAINER_NAMES) return null
        return parent
    }

    fun collections(state: AppState): Map<String, Collection> {
        val grouped = ScreenContent.audiobookEntries(state).groupBy(::parentPath)
        return grouped.filterKeys { it != null }.filterValues { it.size >= 2 }.map { (path, series) ->
            path!! to Collection(path, path.substringAfterLast('/').substringAfter('|'), series)
        }.toMap()
    }

    fun collection(state: AppState, path: String): Collection? = collections(state)[path]

    /** Top level, or one collection's series: what is under way first, then by name. */
    fun rows(state: AppState, collectionPath: String? = null): List<ScreenRow> {
        val collections = collections(state)
        if (collectionPath != null) {
            val series = collections[collectionPath]?.series.orEmpty()
            return ordered(series.map { it to it.updatedAt }).map { seriesRow(state, it) }
        }
        val grouped = collections.values.flatMap { it.series }.mapTo(HashSet()) { it.folderKey }
        val items: List<Pair<Any, Long>> =
            ScreenContent.audiobookEntries(state).filter { it.folderKey !in grouped }.map { it to it.updatedAt } +
                collections.values.map { it to it.updatedAt }
        return ordered(items).map { item ->
            when (item) {
                is AudiobookEntry -> seriesRow(state, item)
                is Collection -> collectionRow(item)
                else -> error("unexpected")
            }
        }
    }

    private fun <T : Any> ordered(items: List<Pair<T, Long>>): List<T> = items.sortedWith(
        compareByDescending<Pair<T, Long>> { it.second }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { nameOf(it.first) }
    ).map { it.first }

    private fun nameOf(item: Any): String = when (item) {
        is AudiobookEntry -> item.name
        is Collection -> item.name
        else -> ""
    }

    private fun collectionRow(collection: Collection): ScreenRow.Action {
        val count = collection.series.size
        val active = collection.inProgressCount
        val detail = if (active == 0) "$count series" else "$count series · $active in progress"
        return ScreenRow.Action(
            collection.name, detail, COLLECTION_KEY + collection.path,
            artworkTrackId = collection.series.first().chapterIds.firstOrNull()
        )
    }

    fun seriesRow(state: AppState, entry: AudiobookEntry): ScreenRow.Action = ScreenRow.Action(
        entry.name,
        seriesDetail(entry),
        SERIES_KEY + entry.folderKey,
        artworkTrackId = entry.currentChapterId ?: entry.chapterIds.firstOrNull(),
        progress = seriesProgress(state, entry)
    )

    /** "Jvala Singh · 7 of 348 played", or "348 episodes · 9 h" before it is started. */
    fun seriesDetail(entry: AudiobookEntry): String {
        val parts = ArrayList<String>(2)
        entry.artist?.let(parts::add)
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
                        timeLeft(current, entry.positionMs),
                        if (entry.chapterCount > 1) "${entry.startIndex} of ${entry.chapterCount} played" else null
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
