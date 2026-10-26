package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.BuildConfig
import com.schulzcode.y2player.core.model.AudioCodecLabels
import com.schulzcode.y2player.core.model.AudioCodecSupport
import com.schulzcode.y2player.core.model.AudioQualityMode
import com.schulzcode.y2player.core.model.AlbumEntry
import com.schulzcode.y2player.core.model.AlbumKey
import com.schulzcode.y2player.core.model.AlbumSortOrder
import com.schulzcode.y2player.core.model.CodecSupport
import com.schulzcode.y2player.core.model.LibraryScope
import com.schulzcode.y2player.core.model.NaturalTextOrder
import com.schulzcode.y2player.core.model.RepeatMode
import com.schulzcode.y2player.core.model.SleepTimerMode
import com.schulzcode.y2player.core.model.QueueEntry
import com.schulzcode.y2player.core.model.QueueOrigin
import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.core.model.TrackSortOrder
import com.schulzcode.y2player.core.model.YearSortOrder
import com.schulzcode.y2player.diagnostics.PlaybackCapabilities
import com.schulzcode.y2player.input.HapticLevel
import com.schulzcode.y2player.playback.AudioBalance
import com.schulzcode.y2player.playback.CrossfadeMode
import com.schulzcode.y2player.playback.VolumeCurve
import com.schulzcode.y2player.playback.VolumeMode
import com.schulzcode.y2player.util.TimeFormat
import java.io.File
import java.util.Locale

sealed interface ScreenRow {
    val title: String
    val subtitle: String?
    val artworkTrackId: Long? get() = null
    /** Artwork found beside a file outside the music library (video folder posters). */
    val artworkPath: String? get() = null

    data class Action(
        override val title: String,
        override val subtitle: String? = null,
        val key: String,
        override val artworkTrackId: Long? = null,
        override val artworkPath: String? = null,
        /** Drawn as a ring on the right: how far through, or a tick once played. */
        val progress: RowProgress? = null,
        /** A short label in its own column on the left, such as an episode number. */
        val leading: String? = null,
        /** Posters shown side by side on the left, for a folder of films or shows. */
        val posterPaths: List<String> = emptyList()
    ) : ScreenRow
    data class TrackRow(
        val track: Track,
        val queueEntry: QueueEntry? = null,
        val selectionIndex: Int? = null,
        val selected: Boolean = false
    ) : ScreenRow {
        override val title: String = track.title
        override val artworkTrackId: Long = track.id
        override val subtitle: String = buildString {
            selectionIndex?.let {
                append(if (selected) "Selected" else "Not selected")
                append(" · ")
            }
            // Only what was added by hand says so; the rest of the queue is just the music.
            if (queueEntry?.origin == QueueOrigin.UP_NEXT) append("Up Next · ")
            append(track.displayArtist)
            if (!track.available) append(" · unavailable")
            if (track.decodeFailed ||
                AudioCodecSupport.of(track.codec, track.extension) == CodecSupport.UNSUPPORTED
            ) {
                append(" · not playable")
            }
        }
    }
    data class Group(
        override val title: String,
        override val subtitle: String? = null,
        val key: String,
        val target: ScreenGroupTarget? = null,
        override val artworkTrackId: Long? = null
    ) : ScreenRow
    data class Folder(override val title: String, val volumeId: String, val relativePath: String) : ScreenRow {
        override val subtitle: String? = null
    }
}

data class RowProgress(val fraction: Float, val played: Boolean = false) {
    companion object {
        val UNPLAYED = RowProgress(0f)
        val PLAYED = RowProgress(1f, played = true)
    }
}

sealed interface ScreenGroupTarget {
    data class Album(val key: AlbumKey) : ScreenGroupTarget
    data class Artist(val name: String) : ScreenGroupTarget
    data class Scope(val scope: LibraryScope) : ScreenGroupTarget
}

object ScreenContent {
    fun title(state: AppState): String = when (val screen = state.currentScreen) {
        Screen.MainMenu -> "Home"
        is Screen.Videos -> screen.path?.let { java.io.File(it).name } ?: "Videos"
        is Screen.VideoOptions -> screen.title
        is Screen.Search -> "Search"
        Screen.Music -> "Music"
        Screen.FmRadio -> "FM Radio"
        Screen.Audiobooks -> "Talks & Podcasts"
        is Screen.AudiobookOptions -> audiobookName(state, screen.folderKey) ?: "Series"
        is Screen.AudiobookChapters -> audiobookName(state, screen.folderKey) ?: "Episodes"
        is Screen.TalkCollection -> screen.path.substringAfterLast('/').substringAfter('|')
        Screen.Continue -> "Continue"
        Screen.Songs -> "Songs"
        Screen.Favorites -> "Favourites"
        Screen.RecentlyPlayed -> "Recently Played"
        Screen.Albums -> "Albums"
        is Screen.AlbumSongs -> "Album"
        Screen.Artists -> "Ragis & Speakers"
        is Screen.ArtistAlbums -> screen.artist
        is Screen.ArtistSongs -> "Artist"
        Screen.Genres -> "Categories"
        Screen.Years -> "Years"
        is Screen.FacetMenu -> screen.scope.label
        is Screen.FacetArtists -> "${screen.scope.label} Artists"
        is Screen.FacetAlbums -> "${screen.scope.label} Albums"
        is Screen.FacetArtistAlbums -> screen.artist
        is Screen.FacetTracks -> screen.title
        is Screen.Folders -> if (screen.volumeId == null) "Folders" else screen.relativePath.takeIf { it.isNotBlank() } ?: volumeName(screen.volumeId)
        Screen.Playlists -> "Playlists"
        is Screen.PlaylistTracks -> screen.name
        is Screen.TrackOptions -> "Track Options"
        is Screen.TrackBrowse -> "Browse Track"
        is Screen.TrackDetails -> "Track Details"
        is Screen.AddToPlaylist -> "Add to Playlist"
        is Screen.CollectionOptions -> screen.title
        is Screen.MultiSelect -> "Select Songs"
        is Screen.QueueOptions -> state.playback.queue.firstOrNull { it.id == screen.entryId }
            ?.trackId?.let(state.library.byId::get)?.title ?: "Queue Item"
        is Screen.QueueMove -> state.playback.queue.firstOrNull { it.id == screen.entryId }
            ?.trackId?.let(state.library.byId::get)?.title?.let { "Move $it" } ?: "Move Queue Item"
        Screen.QueueManagement -> "Queue"
        Screen.NowPlaying -> "Now Playing"
        Screen.NowPlayingOptions -> "Playback Options"
        Screen.SleepTimer -> "Sleep Timer"
        Screen.Queue -> "Queue"
        Screen.Audio -> "Sound"
        Screen.Settings -> "Settings"
        Screen.PlaybackTransitions -> "Transitions"
        Screen.PlaybackSeeking -> "Seeking"
        Screen.PlaybackVolume -> "Volume"
        Screen.PlaybackInterruptions -> "Interruptions"
        Screen.SoundEffects -> "Sound Effects"
        Screen.EqualizerSettings -> "Equaliser"
        Screen.EqualizerPresets -> "Equaliser Preset"
        Screen.OutputInformation -> "Output"
        Screen.EqualizerBands -> "Equaliser Bands"
        is Screen.EqualizerBandLevel -> state.playback.audioEffects.bandFrequenciesHz
            .getOrNull(screen.bandIndex)?.let { "${formatFrequency(it)} Level" } ?: "Band Level"
        Screen.SortOrder -> "Sort Order"
        Screen.TrackSorting -> "Track Order"
        Screen.AlbumSorting -> "Album Order"
        Screen.YearSorting -> "Year Lists"
        Screen.Bluetooth -> "Bluetooth"
        is Screen.BluetoothDevice -> "Device"
        is Screen.ConfirmAction -> "Confirm"
        Screen.InterfaceSettings -> "Interface"
        Screen.LibrarySettings -> "Library"
        Screen.Skins -> "Skins"
        Screen.Display -> "Display"
        Screen.Controls -> "Controls"
        Screen.Balance -> "Balance"
        Screen.Brightness -> "Brightness"
        Screen.ScreenTimeout -> "Screen Timeout"
        Screen.DateTime -> "Date & Time"
        Screen.Bedtime -> "Bedtime"
        Screen.WakeTime -> "Wake Time"
        Screen.ClockHour -> "Set Time"
        is Screen.ClockMinute -> ClockPresentation.hourLabel(screen.hour, state.preferences.use24HourClock)
        Screen.ClockYear -> "Set Date"
        is Screen.ClockMonth -> screen.year.toString()
        is Screen.ClockDay -> "${ClockPresentation.monthName(screen.month)} ${screen.year}"
        Screen.TimeZone -> "Time Zone"
        Screen.Storage -> "Storage & Scan"
        Screen.PlaybackHistory -> "Listening History"
        Screen.System -> "System"
        Screen.BackupRestore -> "Backup & Restore"
        Screen.Diagnostics -> "Diagnostics"
        Screen.Reset -> "Reset"
        Screen.About -> "About"
    }

    @Synchronized
    fun rows(state: AppState): List<ScreenRow> {
        if (!isLargeScreen(state.currentScreen)) return buildRows(state)
        val key = LargeRowsKey(
            screen = state.currentScreen,
            contentRevision = contentRevision(state),
            indexIdentity = System.identityHashCode(state.library.index),
            sortOrder = state.preferences.sortOrder,
            albumSortOrder = state.preferences.albumSortOrder,
            yearSortOrder = state.preferences.yearSortOrder,
            queueFingerprint = if (state.currentScreen == Screen.Queue || state.currentScreen is Screen.QueueMove) {
                System.identityHashCode(state.playback.queue)
            } else 0
        )
        cachedRows[key]?.let { return it }
        return buildRows(state).also { rows ->
            if (cachedRows.size >= ROW_CACHE_ENTRIES) {
                cachedRows.keys.iterator().let { if (it.hasNext()) { it.next(); it.remove() } }
            }
            cachedRows[key] = rows
        }
    }

    private fun contentRevision(state: AppState): Long = when (state.currentScreen) {
        is Screen.Search -> state.library.revision
        Screen.RecentlyPlayed, Screen.Playlists, is Screen.PlaylistTracks, Screen.Favorites,
        Screen.Audiobooks, is Screen.AudiobookChapters, is Screen.TalkCollection -> state.library.revision
        else -> state.library.tracksRevision
    }

    private fun buildRows(state: AppState): List<ScreenRow> = when (val screen = state.currentScreen) {
        Screen.MainMenu -> mainMenuRows(state)
        // Drawn like Now Playing rather than as a list, so it has no rows.
        Screen.FmRadio -> emptyList()
        is Screen.Search -> searchRows(state)
        Screen.Music -> musicRows(state)
        Screen.Audiobooks -> TalksContent.rows(state)
        is Screen.TalkCollection -> TalksContent.rows(state, screen.path)
        Screen.Continue -> TalksContent.continueRows(state)
        is Screen.Videos -> videoRows(state, screen)
        is Screen.VideoOptions -> videoOptionRows(state, screen)
        is Screen.AudiobookOptions -> audiobookOptionRows(state, screen.folderKey)
        is Screen.AudiobookChapters -> TalksContent.episodeRows(state, screen.folderKey)
        Screen.Songs -> collectionRows(state.library.index.organization.sortTracks(
            state.library.musicTracks, state.preferences.sortOrder
        ))
        Screen.Favorites -> favoriteRows(state)
        Screen.RecentlyPlayed -> collectionRows(state.library.recentlyPlayedMusic)
        Screen.Albums -> albumRows(state)
        is Screen.AlbumSongs -> albumDetailRows(state.library.musicTracks, screen.album, screen.albumArtist)
        Screen.Artists -> artistRows(state)
        is Screen.ArtistAlbums -> artistAlbumRows(state, screen.artist)
        is Screen.ArtistSongs -> artistDetailRows(state, screen.artist)
        Screen.Genres -> genreRows(state)
        Screen.Years -> yearRows(state)
        is Screen.FacetMenu -> facetMenuRows(screen.scope)
        is Screen.FacetArtists -> facetArtistRows(state, screen.scope)
        is Screen.FacetAlbums -> facetAlbumRows(state, screen.scope)
        is Screen.FacetArtistAlbums -> facetArtistAlbumRows(state, screen.scope, screen.artist)
        is Screen.FacetTracks -> facetTrackRows(state, screen)
        is Screen.Folders -> folderRows(state.library.musicTracks, screen)
        Screen.Playlists -> playlistRows(state)
        is Screen.PlaylistTracks -> playlistTrackRows(state, screen)
        is Screen.TrackOptions -> trackOptionRows(state, screen)
        is Screen.TrackBrowse -> trackBrowseRows(state, screen.trackId)
        is Screen.TrackDetails -> trackDetailRows(state, screen.trackId)
        is Screen.AddToPlaylist -> addToPlaylistRows(state)
        is Screen.CollectionOptions -> collectionOptionRows(screen)
        is Screen.MultiSelect -> multiSelectRows(state, screen)
        is Screen.QueueOptions -> queueOptionRows(state, screen.entryId)
        is Screen.QueueMove -> queueMoveRows(state, screen)
        Screen.QueueManagement -> queueManagementRows(state)
        Screen.Queue -> queueRows(state)
        Screen.NowPlaying -> nowPlayingTiles(state)
        Screen.NowPlayingOptions -> nowPlayingOptionsRows(state)
        Screen.SleepTimer -> sleepTimerRows(state)
        Screen.Audio -> audioRows(state)
        Screen.Settings -> settingsRows(state)
        Screen.PlaybackTransitions -> playbackTransitionRows(state)
        Screen.PlaybackSeeking -> playbackSeekingRows(state)
        Screen.PlaybackVolume -> playbackVolumeRows(state)
        Screen.PlaybackInterruptions -> playbackInterruptionRows(state)
        Screen.SoundEffects -> soundEffectRows(state)
        Screen.EqualizerSettings -> equalizerRows(state)
        Screen.EqualizerPresets -> equalizerPresetRows(state)
        Screen.OutputInformation -> outputInformationRows(state)
        Screen.EqualizerBands -> equalizerBandRows(state)
        is Screen.EqualizerBandLevel -> equalizerBandLevelRows(state, screen.bandIndex)
        Screen.SortOrder -> sortOrderRows(state)
        Screen.TrackSorting -> trackSortRows()
        Screen.AlbumSorting -> albumSortRows()
        Screen.YearSorting -> yearSortRows()
        Screen.Bluetooth -> bluetoothRows(state)
        is Screen.BluetoothDevice -> bluetoothDeviceRows(state, screen)
        is Screen.ConfirmAction -> confirmActionRows(state, screen)
        Screen.InterfaceSettings -> interfaceRows(state)
        Screen.LibrarySettings -> librarySettingsRows(state)
        Screen.Skins -> skinRows(state)
        Screen.Display -> displayRows(state)
        Screen.Controls -> controlsRows(state)
        Screen.Balance -> balanceRows(state)
        Screen.Brightness -> brightnessRows(state)
        Screen.ScreenTimeout -> timeoutRows(state)
        Screen.DateTime -> dateTimeRows(state)
        Screen.Bedtime -> bedtimeRows(state)
        Screen.WakeTime -> wakeRows(state)
        Screen.ClockHour -> clockHourRows(state)
        is Screen.ClockMinute -> clockMinuteRows(state, screen.hour)
        Screen.ClockYear -> clockYearRows()
        is Screen.ClockMonth -> clockMonthRows()
        is Screen.ClockDay -> clockDayRows(screen.year, screen.month)
        Screen.TimeZone -> timeZoneRows(state)
        Screen.Storage -> storageRows(state)
        Screen.PlaybackHistory -> playbackHistoryRows(state)
        Screen.System -> systemRows(state)
        Screen.BackupRestore -> backupRestoreRows(state)
        Screen.Diagnostics -> diagnosticsRows(state)
        Screen.Reset -> resetRows(state)
        Screen.About -> aboutRows(state)
    }

