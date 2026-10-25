package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.RepeatMode
import com.schulzcode.y2player.core.model.SleepTimerMode
import com.schulzcode.y2player.core.model.Track
import java.util.Locale

/**
 * The right half of Home. It follows the focused row and says what Centre will
 * open, so nothing on Home has to be tried to be understood.
 */
internal sealed interface HomePane {
    data class NowPlaying(
        val trackId: Long,
        val title: String,
        val artist: String,
        /** Always the truth about what happens next, never a guess. */
        val upNext: String,
        val upNextIsRepeat: Boolean
    ) : HomePane

    data class Continue(val first: Item, val others: List<Item>) : HomePane

    data class Collection(
        val title: String,
        val detail: String?,
        val artTrackIds: List<Long>,
        val lines: List<Line>,
        val hint: String?
    ) : HomePane

    data class Item(
        val title: String,
        val detail: String,
        val left: String?,
        val fraction: Float,
        val artTrackId: Long? = null,
        val artPath: String? = null
    )

    data class Line(val text: String, val detail: String? = null, val emphasised: Boolean = false)

    companion object {
        fun of(state: AppState, row: ScreenRow?): HomePane? {
            val key = (row as? ScreenRow.Action)?.key ?: return null
            return when {
                key == ScreenContent.NOW_PLAYING_KEY -> nowPlaying(state)
                key == ScreenContent.CONTINUE_KEY -> continuePane(state)
                key == "favorites" -> favourites(state)
                key.startsWith(ScreenContent.NITNEM_KEY) -> nitnem(state)
                key == "music" -> music(state)
                key == "audiobooks" -> talks(state)
                key == "videos" -> videos(state)
                key == ScreenContent.MAIN_SLEEP_TIMER_KEY -> Collection(
                    "Sleep Timer",
                    ScreenContent.sleepTimerSubtitle(state),
                    emptyList(),
                    listOf(Line("Hold Play/Pause on any screen to open it")),
                    null
                )
                key == "search" -> Collection(
                    "Search", "Songs, talks, albums and playlists", emptyList(),
                    listOf(Line("Type with the wheel, then Centre")), null
                )
                key == "fm_radio" -> Collection("FM Radio", "The built-in tuner", emptyList(), emptyList(), null)
                key == "settings" -> Collection(
                    "Settings", "Sound, display, Bedtime and the clock", emptyList(),
                    listOfNotNull(
                        if (!ClockPresentation.isSet(state.clock)) Line("The clock is not set", emphasised = true) else null
                    ),
                    null
                )
                else -> null
            }
        }

        fun nowPlaying(state: AppState): NowPlaying? {
            val track = state.playback.currentTrackId?.let(state.library.byId::get) ?: return null
            val (upNext, repeat) = upNext(state, track)
            return NowPlaying(track.id, track.title, artistLine(state, track), upNext, repeat)
        }

        fun artistLine(state: AppState, track: Track): String {
            val series = track.audiobookFolderKey?.let { ScreenContent.audiobookEntry(state, it) }
            return when {
                series == null -> track.displayArtist
                track.artist.isNullOrBlank() -> series.name
                series.name.equals(track.displayArtist, ignoreCase = true) -> series.name
                else -> "${track.displayArtist} · ${series.name}"
            }
        }

        /** What plays after this track, said plainly. The flag marks a repeat. */
        fun upNext(state: AppState, track: Track): Pair<String, Boolean> {
            val playback = state.playback
            val talk = track.isAudiobookChapter
            if (playback.repeatMode == RepeatMode.ONE) {
                return (if (talk) "This episode again" else "This track again") to true
            }
            if (playback.sleepTimerMode == SleepTimerMode.END_TRACK) return "Sleep timer stops playback" to false
            val nextId = playback.nextTrackId ?: playback.queue.getOrNull(
                playback.queue.indexOfFirst { it.id == playback.currentQueueEntryId } + 1
            )?.trackId
            val next = nextId?.let(state.library.byId::get)
            if (next == null) {
                if (playback.sleepTimerMode == SleepTimerMode.END_QUEUE) return "Sleep timer stops playback" to false
                return "Nothing after this" to false
            }
            if (next.id == track.id && playback.repeatMode == RepeatMode.ALL) return "This track again" to true
            return "${next.title} · ${next.displayArtist}" to false
        }

        private fun continuePane(state: AppState): Continue? {
            val items = TalksContent.continueItems(state).map(::item)
            val first = items.firstOrNull() ?: return null
            return Continue(first, items.drop(1).take(2))
        }

        private fun item(entry: ContinueItem): Item = when (entry) {
            is ContinueItem.Talk -> Item(
                title = entry.episode.title,
                detail = listOfNotNull(
                    entry.entry.name.takeUnless { it == entry.episode.title },
                    if (entry.entry.chapterCount > 1) "Ep ${entry.entry.startIndex + 1}" else null
                ).joinToString(" · ").ifEmpty { entry.episode.displayArtist },
                left = TalksContent.timeLeft(entry.episode, entry.entry.positionMs)?.removeSuffix(" left"),
                fraction = TalksContent.fraction(entry.episode, entry.entry.positionMs),
                artTrackId = entry.episode.id
            )
            is ContinueItem.Video -> Item(
                title = entry.video.title,
                detail = entry.video.folderName ?: "Video",
                left = TalksContent.videoLeft(entry.video)?.removeSuffix(" left"),
                fraction = entry.video.fraction,
                artPath = entry.video.artworkPath
            )
        }

        private fun favourites(state: AppState): Collection {
            val tracks = state.library.favoriteMusicTracks
            val total = tracks.sumOf { it.durationMs.coerceAtLeast(0L) }
            return Collection(
                "Favourites",
                listOfNotNull(ScreenContent.trackCountLabel(tracks.size), TalksContent.duration(total)).joinToString(" · "),
                distinctArt(tracks),
                emptyList(),
                if (tracks.isEmpty()) "Hold Centre on a track to add it" else null
            )
        }

        private fun nitnem(state: AppState): Collection? {
            val playlist = ScreenContent.nitnemPlaylist(state) ?: return null
            val tracks = state.library.playlistTrackIds[playlist.id].orEmpty().mapNotNull(state.library.byId::get)
            val due = if (ClockPresentation.isSet(state.clock)) NitnemSchedule.due(ClockPresentation.fields(state.clock).hour) else null
            val dueTrack = due?.let { word -> tracks.firstOrNull { it.title.contains(word, ignoreCase = true) } }
            return Collection(
                playlist.name,
                ScreenContent.trackCountLabel(tracks.size),
                distinctArt(tracks),
                tracks.take(3).map { Line(it.title, it.displayArtist, emphasised = it == dueTrack) },
                dueTrack?.let { "Opens on ${it.title}" }
            )
        }

        private fun music(state: AppState): Collection {
            val tracks = state.library.musicTracks
            val artists = artistCount(state)
            val recent = state.library.recentlyPlayedMusic
            return Collection(
                "Music",
                listOf(
                    String.format(Locale.US, "%,d tracks", tracks.size),
                    if (artists == 1) "1 ragi or speaker" else String.format(Locale.US, "%,d ragis & speakers", artists)
                ).joinToString(" · "),
                distinctArt(recent.ifEmpty { tracks }),
                recent.take(2).map { Line(it.title, it.displayArtist) },
                if (recent.isEmpty()) null else "Recently played"
            )
        }

        private fun talks(state: AppState): Collection {
            val series = ScreenContent.audiobookEntries(state)
            val active = series.filter { it.inProgress }
            val detail = when {
                series.isEmpty() -> "Nothing yet"
                active.isEmpty() -> "${series.size} series"
                else -> "${active.size} in progress · ${series.size} series"
            }
            val shown = (active + series.filterNot { it.inProgress }).take(3)
            return Collection(
                "Talks & Podcasts",
                detail,
                shown.mapNotNull { it.currentChapterId ?: it.chapterIds.firstOrNull() },
                shown.map { Line(it.name, TalksContent.seriesDetail(it).substringAfterLast(" · "), emphasised = it.inProgress) },
                if (series.isEmpty()) "Put podcasts in a Podcasts folder, or tag them Podcast" else null
            )
        }

        private fun videos(state: AppState): Collection {
            val progress = state.videos.inProgress
            return Collection(
                "Videos",
                if (progress.isEmpty()) "Films and shows" else "${progress.size} in progress",
                emptyList(),
                progress.take(3).map { Line(it.title, TalksContent.videoLeft(it)) },
                null
            )
        }

        private var countedIndex: Any? = null
        private var countedArtists = 0

        /** Counting artists groups the whole library, so it is done once per library. */
        @Synchronized
        fun artistCount(state: AppState): Int {
            val index = state.library.index
            if (countedIndex !== index) {
                countedArtists = index.organization.artists(com.schulzcode.y2player.core.model.LibraryScope.All).size
                countedIndex = index
            }
            return countedArtists
        }

        /** Up to four covers, one per album, for the mosaic. */
        private fun distinctArt(tracks: List<Track>): List<Long> {
            val seen = HashSet<String>()
            val ids = ArrayList<Long>(4)
            for (track in tracks) {
                if (ids.size == 4) break
                if (seen.add(track.displayAlbum + "\u0000" + track.albumArtistName)) ids += track.id
            }
            return ids
        }
    }
}
