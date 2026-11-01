package com.schulzcode.y2player.backup

import com.schulzcode.y2player.core.state.PlayerPreferencesState
import com.schulzcode.y2player.library.LibraryDatabase
import com.schulzcode.y2player.settings.AppPreferences
import com.schulzcode.y2player.storage.StorageRoot
import com.schulzcode.y2player.storage.preferredWritableRoot
import java.io.File

class UserDataBackupManager(
    private val database: LibraryDatabase,
    private val preferences: AppPreferences,
    private val history: HistoryStore,
    private val appVersion: String,
    private val rootsProvider: () -> List<StorageRoot>,
    /** Episodes heard to the end, carried in the backup so a reflash keeps them. */
    private val playedEpisodes: () -> Set<String> = { emptySet() },
    private val restorePlayedEpisodes: (Set<String>) -> Unit = {}
) {
    interface HistoryStore {
        fun records(): List<String>
        fun replace(records: List<String>): Boolean
    }

    data class Preview(
        val file: File,
        val document: BackupDocument,
        val settings: PlayerPreferencesState,
        val resolved: ResolvedUserData
    ) {
        val summary: String get() = buildString {
            append(document.userData.playlists.size).append(" playlists, ")
            append(document.userData.favorites.size).append(" favourites, ")
            append(document.userData.audiobookProgress.size).append(" book positions, ")
            append(document.userData.recentlyPlayed.size).append(" recent tracks")
            if (resolved.unresolvedReferences > 0) append(" · ${resolved.unresolvedReferences} tracks currently missing")
        }
    }

    data class ExportResult(val file: File, val references: Int)
    data class ImportResult(val restoredReferences: Int, val unresolvedReferences: Int)

    /**
     * Writes a backup. [automatic] writes the card's own copy, kept up to date as
     * the player is used, so a firmware install, which wipes the app's data, can
     * be undone without remembering to export first. It never touches the file
     * Export Backup writes.
     */
    fun export(nowUtcMs: Long = System.currentTimeMillis(), automatic: Boolean = false): Result<ExportResult> = runCatching {
        val file = backupFile(name = if (automatic) AUTO_FILE_NAME else BackupFormat.FILE_NAME)
        val userData = database.exportPortableUserData()
        val historyRecords = history.records()
        val document = BackupDocument(
            appVersion = appVersion,
            createdAtUtcMs = nowUtcMs,
            settings = PreferenceBackup.encode(preferences.snapshot()) +
                (PLAYED_EPISODES_KEY to playedEpisodes().joinToString("\n")) +
                // Android keeps the zone with the system, which an install resets to GMT.
                (TIME_ZONE_KEY to java.util.TimeZone.getDefault().id),
            userData = userData,
            listeningHistory = historyRecords
        )
        BackupFormat.writeAtomic(file, document)
        ExportResult(file, referenceCount(userData))
    }

    fun preview(): Result<Preview> = runCatching { preview(backupFile(requireExisting = true)) }

    /** The newer of the automatic and the exported backup, for restoring after an install. */
    fun newestPreview(): Result<Preview> = runCatching {
        listOf(AUTO_FILE_NAME, BackupFormat.FILE_NAME)
            .mapNotNull { name -> runCatching { preview(backupFile(name = name, requireExisting = true)) }.getOrNull() }
            .maxByOrNull { it.document.createdAtUtcMs } ?: error("No backup on the card")
    }

    private fun preview(file: File): Preview {
        val document = BackupFormat.read(file)
        val settings = PreferenceBackup.decode(document.settings)
        val resolved = PortableUserDataResolver.resolve(document.userData, database.loadTracks())
        return Preview(file, document, settings, resolved)
    }

    fun import(preview: Preview): Result<ImportResult> = runCatching {
        // Validate the card file again immediately before mutation.
        val current = BackupFormat.read(preview.file)
        require(current == preview.document) { "Backup changed after validation; select Import Backup again" }
        val settings = PreferenceBackup.decode(current.settings)
        val resolved = PortableUserDataResolver.resolve(current.userData, database.loadTracks())
        val oldSettings = preferences.snapshot()
        val oldHistory = history.records()
        ImportTransactionCoordinator.apply(
            databaseTransaction = { external -> database.replacePortableUserData(resolved, external) },
            applyHistory = { history.replace(current.listeningHistory) },
            applySettings = { preferences.restore(settings) },
            rollbackHistory = { history.replace(oldHistory) },
            rollbackSettings = { preferences.restore(oldSettings) }
        )
        current.settings[PLAYED_EPISODES_KEY]?.let { joined ->
            runCatching { restorePlayedEpisodes(joined.split('\n').filter { it.isNotBlank() }.toSet()) }
        }
        ImportResult(resolved.restoredReferences, resolved.unresolvedReferences)
    }

    companion object {
        private const val PLAYED_EPISODES_KEY = "played_episodes"
        const val TIME_ZONE_KEY = "system_time_zone"
        const val AUTO_FILE_NAME = "Y2Player-auto.y2backup"
    }

    private fun backupFile(name: String = BackupFormat.FILE_NAME, requireExisting: Boolean = false): File {
        val root = preferredWritableRoot(rootsProvider()) ?: error("No writable storage is mounted")
        val file = File(root.directory, "Y2Player/Backups/$name")
        if (requireExisting && !file.isFile) error("No backup found at ${file.absolutePath}")
        return file
    }

    private fun referenceCount(data: PortableUserData): Int =
        data.favorites.size + data.playlists.sumOf { it.tracks.size } + data.audiobookProgress.size +
            data.recentlyPlayed.size + (data.queue?.tracks?.size ?: 0)
}