    fun selectedTrackCollection(state: AppState): Pair<List<Long>, Int>? {
        val rows = rows(state)
        val selected = rows.getOrNull(state.selectedIndex) as? ScreenRow.TrackRow ?: return null
        val ids = ArrayList<Long>(rows.size)
        var index = -1
        rows.forEach { row ->
            if (row !is ScreenRow.TrackRow || !row.track.available) return@forEach
            val chosen = row.track.id == selected.track.id
            if (row.track.decodeFailed && !chosen) return@forEach
            if (chosen && index < 0) index = ids.size
            ids.add(row.track.id)
        }
        return if (index >= 0) ids to index else null
    }

    fun playableTrackIds(state: AppState): List<Long> {
        val rows = rows(state)
        val ids = ArrayList<Long>(rows.size)
        rows.forEach { row ->
            if (row is ScreenRow.TrackRow && row.track.available && !row.track.decodeFailed) {
                ids.add(row.track.id)
            }
        }
        return ids
    }

    fun sameRowIdentity(first: ScreenRow, second: ScreenRow): Boolean = when {
        first is ScreenRow.Action && second is ScreenRow.Action -> first.key == second.key
        first is ScreenRow.TrackRow && second is ScreenRow.TrackRow ->
            if (first.selectionIndex != null || second.selectionIndex != null) first.selectionIndex == second.selectionIndex
            else if (first.queueEntry != null || second.queueEntry != null) first.queueEntry?.id == second.queueEntry?.id
            else first.track.id == second.track.id
        first is ScreenRow.Group && second is ScreenRow.Group ->
            (first.target != null || second.target != null).let { hasTarget ->
                if (hasTarget) first.target == second.target else first.key == second.key
            }
        first is ScreenRow.Folder && second is ScreenRow.Folder ->
            first.volumeId == second.volumeId && first.relativePath == second.relativePath
        else -> false
    }

    // Now Playing leads while something is loaded, so the wheel can always get back
    // to it, then Continue for anything left part-way. Favourites and Nitnem sit
    // at the top because they are what is played most.
    private fun mainMenuRows(state: AppState): List<ScreenRow> = buildList {
        val track = state.playback.currentTrackId?.let(state.library.byId::get)
        // The playing track leads Home as its own row: cover, title and artist.
        if (track != null) add(ScreenRow.Action(track.title, track.displayArtist, NOW_PLAYING_KEY, track.id))
        TalksContent.continueItems(state).firstOrNull()?.let { first ->
            add(ScreenRow.Action("Continue", continueSummary(first), CONTINUE_KEY, progress = continueProgress(first)))
        }
        add(ScreenRow.Action("Favourites", state.library.favoriteMusicTracks.size.takeIf { it > 0 }?.let(::countLabel), "favorites"))
        nitnemPlaylist(state)?.let { playlist ->
            add(ScreenRow.Action(playlist.name, nitnemSummary(state, playlist), NITNEM_KEY + playlist.id))
        }
        add(ScreenRow.Action("Music", null, "music"))
        add(ScreenRow.Action("Music Folders", null, MUSIC_FOLDERS_KEY))
        add(ScreenRow.Action("Talks & Podcasts", audiobookMenuSubtitle(state), "audiobooks"))
        add(ScreenRow.Action("Videos", state.videos.inProgress.size.takeIf { it > 0 }?.let { "$it in progress" }, "videos"))
        if (state.preferences.showFmRadio) {
            add(ScreenRow.Action("FM Radio", null, "fm_radio"))
        }
        add(ScreenRow.Action("Search", null, "search"))
        add(ScreenRow.Action("Settings", if (state.safeMode) "Safe mode" else null, "settings"))
    }

    private fun countLabel(count: Int): String = String.format(java.util.Locale.US, "%,d", count)

    /** "Suraj Podcast · 18 min": what Continue would pick up. */
    private fun continueSummary(item: ContinueItem): String = when (item) {
        is ContinueItem.Talk -> listOfNotNull(
            item.entry.name,
            TalksContent.timeLeft(item.episode, item.entry.positionMs)?.removeSuffix(" left")
        ).joinToString(" · ")
        is ContinueItem.Video -> listOfNotNull(
            item.video.title,
            TalksContent.videoLeft(item.video)?.removeSuffix(" left")
        ).joinToString(" · ")
    }

    private fun continueProgress(item: ContinueItem): RowProgress = when (item) {
        is ContinueItem.Talk -> RowProgress(TalksContent.fraction(item.episode, item.entry.positionMs).coerceAtLeast(.03f))
        is ContinueItem.Video -> RowProgress(item.video.fraction.coerceAtLeast(.03f))
    }

    /** The bani that is due now ("Rehras Sahib"), or how many there are when the clock is unset. */
    private fun nitnemSummary(state: AppState, playlist: com.schulzcode.y2player.core.model.PlaylistSummary): String? {
        val tracks = state.library.playlistTrackIds[playlist.id].orEmpty().mapNotNull(state.library.byId::get)
        if (tracks.isEmpty()) return null
        if (ClockPresentation.isSet(state.clock)) {
            val word = NitnemSchedule.due(ClockPresentation.fields(state.clock).hour)
            tracks.firstOrNull { it.title.contains(word, ignoreCase = true) }?.let { return it.title }
        }
        return if (tracks.size == 1) "1 bani" else "${tracks.size} banis"
    }

    /** The playlist named Nitnem, which Home offers by name. */
    internal fun nitnemPlaylist(state: AppState): com.schulzcode.y2player.core.model.PlaylistSummary? =
        state.library.playlists.firstOrNull { it.name.trim().equals("Nitnem", ignoreCase = true) }
            ?: state.library.playlists.firstOrNull { it.name.contains("nitnem", ignoreCase = true) }

    private fun audiobookMenuSubtitle(state: AppState): String? {
        val series = audiobookEntries(state)
        val active = series.count { it.inProgress }
        return when {
            series.isEmpty() -> "Nothing yet"
            active > 0 -> "$active in progress"
            else -> null
        }
    }

    const val MUSIC_FOLDERS_KEY = "music_folders"

    /** Music Folders on Home: straight into the SD card, or the list of volumes when there are two. */
    fun musicFoldersScreen(state: AppState): Screen.Folders {
        val volumes = state.device.storageVolumes.filter { it.available }
        val withMusic = volumes.filter { volume -> state.library.musicTracks.any { it.volumeId == volume.id } }
        val only = withMusic.singleOrNull() ?: withMusic.firstOrNull { it.id == "sdcard" }
        return if (only == null) Screen.Folders() else Screen.Folders(only.id, "")
    }

    private fun videoRows(state: AppState, screen: Screen.Videos): List<ScreenRow> =
        state.videos.listing(screen.path).orEmpty().map { item ->
            when (item.kind) {
                VideoItemKind.VOLUME -> ScreenRow.Action(
                    item.title, videoCountLabel(item.videoCount), VIDEO_VOLUME_KEY + item.path
                )
                VideoItemKind.FOLDER -> ScreenRow.Action(
                    item.title, item.detail ?: videoCountLabel(item.videoCount), VIDEO_FOLDER_KEY + item.path,
                    artworkPath = item.artworkPath,
                    posterPaths = item.posterPaths
                )
                VideoItemKind.CONTINUE -> {
                    val progress = state.videos.inProgress.firstOrNull { it.path == item.path }
                    // The film itself leads; the label says why it is first.
                    ScreenRow.Action(
                        item.title,
                        listOfNotNull("Continue watching", progress?.let(TalksContent::videoLeft)).joinToString(" · "),
                        VIDEO_CONTINUE_KEY + item.path,
                        progress = progress?.let { RowProgress(it.fraction.coerceAtLeast(.03f)) } ?: RowProgress(.03f),
                        artworkPath = item.artworkPath,
                        posterPaths = item.posterPaths
                    )
                }
                VideoItemKind.VIDEO -> {
                    val progress = state.videos.inProgress.firstOrNull { it.path == item.path }
                    ScreenRow.Action(
                        item.title,
                        when {
                            item.resumeMs > 0 -> progress?.let(TalksContent::videoLeft) ?: "Resume at ${TimeFormat.duration(item.resumeMs)}"
                            item.watched -> "Watched"
                            else -> videoSizeLabel(item.sizeBytes)
                        },
                        VIDEO_KEY + item.path,
                        progress = when {
                            item.resumeMs > 0 -> RowProgress((progress?.fraction ?: 0f).coerceAtLeast(.03f))
                            item.watched -> RowProgress.PLAYED
                            else -> null
                        }
                    )
                }
            }
        }

    private fun videoOptionRows(state: AppState, screen: Screen.VideoOptions): List<ScreenRow> {
        if (screen.isFolder) return listOf(
            ScreenRow.Action("Mark All Unwatched", "Every video in this folder", VIDEO_OPT_UNWATCHED),
            ScreenRow.Action("Mark All Watched", null, VIDEO_OPT_WATCHED)
        )
        val item = state.videos.listings.values.asSequence().flatten()
            .firstOrNull { it.kind == VideoItemKind.VIDEO && it.path == screen.path }
        val started = (item?.resumeMs ?: 0L) > 0L
        val watched = item?.watched == true
        return listOfNotNull(
            ScreenRow.Action(if (started) "Resume" else "Play", null, VIDEO_OPT_PLAY),
            if (started) ScreenRow.Action("Play from Start", null, VIDEO_OPT_RESTART) else null,
            if (started || watched) ScreenRow.Action("Mark as Unwatched", "Forgets where you were", VIDEO_OPT_UNWATCHED)
            else ScreenRow.Action("Mark as Watched", null, VIDEO_OPT_WATCHED)
        )
    }

    const val VIDEO_OPT_PLAY = "video_opt_play"
    const val VIDEO_OPT_RESTART = "video_opt_restart"
    const val VIDEO_OPT_WATCHED = "video_opt_watched"
    const val VIDEO_OPT_UNWATCHED = "video_opt_unwatched"

    private fun videoCountLabel(count: Int): String = if (count == 1) "1 video" else "$count videos"

    private fun videoSizeLabel(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "${bytes / 1_000_000L} MB"
        else -> "${(bytes / 1_000L).coerceAtLeast(1L)} KB"
    }

    internal fun searchResults(state: AppState): List<DeviceSearchResult> {
        val screen = state.currentScreen as? Screen.Search ?: return emptyList()
        return DeviceSearch.find(state.library, screen.query)
    }

    private fun searchRows(state: AppState): List<ScreenRow> = searchResults(state).mapIndexed { index, result ->
        when (result) {
            is DeviceSearchResult.TrackResult -> ScreenRow.Action(
                result.title,
                if (result.track.isAudiobookChapter) "Talk · ${result.track.displayArtist}"
                else "Song · ${result.track.displayArtist}",
                "search_track:$index",
                result.track.id
            )
            is DeviceSearchResult.AlbumResult -> ScreenRow.Action(
                result.title, "Album · ${result.artist}", "search_album:$index", result.artworkTrackId
            )
            is DeviceSearchResult.ArtistResult -> ScreenRow.Action(
                result.title, "Artist", "search_artist:$index", result.artworkTrackId
            )
            is DeviceSearchResult.PlaylistResult -> ScreenRow.Action(
                result.title, "Playlist · ${trackCountLabel(result.trackCount)}", "search_playlist:$index"
            )
            is DeviceSearchResult.AudiobookResult -> ScreenRow.Action(
                result.title, "Talks & Podcasts", "search_audiobook:$index", result.artworkTrackId
            )
        }
    }

