package com.schulzcode.y2player.video

import android.content.Context
import com.schulzcode.y2player.core.model.NaturalTextOrder
import com.schulzcode.y2player.core.state.VideoItem
import com.schulzcode.y2player.core.state.VideoItemKind
import com.schulzcode.y2player.storage.StorageRoot
import java.io.File
import java.util.Locale

/**
 * Lists the Videos section from the filesystem. There is no scan step: a file
 * copied into a Videos folder appears the next time its folder is opened.
 */
object VideoLibrary {
    /** Folder names accepted at the root of a volume; the first that exists wins. */
    val ROOT_FOLDER_NAMES = listOf("Videos", "Video", "Movies")

    private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "3gp", "mkv", "webm", "mov", "avi", "ts")
    private val ARTWORK_NAMES = setOf(
        "folder.jpg", "folder.jpeg", "folder.png",
        "cover.jpg", "cover.jpeg", "cover.png",
        "poster.jpg", "poster.jpeg", "poster.png"
    )

    /**
     * Never a real file. The artwork resolver finds no embedded picture in it and
     * falls through to the cover/poster image in the same folder, which is exactly
     * the lookup wanted for a folder row.
     */
    private const val ARTWORK_PROBE_NAME = ".y2player-folder-art"

    fun isVideoFile(name: String): Boolean =
        !name.startsWith(".") && name.substringAfterLast('.', "").lowercase(Locale.US) in VIDEO_EXTENSIONS

    fun displayTitle(fileName: String): String =
        fileName.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { fileName }

    fun videoRoot(volume: File): File? = ROOT_FOLDER_NAMES.map { File(volume, it) }.firstOrNull { it.isDirectory }

    /**
     * [path] null is the section's first screen. With one volume holding a Videos
     * folder it opens straight into that folder; with two, each volume is a row.
     */
    fun load(path: String?, roots: List<StorageRoot>, resumeMs: (String) -> Long = { 0L }): List<VideoItem> {
        if (path != null) return list(File(path), resumeMs)
        val withVideos = roots.mapNotNull { root -> videoRoot(root.directory)?.let { root to it } }
        return when (withVideos.size) {
            0 -> emptyList()
            1 -> list(withVideos.single().second, resumeMs)
            else -> withVideos.map { (root, directory) ->
                VideoItem(VideoItemKind.VOLUME, volumeName(root.id), directory.absolutePath, countVideos(directory))
            }
        }
    }

    fun list(directory: File, resumeMs: (String) -> Long = { 0L }): List<VideoItem> {
        val items = directory.listFiles().orEmpty().mapNotNull { child ->
            when {
                child.name.startsWith(".") -> null
                child.isDirectory -> {
                    val count = countVideos(child)
                    if (count == 0) null else VideoItem(
                        VideoItemKind.FOLDER, child.name, child.absolutePath, count,
                        artworkPath = artworkProbe(child)
                    )
                }
                child.isFile && isVideoFile(child.name) -> VideoItem(
                    VideoItemKind.VIDEO, displayTitle(child.name), child.absolutePath,
                    sizeBytes = child.length(), resumeMs = resumeMs(child.absolutePath)
                )
                else -> null
            }
        }
        return sort(items)
    }

    /** Folders first, then videos, each in natural order (Episode 2 before Episode 10). */
    fun sort(items: List<VideoItem>): List<VideoItem> = items.sortedWith { first, second ->
        val rank = compareValues(first.kind.ordinal, second.kind.ordinal)
        if (rank != 0) rank else NaturalTextOrder.compare(first.title.lowercase(Locale.US), second.title.lowercase(Locale.US))
    }

    /** Subtitles beside a video: `Film.srt` next to `Film.mp4`. */
    fun subtitleFor(video: File): File? {
        val base = video.name.substringBeforeLast('.')
        return video.parentFile?.listFiles().orEmpty().firstOrNull {
            it.isFile && it.name.substringBeforeLast('.') == base && it.name.endsWith(".srt", ignoreCase = true)
        }
    }

    fun volumeName(id: String): String = when (id) {
        "internal" -> "Internal storage"
        "sdcard" -> "SD card"
        else -> id
    }

    private fun artworkProbe(folder: File): String? {
        val hasArtwork = folder.list().orEmpty().any { it.lowercase(Locale.US) in ARTWORK_NAMES }
        return if (hasArtwork) File(folder, ARTWORK_PROBE_NAME).absolutePath else null
    }

    private fun countVideos(directory: File, depth: Int = 0): Int {
        if (depth > MAX_DEPTH) return 0
        return directory.listFiles().orEmpty().sumOf { child ->
            when {
                child.name.startsWith(".") -> 0
                child.isDirectory -> countVideos(child, depth + 1)
                isVideoFile(child.name) -> 1
                else -> 0
            }
        }
    }

    private const val MAX_DEPTH = 4
}

/** Where each video was left, keyed by absolute path. */
class VideoResumeStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("video_resume", Context.MODE_PRIVATE)

    fun get(path: String): Long = preferences.getLong(path, 0L)

    fun save(path: String, positionMs: Long, durationMs: Long) {
        val keep = VideoResumePolicy.positionToStore(positionMs, durationMs)
        preferences.edit().apply { if (keep == null) remove(path) else putLong(path, keep) }.apply()
    }

    fun clear(path: String) = preferences.edit().remove(path).apply()
}

/**
 * Positions in the first few seconds are not worth resuming, and a video
 * stopped in its closing stretch counts as watched.
 */
object VideoResumePolicy {
    const val MIN_RESUME_MS = 10_000L
    private const val FINISHED_TAIL_MS = 60_000L
    private const val FINISHED_FRACTION = 0.95

    fun positionToStore(positionMs: Long, durationMs: Long): Long? {
        if (positionMs < MIN_RESUME_MS) return null
        if (durationMs > 0) {
            if (positionMs >= durationMs - FINISHED_TAIL_MS.coerceAtMost(durationMs / 10)) return null
            if (positionMs >= durationMs * FINISHED_FRACTION) return null
        }
        return positionMs
    }

    fun startPosition(storedMs: Long, durationMs: Long): Long = when {
        storedMs < MIN_RESUME_MS -> 0L
        durationMs > 0 && storedMs >= durationMs -> 0L
        else -> storedMs
    }
}
