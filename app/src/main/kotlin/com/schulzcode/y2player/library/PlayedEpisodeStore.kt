package com.schulzcode.y2player.library

import android.content.Context
import com.schulzcode.y2player.core.model.Track

/**
 * Episodes heard to the end (90%), kept per file as "volume|path" so a rescan or
 * a new card keeps them. Shared by the player, which marks them, and the screens.
 */
class PlayedEpisodeStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("played_episodes", Context.MODE_PRIVATE)

    fun all(): Set<String> = runCatching { preferences.getStringSet(KEY, emptySet())!!.toSet() }.getOrDefault(emptySet())

    /** True when something changed. */
    @Synchronized
    fun set(keys: Collection<String>, played: Boolean): Boolean {
        val current = all()
        val next = if (played) current + keys else current - keys.toSet()
        if (next == current) return false
        return preferences.edit().putStringSet(KEY, HashSet(next)).commit()
    }

    companion object {
        private const val KEY = "played"
        fun keyOf(track: Track): String = track.volumeId + "|" + track.relativePath
    }
}