    fun selectedCollection(state: AppState): Pair<String, List<Long>>? {
        val row = rows(state).getOrNull(state.selectedIndex)
        val tracks = state.library.musicTracks
        val organization = state.library.index.organization
        fun album(scope: LibraryScope, group: ScreenRow.Group): Pair<String, List<Track>>? =
            (group.target as? ScreenGroupTarget.Album)?.key?.let { key ->
                group.title to organization.albumTracks(scope, key)
            }
        fun artist(scope: LibraryScope, group: ScreenRow.Group): Pair<String, List<Track>> {
            val name = (group.target as? ScreenGroupTarget.Artist)?.name ?: group.key
            return group.title to organization.artistTracks(scope, name, state.preferences.albumSortOrder)
        }
        val selection = when (val screen = state.currentScreen) {
            Screen.Albums -> (row as? ScreenRow.Group)?.let { album(LibraryScope.All, it) }
            Screen.Artists -> (row as? ScreenRow.Group)?.let { artist(LibraryScope.All, it) }
            is Screen.ArtistAlbums -> when {
                (row as? ScreenRow.Action)?.key == "artist_all_songs" ->
                    screen.artist to organization.artistTracks(
                        LibraryScope.All, screen.artist, state.preferences.albumSortOrder
                    )
                row is ScreenRow.Group -> album(LibraryScope.All, row)
                else -> null
            }
            is Screen.FacetAlbums -> (row as? ScreenRow.Group)?.let { album(screen.scope, it) }
            is Screen.FacetArtists -> (row as? ScreenRow.Group)?.let { artist(screen.scope, it) }
            is Screen.FacetArtistAlbums -> when {
                (row as? ScreenRow.Action)?.key == "facet_artist_all_tracks" ->
                    screen.artist to organization.artistTracks(
                        screen.scope, screen.artist, state.preferences.albumSortOrder
                    )
                row is ScreenRow.Group -> album(screen.scope, row)
                else -> null
            }
            Screen.Playlists -> (row as? ScreenRow.Action)?.key?.takeIf { it.startsWith("playlist:") }
                ?.substringAfter(':')?.toLongOrNull()?.let { playlistId ->
                    row.title to state.library.playlistTrackIds[playlistId].orEmpty().mapNotNull(state.library.byId::get)
                }
            is Screen.Folders -> (row as? ScreenRow.Folder)?.let { folder ->
                val prefix = folder.relativePath.trim('/').let { if (it.isEmpty()) "" else "$it/" }
                folder.title to albumSorted(tracks.filter {
                    it.volumeId == folder.volumeId && it.relativePath.startsWith(prefix)
                })
            }
            else -> null
        } ?: return null
        val ids = selection.second.asSequence().filter { it.available && !it.decodeFailed }.map(Track::id).toList()
        return if (ids.isEmpty()) null else selection.first to ids
    }

    private fun musicRows(state: AppState): List<ScreenRow> = listOf(
        // In the order they are used: Folders most of all.
        ScreenRow.Action("Folders", null, "folders"),
        ScreenRow.Action("Shuffle All", trackCountLabel(state.library.musicTracks.size), "shuffle_all"),
        ScreenRow.Action("Playlists", state.library.playlists.size.takeIf { it > 0 }?.toString(), "playlists"),
        ScreenRow.Action("Categories", categoriesSummary(state), "genres"),
        ScreenRow.Action("Songs", null, "songs"),
        ScreenRow.Action("Ragis & Speakers", HomePane.artistCount(state).takeIf { it > 0 }?.toString(), "artists"),
        ScreenRow.Action("Albums", null, "albums"),
        ScreenRow.Action("Recently Played", null, "recent")
    )

    /** "Nitnem, Kirtan, Katha…": the categories with the most tracks. */
    private fun categoriesSummary(state: AppState): String? {
        val genres = state.library.index.organization.genres().filter { it.key != "" && it.label != "Unknown Genre" }
        if (genres.isEmpty()) return null
        val names = genres.sortedByDescending { it.tracks.size }.map { it.label }
        val shown = ArrayList<String>(3)
        for (name in names) {
            if (shown.size == 3 || (shown + name).joinToString(", ").length > 20) break
            shown += name
        }
        if (shown.isEmpty()) return "${genres.size}"
        return shown.joinToString(", ") + if (shown.size < names.size) "…" else ""
    }

    private fun audiobookRows(state: AppState): List<ScreenRow> =
        audiobookEntries(state).map {
            ScreenRow.Action(
                it.name,
                audiobookSubtitle(it),
                AUDIOBOOK_KEY_PREFIX + it.folderKey,
                it.chapterIds.firstOrNull()
            )
        }

    private fun audiobookName(state: AppState, folderKey: String): String? =
        audiobookEntry(state, folderKey)?.name

    private fun audiobookOptionRows(state: AppState, folderKey: String): List<ScreenRow> {
        val entry = audiobookEntry(state, folderKey)
            ?: return listOf(ScreenRow.Group("Series unavailable", "Its files are no longer on the card", "missing"))
        return buildList {
            add(ScreenRow.Action("Episodes", TalksContent.seriesDetail(entry), "$AUDIOBOOK_CHAPTERS_KEY$folderKey"))
            add(ScreenRow.Action("Start Over", if (entry.chapterCount > 1) "Episode 1" else "From the beginning",
                "$AUDIOBOOK_RESTART_KEY$folderKey"))
            if (entry.inProgress) {
                add(ScreenRow.Action("Mark All Unplayed", "Forget where you are", "$AUDIOBOOK_CLEAR_KEY$folderKey"))
            }
        }
    }

    private fun audiobookChapterRows(state: AppState, folderKey: String): List<ScreenRow> {
        val entry = audiobookEntry(state, folderKey) ?: return emptyList()
        val byId = state.library.byId
        return entry.chapterIds.mapNotNull { id -> byId[id]?.let(ScreenRow::TrackRow) }
    }

    internal fun audiobookEntry(state: AppState, folderKey: String): AudiobookEntry? =
        audiobookEntries(state).firstOrNull { it.folderKey == folderKey }

    @Synchronized
    internal fun audiobookEntries(state: AppState): List<AudiobookEntry> {
        val revision = state.library.revision
        val identity = System.identityHashCode(state.library.index)
        val progress = state.library.audiobookProgress
        if (cachedBookRevision == revision && cachedBookIdentity == identity && cachedBookProgress === progress) return cachedBooks
        val entries = buildAudiobookEntries(state)
        cachedBookRevision = revision
        cachedBookIdentity = identity
        cachedBookProgress = progress
        cachedBooks = entries
        return entries
    }

    private var cachedBookRevision = Long.MIN_VALUE
    private var cachedBookIdentity = 0
    private var cachedBookProgress: Map<String, com.schulzcode.y2player.core.model.AudiobookProgress>? = null
    private var cachedBooks: List<AudiobookEntry> = emptyList()

