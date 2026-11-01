package com.schulzcode.y2player.library

import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.storage.StorageRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FavouritesFileTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun keepsTheFileOrderAndAppendsNewFavourites() {
        assertEquals(listOf(3L, 1L, 5L, 7L), FavouritesFile.ordered(listOf(3, 9, 1), listOf(5, 1, 7, 3)))
    }

    @Test fun writesPathsFromTheCardRootWithCrlf() {
        val root = StorageRoot("sdcard", File("/storage/sdcard1"))
        val text = FavouritesFile.render(
            listOf(track(1, "sdcard", "Bhai X/Shabad - Bhai X.mp3"), track(2, "internal", "Music/a.mp3")),
            root
        )
        assertEquals("#EXTM3U\r\nBhai X/Shabad - Bhai X.mp3\r\n/storage/internal/Music/a.mp3\r\n", text)
    }

    @Test fun findsAnExistingFileWhateverItsCase() {
        val card = folder.newFolder("card")
        File(card, "playlists").mkdirs()
        File(card, "playlists/favorites.m3u").writeText("#EXTM3U\r\n")
        val (_, file) = FavouritesFile { listOf(StorageRoot("sdcard", card)) }.locate()!!
        assertEquals("favorites.m3u", file.name)
        assertTrue(FavouritesFile.isFavouritesFile(file))
        assertFalse(FavouritesFile.isFavouritesFile(File(card, "Music/Favourites.m3u8")))
    }

    @Test fun writeReplacesTheFile() {
        val card = folder.newFolder("card")
        val favourites = FavouritesFile { listOf(StorageRoot("sdcard", card)) }
        assertTrue(favourites.write(listOf(track(1, "sdcard", "a.mp3"))))
        assertTrue(favourites.write(listOf(track(2, "sdcard", "b.mp3"))))
        assertEquals("#EXTM3U\r\nb.mp3\r\n", File(card, "Playlists/Favourites.m3u8").readText())
        assertEquals(listOf("Favourites.m3u8"), File(card, "Playlists").list()!!.toList())
    }

    private fun track(id: Long, volume: String, path: String) = Track(
        id = id,
        volumeId = volume,
        absolutePath = "/storage/$volume/$path",
        relativePath = path,
        title = path,
        artist = null,
        album = null,
        albumArtist = null,
        trackNumber = null,
        discNumber = null,
        durationMs = 0,
        fileSize = 0,
        modifiedAt = 0
    )
}
