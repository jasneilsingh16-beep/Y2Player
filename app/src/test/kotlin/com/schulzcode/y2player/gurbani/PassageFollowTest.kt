package com.schulzcode.y2player.gurbani

import org.junit.Assert.assertEquals
import org.junit.Test

class PassageFollowTest {
    private fun line(seq: Int, gurmukhi: String, kind: Char = 'T') =
        GurbaniText.Line(seq, 1, kind, gurmukhi, "", false, "", "")

    private val lines = listOf(
        line(0, "ਸਿਰੀਰਾਗੁ ਮਹਲਾ ੧ ॥", 'H'),
        line(1, "ਮੋਤੀ ਤ ਮੰਦਰ ਊਸਰਹਿ; ਰਤਨੀ ਤ ਹੋਹਿ ਜੜਾਉ ॥"),
        line(2, "ਕਸਤੂਰਿ ਕੁੰਗੂ ਅਗਰਿ ਚੰਦਨਿ; ਲੀਪਿ ਆਵੈ ਚਾਉ ॥"),
        line(3, "ਮਤੁ ਦੇਖਿ ਭੂਲਾ; ਵੀਸਰੈ. ਤੇਰਾ ਚਿਤਿ ਨ ਆਵੈ ਨਾਉ ॥੧॥"),
    )

    @Test fun theStartIsTheFirstLineAndTheEndTheLast() {
        assertEquals(0, PassageFollow.lineAt(lines, 0, 60_000))
        assertEquals(3, PassageFollow.lineAt(lines, 60_000, 60_000))
        assertEquals(3, PassageFollow.lineAt(lines, 90_000, 60_000))
    }

    @Test fun aHeadingIsPassedQuicklyAndLongLinesTakeLonger() {
        // A twentieth of the way in is already past the heading.
        assertEquals(1, PassageFollow.lineAt(lines, 3_000, 60_000))
        // Halfway is in the second tuk, not the second line of four.
        assertEquals(2, PassageFollow.lineAt(lines, 30_000, 60_000))
    }

    @Test fun noDurationStaysAtTheTop() {
        assertEquals(0, PassageFollow.lineAt(lines, 5_000, 0))
        assertEquals(0, PassageFollow.lineAt(emptyList(), 5_000, 60_000))
    }
}