    private fun buildAudiobookEntries(state: AppState): List<AudiobookEntry> {
        val groups = LinkedHashMap<String, MutableList<Track>>()
        state.library.availableTracks.forEach { track ->
            val key = track.audiobookFolderKey ?: return@forEach
            groups.getOrPut(key) { ArrayList() }.add(track)
        }
        if (groups.isEmpty()) return emptyList()
        val saved = state.library.audiobookProgress
        val entries = ArrayList<AudiobookEntry>(groups.size)
        groups.forEach { (key, tracks) ->
            val ordered = audiobookSorted(tracks)
            var total = 0L
            var everyDurationKnown = true
            ordered.forEach { chapter ->
                if (chapter.durationMs > 0) total += chapter.durationMs else everyDurationKnown = false
            }
            val progress = saved[key]
            val index = if (progress == null) -1 else ordered.indexOfFirst { it.id == progress.trackId }
            var listened = 0L
            if (progress != null && index >= 0) {
                for (position in 0 until index) listened += ordered[position].durationMs
                listened += progress.positionMs
            }
            // A book directly under AUDIOBOOKS keys on its own filename, so it is named
            // from the tag. A folder holding one chapter is still named after the folder.
            val lastSegment = key.substringAfterLast('/').substringAfter('|')
            val looseFile = ordered.size == 1 &&
                ordered.first().relativePath.substringAfterLast('/') == lastSegment
            val firstArtist = ordered.first().artist?.trim()?.takeIf { it.isNotEmpty() }
            val sharedArtist = firstArtist?.takeIf { artist -> ordered.all { it.artist?.trim() == artist } }
                // "Basics of Sikhi" inside the Basics of Sikhi folder says nothing new.
                ?.takeUnless { artist -> key.split('/', '|').any { it.equals(artist, ignoreCase = true) } }
            entries.add(
                AudiobookEntry(
                    folderKey = key,
                    name = if (looseFile) ordered.first().title else lastSegment,
                    chapterIds = ordered.map { it.id },
                    chapterCount = ordered.size,
                    totalDurationMs = if (everyDurationKnown) total else 0L,
                    chapterNumber = if (index >= 0) index + 1 else null,
                    startIndex = index.coerceAtLeast(0),
                    listenedMs = listened,
                    updatedAt = if (index >= 0) progress?.updatedAt ?: 0L else 0L,
                    positionMs = if (index >= 0) progress?.positionMs ?: 0L else 0L,
                    artist = sharedArtist?.takeUnless { it.equals(lastSegment, ignoreCase = true) }
                )
            )
        }
        entries.sortWith(
            compareByDescending<AudiobookEntry> { it.updatedAt }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
        return entries
    }

    internal fun audiobookSubtitle(entry: AudiobookEntry): String {
        val chapters = if (entry.chapterCount == 1) "1 chapter" else "${entry.chapterCount} chapters"
        if (entry.chapterNumber == null) {
            val duration = audiobookDuration(entry.totalDurationMs)
            return if (duration == null) chapters else "$chapters · $duration"
        }
        val position = "Episode ${entry.chapterNumber} of ${entry.chapterCount}"
        if (entry.totalDurationMs <= 0L) return position
        val percent = (entry.listenedMs * 100 / entry.totalDurationMs).coerceIn(0L, 100L)
        return "$position · $percent%"
    }

    private fun audiobookDuration(totalMs: Long): String? {
        if (totalMs <= 0L) return null
        val minutes = totalMs / 60_000
        return if (minutes >= 60) "${minutes / 60} h" else "$minutes min"
    }

    private fun playlistRows(state: AppState): List<ScreenRow> = buildList {
        state.library.playlists.forEach { playlist ->
            val storedIds = state.library.playlistTrackIds[playlist.id]
            var visibleCount = 0
            var artworkTrackId: Long? = null
            storedIds.orEmpty().forEach { id ->
                if (state.library.byId[id] != null) {
                    visibleCount += 1
                    if (artworkTrackId == null) artworkTrackId = id
                }
            }
            add(ScreenRow.Action(
                playlist.name,
                trackCountLabel(if (storedIds == null) playlist.trackCount else visibleCount),
                "playlist:${playlist.id}",
                artworkTrackId
            ))
        }
        add(ScreenRow.Action("New Playlist", null, "playlist_create"))
    }

    private fun playlistTrackRows(state: AppState, screen: Screen.PlaylistTracks): List<ScreenRow> = buildList {
        val tracks = state.library.playlistTrackIds[screen.playlistId].orEmpty()
            .mapNotNull(state.library.byId::get)
        if (tracks.isEmpty()) add(ScreenRow.Group("Playlist is empty", "Hold Centre on a track, then Add to Playlist", "playlist_empty"))
        else if (screen.name.contains("nitnem", ignoreCase = true)) tracks.forEach { add(ScreenRow.TrackRow(it)) }
        else collectionRows(tracks).forEach(::add)
        add(ScreenRow.Action("Delete Playlist", "Removes this playlist, not its music", "playlist_delete:${screen.playlistId}"))
    }

    private fun favoriteRows(state: AppState): List<ScreenRow> =
        collectionRows(state.library.index.organization.sortTracks(
            state.library.favoriteMusicTracks, state.preferences.sortOrder
        ))

    // A one-track collection is already in order, so Shuffle would be a no-op row.
    private fun collectionRows(tracks: List<Track>): List<ScreenRow> {
        if (tracks.isEmpty()) return emptyList()
        if (tracks.size < 2) return tracks.map(ScreenRow::TrackRow)
        return buildList {
            add(shuffleCollectionRow(tracks.size))
            tracks.forEach { add(ScreenRow.TrackRow(it)) }
        }
    }

    private fun shuffleCollectionRow(trackCount: Int): ScreenRow =
        ScreenRow.Action("Shuffle", trackCountLabel(trackCount), COLLECTION_SHUFFLE_KEY)

    private fun trackOptionRows(state: AppState, screen: Screen.TrackOptions): List<ScreenRow> {
        val trackId = screen.trackId
        val track = state.library.byId[trackId] ?: return listOf(ScreenRow.Group("Track unavailable", null, "missing"))
        return buildList {
            if (!screen.fromNowPlaying) {
                add(ScreenRow.Action("Play Next", null, "track_next:$trackId"))
                add(ScreenRow.Action("Add to Up Next", null, "track_queue:$trackId"))
            }
            add(ScreenRow.Action("Favourite", onOff(track.favorite), "track_favorite:$trackId"))
            add(ScreenRow.Action("Add to Playlist", null, "track_playlist:$trackId"))
            screen.sourcePlaylistId?.let { playlistId ->
                add(ScreenRow.Action("Remove from Playlist", null, "track_remove_playlist:$playlistId:$trackId"))
            }
            if (!track.isAudiobookChapter) {
                add(ScreenRow.Action("Go to ${track.primaryArtist}", null, "track_artist:$trackId"))
                add(ScreenRow.Action("Go to Album", track.displayAlbum, "track_album:$trackId"))
            }
            episodeMarkRows(state, track).forEach(::add)
            if (!screen.fromNowPlaying) add(ScreenRow.Action("Select Several", null, "track_multi:$trackId"))
            add(ScreenRow.Action("Details", null, "track_details:$trackId"))
        }
    }

    /**
     * An episode can be marked unplayed, for the ones that played on while you
     * slept, or played. Episodes count as played up to the one in progress, so
     * marking moves that point.
     */
    private fun episodeMarkRows(state: AppState, track: Track): List<ScreenRow> {
        val folderKey = track.audiobookFolderKey ?: return emptyList()
        val entry = audiobookEntry(state, folderKey) ?: return emptyList()
        val index = entry.chapterIds.indexOf(track.id).takeIf { it >= 0 } ?: return emptyList()
        val heard = entry.inProgress && index <= entry.startIndex
        return listOfNotNull(
            if (heard) ScreenRow.Action("Mark as Unplayed", "This and every later episode", "$EPISODE_UNPLAYED_KEY${track.id}") else null,
            if (index < entry.chapterCount - 1 && !(entry.inProgress && index < entry.startIndex))
                ScreenRow.Action("Mark as Played", "This and every earlier episode", "$EPISODE_PLAYED_KEY${track.id}") else null
        )
    }

    const val EPISODE_UNPLAYED_KEY = "episode_unplayed:"
    const val EPISODE_PLAYED_KEY = "episode_played:"

    private fun trackBrowseRows(state: AppState, trackId: Long): List<ScreenRow> {
        val track = state.library.byId[trackId]
            ?: return listOf(ScreenRow.Group("Track unavailable", null, "missing"))
        return listOf(
            ScreenRow.Action("Go to Album", track.displayAlbum, "track_album:$trackId"),
            ScreenRow.Action("Go to Artist", track.primaryArtist, "track_artist:$trackId")
        )
    }

    private fun trackDetailRows(state: AppState, trackId: Long): List<ScreenRow> {
        val track = state.library.byId[trackId]
            ?: return listOf(ScreenRow.Group("Track unavailable", null, "missing"))
        return listOf(
            ScreenRow.Group("Artist", track.displayArtist, "info_artist"),
            ScreenRow.Group("Album", track.displayAlbum, "info_album"),
            ScreenRow.Group("Format", formatTrack(track), "info_format"),
            ScreenRow.Group("Location", track.relativePath, "info_path")
        )
    }

    private fun addToPlaylistRows(state: AppState): List<ScreenRow> = buildList {
        add(ScreenRow.Action("New Playlist", null, "playlist_create_and_add"))
        state.library.playlists.forEach { playlist ->
            val artworkTrackId = state.library.playlistTrackIds[playlist.id].orEmpty().firstOrNull { id ->
                state.library.byId[id] != null
            }
            add(ScreenRow.Action(
                playlist.name,
                trackCountLabel(playlist.trackCount),
                "playlist_add:${playlist.id}",
                artworkTrackId
            ))
        }
    }

    private fun queueRows(state: AppState): List<ScreenRow> = buildList {
        if (state.playback.queue.isEmpty()) return@buildList
        add(ScreenRow.Action("Queue Actions", queueSummaryLabel(state), "queue_actions"))
        state.playback.queue.forEach { entry ->
            add(state.library.byId[entry.trackId]?.let { ScreenRow.TrackRow(it, entry) }
                ?: ScreenRow.Group("Unavailable track", "Not in the current library", "queue_missing:${entry.id}"))
        }
    }

    private fun collectionOptionRows(screen: Screen.CollectionOptions): List<ScreenRow> = listOf(
        ScreenRow.Action("Play Next", trackCountLabel(screen.trackIds.size), "collection_next"),
        ScreenRow.Action("Add to Up Next", "After added songs", "collection_up_next"),
        ScreenRow.Action("Add Shuffled", "Random order in Up Next", "collection_up_next_shuffled")
    )

    private fun multiSelectRows(state: AppState, screen: Screen.MultiSelect): List<ScreenRow> =
        screen.trackIds.mapIndexedNotNull { index, trackId ->
            state.library.byId[trackId]?.let {
                ScreenRow.TrackRow(it, selectionIndex = index, selected = index in screen.selectedIndices)
            }
        }

    private fun queueOptionRows(state: AppState, entryId: Long): List<ScreenRow> {
        val index = state.playback.queue.indexOfFirst { it.id == entryId }
        val entry = state.playback.queue.getOrNull(index) ?: return emptyList()
        val upcoming = entry.id != state.playback.currentQueueEntryId
        return buildList {
            add(ScreenRow.Action("Play Now", "Start this song", "queue_play:$entryId"))
            if (upcoming) {
                add(ScreenRow.Action("Play Next", "Front of Up Next", "queue_next:$entryId"))
                if (state.playback.queue.drop(1).count { it.origin == entry.origin } > 1) {
                    add(ScreenRow.Action("Move", "Wheel to position", "queue_move:$entryId"))
                }
            }
            add(ScreenRow.Action("Remove", "Remove this occurrence", "queue_remove:$entryId"))
        }
    }

    private fun queueMoveRows(state: AppState, screen: Screen.QueueMove): List<ScreenRow> {
        val source = state.playback.queue.indexOfFirst { it.id == screen.entryId }
        if (source < 0) return emptyList()
        val reordered = state.playback.queue.toMutableList()
        val entry = reordered.removeAt(source)
        reordered.add(screen.targetIndex.coerceIn(0, reordered.size), entry)
        return reordered.map { item ->
            state.library.byId[item.trackId]?.let { ScreenRow.TrackRow(it, item) }
                ?: ScreenRow.Group("Unavailable track", "Not in the current library", "queue_missing:${item.id}")
        }
    }

    private fun queueManagementRows(state: AppState): List<ScreenRow> = buildList {
        if (state.playback.queue.any { it.origin == QueueOrigin.UP_NEXT }) {
            add(ScreenRow.Action("Clear Up Next", upNextLabel(state), "queue_clear_up_next"))
        }
        if (state.playback.queue.size > 1) {
            add(ScreenRow.Action("Clear After Current", remainingLabel(state), "queue_clear_remaining"))
        }
        if (state.playback.queue.isNotEmpty()) {
            add(ScreenRow.Action("Stop & Clear", trackCountLabel(state.playback.queue.size), "queue_clear"))
        }
    }

    private fun queueSummaryLabel(state: AppState): String {
        if (state.playback.queue.isEmpty()) return "Queue is empty"
        val remaining = (state.playback.queue.size - 1).coerceAtLeast(0)
        val added = state.playback.queue.count { it.origin == QueueOrigin.UP_NEXT }
        return when {
            remaining == 0 -> "Current song only"
            added == 0 -> "$remaining remaining"
            else -> "$added Up Next · $remaining remaining"
        }
    }

    /** Hold Centre on Now Playing: only what the four tiles do not already do. */
    private fun nowPlayingOptionsRows(state: AppState): List<ScreenRow> {
        val track = state.playback.currentTrackId?.let(state.library.byId::get)
        val queue = ScreenRow.Action("Queue", "${upNextLabel(state)} · Next: ${playingNextLabel(state)}", "queue")
        if (track == null) return listOf(queue)
        return buildList {
            val folderKey = track.audiobookFolderKey
            if (folderKey != null) add(ScreenRow.Action("Episodes", track.displayAlbum, "np_audiobook_chapters:$folderKey"))
            add(ScreenRow.Action("Enhance", enhanceSummary(state), ENHANCE_KEY))
            add(queue)
            add(ScreenRow.Action("Add to Playlist", null, "np_playlist:${track.id}"))
            if (folderKey == null) {
                add(ScreenRow.Action("Go to ${track.primaryArtist}", null, "track_artist:${track.id}"))
                add(ScreenRow.Action("Go to Album", track.displayAlbum, "track_album:${track.id}"))
            }
            add(ScreenRow.Action("Details", null, "np_track_details:${track.id}"))
        }
    }

    /**
     * Now Playing's four tiles, after a first row that stands for "no tile": the
     * wheel changes volume there, and Centre steps down into the tiles.
     */
    internal fun nowPlayingTiles(state: AppState): List<ScreenRow> {
        val track = state.playback.currentTrackId?.let(state.library.byId::get)
        val series = track?.audiobookFolderKey?.let { audiobookEntry(state, it) }
        return listOf(
            ScreenRow.Action("Volume", null, NP_VOLUME_KEY),
            if (series != null && series.chapterCount > 1) {
                val number = series.chapterIds.indexOf(track.id).takeIf { it >= 0 }?.plus(1)
                ScreenRow.Action("Episodes", number?.let { "$it of ${series.chapterCount}" } ?: "${series.chapterCount}",
                    "np_audiobook_chapters:${series.folderKey}")
            } else {
                ScreenRow.Action("Shuffle", onOff(state.playback.shuffleEnabled), "shuffle")
            },
            ScreenRow.Action("Repeat", when (state.playback.repeatMode) {
                RepeatMode.OFF -> "Off"
                RepeatMode.ALL -> "All"
                RepeatMode.ONE -> "One"
            }, "repeat"),
            ScreenRow.Action("Sleep", sleepTileValue(state), "sleep_timer"),
            ScreenRow.Action("Favourite", if (track?.favorite == true) "Yes" else "No",
                if (track != null) "np_favorite:${track.id}" else "np_favorite:")
        )
    }

    private fun sleepTileValue(state: AppState): String = when (state.playback.sleepTimerMode) {
        SleepTimerMode.OFF -> "Off"
        SleepTimerMode.MINUTES -> state.playback.sleepTimerRemainingMs?.let { SleepTimerPresentation.compact(it) } ?: "On"
        SleepTimerMode.END_TRACK -> "End of track"
        SleepTimerMode.END_ALBUM -> "End of album"
        SleepTimerMode.END_QUEUE -> "End of queue"
    }

    // Presets rather than every minute from 1 to 60: a short list the wheel crosses
    // in a flick, like the timer lengths on an iPhone.
    private fun sleepTimerRows(state: AppState): List<ScreenRow> = buildList {
        val playback = state.playback
        val audiobook = playback.currentTrackId?.let(state.library.byId::get)?.isAudiobookChapter == true
        // The running choice says so; a running countdown shows the time left instead.
        fun addChoice(title: String, key: String, active: Boolean, running: String? = null) {
            add(ScreenRow.Action(title, if (active) running ?: "Active" else null, key))
        }
        // While a countdown runs, the likeliest wish leads: a little longer.
        if (playback.sleepTimerMode == SleepTimerMode.MINUTES) {
            add(ScreenRow.Action(
                "Add 15 minutes",
                playback.sleepTimerRemainingMs?.let { "${SleepTimerPresentation.countdown(it)} left" },
                SLEEP_ADD_KEY
            ))
        }
        addChoice("Off", "sleep_timer_off", false)
        addChoice(
            if (audiobook) "End of this episode" else "End of this track",
            "sleep_timer_end_track", playback.sleepTimerMode == SleepTimerMode.END_TRACK
        )
        SleepTimerPresentation.PRESET_MINUTES.forEach { minutes ->
            val active = playback.sleepTimerMode == SleepTimerMode.MINUTES &&
                playback.sleepTimerConfiguredMinutes == minutes
            addChoice(SleepTimerPresentation.durationLabel(minutes), "sleep_timer_minutes:$minutes", active, "Running")
        }
    }

    /** One line of help under a screen's title, for screens whose rows need it. */
    fun note(state: AppState): String? = when (state.currentScreen) {
        Screen.SleepTimer -> if (state.playback.sleepTimerMode == SleepTimerMode.OFF)
            "Fades out over 45 seconds, then pauses." else null
        Screen.Bedtime -> "Warm, softer colours from the start time until you wake."
        Screen.WakeTime -> "Normal colours return at this time."
        // How far through the series, on its own line above the episodes.
        is Screen.AudiobookChapters -> audiobookEntry(state, (state.currentScreen as Screen.AudiobookChapters).folderKey)
            ?.takeIf { it.chapterCount > 1 }
            ?.let { if (it.inProgress) "${it.startIndex} of ${it.chapterCount} played" else "${it.chapterCount} episodes, none played yet" }
        else -> null
    }

    fun sleepTimerSelectionIndex(state: AppState): Int = when (state.playback.sleepTimerMode) {
        SleepTimerMode.OFF -> 0
        SleepTimerMode.END_TRACK -> 1
        SleepTimerMode.END_ALBUM, SleepTimerMode.END_QUEUE -> 0
        // A running countdown opens on Add 15 minutes.
        SleepTimerMode.MINUTES -> 0
    }

    const val SLEEP_ADD_KEY = "sleep_timer_add"

    private fun playingNextLabel(state: AppState): String {
        val playback = state.playback
        if (playback.queue.isEmpty()) return "Queue is empty"
        if (playback.repeatMode == RepeatMode.ONE) {
            return state.library.byId[playback.currentTrackId]?.title?.let { "$it · repeat one" } ?: "Current track repeats"
        }
        val nextId = playback.nextTrackId ?: playback.queue.getOrNull(1)?.trackId
        return when {
            nextId == null -> "End of queue"
            else -> state.library.byId[nextId]?.title ?: "Unavailable track"
        }
    }

    private fun upNextLabel(state: AppState): String {
        val count = state.playback.queue.count { it.origin == QueueOrigin.UP_NEXT }
        return if (count == 0) "No added songs" else "$count added"
    }

    private fun remainingLabel(state: AppState): String {
        val count = (state.playback.queue.size - 1).coerceAtLeast(0)
        return if (count == 0) "Nothing after current" else "$count remaining"
    }

    private fun audioRows(state: AppState): List<ScreenRow> = listOf(
        ScreenRow.Action("Enhance", enhanceSummary(state), ENHANCE_KEY),
        ScreenRow.Action("Sound Effects", soundEffectsSummary(state), "sound_effects"),
        ScreenRow.Action("Volume", volumeRowSummary(state), "playback_volume"),
        ScreenRow.Action("Transitions", transitionsSummary(state), "playback_transitions"),
        ScreenRow.Action("Interruptions", interruptionsSummary(state), "playback_interruptions"),
        ScreenRow.Action("Output", outputRowSummary(state), "output")
    )

    private fun outputRowSummary(state: AppState): String {
        val profile = state.preferences.audioQualityMode.label
        val route = outputSummary(state)
        return if (route.isBlank()) profile else "$profile · $route"
    }

    private fun transitionsSummary(state: AppState): String = when {
        state.preferences.crossfadeMs > 0 -> {
            val suffix = if (state.preferences.crossfadeMode == CrossfadeMode.WHILE_SHUFFLING) " while shuffling" else ""
            "${state.preferences.crossfadeMs / 1000}s crossfade$suffix"
        }
        state.preferences.gaplessEnabled -> "Gapless"
        else -> "Off"
    }

    private fun volumeRowSummary(state: AppState): String =
        "${volumeModeLabel(state)} · ReplayGain ${state.preferences.replayGainMode.label.lowercase(Locale.US)}"

    private fun soundEffectsSummary(state: AppState): String {
        val effects = state.playback.audioEffects
        if (!effects.available) return "Unavailable on this firmware"
        if (state.preferences.audioQualityMode == AudioQualityMode.DIRECT_DAC) return "Disabled by Direct profile"
        if (!state.preferences.audioEffectsEnabled) return "Off"
        return equalizerSummary(state)
    }

    private fun interruptionsSummary(state: AppState): String = buildList {
        add(if (state.preferences.duckOnFocusLoss) "Lowers for alerts" else "Pauses for alerts")
        if (state.preferences.resumePosition) add("resume on start")
    }.joinToString(" · ")

    // Each row says what it is set to now, in words, rather than what is inside it.
    private fun settingsRows(state: AppState): List<ScreenRow> = listOf(
        ScreenRow.Action("Sound", soundSummary(state), "audio"),
        ScreenRow.Action("Display", "Brightness ${state.display.brightnessPercent}% · screen off after " +
            timeoutLabel(state.display.screenTimeoutMs).lowercase(Locale.US), "display"),
        ScreenRow.Action("Bedtime", bedtimeSummary(state), "bedtime"),
        ScreenRow.Action("Controls", controlsSummary(state), "controls"),
        ScreenRow.Action("Date & Time", dateTimeSummary(state), "date_time"),
        ScreenRow.Action("Bluetooth", bluetoothSummary(state), "bluetooth"),
        ScreenRow.Action("Library & Backup", "${trackCountLabel(state.library.availableTracks.size)} · playlists, backup", "library_settings"),
        ScreenRow.Action("System", "Diagnostics, reset and about", "system")
    )

    const val ENHANCE_KEY = "enhance"

    /** What Enhance is doing now: it treats kirtan and katha differently. */
    fun enhanceSummary(state: AppState): String {
        if (!state.preferences.enhanceSound) return "Off"
        val playing = state.playback.currentTrackId?.let(state.library.byId::get)
        return when {
            playing == null -> "On"
            playing.isAudiobookChapter -> "On · tuned for speech"
            else -> "On · tuned for music"
        }
    }

    private fun soundSummary(state: AppState): String {
        val volume = when (state.preferences.volumeMode) {
            VolumeMode.SYSTEM -> "System volume"
            VolumeMode.PERCEPTUAL -> "Volume ${VolumeCurve.percentForLevel(state.preferences.volumeLevel)}%"
        }
        val effects = state.playback.audioEffects
        val equaliser = when {
            !effects.available || !state.preferences.audioEffectsEnabled -> "equaliser off"
            else -> "equaliser ${equalizerSummary(state).lowercase(Locale.US)}"
        }
        return "$volume · $equaliser"
    }

    internal fun bedtimeSummary(state: AppState): String {
        val start = state.preferences.bedtimeStartHour
        if (start < 0) return "Off"
        val range = "${ClockPresentation.hourLabel(start, state.preferences.use24HourClock)} to " +
            ClockPresentation.hourLabel(state.preferences.bedtimeEndHour, state.preferences.use24HourClock)
        return if (ClockPresentation.isSet(state.clock)) "Warm colours, $range" else "$range · set the clock first"
    }

    private fun bedtimeRows(state: AppState): List<ScreenRow> = BEDTIME_STARTS.map { hour ->
        val title = if (hour < 0) "Off" else "From ${ClockPresentation.hourLabel(hour, state.preferences.use24HourClock)}"
        ScreenRow.Action(title, if (hour == state.preferences.bedtimeStartHour) "Active" else null, "$BEDTIME_KEY$hour")
    } + ScreenRow.Action(
        "Wake Time",
        ClockPresentation.hourLabel(state.preferences.bedtimeEndHour, state.preferences.use24HourClock),
        WAKE_TIME_KEY
    )

    private fun wakeRows(state: AppState): List<ScreenRow> = com.schulzcode.y2player.settings.AppPreferences.WAKE_LEVELS.map { hour ->
        ScreenRow.Action(
            ClockPresentation.hourLabel(hour, state.preferences.use24HourClock),
            if (hour == state.preferences.bedtimeEndHour) "Active" else null,
            "$WAKE_KEY$hour"
        )
    }

    const val BEDTIME_KEY = "bedtime:"
    const val WAKE_TIME_KEY = "wake_time"
    const val WAKE_KEY = "wake:"
    val BEDTIME_STARTS = listOf(-1, 20, 21, 22, 23)

    /** Bedtime's warm colours apply from its start hour until 6 am, and only once the clock is set. */
    fun isBedtime(state: AppState): Boolean {
        val start = state.preferences.bedtimeStartHour
        if (start < 0 || !ClockPresentation.isSet(state.clock)) return false
        val hour = ClockPresentation.fields(state.clock).hour
        return hour >= start || hour < state.preferences.bedtimeEndHour
    }


    private fun interfaceRows(state: AppState): List<ScreenRow> = listOf(
        ScreenRow.Action("Display", "${state.display.brightnessPercent}% · ${timeoutLabel(state.display.screenTimeoutMs)}", "display"),
        ScreenRow.Action("Controls", controlsSummary(state), "controls"),
        ScreenRow.Action(
            "FM Radio",
            if (state.preferences.showFmRadio) "Shown on main menu" else "Hidden from main menu",
            "show_fm_radio"
        )
    )

    private fun librarySettingsRows(state: AppState): List<ScreenRow> = listOf(
        ScreenRow.Action("Storage & Scan", scanSubtitle(state), "storage"),
        ScreenRow.Action("Backup & Restore", "Favourites, playlists and settings, on the card", "backup_restore"),
        ScreenRow.Action("Sorting", sortingSummary(state), "sort"),
        ScreenRow.Action("Listening History", historySummaryLabel(state), "playback_history"),
        ScreenRow.Action("Import Playlists", "Find M3U/M3U8 files on music storage", "playlist_import_m3u"),
        ScreenRow.Action("Export Playlists", "Write M3U files to Y2Player/Playlists", "playlist_export_m3u")
    )

    private fun systemRows(state: AppState): List<ScreenRow> = listOf(
        ScreenRow.Action("Android Settings", "System configuration and recovery", "android_settings"),
        ScreenRow.Action("Diagnostics", "Logs and engine capabilities", "diagnostics"),
        ScreenRow.Action("Reset", if (state.safeMode) "SAFE MODE · queue and library" else "Queue, library and safe mode", "reset"),
        ScreenRow.Action("About", "Y2 Player ${BuildConfig.VERSION_NAME}", "about")
    )

    private fun backupRestoreRows(state: AppState): List<ScreenRow> = listOf(
        ScreenRow.Action(
            "Export Backup",
            state.backup.exportedPath ?: "Y2Player/Backups/${com.schulzcode.y2player.backup.BackupFormat.FILE_NAME}",
            "backup_export"
        ),
        ScreenRow.Action("Import Backup", "Validate and restore the card backup", "backup_import")
    )

    private fun resetRows(state: AppState): List<ScreenRow> = listOf(
        ScreenRow.Action("Clear Queue", "Removes every track from the queue", "reset_queue"),
        ScreenRow.Action("Reset Library", "Deletes the index and rescans from the card", "reset_library"),
        ScreenRow.Action(
            if (state.safeMode) "Exit Safe Mode" else "Enter Safe Mode",
            if (state.safeMode) "Normal startup on next launch" else "Skips auto scan and restore",
            "reset_safe_mode"
        )
    )


    private fun playbackTransitionRows(state: AppState): List<ScreenRow> = buildList {
        val crossfadeOn = state.preferences.crossfadeMs > 0
        val shuffleOnly = state.preferences.crossfadeMode == CrossfadeMode.WHILE_SHUFFLING
        val direct = state.preferences.audioQualityMode == AudioQualityMode.DIRECT_DAC
        add(ScreenRow.Action("Crossfade", millisecondsLabel(state.preferences.crossfadeMs), "crossfade"))
        if (crossfadeOn) {
            if (direct) {
                add(ScreenRow.Group("Crossfade Mode", "Disabled by Direct profile", "crossfade_mode_unavailable"))
            } else {
                add(ScreenRow.Action("Crossfade Mode", state.preferences.crossfadeMode.label, "crossfade_mode"))
            }
        }
        add(
            ScreenRow.Action(
                "Gapless Playback",
                when {
                    !crossfadeOn -> onOff(state.preferences.gaplessEnabled)
                    shuffleOnly -> "Crossfade takes priority while shuffling"
                    else -> "Crossfade takes priority"
                },
                "gapless"
            )
        )
        add(ScreenRow.Action("Resume Fade", millisecondsLabel(state.preferences.pauseResumeFadeMs), "pause_fade"))
    }

    private fun playbackSeekingRows(state: AppState): List<ScreenRow> = listOf(
        ScreenRow.Action("Seek Step", secondsLabel(state.preferences.seekStepMs), "seek_step"),
        ScreenRow.Action("Seek Step When Held", secondsLabel(state.preferences.longSeekStepMs), "long_seek_step"),
        ScreenRow.Action("Previous Button", thresholdLabel(state.preferences.previousRestartThresholdMs), "previous_threshold"),
        ScreenRow.Action(
            "Seek When Locked",
            if (state.preferences.seekWhenLocked) "Left and right seek" else "Left and right change tracks",
            "seek_when_locked"
        )
    )

    private fun playbackVolumeRows(state: AppState): List<ScreenRow> = listOf(
        ScreenRow.Action("Volume Control", volumeModeLabel(state), "volume_mode"),
        ScreenRow.Action("ReplayGain", state.preferences.replayGainMode.label, "replay_gain"),
        ScreenRow.Action("Balance", AudioBalance.label(state.preferences.balance), "balance")
    )

    private fun playbackInterruptionRows(state: AppState): List<ScreenRow> = listOf(
        ScreenRow.Action("Resume Position", onOff(state.preferences.resumePosition), "resume_position"),
        ScreenRow.Action("Focus Ducking", if (state.preferences.duckOnFocusLoss) "Lower volume" else "Pause", "duck_focus"),
        ScreenRow.Action(
            "Pause When Unplugged",
            if (state.preferences.pauseOnDisconnect) "On" else "Off · plays on the speaker",
            "pause_disconnect"
        )
    )

    private fun soundEffectRows(state: AppState): List<ScreenRow> {
        val effects = state.playback.audioEffects
        val direct = state.preferences.audioQualityMode == AudioQualityMode.DIRECT_DAC
        return buildList {
            if (!effects.available) {
                add(ScreenRow.Group(
                    "Sound Effects",
                    effects.errorMessage ?: "No compatible Android audio effects found",
                    "effects_unavailable"
                ))
                return@buildList
            }
            add(ScreenRow.Action(
                "Audio Effects",
                if (direct) "Disabled by Direct profile" else onOff(state.preferences.audioEffectsEnabled),
                "effects_toggle"
            ))
            add(ScreenRow.Action("Equaliser", equalizerSummary(state), "equalizer"))
            if (effects.bassBoostSupported) {
                add(ScreenRow.Action("Bass Boost", percent(state.preferences.bassStrength, 1000), "bass"))
            }
            if (effects.loudnessSupported) {
                add(ScreenRow.Action("Loudness", gainLabel(state.preferences.loudnessGainMb), "loudness"))
            }
            effects.errorMessage?.let { add(ScreenRow.Group("Last effect error", it, "effects_error")) }
        }
    }

    private fun equalizerRows(state: AppState): List<ScreenRow> {
        val effects = state.playback.audioEffects
        if (!effects.available || !effects.equalizerSupported) {
            return listOf(ScreenRow.Group(
                "Equaliser",
                effects.errorMessage ?: "Unsupported by this firmware",
                "eq_unsupported"
            ))
        }
        val preset = if (state.preferences.equalizerPreset < 0) "Custom"
        else effects.presetNames.getOrNull(state.preferences.equalizerPreset)
            ?: "Preset ${state.preferences.equalizerPreset + 1}"
        return listOf(
            ScreenRow.Action("Preset", preset, "eq_preset"),
            ScreenRow.Action("Custom Bands", "${effects.bandFrequenciesHz.size} bands · choose values", "eq_bands")
        )
    }

    private fun equalizerPresetRows(state: AppState): List<ScreenRow> {
        val selected = state.preferences.equalizerPreset
        return buildList {
            add(ScreenRow.Action("Custom", if (selected < 0) "Current" else null, "eq_preset:-1"))
            state.playback.audioEffects.presetNames.forEachIndexed { index, name ->
                add(ScreenRow.Action(name, if (selected == index) "Current" else null, "eq_preset:$index"))
            }
        }
    }


    private fun outputInformationRows(state: AppState): List<ScreenRow> {
        val effects = state.playback.audioEffects
        val dac = state.playback.dac
        val direct = state.preferences.audioQualityMode == AudioQualityMode.DIRECT_DAC
        return buildList {
            add(ScreenRow.Action("Audio Profile", state.preferences.audioQualityMode.label, "audio_quality"))
            add(ScreenRow.Group(
                "CS43131 DAC",
                when {
                    !dac.detected -> "Not detected through firmware"
                    dac.hiFiRequestAccepted -> "Detected · Hi-Fi route requested"
                    direct -> "Detected · standard AudioTrack fallback"
                    else -> "Detected"
                },
                "dac_status"
            ))
            val route = buildList {
                dac.outputSampleRate?.let { add("${it / 1000.0} kHz") }
                dac.outputFormat?.let(::add)
            }.ifEmpty { listOf("Firmware route not reported") }.joinToString(" · ")
            add(ScreenRow.Group("Android Output", route, "dac_output"))
            dac.limitation?.let { add(ScreenRow.Group("Firmware Limit", it, "dac_limit")) }
            if (direct) {
                add(ScreenRow.Group("Direct profile", "Effects, crossfade and fades are off in this profile", "dac_bypass"))
            }
            if (effects.available) {
                effects.errorMessage?.let { add(ScreenRow.Group("Last effect error", it, "effects_error")) }
            }
        }
    }

    private fun equalizerSummary(state: AppState): String {
        val effects = state.playback.audioEffects
        if (!effects.available || !effects.equalizerSupported) return "Unsupported"
        return if (state.preferences.equalizerPreset < 0) "Custom"
        else effects.presetNames.getOrNull(state.preferences.equalizerPreset)
            ?: "Preset ${state.preferences.equalizerPreset + 1}"
    }


    private fun outputSummary(state: AppState): String {
        val dac = state.playback.dac
        return buildList {
            dac.outputSampleRate?.let { add("${it / 1000.0} kHz") }
            dac.outputFormat?.let(::add)
        }.ifEmpty { listOf(if (dac.detected) "DAC detected" else "Firmware route not reported") }
            .joinToString(" · ")
    }

    private fun equalizerBandRows(state: AppState): List<ScreenRow> {
        val effects = state.playback.audioEffects
        return effects.bandFrequenciesHz.mapIndexed { index, frequency ->
            val level = effects.bandLevelsMb.getOrNull(index) ?: state.preferences.equalizerBandLevelsMb.getOrNull(index) ?: 0
            ScreenRow.Action(formatFrequency(frequency), signedDb(level), "eq_band:$index")
        }
    }

    internal fun equalizerBandLevels(state: AppState): List<Int> {
        val effects = state.playback.audioEffects
        if (effects.bandMinMb > effects.bandMaxMb) return emptyList()
        return buildSet {
            add(effects.bandMinMb)
            add(effects.bandMaxMb)
            add(0.coerceIn(effects.bandMinMb, effects.bandMaxMb))
            var level = Math.floorDiv(effects.bandMinMb, EQ_BAND_STEP_MB) * EQ_BAND_STEP_MB
            if (level < effects.bandMinMb) level += EQ_BAND_STEP_MB
            while (level <= effects.bandMaxMb) {
                add(level)
                level += EQ_BAND_STEP_MB
            }
        }.sorted()
    }

    internal fun equalizerBandLevel(state: AppState, bandIndex: Int): Int {
        val effects = state.playback.audioEffects
        return state.preferences.equalizerBandLevelsMb.getOrNull(bandIndex)
            ?.takeIf { state.preferences.equalizerPreset < 0 }
            ?: effects.bandLevelsMb.getOrNull(bandIndex)
            ?: 0
    }

    private fun equalizerBandLevelRows(state: AppState, bandIndex: Int): List<ScreenRow> {
        val current = equalizerBandLevel(state, bandIndex)
        return equalizerBandLevels(state).map { level ->
            ScreenRow.Action(signedDb(level), if (level == current) "Current" else null, "eq_level:$level")
        }
    }

    private fun sortOrderRows(state: AppState): List<ScreenRow> = listOf(
        ScreenRow.Action("Tracks", sortLabel(state.preferences.sortOrder), "sort_tracks"),
        ScreenRow.Action("Albums", albumSortLabel(state.preferences.albumSortOrder), "sort_albums")
    )

    private fun trackSortRows(): List<ScreenRow> = TrackSortOrder.entries.map { order ->
        ScreenRow.Action(sortLabel(order), null, "track_sort:${order.storageId}")
    }

    private fun albumSortRows(): List<ScreenRow> = AlbumSortOrder.entries.map { order ->
        ScreenRow.Action(albumSortLabel(order), null, "album_sort:${order.storageId}")
    }

    private fun yearSortRows(): List<ScreenRow> = YearSortOrder.entries.map { order ->
        ScreenRow.Action(yearSortLabel(order), null, "year_sort:${order.storageId}")
    }

    private fun bluetoothRows(state: AppState): List<ScreenRow> = buildList {
        add(ScreenRow.Action("Bluetooth", when (state.bluetooth.adapterMode) {
            BluetoothAdapterMode.UNSUPPORTED -> "Unavailable"
            BluetoothAdapterMode.OFF -> "Off"
            BluetoothAdapterMode.TURNING_ON -> "Turning on…"
            BluetoothAdapterMode.ON -> "On"
            BluetoothAdapterMode.TURNING_OFF -> "Turning off…"
        }, "bt_toggle"))
        if (state.bluetooth.adapterMode == BluetoothAdapterMode.ON) {
            add(ScreenRow.Action("Refresh Audio Service", "Reacquire A2DP backend", "bt_refresh"))
            add(ScreenRow.Action(if (state.bluetooth.isDiscovering) "Stop Scan" else "Scan for Devices", state.bluetooth.pendingOperation ?: "Audio devices only", "bt_scan"))
            state.bluetooth.devices.forEach { device ->
                val maskedIdentity = "…${device.address.takeLast(5)}"
                add(ScreenRow.Action("${device.name} · $maskedIdentity", bluetoothDeviceStatus(device), "bt_device:${device.address}"))
            }
            if (state.bluetooth.devices.isEmpty() && !state.bluetooth.isDiscovering) add(ScreenRow.Group("No audio devices", "Select Scan for Devices", "bt_empty"))
        }
    }

    private fun bluetoothDeviceStatus(device: BluetoothDeviceEntry): String = when {
        device.audioStreaming -> "Streaming audio"
        device.linkState == BluetoothLinkState.CONNECTED -> "Connected for audio"
        device.linkState == BluetoothLinkState.CONNECTING -> "Connecting…"
        device.linkState == BluetoothLinkState.DISCONNECTING -> "Disconnecting…"
        device.bonding -> "Pairing…"
        device.bonded -> "Paired"
        else -> "Nearby · not paired"
    }

    private fun bluetoothDeviceRows(state: AppState, screen: Screen.BluetoothDevice): List<ScreenRow> {
        val device = state.bluetooth.devices.firstOrNull { it.address == screen.address }
            ?: return listOf(ScreenRow.Group("Device unavailable", "It is no longer in range", "bt_device_missing"))
        val connected = device.audioStreaming || device.linkState == BluetoothLinkState.CONNECTED
        return buildList {
            add(ScreenRow.Group(device.name, bluetoothDeviceStatus(device), "bt_device_status"))
            add(
                ScreenRow.Action(
                    when {
                        connected -> "Disconnect"
                        device.bonded -> "Connect"
                        else -> "Pair"
                    },
                    null,
                    "bt_device_activate:${device.address}"
                )
            )
            if (device.bonded || device.bonding) {
                add(ScreenRow.Action("Forget Device", "Removes the pairing", "bt_device_forget:${device.address}"))
            }
        }
    }

    private fun confirmActionRows(state: AppState, screen: Screen.ConfirmAction): List<ScreenRow> {
        val prompt = ConfirmPrompts.of(state, screen.key)
        return listOf(
            ScreenRow.Group(prompt.title, prompt.detail, "confirm_prompt"),
            ScreenRow.Action("Cancel", null, CONFIRM_CANCEL_KEY),
            ScreenRow.Action(prompt.confirmLabel, null, CONFIRM_OK_KEY)
        )
    }

    private fun displayRows(state: AppState): List<ScreenRow> = buildList {
        add(ScreenRow.Action("Brightness", "${state.display.brightnessPercent}%", "brightness"))
        add(ScreenRow.Action("Screen Off After", timeoutLabel(state.display.screenTimeoutMs), "timeout"))
        add(ScreenRow.Action("Look", state.skins.name(state.preferences.skinId), "theme"))
        add(ScreenRow.Action("Bedtime", bedtimeSummary(state), "bedtime"))
        add(ScreenRow.Action("Stay On While Playing", onOff(state.preferences.keepScreenOnWhilePlaying), "keep_screen_on"))
    }

    private fun skinRows(state: AppState): List<ScreenRow> = buildList {
        state.skins.available.forEach { skin ->
            add(ScreenRow.Action(skin.name, if (state.preferences.skinId == skin.id) "Selected" else skin.author, "skin:${skin.id}"))
        }
        add(ScreenRow.Action("Reload Skins", "Read Y2Player/Skins again", "reload_skins"))
        state.skins.errors.forEachIndexed { index, error ->
            add(ScreenRow.Action("Skin could not load", error, "skin_error:$index"))
        }
    }

    private fun controlsRows(state: AppState): List<ScreenRow> = buildList {
        add(ScreenRow.Action("Seeking", seekingSummary(state), "playback_seeking"))
        add(ScreenRow.Action("FM Radio on Home", if (state.preferences.showFmRadio) "Shown" else "Hidden", "show_fm_radio"))
        if (state.device.hapticsAvailable) {
            add(ScreenRow.Action("Haptics", hapticLabel(state.preferences.hapticLevel), "haptics"))
        }
        add(
            ScreenRow.Action(
                "Wrap Lists",
                if (state.preferences.wrapLists) "On · the end of a list leads back to the top"
                else "Off · lists stop at the first and last item",
                "wrap_lists"
            )
        )
        add(
            ScreenRow.Action(
                "Wheel With Screen Off",
                if (state.preferences.localKeysWhileScreenOff) "On · it can change things in a pocket"
                else "Off · only the headphone remote works",
                "screen_off_keys"
            )
        )
        add(
            ScreenRow.Action(
                "Click Sounds",
                if (state.preferences.uiSoundEffectsEnabled) "On · Android clicks as you scroll" else "Off",
                "ui_sounds"
            )
        )
    }

    private fun seekingSummary(state: AppState): String =
        "${state.preferences.seekStepMs / 1000} s per press · ${state.preferences.longSeekStepMs / 1000} s when held"

    private fun controlsSummary(state: AppState): String = buildList {
        add("Skip ${state.preferences.seekStepMs / 1000} s")
        add(if (state.preferences.wrapLists) "lists wrap" else "lists stop at the ends")
        if (state.device.hapticsAvailable && state.preferences.hapticLevel != HapticLevel.OFF) add("haptics")
        if (state.preferences.localKeysWhileScreenOff) add("wheel works with screen off")
    }.joinToString(" · ").replaceFirstChar { it.titlecase(Locale.US) }

    private fun hapticLabel(level: HapticLevel): String =
        if (level == HapticLevel.OFF) "Off" else "${level.label} · ${level.durationMs} ms pulse"

    private fun balanceRows(state: AppState): List<ScreenRow> = AudioBalance.LEVELS.map { value ->
        ScreenRow.Action(
            AudioBalance.label(value),
            if (value == state.preferences.balance) "Selected" else null,
            "balance:$value"
        )
    }

    private fun brightnessRows(state: AppState): List<ScreenRow> = BRIGHTNESS_LEVELS.map { percent ->
        ScreenRow.Action("$percent%", null, "brightness:$percent")
    }

    private fun timeoutRows(state: AppState): List<ScreenRow> = TIMEOUT_LEVELS.map { timeout ->
        ScreenRow.Action(timeoutLabel(timeout), null, "timeout:$timeout")
    }

    private fun dateTimeSummary(state: AppState): String =
        if (!ClockPresentation.isSet(state.clock)) "Not set · history dates are wrong until it is"
        else "${ClockPresentation.time(state.clock, state.preferences.use24HourClock)} · ${ClockPresentation.zoneName(state.clock.timeZoneId)}"

    private fun dateTimeRows(state: AppState): List<ScreenRow> = buildList {
        val clock = state.clock
        val set = ClockPresentation.isSet(clock)
        // One line per row, value on the right, as on the design.
        add(ScreenRow.Action("Time", if (set) ClockPresentation.time(clock, state.preferences.use24HourClock) else "Not set", "clock_set_time"))
        add(ScreenRow.Action("Date", if (set) ClockPresentation.date(clock) else "Not set", "clock_set_date"))
        add(ScreenRow.Action(
            "Time Zone",
            "${ClockPresentation.zoneName(clock.timeZoneId).substringBefore(',')} · ${ClockPresentation.offsetLabel(clock.timeZoneId, clock.nowMs)}",
            "clock_time_zone"
        ))
        add(ScreenRow.Action("24-Hour Time", onOff(state.preferences.use24HourClock), "clock_24_hour"))
        add(ScreenRow.Action(
            "Clock in Status Bar",
            if (state.preferences.showStatusClock) "Shown" else "Hidden",
            "clock_status_bar"
        ))
        val bedtime = state.preferences.bedtimeStartHour
        add(ScreenRow.Action(
            "Bedtime",
            if (bedtime < 0) "Off" else "${ClockPresentation.hourLabel(bedtime, state.preferences.use24HourClock)} – " +
                ClockPresentation.hourLabel(state.preferences.bedtimeEndHour, state.preferences.use24HourClock),
            "bedtime"
        ))
    }

    private fun clockHourRows(state: AppState): List<ScreenRow> = (0..23).map { hour ->
        ScreenRow.Action(ClockPresentation.hourLabel(hour, state.preferences.use24HourClock), null, "$CLOCK_HOUR_KEY$hour")
    }

    private fun clockMinuteRows(state: AppState, hour: Int): List<ScreenRow> = (0..59).map { minute ->
        ScreenRow.Action(ClockPresentation.time(hour, minute, state.preferences.use24HourClock), null, "$CLOCK_MINUTE_KEY$minute")
    }

    private fun clockYearRows(): List<ScreenRow> = (ClockPresentation.FIRST_YEAR..ClockPresentation.LAST_YEAR).map { year ->
        ScreenRow.Action(year.toString(), null, "$CLOCK_YEAR_KEY$year")
    }

    private fun clockMonthRows(): List<ScreenRow> = (1..12).map { month ->
        ScreenRow.Action(ClockPresentation.monthName(month), null, "$CLOCK_MONTH_KEY$month")
    }

    private fun clockDayRows(year: Int, month: Int): List<ScreenRow> =
        (1..ClockPresentation.daysInMonth(year, month)).map { day ->
            ScreenRow.Action(ClockPresentation.dayLabel(year, month, day), null, "$CLOCK_DAY_KEY$day")
        }

    internal fun timeZoneIds(state: AppState): List<String> {
        val listed = ClockPresentation.ZONES.map { it.id }
        val current = state.clock.timeZoneId
        return if (current in listed) listed else listOf(current) + listed
    }

    private fun timeZoneRows(state: AppState): List<ScreenRow> = timeZoneIds(state).map { id ->
        ScreenRow.Action(
            ClockPresentation.zoneName(id),
            ClockPresentation.offsetLabel(id, state.clock.nowMs) + if (id == state.clock.timeZoneId) " · Current" else "",
            "$CLOCK_ZONE_KEY$id"
        )
    }

    private fun storageRows(state: AppState): List<ScreenRow> = buildList {
        state.device.storageVolumes.forEach { volume ->
            val count = state.library.tracks.count { it.volumeId == volume.id && it.available }
            val subtitle = if (volume.available) "${trackCountLabel(count)} · ${formatBytes(volume.freeBytes)} free of ${formatBytes(volume.totalBytes)}" else "Not mounted · metadata retained"
            add(ScreenRow.Action(volume.label, subtitle, "storage:${volume.id}"))
        }
        add(ScreenRow.Action("Rescan Library", scanSubtitle(state), "rescan"))
    }

    private fun playbackHistoryRows(state: AppState): List<ScreenRow> = listOf(
        ScreenRow.Group("Sessions", historySummaryLabel(state), "history_summary"),
        ScreenRow.Group("File", "Y2Player/playback-history.ndjson", "history_location"),
        ScreenRow.Group(
            "Copy it from the card",
            "One JSON object per line; unknown fields are safe to ignore",
            "history_hint"
        ),
        ScreenRow.Action("Clear History", "Deletes every recorded session", "history_clear")
    )

    // Null until the history file has been read. Reporting zero before that told
    // the user their history was empty when it was only unread.
    private fun historySummaryLabel(state: AppState): String? {
        val sessions = state.diagnostics.historySessions ?: return null
        if (sessions <= 0) return "No sessions recorded yet"
        val count = if (sessions == 1) "1 session" else "$sessions sessions"
        return "$count · ${formatBytes(state.diagnostics.historyBytes)}"
    }

    private fun diagnosticsRows(state: AppState): List<ScreenRow> = buildList {
        add(ScreenRow.Action("Export Diagnostics", state.diagnostics.exportedPath ?: "Save logs to internal storage", "diag_export"))
        add(ScreenRow.Action("Clear Diagnostic Log", "Start a fresh diagnostic capture", "diag_clear"))
        add(ScreenRow.Action(
            "Verbose Diagnostics",
            if (state.preferences.verboseDiagnostics) "On · detailed event log" else "Off · errors only",
            "diag_verbose"
        ))
        add(ScreenRow.Group("USB", state.diagnostics.usb.summary(), "diag_usb"))
        PlaybackCapabilities.lines().forEach { line ->
            add(ScreenRow.Group(line.label, line.value, "capability:${line.label}"))
        }
    }

    private fun aboutRows(state: AppState): List<ScreenRow> = listOf(
        ScreenRow.Group("Y2 Player", "Version ${BuildConfig.VERSION_NAME} · with Videos", "about_app"),
        ScreenRow.Group("Device", state.device.deviceModel, "about_device"),
        ScreenRow.Group("System", state.device.androidVersion, "about_android"),
        ScreenRow.Group("Firmware", state.device.firmwareBuild, "about_firmware"),
        ScreenRow.Group("Library", "${state.library.availableTracks.size} available · ${state.library.totalIndexedTracks} indexed", "about_library"),
        ScreenRow.Group("Uptime", formatUptime(state.device.uptimeMs), "about_uptime"),
        ScreenRow.Group("Build", "Local-only · API 19 · no network", "about_build")
    )

    private fun albumRows(state: AppState): List<ScreenRow> = albumRows(
        state.library.index.organization.albums(LibraryScope.All, state.preferences.albumSortOrder)
    )

    private fun albumRows(albums: List<AlbumEntry>): List<ScreenRow> {
        val duplicateTitles = albums.groupingBy { it.key.title }.eachCount().filterValues { it > 1 }.keys
        return albums.map { album ->
        ScreenRow.Group(
            album.title,
            albumSubtitle(album.year, album.albumArtist),
            if (album.key.title in duplicateTitles) "${album.title}\u0000${album.albumArtist}" else album.title,
            ScreenGroupTarget.Album(album.key),
            album.tracks.firstOrNull()?.id
        )
        }
    }

    private fun albumDetailRows(
        tracks: List<Track>,
        album: String,
        albumArtist: String? = null
    ): List<ScreenRow> {
        return collectionRows(
            albumSorted(tracks.filter {
                it.displayAlbum == album &&
                    (albumArtist == null || it.albumArtistName.equals(albumArtist, ignoreCase = true))
            })
        )
    }

    private fun artistAlbumRows(state: AppState, artist: String): List<ScreenRow> {
        val organization = state.library.index.organization
        val byArtist = organization.tracks(LibraryScope.All).filter { it.isCreditedTo(artist) }
        val albums = organization.artistAlbums(LibraryScope.All, artist, state.preferences.albumSortOrder)
        return buildList {
            add(ScreenRow.Action(
                "All Songs",
                trackCountLabel(byArtist.size),
                "artist_all_songs",
                byArtist.firstOrNull()?.id
            ))
            albums.forEach { album ->
                val detail = if (!album.albumArtist.equals(artist, ignoreCase = true)) {
                    album.albumArtist
                } else trackCountLabel(album.tracks.size)
                add(ScreenRow.Group(
                    album.title,
                    albumSubtitle(album.year, detail),
                    album.title,
                    ScreenGroupTarget.Album(album.key),
                    album.tracks.firstOrNull()?.id
                ))
            }
        }
    }

    private fun artistRows(state: AppState): List<ScreenRow> = state.library.index.organization
        .artists(LibraryScope.All).map { artist ->
            ScreenRow.Group(
                artist.name,
                trackCountLabel(artist.tracks.size),
                artist.name,
                ScreenGroupTarget.Artist(artist.name),
                artist.tracks.firstOrNull()?.id
            )
        }

    private fun artistDetailRows(
        state: AppState,
        artist: String
    ): List<ScreenRow> = collectionRows(state.library.index.organization.artistTracks(
        LibraryScope.All, artist, state.preferences.albumSortOrder
    ))

    private fun genreRows(state: AppState): List<ScreenRow> = state.library.index.organization.genres().map { genre ->
        val scope = LibraryScope.Genre(genre.key, genre.label)
        ScreenRow.Group(
            genre.label,
            trackCountLabel(genre.tracks.size),
            genre.key,
            ScreenGroupTarget.Scope(scope),
            genre.tracks.firstOrNull()?.id
        )
    }

    private fun yearRows(state: AppState): List<ScreenRow> = state.library.index.organization
        .years(state.preferences.yearSortOrder).map { year ->
            val scope = LibraryScope.Year(year.year)
            ScreenRow.Group(
                scope.label,
                trackCountLabel(year.tracks.size),
                scope.label,
                ScreenGroupTarget.Scope(scope),
                year.tracks.firstOrNull()?.id
            )
        }

    private fun facetMenuRows(scope: LibraryScope): List<ScreenRow> = listOf(
        ScreenRow.Action("All Tracks", "Every track in ${scope.label}", "facet_all_tracks"),
        ScreenRow.Action("Artists", "Browse artists in ${scope.label}", "facet_artists"),
        ScreenRow.Action("Albums", "Browse albums in ${scope.label}", "facet_albums")
    )

    private fun facetArtistRows(state: AppState, scope: LibraryScope): List<ScreenRow> =
        state.library.index.organization.artists(scope).map { artist ->
            ScreenRow.Group(
                artist.name,
                trackCountLabel(artist.tracks.size),
                artist.name,
                ScreenGroupTarget.Artist(artist.name),
                artist.tracks.firstOrNull()?.id
            )
        }

    private fun facetAlbumRows(state: AppState, scope: LibraryScope): List<ScreenRow> = albumRows(
        state.library.index.organization.albums(scope, state.preferences.albumSortOrder)
    )

    private fun facetArtistAlbumRows(state: AppState, scope: LibraryScope, artist: String): List<ScreenRow> {
        val organization = state.library.index.organization
        val tracks = organization.tracks(scope).filter { it.isCreditedTo(artist) }
        val albums = organization.artistAlbums(scope, artist, state.preferences.albumSortOrder)
        return buildList {
            add(ScreenRow.Action(
                "All Tracks",
                trackCountLabel(tracks.size),
                "facet_artist_all_tracks",
                tracks.firstOrNull()?.id
            ))
            albums.forEach { album ->
                val detail = if (!album.albumArtist.equals(artist, ignoreCase = true)) {
                    album.albumArtist
                } else trackCountLabel(album.tracks.size)
                add(ScreenRow.Group(
                    album.title,
                    albumSubtitle(album.year, detail),
                    album.title,
                    ScreenGroupTarget.Album(album.key),
                    album.tracks.firstOrNull()?.id
                ))
            }
        }
    }

    private fun facetTrackRows(state: AppState, screen: Screen.FacetTracks): List<ScreenRow> {
        val organization = state.library.index.organization
        val tracks = when {
            screen.album != null -> organization.albumTracks(screen.scope, screen.album)
            screen.artist != null -> organization.artistTracks(
                screen.scope, screen.artist, state.preferences.albumSortOrder
            )
            else -> organization.sortTracks(organization.tracks(screen.scope), state.preferences.sortOrder)
        }
        return collectionRows(tracks)
    }

    internal fun albumEntry(state: AppState, scope: LibraryScope, key: AlbumKey): AlbumEntry? =
        state.library.index.organization.albums(scope, AlbumSortOrder.TITLE).firstOrNull { it.key == key }

    private fun folderRows(tracks: List<Track>, screen: Screen.Folders): List<ScreenRow> {
        if (screen.volumeId == null) return tracks.groupBy { it.volumeId }.keys.sorted().map { ScreenRow.Folder(volumeName(it), it, "") }
        val prefix = screen.relativePath.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val matching = tracks.filter { it.volumeId == screen.volumeId && it.relativePath.startsWith(prefix) }
        val folders = linkedSetOf<String>()
        val directTracks = mutableListOf<Track>()
        matching.forEach { track ->
            val remainder = track.relativePath.removePrefix(prefix)
            val separator = remainder.indexOf('/')
            if (separator >= 0) folders += remainder.substring(0, separator) else directTracks += track
        }
        return buildList {
            folders.sortedWith(::compareText).forEach { add(ScreenRow.Folder(it, screen.volumeId, (prefix + it).trim('/'))) }
            albumSorted(directTracks).forEach { add(ScreenRow.TrackRow(it)) }
        }
    }

    private fun albumSorted(tracks: List<Track>): List<Track> {
        val numbered = tracks.all { it.trackNumber != null }
        return tracks.sortedWith { first, second ->
            compareValues(first.discNumber ?: 0, second.discNumber ?: 0).takeUnless { it == 0 }
                ?: (if (numbered) compareValues(first.trackNumber, second.trackNumber).takeUnless { it == 0 } else null)
                ?: NaturalOrder.compare(first.fileName, second.fileName).takeUnless { it == 0 }
                ?: compareText(first.title, second.title)
        }
    }

    private fun audiobookSorted(tracks: List<Track>): List<Track> {
        val numbered = tracks.all { it.trackNumber != null }
        return tracks.sortedWith { first, second ->
            compareValues(first.discNumber ?: 0, second.discNumber ?: 0).takeUnless { it == 0 }
                ?: NaturalOrder.compare(
                    first.relativePath.substringBeforeLast('/', ""),
                    second.relativePath.substringBeforeLast('/', "")
                ).takeUnless { it == 0 }
                ?: (if (numbered) compareValues(first.trackNumber, second.trackNumber).takeUnless { it == 0 } else null)
                ?: NaturalOrder.compare(first.fileName, second.fileName).takeUnless { it == 0 }
                ?: compareText(first.title, second.title).takeUnless { it == 0 }
                ?: compareText(first.relativePath, second.relativePath).takeUnless { it == 0 }
                ?: compareValues(first.id, second.id)
        }
    }

    private fun compareText(first: String, second: String): Int = String.CASE_INSENSITIVE_ORDER.compare(first, second)
    private fun playbackSummary(state: AppState): String = buildList {
        if (state.playback.shuffleEnabled) add("Shuffle")
        if (state.playback.repeatMode != RepeatMode.OFF) add("Repeat ${state.playback.repeatMode.name.lowercase(Locale.US)}")
        if (state.preferences.crossfadeMs > 0) {
            val suffix = if (state.preferences.crossfadeMode == CrossfadeMode.WHILE_SHUFFLING) " (shuffle)" else ""
            add("${state.preferences.crossfadeMs / 1000}s crossfade$suffix")
        }
        else if (state.preferences.gaplessEnabled) add("Gapless")
    }.ifEmpty { listOf("Standard") }.joinToString(" · ")

    private fun bluetoothSummary(state: AppState): String = when {
        state.bluetooth.adapterMode == BluetoothAdapterMode.UNSUPPORTED -> "Unavailable"
        state.bluetooth.adapterMode == BluetoothAdapterMode.OFF -> "Off"
        state.bluetooth.audioStreaming -> "Streaming"
        state.bluetooth.audioConnected -> state.bluetooth.connectedDeviceName ?: "Audio connected"
        state.bluetooth.adapterMode == BluetoothAdapterMode.ON -> "On · not connected"
        else -> "Changing state…"
    }

    private fun scanSubtitle(state: AppState): String = if (state.library.isScanning) {
        "${state.library.scanProgress.processedFiles} files · ${state.library.scanProgress.currentPath?.substringAfterLast('/') ?: "scanning"}"
    } else "${state.library.availableTracks.size} available tracks"

    private fun formatTrack(track: Track): String = buildList {
        add(AudioCodecLabels.label(track.codec, track.extension))
        add(track.trackNumber?.let { "track $it" } ?: "no track number")
        track.sampleRate?.let { add("${it / 1000.0} kHz") }
        track.bitDepth?.let { add("$it-bit") }
        if (track.durationMs > 0) add(duration(track.durationMs))
        when (AudioCodecSupport.of(track.codec, track.extension)) {
            CodecSupport.UNSUPPORTED -> add(
                if (track.decodeFailed) "playback failed on this device"
                else "no decoder in this build"
            )
            else -> if (track.decodeFailed) add("playback failed on this device")
        }
    }.joinToString(" · ")

    private fun duration(ms: Long): String {
        val seconds = ms / 1000
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun sortLabel(order: TrackSortOrder): String = when (order) {
        TrackSortOrder.TITLE -> "Title"
        TrackSortOrder.ARTIST -> "Artist"
        TrackSortOrder.ALBUM -> "Album"
        TrackSortOrder.YEAR -> "Release Year"
        TrackSortOrder.ADDED -> "Recently Added"
        TrackSortOrder.RECENT -> "File Modified"
    }

    private fun albumSortLabel(order: AlbumSortOrder): String = when (order) {
        AlbumSortOrder.TITLE -> "Title"
        AlbumSortOrder.ARTIST -> "Artist"
        AlbumSortOrder.YEAR_ASCENDING -> "Year · oldest first"
        AlbumSortOrder.YEAR_DESCENDING -> "Year · newest first"
    }

    private fun yearSortLabel(order: YearSortOrder): String = when (order) {
        YearSortOrder.NEWEST_FIRST -> "Newest first"
        YearSortOrder.OLDEST_FIRST -> "Oldest first"
    }

    private fun sortingSummary(state: AppState): String =
        "Tracks: ${sortLabel(state.preferences.sortOrder)} · Albums: ${albumSortLabel(state.preferences.albumSortOrder)}"

    private fun albumSubtitle(year: Int?, detail: String): String =
        "${year?.toString() ?: "Year unknown"} · $detail"

    private fun millisecondsLabel(value: Int): String = if (value <= 0) "Off" else if (value < 1_000) "${value} ms" else "${value / 1_000} seconds"
    private fun secondsLabel(value: Int): String = "${value / 1_000} seconds"
    private fun thresholdLabel(value: Int): String =
        if (value <= 0) "Always goes to the previous track" else "Restarts the track after ${value / 1_000} seconds"
    internal fun sleepTimerSubtitle(state: AppState): String =
        SleepTimerPresentation.label(state.playback.sleepTimerMode, state.playback.sleepTimerRemainingMs)
    private fun volumeModeLabel(state: AppState): String = when (state.preferences.volumeMode) {
        VolumeMode.SYSTEM -> "System volume"
        VolumeMode.PERCEPTUAL -> "Fine steps · ${VolumeCurve.percentForLevel(state.preferences.volumeLevel)}%"
    }

    private fun percent(value: Int, maximum: Int): String = "${(value * 100 / maximum.coerceAtLeast(1)).coerceIn(0, 100)}%"
    private fun gainLabel(millibels: Int): String = if (millibels <= 0) "Off" else "+${millibels / 100.0} dB"
    private fun signedDb(millibels: Int): String = String.format(Locale.US, "%+.1f dB", millibels / 100.0)
    private fun formatFrequency(hz: Int): String = if (hz >= 1000) String.format(Locale.US, "%.1f kHz", hz / 1000.0) else "$hz Hz"

    private fun onOff(value: Boolean): String = if (value) "On" else "Off"

    fun trackCountLabel(count: Int): String = when (count) {
        0 -> "Empty"
        1 -> "1 track"
        else -> String.format(Locale.US, "%,d tracks", count)
    }
    fun volumeName(volumeId: String): String = when (volumeId) { "internal" -> "Internal Storage"; "sdcard" -> "SD Card"; else -> volumeId.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
    fun parentFolder(screen: Screen.Folders): Screen.Folders? {
        if (screen.volumeId == null) return null
        if (screen.relativePath.isBlank()) return Screen.Folders()
        return Screen.Folders(screen.volumeId, File(screen.relativePath).parent.orEmpty().replace('\\', '/'))
    }
    fun timeoutLabel(timeoutMs: Int): String = when (timeoutMs) {
        Int.MAX_VALUE -> "Never"; 15_000 -> "15 seconds"; 30_000 -> "30 seconds"; 60_000 -> "1 minute";
        120_000 -> "2 minutes"; 300_000 -> "5 minutes"; 600_000 -> "10 minutes"
        else -> if (timeoutMs >= 60_000) "${timeoutMs / 60_000} minutes" else "${timeoutMs / 1_000} seconds"
    }
    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) String.format(Locale.US, "%.1f GB", gb) else String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
    }
    private fun formatUptime(milliseconds: Long): String {
        val totalMinutes = milliseconds.coerceAtLeast(0) / 60_000
        return if (totalMinutes >= 60) "${totalMinutes / 60}h ${totalMinutes % 60}m" else "${totalMinutes}m"
    }

    private data class LargeRowsKey(
        val screen: Screen,
        val contentRevision: Long,
        val indexIdentity: Int,
        val sortOrder: TrackSortOrder,
        val albumSortOrder: AlbumSortOrder,
        val yearSortOrder: YearSortOrder,
        val queueFingerprint: Int
    )

    private val cachedRows = LinkedHashMap<LargeRowsKey, List<ScreenRow>>(
        ROW_CACHE_ENTRIES + 1, 0.75f, true
    )

    @Synchronized
    fun clearCachedRows() {
        cachedRows.clear()
        cachedBookRevision = Long.MIN_VALUE
        cachedBookIdentity = 0
        cachedBooks = emptyList()
    }

    private fun isLargeScreen(screen: Screen): Boolean = when (screen) {
        Screen.Songs, Screen.Favorites, Screen.RecentlyPlayed, Screen.Albums, Screen.Artists,
        Screen.Genres, Screen.Years, Screen.Playlists, Screen.Queue, Screen.Audiobooks -> true
        is Screen.Search -> true
        is Screen.AlbumSongs, is Screen.ArtistAlbums, is Screen.ArtistSongs,
        is Screen.FacetMenu, is Screen.FacetArtists, is Screen.FacetAlbums,
        is Screen.FacetArtistAlbums, is Screen.FacetTracks,
        is Screen.Folders, is Screen.PlaylistTracks, is Screen.AudiobookChapters, is Screen.TalkCollection,
        is Screen.MultiSelect, is Screen.QueueMove -> true
        else -> false
    }

    private const val ROW_CACHE_ENTRIES = 4
    private const val EQ_BAND_STEP_MB = 100

    const val COLLECTION_SHUFFLE_KEY = "collection_shuffle"
    const val AUDIOBOOK_KEY_PREFIX = "audiobook:"
    const val VIDEO_KEY = "video:"
    const val VIDEO_CONTINUE_KEY = "video_continue:"
    const val NOW_PLAYING_KEY = "now_playing"
    const val CONTINUE_KEY = "continue"
    const val NP_VOLUME_KEY = "np_volume"
    const val NITNEM_KEY = "nitnem:"
    const val MAIN_SLEEP_TIMER_KEY = "main_sleep_timer"
    const val CLOCK_HOUR_KEY = "clock_hour:"
    const val CLOCK_MINUTE_KEY = "clock_minute:"
    const val CLOCK_YEAR_KEY = "clock_year:"
    const val CLOCK_MONTH_KEY = "clock_month:"
    const val CLOCK_DAY_KEY = "clock_day:"
    const val CLOCK_ZONE_KEY = "clock_zone:"
    const val VIDEO_FOLDER_KEY = "video_folder:"
    const val VIDEO_VOLUME_KEY = "video_volume:"
    const val AUDIOBOOK_CHAPTERS_KEY = "audiobook_chapters:"
    const val AUDIOBOOK_RESTART_KEY = "audiobook_restart:"
    const val AUDIOBOOK_CLEAR_KEY = "audiobook_clear:"
    const val CONFIRM_CANCEL_KEY = "confirm_cancel"
    const val CONFIRM_OK_KEY = "confirm_ok"
    const val CONFIRM_DEFAULT_INDEX = 1

    val BRIGHTNESS_LEVELS = listOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100)
    val TIMEOUT_LEVELS = listOf(15_000, 30_000, 60_000, 120_000, 300_000, 600_000, Int.MAX_VALUE)
}

