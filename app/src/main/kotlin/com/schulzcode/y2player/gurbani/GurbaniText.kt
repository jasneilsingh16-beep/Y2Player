package com.schulzcode.y2player.gurbani

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Sri Guru Granth Sahib Ji on the card, with English, and which part of it each
 * recording is. tools/gurbani/build_y2_gurbani.py writes the file on a computer:
 * every line of the 1,430 angs in Granth order, the English of each tuk, and a
 * table from a recording's path to its shabad, its angs (Sehaj Paath) or its bani
 * (Nitnem). A shabad the script could not be sure of is there too, marked
 * unconfirmed, with the other shabads it might be.
 *
 * What the listener decides on the player (this is the shabad, it is another, or
 * it has none) is added to overrides.tsv beside the file. The player reads it
 * before the database, and the script reads it on every run, so a choice made on
 * either side is kept.
 *
 * The database is read-only; opened on first use and kept open. Not thread-safe:
 * use from one background thread.
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

    /** A shabad an uncertain recording might be: its line, best first. */
    data class Candidate(val rank: Int, val lineId: String, val focusSeq: Int)

    data class Passage(
        val path: String,
        val kind: Kind,
        val lines: List<Line>,
        val focus: Int,
        val label: String?,
        /** False when the title only probably names this shabad. */
        val confident: Boolean = true,
        val candidates: List<Candidate> = emptyList(),
        /** Which of [candidates] this is. */
        val candidate: Int = 0,
        /** The line this shabad was found by, kept when the listener confirms it. */
        val lineId: String? = null
    ) {
        /** Sehaj Paath and banis are read straight through, so the page can follow the recording. */
        val follows: Boolean get() = kind != Kind.SHABAD
    }

    sealed interface Lookup {
        data class Found(val passage: Passage) : Lookup
        /** The listener said this recording has no shabad. */
        data object MarkedNone : Lookup
        data object Unknown : Lookup
    }

    private var database: SQLiteDatabase? = null

    val available: Boolean get() = file.isFile

    private val overridesFile: File get() = File(file.parentFile, OVERRIDES)

    /** What [relativePath] (from the card's root) is: the listener's choice first, then the database's. */
    fun lookup(relativePath: String): Lookup {
        val path = relativePath.trimStart('/')
        val db = open() ?: return Lookup.Unknown
        when (val chosen = readOverrides()[path.lowercase()]) {
            null, BACK_TO_GUESS -> Unit
            NONE -> return Lookup.MarkedNone
            else -> seqOf(db, chosen)?.let { seq ->
                shabad(db, path, seq, confident = true, candidates = emptyList(), candidate = 0)?.let { return Lookup.Found(it) }
            }
        }
        val row = db.rawQuery(
            "SELECT kind, first_seq, last_seq, focus_seq, label, confident FROM tracks WHERE path = ? COLLATE NOCASE",
            arrayOf(path)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return Lookup.Unknown
            TrackRow(cursor.getString(0), cursor.getInt(1), cursor.getInt(2),
                if (cursor.isNull(3)) null else cursor.getInt(3),
                if (cursor.isNull(4)) null else cursor.getString(4),
                cursor.isNull(5) || cursor.getInt(5) != 0)
        }
        val kind = when (row.kind) {
            "shabad" -> Kind.SHABAD
            "angs" -> Kind.ANGS
            "bani" -> Kind.BANI
            else -> return Lookup.Unknown
        }
        if (kind == Kind.SHABAD && row.focusSeq != null) {
            val candidates = if (row.confident) emptyList() else candidates(db, path)
            return shabad(db, path, row.focusSeq, row.confident, candidates, 0)?.let { Lookup.Found(it) } ?: Lookup.Unknown
        }
        val lines = lines(db, row.first, row.last)
        if (lines.isEmpty()) return Lookup.Unknown
        return Lookup.Found(Passage(path, kind, lines, 0, row.label))
    }

    /** The next shabad an unconfirmed recording might be, after [current]; still unconfirmed. */
    fun nextCandidate(current: Passage): Passage? {
        if (current.candidates.size < 2) return null
        val db = open() ?: return null
        val next = (current.candidate + 1) % current.candidates.size
        return shabad(db, current.path, current.candidates[next].focusSeq, false, current.candidates, next)
    }

    /** Keeps the listener's choice on the card: [lineId] is the shabad, or null for none. */
    fun remember(relativePath: String, lineId: String?): Boolean = runCatching {
        val target = overridesFile
        val fresh = !target.exists()
        java.io.FileOutputStream(target, true).bufferedWriter(Charsets.UTF_8).use { out ->
            if (fresh) out.write(OVERRIDES_HEADER)
            out.write(relativePath.trimStart('/') + "\t" + (lineId ?: NONE) + "\r\n")
        }
        true
    }.getOrDefault(false)

    private class TrackRow(
        val kind: String, val first: Int, val last: Int, val focusSeq: Int?, val label: String?, val confident: Boolean
    )

    private fun shabad(
        db: SQLiteDatabase, path: String, focusSeq: Int, confident: Boolean, candidates: List<Candidate>, candidate: Int
    ): Passage? {
        val range = db.rawQuery(
            "SELECT s.first_seq, s.last_seq, l.id FROM lines l JOIN shabads s ON s.shabad = l.shabad WHERE l.seq = ?",
            arrayOf(focusSeq.toString())
        ).use { if (it.moveToFirst()) Triple(it.getInt(0), it.getInt(1), it.getString(2)) else null } ?: return null
        val lines = lines(db, range.first, range.second)
        if (lines.isEmpty()) return null
        val focus = lines.indexOfFirst { it.seq == focusSeq }.coerceAtLeast(0)
        return Passage(path, Kind.SHABAD, lines, focus, null, confident, candidates, candidate, range.third)
    }

    private fun candidates(db: SQLiteDatabase, path: String): List<Candidate> = db.rawQuery(
        "SELECT rank, line_id, focus_seq FROM candidates WHERE path = ? COLLATE NOCASE ORDER BY rank", arrayOf(path)
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(Candidate(cursor.getInt(0), cursor.getString(1), cursor.getInt(2))) } }

    private fun seqOf(db: SQLiteDatabase, lineId: String): Int? =
        db.rawQuery("SELECT seq FROM lines WHERE id = ?", arrayOf(lineId)).use { if (it.moveToFirst()) it.getInt(0) else null }

    private fun readOverrides(): Map<String, String> =
        runCatching { parseOverrides(overridesFile.readText(Charsets.UTF_8)) }.getOrDefault(emptyMap())

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
        private const val OVERRIDES = "overrides.tsv"
        private const val NONE = "-"
        /** Undoes an earlier choice: the database's own match counts again. */
        const val BACK_TO_GUESS = "*"
        private const val OVERRIDES_HEADER =
            "# path<TAB>line id (from to-check.csv), or path<TAB>- for no text. Wins over matching.\r\n"

        /** path (lower case) to line id or "-"; a later line wins, so the newest choice counts. */
        fun parseOverrides(text: String): Map<String, String> = buildMap {
            text.lineSequence().forEach { raw ->
                val line = raw.removePrefix("\uFEFF").trimEnd('\r')
                if (line.isBlank() || line.startsWith("#")) return@forEach
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEach
                val value = line.substring(tab + 1).trim()
                if (value.isNotEmpty()) put(line.substring(0, tab).replace('\\', '/').trimStart('/').lowercase(), value)
            }
        }
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
