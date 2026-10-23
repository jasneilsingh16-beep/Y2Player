package com.schulzcode.y2player.video

import com.schulzcode.y2player.core.state.VideoItemKind
import com.schulzcode.y2player.storage.StorageRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VideoLibraryTest {
    @get:Rule val temp = TemporaryFolder()

    private fun touch(parent: File, name: String): File =
        File(parent, name).also { it.parentFile?.mkdirs(); it.writeBytes(ByteArray(10)) }

    @Test fun `recognises video containers and ignores everything else`() {
        assertTrue(VideoLibrary.isVideoFile("Film.mp4"))
        assertTrue(VideoLibrary.isVideoFile("Show.MKV"))
        assertFalse(VideoLibrary.isVideoFile("Film.srt"))
        assertFalse(VideoLibrary.isVideoFile("song.flac"))
        assertFalse(VideoLibrary.isVideoFile("._Film.mp4"))
    }

    @Test fun `folders come first and episodes sort naturally`() {
        val videos = temp.newFolder("Videos")
        touch(videos, "Episode 10.mp4")
        touch(videos, "Episode 2.mp4")
        touch(videos, "Episode 1.mp4")
        touch(videos, "Series/S01E01.mp4")
        touch(videos, "notes.txt")
        File(videos, "Empty").mkdirs()

        val items = VideoLibrary.list(videos)
        assertEquals(listOf("Series", "Episode 1", "Episode 2", "Episode 10"), items.map { it.title })
        assertEquals(VideoItemKind.FOLDER, items.first().kind)
    }

    @Test fun `nested seasons are counted for their show`() {
        val videos = temp.newFolder("Videos")
        touch(videos, "Show/Season 1/E1.mp4")
        touch(videos, "Show/Season 2/E1.mp4")
        assertEquals(2, VideoLibrary.list(videos).single().videoCount)
    }

    @Test fun `a single volume opens straight into its Videos folder`() {
        val internal = temp.newFolder("internal")
        val sd = temp.newFolder("sd")
        touch(sd, "Videos/Film.mp4")

        val top = VideoLibrary.load(null, listOf(StorageRoot("internal", internal), StorageRoot("sdcard", sd)))
        assertEquals(listOf("Film"), top.map { it.title })
    }

    @Test fun `two volumes with videos are listed by name`() {
        val internal = temp.newFolder("internal")
        touch(internal, "Videos/A.mp4")
        val sd = temp.newFolder("sd")
        touch(sd, "Movies/B.mp4")

        val top = VideoLibrary.load(null, listOf(StorageRoot("internal", internal), StorageRoot("sdcard", sd)))
        assertEquals(listOf("Internal storage", "SD card"), top.map { it.title })
        assertTrue(top.all { it.kind == VideoItemKind.VOLUME })
    }

    @Test fun `no Videos folder anywhere gives an empty section`() {
        assertTrue(VideoLibrary.load(null, listOf(StorageRoot("sdcard", temp.newFolder("sd")))).isEmpty())
    }

    @Test fun `a folder with a poster carries an artwork probe inside it`() {
        val videos = temp.newFolder("Videos")
        touch(videos, "Film/Film.mp4")
        touch(videos, "Film/Poster.JPG")
        touch(videos, "Plain/Plain.mp4")
        val items = VideoLibrary.list(videos).associateBy { it.title }
        val probe = items.getValue("Film").artworkPath
        assertNotNull(probe)
        assertEquals(File(videos, "Film").absolutePath, File(probe!!).parent)
        assertFalse(File(probe).exists())
        assertNull(items.getValue("Plain").artworkPath)
    }

    @Test fun `resume positions are attached to videos`() {
        val videos = temp.newFolder("Videos")
        val film = touch(videos, "Film.mp4")
        val items = VideoLibrary.list(videos) { path -> if (path == film.absolutePath) 600_000L else 0L }
        assertEquals(600_000L, items.single().resumeMs)
    }

    @Test fun `subtitle file is matched by name`() {
        val videos = temp.newFolder("Videos")
        val film = touch(videos, "Film.mp4")
        assertNull(VideoLibrary.subtitleFor(film))
        touch(videos, "Film.srt")
        assertEquals("Film.srt", VideoLibrary.subtitleFor(film)?.name)
    }

    @Test fun `resume ignores the opening seconds and the closing stretch`() {
        val hour = 3_600_000L
        assertNull(VideoResumePolicy.positionToStore(5_000, hour))
        assertEquals(600_000L, VideoResumePolicy.positionToStore(600_000, hour))
        assertNull(VideoResumePolicy.positionToStore(hour - 30_000, hour))
        assertEquals(0L, VideoResumePolicy.startPosition(0, hour))
        assertEquals(0L, VideoResumePolicy.startPosition(hour + 1, hour))
        assertEquals(600_000L, VideoResumePolicy.startPosition(600_000, hour))
    }

    @Test fun `display title drops the extension`() {
        assertEquals("My Film", VideoLibrary.displayTitle("My_Film.mp4"))
    }
}
