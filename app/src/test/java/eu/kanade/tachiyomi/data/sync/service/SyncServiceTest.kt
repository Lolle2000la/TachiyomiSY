package eu.kanade.tachiyomi.data.sync.service

import android.content.Context
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.domain.sync.models.SyncSettings
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference

class SyncServiceTest {

    private lateinit var syncPreferences: SyncPreferences
    private lateinit var lastSyncTimestampPref: Preference<Long>
    private lateinit var syncService: TestSyncService

    class TestSyncService(
        context: Context,
        json: Json,
        syncPreferences: SyncPreferences,
    ) : SyncService(context, json, syncPreferences) {
        override suspend fun doSync(syncData: SyncData): Backup? = null
    }

    @BeforeEach
    fun setUp() {
        syncPreferences = mockk(relaxed = true)
        lastSyncTimestampPref = mockk(relaxed = true)

        every { syncPreferences.lastSyncTimestamp } returns lastSyncTimestampPref
        every { syncPreferences.getSyncSettings() } returns SyncSettings(
            libraryEntries = true,
            categories = true,
            chapters = true,
        )
        every { syncPreferences.uniqueDeviceID() } returns "test-device-id"

        val context = mockk<Context>(relaxed = true)
        val json = Json { ignoreUnknownKeys = true }
        syncService = TestSyncService(context, json, syncPreferences)
    }

    /**
     * Regression test for https://github.com/jobobby04/TachiyomiSY/issues/1635
     * Newly added local manga (with lastModifiedAt == 0 or older than lastSyncTime)
     * must NOT be dropped during merge when remote does not have it.
     */
    @Test
    fun testMergeMangaLists_localMangaPreservedWhenNotOnRemote() {
        // Last sync was at 1000 seconds (1,000,000 ms)
        every { lastSyncTimestampPref.get() } returns 1_000_000L

        val localManga = BackupManga(
            source = 1L,
            url = "/manga/1",
            title = "Newly Saved Manga",
            favorite = true,
            lastModifiedAt = 0L, // uninitialized or newly added
            version = 1L,
        )

        val merged = syncService.mergeMangaLists(
            localMangaList = listOf(localManga),
            remoteMangaList = emptyList(),
            localCategories = emptyList(),
            remoteCategories = emptyList(),
            mergedCategories = emptyList(),
        )

        merged shouldHaveSize 1
        merged.first().url shouldBe "/manga/1"
        merged.first().title shouldBe "Newly Saved Manga"
    }

    /**
     * Regression test for https://github.com/jobobby04/TachiyomiSY/issues/1635
     * Newly fetched local chapters (with lastModifiedAt == 0 or older than lastSyncTime)
     * must NOT be dropped during merge when remote does not have them.
     */
    @Test
    fun testMergeChapters_localChaptersPreservedWhenNotOnRemote() {
        // Last sync was at 1000 seconds
        val lastSyncTime = 1000L

        val localChapters = listOf(
            BackupChapter(
                url = "/manga/1/chapter/1",
                name = "Chapter 1",
                lastModifiedAt = 0L,
                version = 1L,
            ),
            BackupChapter(
                url = "/manga/1/chapter/2",
                name = "Chapter 2",
                lastModifiedAt = 500L, // older than lastSyncTime
                version = 1L,
            ),
        )

        val merged = syncService.mergeChapters(
            localChapters = localChapters,
            remoteChapters = emptyList(),
            lastSyncTime = lastSyncTime,
            syncingChapters = true,
        )

        merged shouldHaveSize 2
        merged.map { it.url } shouldBe listOf("/manga/1/chapter/1", "/manga/1/chapter/2")
    }

    @Test
    fun testMergeMangaLists_remoteMangaPreservedWhenNotOnLocal() {
        every { lastSyncTimestampPref.get() } returns 1_000_000L

        val remoteManga = BackupManga(
            source = 1L,
            url = "/manga/2",
            title = "Remote Manga",
            favorite = true,
            lastModifiedAt = 500L,
            version = 1L,
        )

        val merged = syncService.mergeMangaLists(
            localMangaList = emptyList(),
            remoteMangaList = listOf(remoteManga),
            localCategories = emptyList(),
            remoteCategories = emptyList(),
            mergedCategories = emptyList(),
        )

        merged shouldHaveSize 1
        merged.first().url shouldBe "/manga/2"
        merged.first().title shouldBe "Remote Manga"
    }

