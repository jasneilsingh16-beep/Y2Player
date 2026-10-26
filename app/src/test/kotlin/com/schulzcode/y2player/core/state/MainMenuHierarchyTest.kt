package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.PlaybackSnapshot
import com.schulzcode.y2player.core.model.PlaybackStatus
import com.schulzcode.y2player.core.model.PlaylistSummary
import com.schulzcode.y2player.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MainMenuHierarchyTest {

    @Before fun clearRowCache() {
        ScreenContent.clearCachedRows()
    }

    private val track = Track(
        id = 1,
        volumeId = "sdcard",
        absolutePath = "/storage/sdcard1/Music/song.mp3",
        relativePath = "Music/song.mp3",
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

    private val library = LibraryState(tracks = listOf(track))
    private val playing = PlaybackSnapshot(
        currentTrackId = 1L,
        queue = testQueue(1L),
        currentQueueEntryId = 1L,
        status = PlaybackStatus.PLAYING
    )

    private fun keys(state: AppState) = ScreenContent.rows(state).map { (it as ScreenRow.Action).key }

    private fun select(state: AppState, key: String): AppState {
        val index = ScreenContent.rows(state).indexOfFirst { (it as? ScreenRow.Action)?.key == key }
        require(index >= 0) { "Missing row $key on ${state.currentScreen}" }
        return state.copy(screenStack = state.screenStack.dropLast(1) + state.currentEntry.copy(selectedIndex = index))
    }

    private fun open(state: AppState, key: String) = AppReducer.reduce(select(state, key), AppAction.Confirm)

    // ---- main menu -------------------------------------------------------------

    @Test fun `the idle main menu fits the split layout without scrolling`() {
        // 322 px of row area at 40 dp per row leaves room for eight destinations.
        assertTrue("main menu must not exceed the split-home row budget", ScreenContent.rows(AppState()).size <= 8)
        assertEquals(7, ScreenContent.rows(AppState()).size)
    }

    @Test fun `FM is hidden from the main menu by default`() {
        assertEquals(
            listOf("favorites", "music", "music_folders", "audiobooks", "videos", "search", "settings"),
            keys(AppState(library = library))
        )
    }

    @Test fun `FM can be shown on the main menu`() {
        val state = AppState(
            library = library,
            preferences = PlayerPreferencesState(showFmRadio = true)
        )
        assertEquals(listOf("favorites", "music", "music_folders", "audiobooks", "videos", "fm_radio", "search", "settings"), keys(state))
    }

    @Test fun `a session leads with the playing track`() {
        val state = AppState(library = library, playback = playing)
        assertEquals(
            listOf("now_playing", "favorites", "music", "music_folders", "audiobooks", "videos", "search", "settings"),
            keys(state)
        )
        assertEquals(Screen.NowPlaying, open(state, "now_playing").state.currentScreen)
        val first = ScreenContent.rows(state).first()
        assertEquals("Song", first.title)
        assertEquals("Artist", first.subtitle)
    }

    @Test fun `right from the main menu skips to the next track`() {
        val state = AppState(library = library, playback = playing)
        val result = AppReducer.reduce(state, AppAction.Right)
        assertEquals(Screen.MainMenu, result.state.currentScreen)
        assertEquals(AppEffect.NextTrack, result.effects.single())
    }

    @Test fun `Audiobooks stays on the main menu even with no books`() {
        assertTrue("audiobooks" in keys(AppState()))
        assertTrue("audiobooks" in keys(AppState(library = library)))
    }

    @Test fun `Audiobooks opens its own screen`() {
        assertEquals(Screen.Audiobooks, open(AppState(library = library), "audiobooks").state.currentScreen)
    }

    @Test fun `Talks and Podcasts says when there is nothing in it yet`() {
        val row = ScreenContent.rows(AppState(library = library))[3] as ScreenRow.Action
        assertEquals("Talks & Podcasts", row.title)
        assertEquals("Nothing yet", row.subtitle)
    }

    @Test fun `a playlist named Nitnem sits on Home and opens on the bani that is due`() {
        val banis = listOf("Japji Sahib", "Rehras Sahib", "Kirtan Sohila").mapIndexed { index, title ->
            track.copy(id = 10L + index, title = title, relativePath = "Nitnem/$title.mp3", absolutePath = "/x/$title.mp3")
        }
        // 9:30 pm in UTC: Kirtan Sohila is due.
        val evening = AppState(
            library = LibraryState(tracks = banis, playlists = listOf(PlaylistSummary(3, "Nitnem", 3)),
                playlistTrackIds = mapOf(3L to listOf(10L, 11L, 12L))),
            clock = ClockState(1_793_050_200_000L, "UTC")
        )
        assertTrue("nitnem:3" in keys(evening))
        val opened = open(evening, "nitnem:3").state
        assertEquals(Screen.PlaylistTracks(3, "Nitnem"), opened.currentScreen)
        assertEquals("Kirtan Sohila", ScreenContent.rows(opened)[opened.selectedIndex].title)
    }

    // ---- Music -----------------------------------------------------------------

    @Test fun `Music owns every library entry point except Favourites, which is on Home`() {
        val music = AppState(screenStack = listOf(ScreenEntry(Screen.Music)), library = library)
        assertEquals(
            listOf("folders", "shuffle_all", "songs", "artists", "genres", "albums", "playlists", "recent"),
            keys(music)
        )
        assertEquals("Ragis & Speakers", ScreenContent.rows(music)[3].title)
        assertEquals("Categories", ScreenContent.rows(music)[4].title)
    }

    @Test fun `Recently Played is one Confirm from Music`() {
        val music = AppState(screenStack = listOf(ScreenEntry(Screen.Music)), library = library)
        assertEquals(Screen.RecentlyPlayed, open(music, "recent").state.currentScreen)
    }

    @Test fun `Favourites is one Confirm from Home`() {
        val state = open(AppState(library = library), "favorites").state
        assertEquals(Screen.Favorites, state.currentScreen)
        assertEquals(2, state.screenStack.size)
    }

    @Test fun `Shuffle All inside Music shuffles the whole library`() {
        val music = AppState(screenStack = listOf(ScreenEntry(Screen.Music)), library = library)
        assertEquals(AppEffect.ShuffleAll, open(music, "shuffle_all").effects.single())
    }

    @Test fun `Music gives a value only where it tells you something`() {
        val music = AppState(screenStack = listOf(ScreenEntry(Screen.Music)), library = library)
        val rows = ScreenContent.rows(music).associate { (it as ScreenRow.Action).key to it.subtitle }
        assertEquals("1 track", rows["shuffle_all"])
        assertNull(rows["songs"])
        assertNull(rows["recent"])
    }

    // ---- Playlists -------------------------------------------------------------

    @Test fun `Playlists holds only user playlists`() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.Playlists)),
            library = LibraryState(tracks = listOf(track), playlists = listOf(PlaylistSummary(5, "Road Trip", 1)))
        )
        assertEquals(listOf("playlist:5", "playlist_create"), keys(state))
    }

    @Test fun `Favorites and Recently Played no longer masquerade as playlists`() {
        val state = AppState(screenStack = listOf(ScreenEntry(Screen.Playlists)), library = library)
        val all = keys(state)
        assertFalse("playlist_favorites" in all)
        assertFalse("playlist_recent" in all)
    }

    // ---- Settings --------------------------------------------------------------

    @Test fun `Audio moved under Settings and stays reachable`() {
        val settings = AppState(screenStack = listOf(ScreenEntry(Screen.Settings)))
        assertEquals(
            listOf("audio", "display", "bedtime", "controls", "date_time", "bluetooth", "library_settings", "system"),
            keys(settings)
        )
        assertEquals(Screen.Audio, open(settings, "audio").state.currentScreen)
    }

    @Test fun `Audio is no longer a top level destination`() {
        assertFalse("audio" in keys(AppState()))
    }

    // ---- reachability ----------------------------------------------------------

    @Test fun `every screen the reducer can show is reachable from the main menu`() {
        val reachable = HashSet<String>()
        val seen = HashSet<String>()

        fun walk(state: AppState, depth: Int) {
            val code = state.currentScreen.code
            reachable += code
            if (depth > 6 || !seen.add(code + "@" + depth)) return
            val rows = ScreenContent.rows(state)
            rows.indices.forEach { index ->
                val moved = state.copy(
                    screenStack = state.screenStack.dropLast(1) + state.currentEntry.copy(selectedIndex = index)
                )
                val next = AppReducer.reduce(moved, AppAction.Confirm).state
                if (next.screenStack.size > state.screenStack.size) walk(next, depth + 1)
            }
        }

        walk(
            AppState(
                library = LibraryState(tracks = listOf(track), playlists = listOf(PlaylistSummary(5, "Road Trip", 1))),
                device = DeviceState(internalStorageAvailable = true)
            ),
            0
        )

        listOf(
            "music", "audiobooks", "songs", "albums", "artists", "playlists", "favorites",
            "recently_played", "folders", "settings", "audio", "playback_transitions",
            "sound_effects", "bluetooth", "bedtime", "library_settings", "backup_restore",
            "display", "controls", "storage", "playback_history", "system", "diagnostics", "about"
        ).forEach { assertTrue("$it is unreachable from the main menu", it in reachable) }
    }
}
