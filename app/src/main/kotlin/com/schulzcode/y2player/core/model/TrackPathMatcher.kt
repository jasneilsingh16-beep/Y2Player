package com.schulzcode.y2player.core.model

import java.util.Locale

/**
 * Finds a library track for a path written somewhere else: an M3U made on a PC,
 * or a backup from another card. Tries the whole path ignoring case (FAT cards
 * are case-insensitive, so "music/" and "Music/" are one folder), then the last
 * folder and file name, then the file name alone. The looser two only match
 * when exactly one track fits, so a common name like "01.mp3" is never guessed.
 */
class TrackPathMatcher(tracks: List<Track>) {
    private val byPath = HashMap<String, Long>(tracks.size * 2)
    private val byRelative = HashMap<String, Long?>(tracks.size * 2)
    private val byFolderAndName = HashMap<String, Long?>(tracks.size * 2)
    private val byName = HashMap<String, Long?>(tracks.size * 2)

    init {
        tracks.forEach { track ->
            byPath[key(track.absolutePath)] = track.id
            putUnique(byRelative, key(track.relativePath).trim('/'), track.id)
            val segments = segments(track.absolutePath)
            if (segments.size >= 2) putUnique(byFolderAndName, segments.takeLast(2).joinToString("/"), track.id)
            segments.lastOrNull()?.let { putUnique(byName, it, track.id) }
        }
    }

    fun match(path: String): Long? {
        byPath[key(path)]?.let { return it }
        byRelative[key(path).trim('/')]?.let { return it }
        val segments = segments(path)
        if (segments.size >= 2) byFolderAndName[segments.takeLast(2).joinToString("/")]?.let { return it }
        return segments.lastOrNull()?.let { byName[it] }
    }

    private fun putUnique(map: HashMap<String, Long?>, key: String, id: Long) {
        if (key.isEmpty()) return
        map[key] = if (map.containsKey(key) && map[key] != id) null else id
    }

    private fun key(path: String): String = path.replace('\\', '/').lowercase(Locale.US)

    private fun segments(path: String): List<String> = key(path).split('/').filter { it.isNotEmpty() }
}
