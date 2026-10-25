package com.schulzcode.y2player.core.state

/**
 * Videos are browsed straight from the filesystem, not from the music library.
 * Each opened folder is listed once on a background thread and the result is
 * kept here so the list renders like any other Y2Player screen.
 *
 * A kind's ordinal is its sort rank: Continue Watching leads, then volumes,
 * folders and videos.
 */
enum class VideoItemKind { CONTINUE, VOLUME, FOLDER, VIDEO }

data class VideoItem(
    val kind: VideoItemKind,
    val title: String,
    val path: String,
    val videoCount: Int = 0,
    val sizeBytes: Long = 0,
    val resumeMs: Long = 0,
    /** A path inside the folder whose cover.jpg / poster.jpg should be shown, if any. */
    val artworkPath: String? = null,
    /** Finished at least once; shown until it is started again. */
    val watched: Boolean = false,
    /** For [VideoItemKind.CONTINUE]: the videos of its folder, so the next episode follows. */
    val playlist: List<String> = emptyList()
)

data class VideoBrowseState(
    /** Listings keyed by folder path; [TOP_LEVEL] is the section's first screen. */
    val listings: Map<String, List<VideoItem>> = emptyMap()
) {
    fun listing(path: String?): List<VideoItem>? = listings[path ?: TOP_LEVEL]

    companion object {
        const val TOP_LEVEL = ""
    }
}
