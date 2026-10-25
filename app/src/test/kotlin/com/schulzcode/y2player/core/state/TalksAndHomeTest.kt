package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.AudiobookProgress
import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.PlaybackSnapshot
import com.schulzcode.y2player.core.model.PlaybackStatus
import com.schulzcode.y2player.core.model.RepeatMode
import com.schulzcode.y2player.core.model.SleepTimerMode
import com.schulzcode.y2player.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TalksAndHomeTest {

    @Before fun clearRowCache() {
        ScreenContent.clearCachedRows()
    }

    private var nextId = 1L

    private fun track(path: String, title: String = path.substringAfterLast('/'), genre: String? = null) = Track(
        id = nextId++,
        volumeId = "sdcard",
        absolutePath = "/storage/sdcard1/$path",
        relativePath = path,
        title = title,
        artist = "Speaker",
        album = null,
        albumArtist = null,
        trackNumber = null,
        discNumber = null,
        durationMs = 600_000,
        fileSize = 1,
        modifiedAt = 1,
        genre = genre
    )

    private val song = track("Music/Kirtan/Shabad.mp3", "Shabad")
    private val next = track("Music/Kirtan/Next.mp3", "Next Shabad")
    private val japji = (1..3).map { track("Talks/Basics of Sikhi/Japji/0$it.mp3") }
    private val sikhi = (1..2).map { track("Talks/Basics of Sikhi/Explained/0$it.mp3") }
    private val suraj = (1..4).map { track("Downloads/Suraj/Ep $it.mp3", genre = "Podcast") }

    private val library = LibraryState(tracks = listOf(song, next) + japji + sikhi + suraj).copy(
        revision = 1,
        audiobookProgress = mapOf("sdcard|Downloads/Suraj" to AudiobookProgress(suraj[2].id, 120_000, 50))
    )

    private fun playing(vararg overrides: (PlaybackSnapshot) -> PlaybackSnapshot): PlaybackSnapshot =
        overrides.fold(
            PlaybackSnapshot(
                status = PlaybackStatus.PLAYING,
                currentTrackId = song.id,
                nextTrackId = next.id,
                queue = testQueue(song.id, next.id),
                currentQueueEntryId = 1L
            )
        ) { snapshot, change -> change(snapshot) }

    // ---- Up next says what will actually happen -------------------------------------

    @Test fun `Up next names the following track`() {
        val pane = HomePane.nowPlaying(AppState(library = library, playback = playing()))!!
        assertEquals("Next Shabad · Speaker", pane.upNext)
    }

    @Test fun `Up next says the same track again under Repeat One`() {
        val state = AppState(library = library, playback = playing({ it.copy(repeatMode = RepeatMode.ONE) }))
        val pane = HomePane.nowPlaying(state)!!
        assertEquals("This track again", pane.upNext)
        assertTrue(pane.upNextIsRepeat)
    }

    @Test fun `Up next warns when the sleep timer ends with this track`() {
        val state = AppState(library = library, playback = playing({ it.copy(sleepTimerMode = SleepTimerMode.END_TRACK) }))
        assertEquals("Sleep timer stops playback", HomePane.nowPlaying(state)!!.upNext)
    }

    @Test fun `Up next says when nothing follows`() {
        val state = AppState(library = library, playback = playing({ it.copy(nextTrackId = null, queue = testQueue(song.id)) }))
        assertEquals("Nothing after this", HomePane.nowPlaying(state)!!.upNext)
    }

    // ---- Talks & Podcasts -------------------------------------------------------------

    @Test fun `a folder of series is a collection that opens into them`() {
        val talks = AppState(screenStack = listOf(ScreenEntry(Screen.Audiobooks)), library = library)
        val rows = ScreenContent.rows(talks).map { it as ScreenRow.Action }
        assertEquals("what is under way comes first", "Suraj", rows.first().title)
        val collection = rows.single { it.key.startsWith(TalksContent.COLLECTION_KEY) }
        assertEquals("Basics of Sikhi", collection.title)
        assertEquals("2 series", collection.subtitle)

        val selected = talks.copy(screenStack = listOf(ScreenEntry(Screen.Audiobooks, rows.indexOf(collection))))
        val opened = AppReducer.reduce(selected, AppAction.Confirm).state
        assertEquals(Screen.TalkCollection("sdcard|Talks/Basics of Sikhi"), opened.currentScreen)
        assertEquals("Basics of Sikhi", ScreenContent.title(opened))
        assertEquals(setOf("Japji", "Explained"), ScreenContent.rows(opened).map { it.title }.toSet())
    }

    @Test fun `talks never appear in Songs`() {
        val songs = AppState(screenStack = listOf(ScreenEntry(Screen.Songs)), library = library)
        val titles = ScreenContent.rows(songs).filterIsInstance<ScreenRow.TrackRow>().map { it.track.title }
        assertEquals(setOf("Shabad", "Next Shabad"), titles.toSet())
    }

    // ---- Continue ----------------------------------------------------------------------

    private val film = VideoProgress("/v/Soul.mp4", "Soul", 3_000_000, 6_000_000, 100, "Movies")

    @Test fun `Continue lists talks and videos left part-way, most recent first`() {
        val state = AppState(library = library, videos = VideoBrowseState(inProgress = listOf(film)))
        val rows = TalksContent.continueRows(state).map { it as ScreenRow.Action }
        assertEquals(listOf("Soul", "Ep 3.mp3"), rows.map { it.title })
        assertEquals("Movies · 50 min left", rows[0].subtitle)
        assertEquals("Suraj · Ep 3 · 8 min left", rows[1].subtitle)
        assertEquals(0.2f, rows[1].progress!!.fraction, 0.001f)
    }

    @Test fun `Home offers Continue only when something is part-way`() {
        assertTrue(ScreenContent.CONTINUE_KEY in homeKeys(AppState(library = library)))
        val nothing = AppState(library = library.copy(audiobookProgress = emptyMap()))
        assertTrue(ScreenContent.CONTINUE_KEY !in homeKeys(nothing))
    }

    private fun homeKeys(state: AppState) = ScreenContent.rows(state).map { (it as ScreenRow.Action).key }

    private fun onContinue(state: AppState): AppState =
        state.copy(screenStack = listOf(ScreenEntry(Screen.MainMenu, homeKeys(state).indexOf(ScreenContent.CONTINUE_KEY))))

    @Test fun `Right on Continue opens the full page and Centre resumes from it`() {
        val home = onContinue(AppState(library = library))
        val page = AppReducer.reduce(home, AppAction.Right)
        assertEquals(Screen.Continue, page.state.currentScreen)
        assertTrue("Right must not skip the track here", page.effects.isEmpty())

        val resumed = AppReducer.reduce(page.state, AppAction.Confirm)
        val effect = resumed.effects.single() as AppEffect.PlayCollection
        assertEquals(suraj.map { it.id }, effect.trackIds)
        assertEquals(2, effect.startIndex)
    }

    @Test fun `Play on Continue with nothing loaded picks the top item back up`() {
        val home = onContinue(AppState(library = library))
        val effect = AppReducer.reduce(home, AppAction.PlayPause).effects.single() as AppEffect.PlayCollection
        assertEquals(2, effect.startIndex)
    }

    @Test fun `Play on Continue while something is loaded still just pauses`() {
        val home = onContinue(AppState(library = library, playback = playing()))
        assertEquals(AppEffect.TogglePlayback, AppReducer.reduce(home, AppAction.PlayPause).effects.single())
    }

    @Test fun `a video in Continue reopens in the player with its folder`() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.MainMenu), ScreenEntry(Screen.Continue, 0)),
            library = library,
            videos = VideoBrowseState(inProgress = listOf(film.copy(updatedAt = Long.MAX_VALUE, playlist = listOf("/v/Soul.mp4"))))
        )
        assertEquals(AppEffect.PlayVideo("/v/Soul.mp4", listOf("/v/Soul.mp4")), AppReducer.reduce(state, AppAction.Confirm).effects.single())
    }

    // ---- Home's pane follows the focused row ---------------------------------------------

    @Test fun `the pane under Continue shows the top item and the next two`() {
        val state = AppState(library = library, videos = VideoBrowseState(inProgress = listOf(film)))
        val pane = HomePane.of(state, ScreenRow.Action("Continue", null, ScreenContent.CONTINUE_KEY)) as HomePane.Continue
        assertEquals("Soul", pane.first.title)
        assertEquals(listOf("Ep 3.mp3"), pane.others.map { it.title })
    }

    @Test fun `the pane under Search explains how to type`() {
        val pane = HomePane.of(AppState(), ScreenRow.Action("Search", null, "search")) as HomePane.Collection
        assertEquals("Type with the wheel, then Centre", pane.lines.single().text)
        assertNull(HomePane.of(AppState(), null))
    }

    @Test fun `holding Play opens the sleep timer from anywhere`() {
        val state = AppState(screenStack = listOf(ScreenEntry(Screen.MainMenu), ScreenEntry(Screen.Songs)), library = library)
        val result = AppReducer.reduce(state, AppAction.ShowSleepTimer)
        assertEquals(Screen.SleepTimer, result.state.currentScreen)
        assertEquals("a second hold does not stack another", result.state,
            AppReducer.reduce(result.state, AppAction.ShowSleepTimer).state)
    }

    @Test fun `the Nitnem schedule follows the day`() {
        assertEquals("Japji", NitnemSchedule.due(4))
        assertEquals("Rehras", NitnemSchedule.due(18))
        assertEquals("Sohila", NitnemSchedule.due(22))
        assertEquals("Sohila", NitnemSchedule.due(1))
    }
}
