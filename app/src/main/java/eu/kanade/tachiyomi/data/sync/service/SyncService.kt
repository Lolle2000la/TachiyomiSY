package eu.kanade.tachiyomi.data.sync.service

import android.content.Context
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.models.Backup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SyncData(
    val deviceId: String = "",
    val backup: Backup? = null,
)

abstract class SyncService(
    val context: Context,
    val json: Json,
    val syncPreferences: SyncPreferences,
) {
    /** Holds the merge rules, kept separate so they can be unit tested without a [Context]. */
    protected val syncMerger = SyncMerger(syncPreferences)

    abstract suspend fun doSync(syncData: SyncData): Backup?
}
