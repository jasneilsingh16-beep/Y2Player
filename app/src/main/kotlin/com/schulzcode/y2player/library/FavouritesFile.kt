package com.schulzcode.y2player.library

import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.storage.StorageRoot
import com.schulzcode.y2player.storage.preferredWritableRoot
import java.io.File

/**
 * Favourites live on the card, in Playlists/Favourites.m3u8, not only in the
 * app's own database. Installing firmware wipes the database; the card keeps
 * the file, so favourites come back on the first scan. The file is a plain M3U
 * with paths from the card's root, so it can be edited on a computer too.
 *
 * The file is the record: whenever the app changes a favourite it rewrites the
 * file, and a scan makes the database match the file. A rewrite that fails
 * (card busy or removed) marks the database as ahead, and the next scan writes
 * the database out instead of reading the file back.
 */
internal class FavouritesFile(private val rootsProvider: () -> List<StorageRoot>) {

    /** The file that exists, sdcard first; else where a new one would go. */
    fun locate(): Pair<StorageRoot, File>? {
        val roots = rootsProvider().sortedBy { if (it.id == "sdcard") 0 else 1 }
        roots.forEach { root -> existing(root)?.let { return root to it } }
        val root = preferredWritableRoot(roots) ?: return null
        return root to File(File(root.directory, DIRECTORY), FILE_NAME)
    }

    /** Writes [tracks] in order. False if the card cannot be written now. */
    fun write(tracks: List<Track>): Boolean {
        val (root, file) = locate() ?: return false
        val directory = file.parentFile ?: return false
        if (!directory.isDirectory && !directory.mkdirs()) return false
        val temp = File(directory, ".${file.name}.tmp")
        return runCatching {
            temp.outputStream().use { stream -> stream.write(render(tracks, root).toByteArray(Charsets.UTF_8)) }
            // FAT cannot rename over a file, so the old one goes first; the temp
            // file is still there if the rename then fails.
            if (file.exists() && !file.delete()) error("cannot replace ${file.name}")
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            true
        }.getOrElse {
            temp.delete()
            false
        }
    }

    private fun existing(root: StorageRoot): File? {
        val directory = root.directory.listFiles()
            ?.firstOrNull { it.isDirectory && it.name.equals(DIRECTORY, ignoreCase = true) } ?: return null
        return directory.listFiles()?.firstOrNull { it.isFile && isFavouritesName(it.name) }
    }

    companion object {
        const val DIRECTORY = "Playlists"
        const val FILE_NAME = "Favourites.m3u8"
        private val NAMES = setOf("favourites.m3u8", "favourites.m3u", "favorites.m3u8", "favorites.m3u")

        fun isFavouritesName(name: String): Boolean = name.lowercase() in NAMES

        fun isFavouritesFile(file: File): Boolean =
            isFavouritesName(file.name) && file.parentFile?.name.equals(DIRECTORY, ignoreCase = true)

        /** Paths from the card's root, as the other playlists on the card are written, with CRLF for Windows. */
        fun render(tracks: List<Track>, root: StorageRoot): String = buildString {
            append("#EXTM3U\r\n")
            tracks.forEach { track ->
                append(if (track.volumeId == root.id) track.relativePath.trimStart('/') else track.absolutePath)
                append("\r\n")
            }
        }

        /**
         * The order to write: the file's order for what is still a favourite, then
         * anything new, oldest first.
         */
        fun ordered(fileOrder: List<Long>, favourites: Collection<Long>): List<Long> {
            val wanted = LinkedHashSet(favourites)
            val result = ArrayList<Long>(wanted.size)
            fileOrder.forEach { if (wanted.remove(it)) result += it }
            result.addAll(wanted)
            return result
        }
    }
}
