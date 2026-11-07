package com.schulzcode.y2player.gurbani

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Sri Guru Granth Sahib Ji on the card, with English, and which part of it each
 * recording is. tools/gurbani/build_y2_gurbani.py writes the file on a computer:
 * every line of the 1,430 angs in Granth order, the English of each tuk, and a
 * table from a recording's path to its shabad, its angs (Sehaj Paath) or its bani
 * (Nitnem). A recording is in that table only when its shabad is certain.
 *
 * Read-only; opened on first use and kept open. Not thread-safe: use from one
 * background thread.
 */
class GurbaniText(private val file: File) {
    data class Line(
        val seq: Int,
        val ang: Int,
        /** M mangal, B bani heading, H small heading, T tuk, R rahao tuk. */
        val kind: Char,
        val gurmukhi: String,
        val english: String,
        /** The English has no Sahib Singh arth behind it. */
        val noSource: Boolean,
        val section: String,
        val author: String
    ) {
        val isHeading: Boolean get() = kind == 'M' || kind == 'B' || kind == 'H'
    }

    enum class Kind { SHABAD, ANGS, BANI }

    data class Passage(val kind: Kind, val lines: List<Line>, val focus: Int, val label: String?) {
        /** Sehaj Paath and banis are read straight through, so the page can follow the recording. */
        val follows: Boolean get() = kind != Kind.SHABAD
    }

    private var database: SQLiteDatabase? = null

    val available: Boolean get() = file.isFile

    /** What [relativePath] (from the card's root) is, or null when it is not certain. */
    fun passageFor(relativePath: String): Passage? {
        val db = open() ?: return null
        val row = db.rawQuery(
            "SELECT kind, first_seq, last_seq, focus_seq, label FROM tracks WHERE path = ? COLLATE NOCASE",
            arrayOf(relativePath.trimStart('/'))
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            Triple(cursor.getString(0), cursor.getInt(1) to cursor.getInt(2),
                (if (cursor.isNull(3)) null else cursor.getInt(3)) to (if (cursor.isNull(4)) null else cursor.getString(4)))
        }
        val kind = when (row.first) {
            "shabad" -> Kind.SHABAD
            "angs" -> Kind.ANGS
            "bani" -> Kind.BANI
            else -> return null
        }
        val (first, last) = row.second
        val (focusSeq, label) = row.third
        val lines = lines(db, first, last)
        if (lines.isEmpty()) return null
        val focus = focusSeq?.let { seq -> lines.indexOfFirst { it.seq == seq } }?.takeIf { it >= 0 } ?: 0
        return Passage(kind, lines, focus, label)
    }

    /** Where the English comes from, in the file's own words. */
    fun attribution(): String = open()?.rawQuery("SELECT value FROM info WHERE key = 'english'", null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: ""

    fun close() {
        database?.close()
        database = null
    }

    private fun lines(db: SQLiteDatabase, first: Int, last: Int): List<Line> = db.rawQuery(
        "SELECT seq, ang, kind, gurmukhi, english, nosrc, section, author FROM lines WHERE seq BETWEEN ? AND ? ORDER BY seq",
        arrayOf(first.toString(), last.toString())
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(Line(
                seq = cursor.getInt(0),
                ang = cursor.getInt(1),
                kind = cursor.getString(2).firstOrNull() ?: 'T',
                gurmukhi = cursor.getString(3).orEmpty(),
                english = cursor.getString(4).orEmpty(),
                noSource = cursor.getInt(5) != 0,
                section = cursor.getString(6).orEmpty(),
                author = cursor.getString(7).orEmpty()
            ))
        }
    }

    private fun open(): SQLiteDatabase? {
        database?.let { return it }
        if (!file.isFile) return null
        return runCatching {
            SQLiteDatabase.openDatabase(
                file.absolutePath, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
        }.getOrNull()?.also { database = it }
    }

    companion object {
        const val RELATIVE_PATH = "Y2Player/Gurbani/sggs.db"
    }
}

/**
 * Where a recording that reads straight through (Sehaj Paath, a bani) has got to.
 * A line takes as long to read as it is long, so the position is laid over the
 * passage by the length of each line's Gurmukhi, not by counting lines; a heading
 * counts for little, as a reader passes over it quickly.
 */
object PassageFollow {
    fun lineAt(lines: List<GurbaniText.Line>, positionMs: Long, durationMs: Long): Int {
        if (lines.isEmpty() || durationMs <= 0L) return 0
        val fraction = (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
        val weights = lines.map { weight(it) }
        val total = weights.sum()
        if (total <= 0.0) return 0
        var reached = 0.0
        val target = fraction * total
        for (index in weights.indices) {
            reached += weights[index]
            if (reached > target) return index
        }
        return lines.lastIndex
    }

    private fun weight(line: GurbaniText.Line): Double {
        val letters = line.gurmukhi.count { it in 'ਅ'..'ਹ' || it in 'ਖ਼'..'ਫ਼' }
        return if (line.isHeading) letters * .3 else letters.toDouble().coerceAtLeast(1.0)
    }
}
