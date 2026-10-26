package com.schulzcode.y2player.video

import android.content.Context
import com.schulzcode.y2player.core.model.NaturalTextOrder
import com.schulzcode.y2player.core.state.VideoItem
import com.schulzcode.y2player.core.state.VideoItemKind
import com.schulzcode.y2player.core.state.VideoProgress
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
    fun load(
        path: String?,
        roots: List<StorageRoot>,
        resumeMs: (String) -> Long = { 0L },
        watched: (String) -> Boolean = { false },
        lastWatched: () -> String? = { null }
    ): List<VideoItem> {
        if (path != null) return list(File(path), watched, resumeMs)
        val withVideos = roots.mapNotNull { root -> videoRoot(root.directory)?.let { root to it } }
        val items = when (withVideos.size) {
            0 -> emptyList()
            1 -> list(withVideos.single().second, watched, resumeMs)
            else -> withVideos.map { (root, directory) ->
                VideoItem(VideoItemKind.VOLUME, volumeName(root.id), directory.absolutePath, countVideos(directory))
            }
        }
        val continuing = if (items.isEmpty()) null else continueWatching(lastWatched(), resumeMs)
        return listOfNotNull(continuing) + items
    }

    /** The video last left part-way, offered first so one press picks it back up. */
    private fun continueWatching(path: String?, resumeMs: (String) -> Long): VideoItem? {
        val file = path?.let(::File)?.takeIf { it.isFile } ?: return null
        val position = resumeMs(file.absolutePath).takeIf { it > 0 } ?: return null
        val siblings = siblings(file)
        return VideoItem(
            VideoItemKind.CONTINUE, displayTitle(file.name), file.absolutePath,
            resumeMs = position, playlist = siblings
        )
    }

    /** The videos beside [file], in natural order, so the next episode follows. */
    fun siblings(file: File): List<String> = file.parentFile?.listFiles().orEmpty()
        .filter { it.isFile && isVideoFile(it.name) }
        .sortedWith { first, second ->
            NaturalTextOrder.compare(first.name.lowercase(Locale.US), second.name.lowercase(Locale.US))
        }
        .map { it.absolutePath }

    fun artworkFor(folder: File): String? = artworkProbe(folder)

    /**
     * Up to three posters for a folder's strip: those of its subfolders (one film or
     * show each), or else its own cover.
     */
    fun posters(folder: File): List<String> {
        val inside = folder.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedWith { first, second ->
                NaturalTextOrder.compare(first.name.lowercase(Locale.US), second.name.lowercase(Locale.US))
            }
            .mapNotNull(::artworkProbe)
            .take(3)
        return inside.ifEmpty { listOfNotNull(artworkProbe(folder)) }
    }

    fun list(
        directory: File,
        watched: (String) -> Boolean = { false },
        resumeMs: (String) -> Long = { 0L }
    ): List<VideoItem> {
        val items = directory.listFiles().orEmpty().mapNotNull { child ->
            when {
                child.name.startsWith(".") -> null
                child.isDirectory -> {
                    val count = countVideos(child)
                    if (count == 0) null else VideoItem(
                        VideoItemKind.FOLDER, child.name, child.absolutePath, count,
                        artworkPath = artworkProbe(child),
                        posterPaths = posters(child)
                    )
                }
                child.isFile && isVideoFile(child.name) -> VideoItem(
                    VideoItemKind.VIDEO, displayTitle(child.name), child.absolutePath,
                    sizeBytes = child.length(), resumeMs = resumeMs(child.absolutePath),
                    watched = watched(child.absolutePath)
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

    /** Every video in a folder and the folders inside it. */
    fun videosIn(directory: File, depth: Int = 0): List<String> {
        if (depth > MAX_DEPTH) return emptyList()
        return directory.listFiles().orEmpty().flatMap { child ->
            when {
                child.name.startsWith(".") -> emptyList()
                child.isDirectory -> videosIn(child, depth + 1)
                isVideoFile(child.name) -> listOf(child.absolutePath)
                else -> emptyList()
            }
        }
    }

    private const val MAX_DEPTH = 4
}

/**
 * Where each video was left, keyed by absolute path, plus when it was last
 * watched and whether it has been finished. Paths are absolute, so the prefixed
 * keys can never collide with a position key.
 */
class VideoResumeStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("video_resume", Context.MODE_PRIVATE)

    fun get(path: String): Long = runCatching { preferences.getLong(path, 0L) }.getOrDefault(0L)

    fun isWatched(path: String): Boolean = runCatching { preferences.getBoolean(WATCHED_PREFIX + path, false) }.getOrDefault(false)

    fun save(path: String, positionMs: Long, durationMs: Long) {
        val keep = VideoResumePolicy.positionToStore(positionMs, durationMs)
        val finished = VideoResumePolicy.isFinished(positionMs, durationMs)
        preferences.edit().apply {
            if (keep == null) {
                remove(path)
                remove(TIME_PREFIX + path)
                remove(DURATION_PREFIX + path)
            } else {
                putLong(path, keep)
                putLong(TIME_PREFIX + path, System.currentTimeMillis())
                if (durationMs > 0) putLong(DURATION_PREFIX + path, durationMs)
                remove(WATCHED_PREFIX + path)
            }
            if (finished) putBoolean(WATCHED_PREFIX + path, true)
        }.apply()
    }

    /** Played to the end: the position is forgotten and the video is marked watched. */
    fun markWatched(path: String) = preferences.edit()
        .remove(path)
        .remove(TIME_PREFIX + path)
        .remove(DURATION_PREFIX + path)
        .putBoolean(WATCHED_PREFIX + path, true)
        .apply()

    /** Watched keeps it finished; unwatched also forgets its place, so it starts fresh. */
    fun setWatched(path: String, watched: Boolean) {
        preferences.edit().apply {
            remove(path)
            remove(TIME_PREFIX + path)
            remove(DURATION_PREFIX + path)
            if (watched) putBoolean(WATCHED_PREFIX + path, true) else remove(WATCHED_PREFIX + path)
        }.commit()
    }

    /** The most recently left video that still has a position to resume. */
    fun lastWatched(): String? = runCatching {
        preferences.all.entries
            .filter { it.key.startsWith(TIME_PREFIX) && it.value is Long }
            .sortedByDescending { it.value as Long }
            .map { it.key.removePrefix(TIME_PREFIX) }
            .firstOrNull { get(it) > 0 }
    }.getOrNull()

    /** Every video left part-way that is still on the card, most recent first. */
    fun inProgress(limit: Int = 12): List<VideoProgress> = runCatching {
        preferences.all.entries
            .filter { it.key.startsWith(TIME_PREFIX) && it.value is Long }
            .sortedByDescending { it.value as Long }
            .mapNotNull { entry ->
                val path = entry.key.removePrefix(TIME_PREFIX)
                val position = get(path).takeIf { it > 0 } ?: return@mapNotNull null
                val file = File(path).takeIf { it.isFile } ?: return@mapNotNull null
                val folder = file.parentFile
                val folderName = folder?.name?.takeUnless { name ->
                    VideoLibrary.ROOT_FOLDER_NAMES.any { it.equals(name, ignoreCase = true) }
                }
                VideoProgress(
                    path = path,
                    title = VideoLibrary.displayTitle(file.name),
                    positionMs = position,
                    durationMs = runCatching { preferences.getLong(DURATION_PREFIX + path, 0L) }.getOrDefault(0L),
                    updatedAt = entry.value as Long,
                    folderName = folderName,
                    artworkPath = folder?.let(VideoLibrary::artworkFor),
                    playlist = VideoLibrary.siblings(file)
                )
            }
            .take(limit)
    }.getOrDefault(emptyList())

    private companion object {
        const val TIME_PREFIX = "t:"
        const val WATCHED_PREFIX = "w:"
        const val DURATION_PREFIX = "d:"
    }
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

    /** Stopped in the closing stretch: counts as watched. */
    fun isFinished(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs >= MIN_RESUME_MS && positionToStore(positionMs, durationMs) == null

    fun startPosition(storedMs: Long, durationMs: Long): Long = when {
        storedMs < MIN_RESUME_MS -> 0L
        durationMs > 0 && storedMs >= durationMs -> 0L
        else -> storedMs
    }
}
