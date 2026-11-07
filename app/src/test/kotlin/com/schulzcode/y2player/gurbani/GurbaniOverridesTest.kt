package com.schulzcode.y2player.gurbani

import org.junit.Assert.assertEquals
import org.junit.Test

class GurbaniOverridesTest {
    @Test fun theNewestChoiceForARecordingCounts() {
        val text = "﻿# path<TAB>line id\r\n" +
            "Bhai X/Shabad - Bhai X.mp3\tABCD\r\n" +
            "Bhai Y\\Other - Bhai Y.mp3\t-\r\n" +
            "bhai x/shabad - bhai x.mp3\tWXYZ\r\n" +
            "\r\n" +
            "no tab here\r\n"
        assertEquals(
            mapOf("bhai x/shabad - bhai x.mp3" to "WXYZ", "bhai y/other - bhai y.mp3" to "-"),
            GurbaniText.parseOverrides(text)
        )
    }

    @Test fun undoingAChoiceIsAChoiceToo() {
        val text = "A/b.mp3\t-\nA/b.mp3\t${GurbaniText.BACK_TO_GUESS}\n"
        assertEquals(mapOf("a/b.mp3" to GurbaniText.BACK_TO_GUESS), GurbaniText.parseOverrides(text))
    }
}
