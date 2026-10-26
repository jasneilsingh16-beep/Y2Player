package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.PlaybackSnapshot
import com.schulzcode.y2player.core.model.RepeatMode
import com.schulzcode.y2player.core.model.SleepTimerMode
import com.schulzcode.y2player.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReducerNowPlayingTest {
    private val track = Track(
        id = 1,
        volumeId = "internal",
        absolutePath = "/storage/sdcard0/song.mp3",
        relativePath = "song.mp3",
        title = "Song",
        artist = "Artist",
        album = "Album",
        albumArtist = null,
        trackNumber = 1,
        discNumber = 1,
        durationMs = 60_000,
        fileSize = 123,
        modifiedAt = 1
    )

    private fun nowPlayingState() = AppState(
        screenStack = listOf(ScreenEntry(Screen.NowPlaying)),
        library = LibraryState(tracks = listOf(track)),
        playback = PlaybackSnapshot(currentTrackId = 1, queue = testQueue(1L), currentQueueEntryId = 1L)
    )

    private fun optionsState() = nowPlayingState().let {
        it.copy(screenStack = it.screenStack + ScreenEntry(Screen.NowPlayingOptions))
    }

    private fun optionsReductionFor(key: String): Reduction {
        val base = optionsState()
        val index = ScreenContent.rows(base).indexOfFirst { (it as? ScreenRow.Action)?.key == key }
        assertTrue("row $key must exist", index >= 0)
        val selected = base.copy(screenStack = base.screenStack.dropLast(1) + base.currentEntry.copy(selectedIndex = index))
        return AppReducer.reduce(selected, AppAction.Confirm)
    }

    private fun tileReductionFor(key: String, base: AppState = nowPlayingState()): Reduction {
        val index = ScreenContent.rows(base).indexOfFirst { (it as? ScreenRow.Action)?.key == key }
        assertTrue("tile $key must exist", index > 0)
        val selected = base.copy(screenStack = base.screenStack.dropLast(1) + base.currentEntry.copy(selectedIndex = index))
        return AppReducer.reduce(selected, AppAction.Confirm)
    }

    @Test fun tilesToggleShuffleAndRepeatAndOpenTheSleepTimer() {
        assertEquals(AppEffect.ToggleShuffle, tileReductionFor("shuffle").effects.single())
        assertEquals(AppEffect.CycleRepeat, tileReductionFor("repeat").effects.single())
        val sleepTimer = tileReductionFor("sleep_timer")
        assertEquals(Screen.SleepTimer, sleepTimer.state.currentScreen)
        assertTrue(sleepTimer.effects.isEmpty())
    }

    @Test fun theFavouriteTileTogglesTheCurrentTrack() {
        assertEquals(AppEffect.ToggleFavorite(1), tileReductionFor("np_favorite:1").effects.single())
    }

    @Test fun sleepTimerOffersSevenChoicesAndAppliesTheSelectedValue() {
        val opened = tileReductionFor("sleep_timer").state
        val rows = ScreenContent.rows(opened).filterIsInstance<ScreenRow.Action>()
        assertEquals(listOf("Off", "End of this track", "15 minutes", "30 minutes", "45 minutes", "1 hour", "2 hours"),
            rows.map { it.title })
        val selected = opened.copy(
            screenStack = opened.screenStack.dropLast(1) + opened.currentEntry.copy(selectedIndex = 3)
        )
        val applied = AppReducer.reduce(selected, AppAction.Confirm)
        assertEquals(Screen.NowPlaying, applied.state.currentScreen)
        assertEquals(AppEffect.SetSleepTimer(SleepTimerMode.MINUTES, 30), applied.effects.single())
    }

    @Test fun aRunningTimerOpensOnAddFifteenMinutes() {
        val running = nowPlayingState().let {
            it.copy(playback = it.playback.copy(
                sleepTimerMode = SleepTimerMode.MINUTES,
                sleepTimerConfiguredMinutes = 30,
                sleepTimerRemainingMs = 7 * 60_000L
            ))
        }
        val opened = tileReductionFor("sleep_timer", running).state
        assertEquals(0, opened.selectedIndex)
        assertEquals(ScreenContent.SLEEP_ADD_KEY, (ScreenContent.rows(opened)[0] as ScreenRow.Action).key)
        assertEquals(AppEffect.SetSleepTimer(SleepTimerMode.MINUTES, 22), AppReducer.reduce(opened, AppAction.Confirm).effects.single())
    }

    @Test fun nowPlayingOptionsHoldOnlyWhatTheTilesDoNot() {
        val base = optionsState()
        assertEquals(
            listOf("queue", "np_playlist:1", "track_artist:1", "track_album:1", "np_track_details:1"),
            ScreenContent.rows(base).map { (it as ScreenRow.Action).key }
        )
        val album = optionsReductionFor("track_album:1").state
        assertEquals(Screen.AlbumSongs("Album", "Artist"), album.currentScreen)
        assertFalse("the card closes behind the album", album.screenStack.any { it.screen == Screen.NowPlayingOptions })
        assertEquals(Screen.ArtistAlbums("Artist"), optionsReductionFor("track_artist:1").state.currentScreen)
    }

    @Test fun startingPlaybackNavigatesToNowPlaying() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.Songs)),
            library = LibraryState(tracks = listOf(track))
        )
        val result = AppReducer.reduce(state, AppAction.Confirm)
        assertEquals(Screen.NowPlaying, result.state.currentScreen)
        assertTrue(result.effects.single() is AppEffect.PlayCollection)
        assertEquals(Screen.Songs, AppReducer.reduce(result.state, AppAction.Back).state.currentScreen)
    }

    @Test fun mainMenuKeepsOnlyThePrimaryDestinations() {
        val rows = ScreenContent.rows(AppState())
        assertEquals(
            listOf("favorites", "music", "music_folders", "audiobooks", "videos", "search", "settings"),
            rows.map { (it as ScreenRow.Action).key }
        )

        val music = AppReducer.reduce(AppState(screenStack = listOf(ScreenEntry(Screen.MainMenu, 1))), AppAction.Confirm).state
        assertEquals(Screen.Music, music.currentScreen)
        assertEquals(
            listOf("folders", "shuffle_all", "playlists", "genres", "songs", "artists", "albums", "recent"),
            ScreenContent.rows(music).map { (it as ScreenRow.Action).key }
        )
    }

    @Test fun navigateHomeResetsTheScreenStack() {
        val deep = AppState(
            screenStack = listOf(
                ScreenEntry(Screen.MainMenu, 3),
                ScreenEntry(Screen.Albums, 7),
                ScreenEntry(Screen.AlbumSongs("Album"), 2)
            )
        )
        val result = AppReducer.reduce(deep, AppAction.NavigateHome).state
        assertEquals(1, result.screenStack.size)
        assertEquals(Screen.MainMenu, result.currentScreen)
        assertEquals(0, result.selectedIndex)
    }

    @Test fun navigateHomeOnHomeScreenIsANoOp() {
        val home = AppState(screenStack = listOf(ScreenEntry(Screen.MainMenu, 2)))
        val result = AppReducer.reduce(home, AppAction.NavigateHome).state
        assertEquals(2, result.selectedIndex)
    }

    @Test fun systemScreenExposesTheAndroidSettingsEscapeHatch() {
        val system = AppState(screenStack = listOf(ScreenEntry(Screen.System)))
        val rows = ScreenContent.rows(system)
        val index = rows.indexOfFirst { (it as? ScreenRow.Action)?.key == "android_settings" }
        assertTrue("Android Settings row must exist", index >= 0)
        val selected = system.copy(screenStack = listOf(ScreenEntry(Screen.System, index)))
        assertEquals(AppEffect.OpenAndroidSettings, AppReducer.reduce(selected, AppAction.Confirm).effects.single())
    }

    @Test fun holdCenterOnNowPlayingOpensTheOptionsMenu() {
        val result = AppReducer.reduce(nowPlayingState(), AppAction.ConfirmLong)
        assertEquals(Screen.NowPlayingOptions, result.state.currentScreen)
        assertTrue(result.effects.isEmpty())
    }

    @Test fun holdCenterAgainClosesTheOptionsMenu() {
        val result = AppReducer.reduce(optionsState(), AppAction.ConfirmLong)
        assertEquals(Screen.NowPlaying, result.state.currentScreen)
        assertTrue(result.effects.isEmpty())
    }

    @Test fun holdCenterOnATrackListOpensTrackOptions() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.Songs)),
            library = LibraryState(tracks = listOf(track))
        )
        val options = AppReducer.reduce(state, AppAction.ConfirmLong).state
        assertEquals(Screen.TrackOptions(1), options.currentScreen)
        val keys = ScreenContent.rows(options).filterIsInstance<ScreenRow.Action>().map { it.key }
        assertTrue("track-list options keep Favorite", "track_favorite:1" in keys)
        assertTrue("track-list options keep Add to Playlist", "track_playlist:1" in keys)
    }





    @Test fun optionsMenuOpensAddToPlaylistForTheCurrentTrack() {
        assertEquals(Screen.AddToPlaylist(1), optionsReductionFor("np_playlist:1").state.currentScreen)
    }


    @Test fun queueOpensTheLinearQueueDirectlyWithActionsVisible() {
        val base = optionsState().let {
            it.copy(playback = it.playback.copy(queue = testQueue(1L, 7L, 8L), currentQueueEntryId = 1L))
        }
        val index = ScreenContent.rows(base).indexOfFirst { (it as? ScreenRow.Action)?.key == "queue" }
        val selected = base.copy(screenStack = base.screenStack.dropLast(1) + base.currentEntry.copy(selectedIndex = index))
        val result = AppReducer.reduce(selected, AppAction.Confirm).state
        assertEquals(Screen.Queue, result.currentScreen)
        assertEquals(1, result.selectedIndex)
        assertEquals("queue_actions", (ScreenContent.rows(result).first() as ScreenRow.Action).key)
        assertEquals(Screen.NowPlayingOptions, AppReducer.reduce(result, AppAction.Back).state.currentScreen)
    }

    @Test fun shuffleAllStartsPlaybackAndOpensThePlayer() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.MainMenu), ScreenEntry(Screen.Music)),
            library = LibraryState(tracks = listOf(track))
        )
        val index = ScreenContent.rows(state).indexOfFirst { (it as? ScreenRow.Action)?.key == "shuffle_all" }
        val selected = state.copy(screenStack = state.screenStack.dropLast(1) + state.currentEntry.copy(selectedIndex = index))
        val result = AppReducer.reduce(selected, AppAction.Confirm)
        assertEquals(Screen.NowPlaying, result.state.currentScreen)
        assertEquals(AppEffect.ShuffleAll, result.effects.single())
    }

    @Test fun rightOnHomeAlwaysRequestsTheNextTrackWithoutOpeningThePlayer() {
        val idle = AppState(library = LibraryState(tracks = listOf(track)))
        val idleResult = AppReducer.reduce(idle, AppAction.Right)
        assertEquals(Screen.MainMenu, idleResult.state.currentScreen)
        assertEquals(AppEffect.NextTrack, idleResult.effects.single())
        val playing = idle.copy(playback = PlaybackSnapshot(currentTrackId = 1, queue = testQueue(1L), currentQueueEntryId = 1L))
        val playingResult = AppReducer.reduce(playing, AppAction.Right)
        assertEquals(Screen.MainMenu, playingResult.state.currentScreen)
        assertEquals(AppEffect.NextTrack, playingResult.effects.single())
    }

    @Test fun playlistsScreenHoldsOnlyUserPlaylists() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.Playlists)),
            library = LibraryState(tracks = listOf(track))
        )
        val keys = ScreenContent.rows(state).map { (it as ScreenRow.Action).key }
        assertEquals(listOf("playlist_create"), keys)
    }

    @Test fun settingsGroupsMaintenanceUnderSystem() {
        val settings = AppState(screenStack = listOf(ScreenEntry(Screen.Settings)))
        val rows = ScreenContent.rows(settings)
        assertEquals(
            listOf("audio", "display", "bedtime", "controls", "date_time", "bluetooth", "library_settings", "system"),
            rows.map { (it as ScreenRow.Action).key }
        )
        val systemIndex = rows.indexOfFirst { (it as? ScreenRow.Action)?.key == "system" }
        val selected = settings.copy(screenStack = listOf(ScreenEntry(Screen.Settings, systemIndex)))
        val system = AppReducer.reduce(selected, AppAction.Confirm).state
        assertEquals(Screen.System, system.currentScreen)
        val diagIndex = ScreenContent.rows(system).indexOfFirst { (it as? ScreenRow.Action)?.key == "diagnostics" }
        val diagSelected = system.copy(screenStack = system.screenStack.dropLast(1) + system.currentEntry.copy(selectedIndex = diagIndex))
        assertEquals(Screen.Diagnostics, AppReducer.reduce(diagSelected, AppAction.Confirm).state.currentScreen)
    }

    @Test fun audioOwnsEveryOutputConcernIncludingBluetooth() {
        val settingsHome = AppState(screenStack = listOf(ScreenEntry(Screen.Settings)))
        val audioIndex = ScreenContent.rows(settingsHome)
            .indexOfFirst { (it as? ScreenRow.Action)?.key == "audio" }
        assertTrue("Audio must be reachable from Settings", audioIndex >= 0)

        val audio = AppReducer.reduce(
            settingsHome.copy(screenStack = listOf(ScreenEntry(Screen.Settings, audioIndex))),
            AppAction.Confirm
        ).state
        assertEquals(Screen.Audio, audio.currentScreen)
        assertEquals("Sound", ScreenContent.title(audio))
        assertEquals(
            listOf("sound_effects", "playback_volume", "playback_transitions", "playback_interruptions", "output"),
            ScreenContent.rows(audio).map { (it as ScreenRow.Action).key }
        )

        assertEquals(Screen.SoundEffects, AppReducer.reduce(audio, AppAction.Confirm).state.currentScreen)

        fun openAudioRow(index: Int) = AppReducer.reduce(
            audio.copy(screenStack = audio.screenStack.dropLast(1) + audio.currentEntry.copy(selectedIndex = index)),
            AppAction.Confirm
        ).state.currentScreen
        assertEquals(Screen.PlaybackVolume, openAudioRow(1))
        assertEquals(Screen.PlaybackTransitions, openAudioRow(2))
        assertEquals(Screen.PlaybackInterruptions, openAudioRow(3))
        assertEquals(Screen.OutputInformation, openAudioRow(4))

        val bluetoothIndex = ScreenContent.rows(settingsHome).indexOfFirst { (it as? ScreenRow.Action)?.key == "bluetooth" }
        val settingsRoot = AppState(screenStack = listOf(ScreenEntry(Screen.Settings, bluetoothIndex)))
        assertEquals(Screen.Bluetooth, AppReducer.reduce(settingsRoot, AppAction.Confirm).state.currentScreen)
    }

    @Test fun playingNextLabelReflectsRepeatOneAndQueueOrder() {
        val base = optionsState()
        val repeatOne = base.copy(playback = base.playback.copy(repeatMode = RepeatMode.ONE))
        val queueRow = ScreenContent.rows(repeatOne)
            .filterIsInstance<ScreenRow.Action>().first { it.key == "queue" }
        assertTrue(queueRow.subtitle!!.contains("repeat one"))

        val linear = base.copy(
            playback = base.playback.copy(queue = testQueue(1L, 1L), currentQueueEntryId = 1L)
        )
        val linearRow = ScreenContent.rows(linear)
            .filterIsInstance<ScreenRow.Action>().first { it.key == "queue" }
        assertEquals("No added songs · Next: Song", linearRow.subtitle)
    }

    @Test fun audiobookPlaybackOptionsAreChapterFocused() {
        val audiobook = track.copy(
            absolutePath = "/storage/sdcard/AUDIOBOOKS/Book/01.mp3",
            relativePath = "AUDIOBOOKS/Book/01.mp3"
        )
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.NowPlayingOptions)),
            library = LibraryState(tracks = listOf(audiobook)),
            playback = PlaybackSnapshot(
                currentTrackId = audiobook.id,
                queue = testQueue(audiobook.id),
                currentQueueEntryId = 1L
            )
        )
        val keys = ScreenContent.rows(state).filterIsInstance<ScreenRow.Action>().map { it.key }
        assertTrue(keys.first().startsWith("np_audiobook_chapters:"))
        assertEquals(listOf("queue", "np_playlist:${audiobook.id}"), keys.drop(1).take(2))
        assertEquals("np_track_details:${audiobook.id}", keys.last())
        val details = AppReducer.reduce(
            state.copy(screenStack = listOf(ScreenEntry(Screen.NowPlayingOptions, keys.lastIndex))),
            AppAction.Confirm
        ).state
        assertEquals(Screen.TrackDetails(audiobook.id), details.currentScreen)
        assertTrue("audiobooks must not expose shuffle", "shuffle" !in keys)
        assertTrue("audiobooks must not expose repeat", "repeat" !in keys)
    }
}
