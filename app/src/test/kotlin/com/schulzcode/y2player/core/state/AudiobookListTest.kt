package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.AudiobookProgress
import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudiobookListTest {

    @Before fun clearRowCache() {
        ScreenContent.clearCachedRows()
    }

    private var nextId = 1L

    private fun chapter(
        path: String,
        durationMs: Long = 600_000,
        title: String? = null,
        trackNumber: Int? = null,
        discNumber: Int? = null
    ) = Track(
        id = nextId++,
        volumeId = "sdcard",
        absolutePath = "/storage/sdcard1/$path",
        relativePath = path,
        title = title ?: path.substringAfterLast('/'),
        artist = "Narrator",
        album = null,
        albumArtist = null,
        trackNumber = trackNumber,
        discNumber = discNumber,
        durationMs = durationMs,
        fileSize = 1,
        modifiedAt = 1
    )

    private fun state(
        tracks: List<Track>,
        progress: Map<String, AudiobookProgress> = emptyMap()
    ) = AppState(
        screenStack = listOf(ScreenEntry(Screen.Audiobooks)),
        library = LibraryState(tracks = tracks).copy(audiobookProgress = progress)
    )

    private fun rows(state: AppState) = ScreenContent.rows(state).map { it as ScreenRow.Action }

    private fun key(book: String) = "sdcard|AUDIOBOOKS/$book"

    // ---- grouping ---------------------------------------------------------------

    @Test fun `one row per book, not per chapter`() {
        val tracks = (1..5).map { chapter("AUDIOBOOKS/Dune/0$it.mp3") }
        val rows = rows(state(tracks))
        assertEquals(1, rows.size)
        assertEquals("Dune", rows.single().title)
    }

    @Test fun `disc folders stay one book`() {
        val tracks = listOf(
            chapter("AUDIOBOOKS/Neuromancer/Disc 1/01.mp3"),
            chapter("AUDIOBOOKS/Neuromancer/Disc 1/02.mp3"),
            chapter("AUDIOBOOKS/Neuromancer/Disc 2/01.mp3")
        )
        val rows = rows(state(tracks))
        assertEquals(1, rows.size)
        assertEquals("Neuromancer", rows.single().title)
        assertTrue(rows.single().subtitle!!.contains("3 episodes"))
    }

    @Test fun `an author folder shows the book, not the author`() {
        val tracks = (1..3).map { chapter("AUDIOBOOKS/Frank Herbert/Children of Dune/0$it.mp3") }
        assertEquals("Children of Dune", rows(state(tracks)).single().title)
    }

    @Test fun `a single file book uses its track title`() {
        val tracks = listOf(chapter("AUDIOBOOKS/single-file-book.mp3", title = "The Hobbit"))
        val row = rows(state(tracks)).single()
        assertEquals("The Hobbit", row.title)
        assertEquals("10 min", row.subtitle)
    }

    @Test fun `music outside AUDIOBOOKS never appears`() {
        val tracks = listOf(chapter("Music/Album/01.mp3"), chapter("AUDIOBOOKS/Dune/01.mp3"))
        assertEquals(1, rows(state(tracks)).size)
    }

    @Test fun `chapters order naturally, not lexically`() {
        val tracks = listOf(
            chapter("AUDIOBOOKS/Dune/10.mp3"),
            chapter("AUDIOBOOKS/Dune/2.mp3"),
            chapter("AUDIOBOOKS/Dune/1.mp3")
        )
        val entry = ScreenContent.audiobookEntry(state(tracks), key("Dune"))!!
        val titles = entry.chapterIds.map { id -> tracks.first { it.id == id }.title }
        assertEquals(listOf("1.mp3", "2.mp3", "10.mp3"), titles)
    }

    @Test fun `track numbers that restart in each untagged disc do not interleave discs`() {
        val tracks = listOf(
            chapter("AUDIOBOOKS/Dune/Disc 2/02.mp3", trackNumber = 2),
            chapter("AUDIOBOOKS/Dune/Disc 1/02.mp3", trackNumber = 2),
            chapter("AUDIOBOOKS/Dune/Disc 2/01.mp3", trackNumber = 1),
            chapter("AUDIOBOOKS/Dune/Disc 1/01.mp3", trackNumber = 1)
        )
        val entry = ScreenContent.audiobookEntry(state(tracks), key("Dune"))!!
        assertEquals(listOf(tracks[3].id, tracks[1].id, tracks[2].id, tracks[0].id), entry.chapterIds)
    }

    @Test fun `disc folder numbers sort naturally and deterministically`() {
        val tracks = listOf(
            chapter("AUDIOBOOKS/Dune/Disc 10/01.mp3"),
            chapter("AUDIOBOOKS/Dune/Disc 2/01.mp3"),
            chapter("AUDIOBOOKS/Dune/Disc 1/01.mp3")
        )
        val entry = ScreenContent.audiobookEntry(state(tracks), key("Dune"))!!
        assertEquals(listOf(tracks[2].id, tracks[1].id, tracks[0].id), entry.chapterIds)
    }

    // ---- subtitle correctness ----------------------------------------------------

    @Test fun `an unstarted series shows its episodes and total length`() {
        val tracks = (1..24).map { chapter("AUDIOBOOKS/Dune/%02d.mp3".format(it)) }
        assertEquals("24 episodes · 4 h", rows(state(tracks)).single().subtitle)
        assertEquals(RowProgress.UNPLAYED, rows(state(tracks)).single().progress)
    }

    @Test fun `a started series counts the episodes before the current one as played`() {
        val tracks = (1..24).map { chapter("AUDIOBOOKS/Dune/%02d.mp3".format(it)) }
        val progress = mapOf(key("Dune") to AudiobookProgress(tracks[7].id, 300_000, 99))
        val row = rows(state(tracks, progress)).single()
        assertEquals("7 of 24 played", row.subtitle)
        assertEquals(0.3125f, row.progress!!.fraction, 0.001f)
    }

    @Test fun `episode one at zero still shows a sliver of progress`() {
        val tracks = (1..10).map { chapter("AUDIOBOOKS/Dune/%02d.mp3".format(it)) }
        val progress = mapOf(key("Dune") to AudiobookProgress(tracks[0].id, 0, 99))
        val row = rows(state(tracks, progress)).single()
        assertEquals("0 of 10 played", row.subtitle)
        assertTrue("an in-progress ring is never empty", row.progress!!.fraction > 0f)
    }

    @Test fun `the final episode near its end never exceeds a full ring`() {
        val tracks = (1..3).map { chapter("AUDIOBOOKS/Dune/0$it.mp3") }
        val progress = mapOf(key("Dune") to AudiobookProgress(tracks[2].id, 599_999, 99))
        assertTrue(rows(state(tracks, progress)).single().progress!!.fraction <= 1f)
    }

    @Test fun `a position past the total is clamped to a full ring`() {
        val tracks = listOf(chapter("AUDIOBOOKS/Dune/01.mp3", durationMs = 1_000))
        val progress = mapOf(key("Dune") to AudiobookProgress(tracks[0].id, 9_999_999, 99))
        val row = rows(state(tracks, progress)).single()
        assertEquals("In progress", row.subtitle)
        assertEquals(1f, row.progress!!.fraction, 0f)
    }

    @Test fun `an unknown duration drops the length instead of dividing by zero`() {
        val tracks = (1..4).map { chapter("AUDIOBOOKS/Dune/0$it.mp3", durationMs = 0) }
        assertEquals("4 episodes", rows(state(tracks)).single().subtitle)

        val progress = mapOf(key("Dune") to AudiobookProgress(tracks[1].id, 5_000, 99))
        val row = rows(state(tracks, progress)).single()
        assertEquals("1 of 4 played", row.subtitle)
        assertEquals(0.25f, row.progress!!.fraction, 0.001f)
    }

    @Test fun `one unknown episode length suppresses the whole total`() {
        val tracks = listOf(
            chapter("AUDIOBOOKS/Dune/01.mp3", durationMs = 600_000),
            chapter("AUDIOBOOKS/Dune/02.mp3", durationMs = 0)
        )
        assertEquals("2 episodes", rows(state(tracks)).single().subtitle)
    }

    @Test fun `progress naming a deleted episode falls back to unstarted`() {
        val tracks = (1..5).map { chapter("AUDIOBOOKS/Dune/0$it.mp3") }
        val progress = mapOf(key("Dune") to AudiobookProgress(9_999L, 300_000, 99))
        val row = rows(state(tracks, progress)).single()
        assertEquals("5 episodes · 50 min", row.subtitle)
        assertEquals(RowProgress.UNPLAYED, row.progress)
    }

    @Test fun `a speaker named like the series is not repeated`() {
        val tracks = (1..2).map { chapter("Podcasts/Narrator/0$it.mp3") }
        assertEquals("2 episodes · 20 min", rows(state(tracks)).single().subtitle)
    }

    // ---- ordering ----------------------------------------------------------------

    @Test fun `podcasts keep the card's folder order whatever is in progress`() {
        val tracks = listOf("Alpha", "Beta", "Gamma").flatMap { book ->
            (1..2).map { chapter("AUDIOBOOKS/$book/0$it.mp3") }
        }
        val progress = mapOf(
            key("Gamma") to AudiobookProgress(tracks[4].id, 1_000, 500),
            key("Alpha") to AudiobookProgress(tracks[0].id, 1_000, 100)
        )
        assertEquals(listOf("Alpha", "Beta", "Gamma"), rows(state(tracks, progress)).map { it.title })
    }

    @Test fun `untouched books stay alphabetical and stable across rebuilds`() {
        val tracks = listOf("Zeta", "alpha", "Mu").flatMap { book ->
            listOf(chapter("AUDIOBOOKS/$book/01.mp3"))
        }
        val first = rows(state(tracks)).map { it.title }
        ScreenContent.clearCachedRows()
        val second = rows(state(tracks)).map { it.title }
        assertEquals(listOf("alpha", "Mu", "Zeta"), first)
        assertEquals(first, second)
    }

    // ---- actions -----------------------------------------------------------------

    private fun openSeries(state: AppState): Reduction = AppReducer.reduce(state, AppAction.Confirm)

    @Test fun `Confirm opens the series page`() {
        val tracks = (1..10).map { chapter("AUDIOBOOKS/Dune/%02d.mp3".format(it)) }
        val result = openSeries(state(tracks))
        assertEquals(Screen.AudiobookChapters(key("Dune")), result.state.currentScreen)
        assertTrue(result.effects.isEmpty())
    }

    @Test fun `Continue on the series page resumes at the saved episode`() {
        val tracks = (1..10).map { chapter("AUDIOBOOKS/Dune/%02d.mp3".format(it)) }
        val progress = mapOf(key("Dune") to AudiobookProgress(tracks[6].id, 120_000, 99))
        val page = openSeries(state(tracks, progress)).state
        assertTrue(ScreenContent.rows(page).first().title.startsWith("Continue · "))
        val result = AppReducer.reduce(page, AppAction.Confirm)
        val effect = result.effects.single() as AppEffect.PlayCollection
        assertEquals(10, effect.trackIds.size)
        assertEquals("resumes at episode 7", 6, effect.startIndex)
        assertFalse("a series must never start shuffled", effect.shuffled)
        assertFalse("it carries on rather than restarting", effect.fromStart)
        assertEquals(Screen.NowPlaying, result.state.currentScreen)
    }

    @Test fun `an untouched series starts at episode one`() {
        val tracks = (1..10).map { chapter("AUDIOBOOKS/Dune/%02d.mp3".format(it)) }
        val page = openSeries(state(tracks)).state
        assertEquals("Play from Episode 1", ScreenContent.rows(page).first().title)
        val effect = AppReducer.reduce(page, AppAction.Confirm).effects.single() as AppEffect.PlayCollection
        assertEquals(0, effect.startIndex)
    }

    @Test fun `playing queues every episode of that series only`() {
        val tracks = (1..3).map { chapter("AUDIOBOOKS/Dune/0$it.mp3") } +
            (1..4).map { chapter("AUDIOBOOKS/Neuromancer/0$it.mp3") }
        val duneRow = rows(state(tracks)).indexOfFirst { it.title == "Dune" }
        val selected = state(tracks).copy(screenStack = listOf(ScreenEntry(Screen.Audiobooks, duneRow)))
        val page = openSeries(selected).state
        val effect = AppReducer.reduce(page, AppAction.Confirm).effects.single() as AppEffect.PlayCollection
        assertEquals(3, effect.trackIds.size)
    }

    @Test fun `opening Talks and Podcasts reloads progress once`() {
        val home = AppState(library = LibraryState(tracks = listOf(chapter("AUDIOBOOKS/Dune/01.mp3"))))
        val talks = ScreenContent.rows(home).indexOfFirst { (it as? ScreenRow.Action)?.key == "audiobooks" }
        val selected = home.copy(screenStack = listOf(ScreenEntry(Screen.MainMenu, talks)))
        val result = AppReducer.reduce(selected, AppAction.Confirm)
        assertEquals(Screen.Audiobooks, result.state.currentScreen)
        assertEquals(AppEffect.RefreshAudiobooks, result.effects.single())
    }

    @Test fun `an empty AUDIOBOOKS folder produces the empty state, not a phantom row`() {
        assertTrue(ScreenContent.rows(state(emptyList())).isEmpty())
    }

    @Test fun `Confirm on a book whose chapters vanished does nothing`() {
        val tracks = listOf(chapter("AUDIOBOOKS/Dune/01.mp3"))
        val populated = state(tracks)
        val emptied = populated.copy(library = LibraryState())
        assertTrue(AppReducer.reduce(emptied, AppAction.Confirm).effects.none { it is AppEffect.PlayCollection })
    }

    @Test fun `a rescan does not wipe saved progress`() {
        val tracks = (1..10).map { chapter("AUDIOBOOKS/Dune/%02d.mp3".format(it)) }
        val progress = mapOf(key("Dune") to AudiobookProgress(tracks[4].id, 60_000, 99))
        val before = state(tracks, progress)
        assertTrue(rows(before).single().subtitle!!.contains("4 of 10 played"))

        // What a scan produces: a new index and bumped revisions, everything else carried.
        val rescanned = before.copy(
            library = before.library.copy(
                revision = before.library.revision + 1,
                tracksRevision = before.library.tracksRevision + 1,
                index = com.schulzcode.y2player.core.model.LibraryIndex.of(tracks)
            )
        )
        assertTrue(
            "a rescan must not reset the series to unstarted",
            rows(rescanned).single().subtitle!!.contains("4 of 10 played")
        )
    }

    @Test fun `progress surviving a rescan keeps Confirm resuming`() {
        val tracks = (1..10).map { chapter("AUDIOBOOKS/Dune/%02d.mp3".format(it)) }
        val progress = mapOf(key("Dune") to AudiobookProgress(tracks[4].id, 60_000, 99))
        val rescanned = state(tracks, progress).let {
            it.copy(library = it.library.copy(
                revision = it.library.revision + 1,
                index = com.schulzcode.y2player.core.model.LibraryIndex.of(tracks)
            ))
        }
        val page = openSeries(rescanned).state
        val effect = AppReducer.reduce(page, AppAction.Confirm).effects.single() as AppEffect.PlayCollection
        assertEquals("must still resume at episode 5", 4, effect.startIndex)
    }

    // ---- cost --------------------------------------------------------------------

    @Test fun `a large library groups once per revision, not per call`() {
        val tracks = ArrayList<Track>(9_000)
        repeat(300) { book ->
            repeat(30) { part ->
                tracks += chapter("AUDIOBOOKS/Book %03d/%02d.mp3".format(book, part))
            }
        }
        val large = state(tracks)

        val first = ScreenContent.rows(large)
        val second = ScreenContent.rows(large)

        assertEquals(300, first.size)
        assertSame("the grouping pass must be cached, not repeated", first, second)
    }

    @Test fun `a library change rebuilds the grouping`() {
        val tracks = (1..2).map { chapter("AUDIOBOOKS/Dune/0$it.mp3") }
        val before = state(tracks)
        val rowsBefore = ScreenContent.rows(before)

        val added = tracks + chapter("AUDIOBOOKS/Neuromancer/01.mp3")
        val after = before.copy(
            library = before.library.copy(
                revision = 1,
                tracksRevision = 1,
                index = com.schulzcode.y2player.core.model.LibraryIndex.of(added)
            )
        )
        val rowsAfter = ScreenContent.rows(after)

        assertEquals(1, rowsBefore.size)
        assertEquals(2, rowsAfter.size)
    }

    @Test fun `saved progress changes the subtitle without a track change`() {
        val tracks = (1..4).map { chapter("AUDIOBOOKS/Dune/0$it.mp3") }
        val before = state(tracks)
        assertFalse(
            "an untouched series must not claim progress",
            (ScreenContent.rows(before).single() as ScreenRow.Action).subtitle!!.contains("played")
        )

        val progress = mapOf(key("Dune") to AudiobookProgress(tracks[2].id, 0, 5))
        val after = before.copy(library = before.library.copy(revision = 1, audiobookProgress = progress))
        val row = ScreenContent.rows(after).single() as ScreenRow.Action
        assertNotNull(row.subtitle)
        assertTrue("progress must invalidate the cached rows", row.subtitle!!.contains("2 of 4 played"))
    }
}