    @Test
    fun testMergeMangaLists_versionConflictResolution() {
        every { lastSyncTimestampPref.get() } returns 0L

        val localManga = BackupManga(
            source = 1L,
            url = "/manga/1",
            title = "Local Newer Title",
            favorite = true,
            version = 2L,
        )
        val remoteManga = BackupManga(
            source = 1L,
            url = "/manga/1",
            title = "Remote Older Title",
            favorite = true,
            version = 1L,
        )

        val merged = syncService.mergeMangaLists(
            localMangaList = listOf(localManga),
            remoteMangaList = listOf(remoteManga),
            localCategories = emptyList(),
            remoteCategories = emptyList(),
            mergedCategories = emptyList(),
        )

        merged shouldHaveSize 1
        merged.first().title shouldBe "Local Newer Title"
        merged.first().version shouldBe 2L
    }

    @Test
    fun testMergeChapters_remoteTombstoneDetection() {
        val lastSyncTime = 1000L

        val deletedRemoteChapter = BackupChapter(
            url = "/manga/1/chapter/deleted",
            name = "Deleted Chapter",
            lastModifiedAt = 500L, // <= lastSyncTime: local doesn't have it, so local deleted it
            version = 1L,
        )
        val newRemoteChapter = BackupChapter(
            url = "/manga/1/chapter/new",
            name = "New Remote Chapter",
            lastModifiedAt = 1500L, // > lastSyncTime: added remotely after last sync
            version = 1L,
        )

        val merged = syncService.mergeChapters(
            localChapters = emptyList(),
            remoteChapters = listOf(deletedRemoteChapter, newRemoteChapter),
            lastSyncTime = lastSyncTime,
            syncingChapters = true,
        )

        // Only the new remote chapter should be retained; deleted one is dropped
        merged shouldHaveSize 1
        merged.first().url shouldBe "/manga/1/chapter/new"
    }

    @Test
    fun testMergeChapters_firstSyncKeepsAllRemoteChapters() {
        val lastSyncTime = 0L // First sync

        val remoteChapter = BackupChapter(
            url = "/manga/1/chapter/1",
            name = "Chapter 1",
            lastModifiedAt = 500L,
            version = 1L,
        )

        val merged = syncService.mergeChapters(
            localChapters = emptyList(),
            remoteChapters = listOf(remoteChapter),
            lastSyncTime = lastSyncTime,
            syncingChapters = true,
        )

        merged shouldHaveSize 1
        merged.first().url shouldBe "/manga/1/chapter/1"
    }

    @Test
    fun testMergeChapters_versionComparisonKeepsHigherVersion() {
        val lastSyncTime = 1000L

        val localChapter = BackupChapter(
            url = "/manga/1/chapter/1",
            name = "Chapter 1",
            read = true,
            lastPageRead = 10L,
            version = 2L,
        )
        val remoteChapter = BackupChapter(
            url = "/manga/1/chapter/1",
            name = "Chapter 1",
            read = false,
            lastPageRead = 0L,
            version = 1L,
        )

        val merged = syncService.mergeChapters(
            localChapters = listOf(localChapter),
            remoteChapters = listOf(remoteChapter),
            lastSyncTime = lastSyncTime,
            syncingChapters = true,
        )

        merged shouldHaveSize 1
        merged.first().read shouldBe true
        merged.first().lastPageRead shouldBe 10L
        merged.first().version shouldBe 2L
    }

    @Test
    fun testMergeSyncData_fullMerge() {
        every { lastSyncTimestampPref.get() } returns 1_000_000L

        val localManga = BackupManga(
            source = 1L,
            url = "/manga/local",
            title = "Local Manga",
            favorite = true,
            chapters = listOf(
                BackupChapter(
                    url = "/manga/local/c1",
                    name = "Chapter 1",
                    lastModifiedAt = 0L,
                    version = 1L,
                ),
            ),
        )
        val remoteManga = BackupManga(
            source = 1L,
            url = "/manga/remote",
            title = "Remote Manga",
            favorite = true,
            chapters = listOf(
                BackupChapter(
                    url = "/manga/remote/c1",
                    name = "Chapter 1",
                    lastModifiedAt = 2000L,
                    version = 1L,
                ),
            ),
        )

        val localCategory = BackupCategory(name = "Manga", order = 0L, uid = 100L, lastModifiedAt = 2000L)
        val remoteCategory = BackupCategory(name = "Anime", order = 1L, uid = 200L, lastModifiedAt = 2000L)

        val localSyncData = SyncData(
            deviceId = "local-device",
            backup = Backup(
                backupManga = listOf(localManga),
                backupCategories = listOf(localCategory),
            ),
        )
        val remoteSyncData = SyncData(
            deviceId = "remote-device",
            backup = Backup(
                backupManga = listOf(remoteManga),
                backupCategories = listOf(remoteCategory),
            ),
        )

        val result = syncService.mergeSyncData(localSyncData, remoteSyncData)
        val mergedBackup = result.backup.shouldNotBeNull()

        mergedBackup.backupManga shouldHaveSize 2
        mergedBackup.backupCategories shouldHaveSize 2
    }
}
