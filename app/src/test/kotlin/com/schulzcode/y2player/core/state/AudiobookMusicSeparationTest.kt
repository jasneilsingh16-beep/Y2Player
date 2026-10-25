package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.PlaylistSummary
import com.schulzcode.y2player.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudiobookMusicSeparationTest {

    @Before fun clearRowCache() {
        ScreenContent.clearCachedRows()
    }

    private val song = track(
        id = 1,
        path = "Music/Record/01.mp3",
        title = "Song",
        artist = "Musician",
        album = "Record"
    )
    private val chapter = track(
        id = 2,
        path = "AUDIOBOOKS/Book/01.mp3",
        title = "Chapter",
        artist = "Narrator",
        album = "Book"
    )
    private val library = LibraryState(
        tracks = listOf(song, chapter),
        playlists = listOf(PlaylistSummary(7, "Mixed", 2)),
        playlistTrackIds = mapOf(7L to listOf(song.id, chapter.id))
    ).copy(recentlyPlayedIds = listOf(chapter.id, song.id))

    @Test fun `dedicated audiobooks retain chapters hidden from music`() {
        assertEquals(listOf(song, chapter), library.availableTracks)
        assertEquals(listOf(song), library.musicTracks)
        assertEquals(listOf("Book"), rows(Screen.Audiobooks).map { it.title })
    }

    @Test fun `music track screens exclude talks`() {
        listOf(Screen.Songs, Screen.RecentlyPlayed)
            .forEach { screen -> assertEquals(screen.toString(), listOf(song.id), trackIds(screen)) }

        assertTrue(trackIds(Screen.AlbumSongs("Book")).isEmpty())
        assertTrue(trackIds(Screen.ArtistSongs("Narrator")).isEmpty())
    }

    @Test fun `music group screens exclude audiobook metadata and folders`() {
        assertEquals(listOf("Record"), groups(Screen.Albums).map { it.key })
        assertEquals(listOf("Musician"), groups(Screen.Artists).map { it.key })
        assertEquals(listOf("Music"), rows(Screen.Folders("sdcard", "")).map { it.title })
    }

    @Test fun `a talk you put in a playlist or Favourites stays there`() {
        // Chosen by hand, so it is kept, while Songs and Recently Played stay music only.
        assertEquals(listOf(song.id, chapter.id), trackIds(Screen.PlaylistTracks(7, "Mixed")))
        val playlist = actions(Screen.Playlists).single { it.key == "playlist:7" }
        assertEquals("2 tracks", playlist.subtitle)

        val favourites = library.copy(index = com.schulzcode.y2player.core.model.LibraryIndex.of(
            listOf(song.copy(favorite = true), chapter.copy(favorite = true))
        ))
        val state = AppState(screenStack = listOf(ScreenEntry(Screen.Favorites)), library = favourites)
        assertEquals(setOf(song.id, chapter.id),
            ScreenContent.rows(state).filterIsInstance<ScreenRow.TrackRow>().map { it.track.id }.toSet())
        assertEquals("Music shuffles music only", "1 track",
            actions(Screen.Music).single { it.key == "shuffle_all" }.subtitle)
    }

    @Test fun `a file tagged Podcast is a talk wherever it is`() {
        val podcast = track(3, "Downloads/Suraj/Ep 1.mp3", "Ep 1", "Jvala Singh", "Suraj").copy(genre = "Podcast")
        val katha = track(4, "Music/Giani Ji/Japji 1.mp3", "Japji 1", "Giani Ji", "Japji").copy(genre = "Katha")
        val talks = LibraryState(tracks = listOf(song, podcast, katha))
        assertEquals(listOf(song), talks.musicTracks)
        val state = AppState(screenStack = listOf(ScreenEntry(Screen.Audiobooks)), library = talks)
        assertEquals(setOf("Suraj", "Giani Ji"), ScreenContent.rows(state).map { it.title }.toSet())
    }

    private fun state(screen: Screen) = AppState(
        screenStack = listOf(ScreenEntry(screen)),
        library = library
    )

    private fun rows(screen: Screen): List<ScreenRow> = ScreenContent.rows(state(screen))

    private fun actions(screen: Screen): List<ScreenRow.Action> = rows(screen).filterIsInstance<ScreenRow.Action>()

    private fun groups(screen: Screen): List<ScreenRow.Group> = rows(screen).filterIsInstance<ScreenRow.Group>()

    private fun trackIds(screen: Screen): List<Long> = rows(screen)
        .filterIsInstance<ScreenRow.TrackRow>()
        .map { it.track.id }

    private fun track(id: Long, path: String, title: String, artist: String, album: String) = Track(
        id = id,
        volumeId = "sdcard",
        absolutePath = "/storage/sdcard1/$path",
        relativePath = path,
        title = title,
        artist = artist,
        album = album,
        albumArtist = artist,
        trackNumber = 1,
        discNumber = 1,
        durationMs = 60_000,
        fileSize = 1,
        modifiedAt = 1,
        favorite = true
    )
}