internal data class AudiobookEntry(
    val folderKey: String,
    val name: String,
    val chapterIds: List<Long>,
    val chapterCount: Int,
    val totalDurationMs: Long,
    val chapterNumber: Int?,
    val startIndex: Int,
    val listenedMs: Long,
    val updatedAt: Long,
    /** Where the current episode was left; 0 when nothing has been played. */
    val positionMs: Long = 0L,
    /** The speaker or podcast when every episode shares one. */
    val artist: String? = null
) {
    val inProgress: Boolean get() = chapterNumber != null
    val currentChapterId: Long? get() = chapterIds.getOrNull(startIndex)
}

internal data class ConfirmPrompt(val title: String, val detail: String, val confirmLabel: String)

internal object ConfirmPrompts {
    const val FORGET_DEVICE = "forget_device:"
    const val CLEAR_AUDIOBOOK = "clear_audiobook:"
    const val CLEAR_QUEUE = "clear_queue"
    const val RESET_LIBRARY = "reset_library"
    const val CLEAR_HISTORY = "clear_history"
    const val CLEAR_DIAGNOSTICS = "clear_diagnostics"
    const val IMPORT_BACKUP = "import_backup"
    const val DELETE_PLAYLIST = "delete_playlist:"

    fun of(state: AppState, key: String): ConfirmPrompt = when {
        key.startsWith(FORGET_DEVICE) -> {
            val address = key.substringAfter(':')
            val name = state.bluetooth.devices.firstOrNull { it.address == address }?.name ?: "this device"
            ConfirmPrompt(
                "Forget $name?",
                "The pairing is removed. You have to pair again to use it.",
                "Forget Device"
            )
        }
        key.startsWith(CLEAR_AUDIOBOOK) -> {
            val folderKey = key.substringAfter(':')
            val name = ScreenContent.audiobookEntry(state, folderKey)?.name ?: "this series"
            ConfirmPrompt(
                "Mark all of $name unplayed?",
                "It starts from the first episode next time. No files are deleted.",
                "Mark Unplayed"
            )
        }
        key == CLEAR_QUEUE -> ConfirmPrompt(
            "Clear the queue?",
            "Playback stops. Your music files and playlists are kept.",
            "Clear Queue"
        )
        key == CLEAR_HISTORY -> ConfirmPrompt(
            "Clear listening history?",
            "Every recorded session is deleted. Your music files are kept.",
            "Clear History"
        )
        key == CLEAR_DIAGNOSTICS -> ConfirmPrompt(
            "Clear diagnostic log?",
            "Current Y2Player logs and rotations are deleted. Exported files and all user data are kept.",
            "Clear Log"
        )
        key == IMPORT_BACKUP -> ConfirmPrompt(
            "Import backup?",
            state.backup.importPreview ?: "Settings and portable user collections will be restored.",
            "Import Backup"
        )
        key.startsWith(DELETE_PLAYLIST) -> {
            val id = key.substringAfter(':').toLongOrNull()
            val name = state.library.playlists.firstOrNull { it.id == id }?.name ?: "this playlist"
            ConfirmPrompt(
                "Delete $name?",
                "The playlist is removed. The music files in it are kept.",
                "Delete Playlist"
            )
        }
        key == RESET_LIBRARY -> ConfirmPrompt(
            "Reset the library?",
            "Playlists, favourites, history and places in talks are deleted. " +
                "Music files are kept and scanning restarts unless Safe Mode is active.",
            "Reset Library"
        )
        else -> ConfirmPrompt("Are you sure?", "This cannot be undone.", "Continue")
    }
}

internal object NaturalOrder {
    fun compare(first: String, second: String): Int = NaturalTextOrder.compare(first, second)
}
