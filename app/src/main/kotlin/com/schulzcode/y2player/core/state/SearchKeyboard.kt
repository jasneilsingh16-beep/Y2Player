package com.schulzcode.y2player.core.state

internal object SearchKeyboard {
    const val SPACE = "space"
    const val DELETE = "delete"
    const val CLEAR = "clear"
    const val RESULTS = "results"

    // In alphabetical order: the wheel walks the keys in reading order, so the
    // alphabet puts every letter where you expect it.
    val rows: List<List<String>> = listOf(
        "ABCDEFGHI".map(Char::toString),
        "JKLMNOPQR".map(Char::toString),
        "STUVWXYZ".map(Char::toString) + DELETE,
        listOf(SPACE, CLEAR, RESULTS)
    )

    fun key(screen: Screen.Search): String = rows[screen.keyboardRow][screen.keyboardColumn]

    fun moveLinear(screen: Screen.Search, delta: Int, wrap: Boolean): Screen.Search {
        val current = rows.take(screen.keyboardRow).sumOf(List<String>::size) + screen.keyboardColumn
        val total = rows.sumOf(List<String>::size)
        val next = if (wrap) {
            ((current + delta) % total + total) % total
        } else {
            (current + delta).coerceIn(0, total - 1)
        }
        var remaining = next
        rows.forEachIndexed { rowIndex, row ->
            if (remaining < row.size) return screen.copy(keyboardRow = rowIndex, keyboardColumn = remaining)
            remaining -= row.size
        }
        return screen
    }

    fun label(key: String): String = when (key) {
        SPACE -> "Space"
        DELETE -> "⌫"
        CLEAR -> "Clear"
        RESULTS -> "Results"
        else -> key
    }
}
