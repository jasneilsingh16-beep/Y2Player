package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.AudiobookIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SehajPaathTalksTest {
    @Test fun aSehajPaathFolderIsKeptLikeAPodcast() {
        assertTrue(AudiobookIdentity.isRootSegment("Sehaj Paath"))
        assertEquals(
            "sdcard|Sehaj Paath/Bhai Balbir Singh (Sidki)",
            AudiobookIdentity.folderKey("sdcard", "Sehaj Paath/Bhai Balbir Singh (Sidki)/Ang 0001-0008 - Bhai Balbir Singh Sidki Ji.mp3")
        )
    }

    @Test fun everyReaderSitsUnderOneSehajPaathRow() {
        assertEquals("sdcard|Sehaj Paath", TalksContent.groupOf("sdcard|Sehaj Paath/Giani Mehnga Singh"))
        assertEquals("sdcard|Sehaj Paath", TalksContent.groupOf("sdcard|Sehaj Paath/Bhai Gursharan Singh (Damdami Taksal)/Old Set"))
    }

    @Test fun aSehajPaathGenreTagCountsWhereverTheFileIs() {
        assertTrue(AudiobookIdentity.isSpokenGenre("Sehaj Paath"))
    }
}
