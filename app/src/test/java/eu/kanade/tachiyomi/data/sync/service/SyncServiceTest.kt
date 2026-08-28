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

/**
 * Tests for the merge rules in [SyncService].
 *
 * Units: `SyncPreferences.lastSyncTimestamp` stores milliseconds, while `lastModifiedAt` on the
 * backup models is in seconds (it mirrors the `last_modified_at` column, which is written with
 * `strftime('%s', 'now')`). The [LAST_SYNC_MILLIS] constant below is therefore converted to seconds
 * when asserting on entry timestamps.
 *
 * The "kept" cases for entries that only one side has deliberately use timestamps that are newer
 * than the last sync, because that is what mangas.sq/chapters.sq guarantee: every insert stamps
 * `last_modified_at`. See https://github.com/jobobby04/TachiyomiSY/issues/1635.
 */
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
     * A manga that was added locally after the last sync must survive the merge even though the
     * remote does not know it yet.
     */
    @Test
    fun testMergeMangaLists_localMangaPreservedWhenNotOnRemote() {
        every { lastSyncTimestampPref.get() } returns LAST_SYNC_MILLIS

        val localManga = BackupManga(
            source = 1L,
            url = "/manga/1",
            title = "Newly Added Manga",
            favorite = true,
            lastModifiedAt = JUST_ADDED, // stamped by the insert, newer than the last sync
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
        merged.first().title shouldBe "Newly Added Manga"
    }

    /**
     * An entry that has not been touched since the last sync but is gone from the remote was
     * deleted on the other device, so the deletion has to propagate. Without this rule deleted
     * entries are resurrected on every sync.
     */
    @Test
    fun testMergeMangaLists_localMangaDroppedWhenDeletedOnRemote() {
        every { lastSyncTimestampPref.get() } returns LAST_SYNC_MILLIS

        val staleLocalManga = BackupManga(
            source = 1L,
            url = "/manga/1",
            title = "Manga Deleted On The Other Device",
            favorite = true,
            lastModifiedAt = BEFORE_LAST_SYNC,
            version = 1L,
        )

        val merged = syncService.mergeMangaLists(
            localMangaList = listOf(staleLocalManga),
            remoteMangaList = emptyList(),
            localCategories = emptyList(),
            remoteCategories = emptyList(),
            mergedCategories = emptyList(),
        )

        merged shouldHaveSize 0
    }

    /**
     * Symmetric to the case above: an entry that only the remote has and that predates the last
     * sync was deleted locally, so it must not be pulled back in.
     */
    @Test
    fun testMergeMangaLists_staleRemoteMangaDroppedWhenDeletedLocally() {
        every { lastSyncTimestampPref.get() } returns LAST_SYNC_MILLIS

        val merged = syncService.mergeMangaLists(
            localMangaList = emptyList(),
            remoteMangaList = listOf(
                BackupManga(
                    source = 1L,
                    url = "/manga/2",
                    title = "Deleted Locally",
                    favorite = true,
                    lastModifiedAt = BEFORE_LAST_SYNC,
                    version = 1L,
                ),
            ),
            localCategories = emptyList(),
            remoteCategories = emptyList(),
            mergedCategories = emptyList(),
        )

        merged shouldHaveSize 0
    }

    @Test
    fun testMergeMangaLists_remoteMangaPreservedWhenNotOnLocal() {
        every { lastSyncTimestampPref.get() } returns LAST_SYNC_MILLIS

        val remoteManga = BackupManga(
            source = 1L,
            url = "/manga/2",
            title = "Remote Manga",
            favorite = true,
            lastModifiedAt = JUST_ADDED, // added on the other device after our last sync
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
    fun testMergeMangaLists_firstSyncKeepsLocalManga() {
        every { lastSyncTimestampPref.get() } returns 0L // never synced before

        val merged = syncService.mergeMangaLists(
            localMangaList = listOf(
                BackupManga(
                    source = 1L,
                    url = "/manga/1",
                    title = "Pre-existing Manga",
                    favorite = true,
                    lastModifiedAt = 0L, // migrated over before insert stamping existed
                    version = 1L,
                ),
            ),
            remoteMangaList = emptyList(),
            localCategories = emptyList(),
            remoteCategories = emptyList(),
            mergedCategories = emptyList(),
        )

        merged shouldHaveSize 1
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

    /**
     * Regression test for https://github.com/jobobby04/TachiyomiSY/issues/1635
     * Chapters fetched locally after the last sync (new manga or a source that gained chapters)
     * must NOT be dropped during merge when the remote does not have them yet.
     */
    @Test
    fun testMergeChapters_localChaptersPreservedWhenNotOnRemote() {
        val localChapters = listOf(
            BackupChapter(
                url = "/manga/1/chapter/1",
                name = "Chapter 1",
                lastModifiedAt = JUST_ADDED, // stamped by the insert
                version = 1L,
            ),
            BackupChapter(
                url = "/manga/1/chapter/2",
                name = "Chapter 2",
                lastModifiedAt = JUST_ADDED + 1,
                version = 1L,
            ),
        )

        val merged = syncService.mergeChapters(
            localChapters = localChapters,
            remoteChapters = emptyList(),
            lastSyncTime = LAST_SYNC_SECONDS,
            syncingChapters = true,
        )

        merged shouldHaveSize 2
        merged.map { it.url } shouldBe listOf("/manga/1/chapter/1", "/manga/1/chapter/2")
    }

    /**
     * Chapters removed by the other device (e.g. a source that dropped or renumbered a chapter via
     * SyncChaptersWithSource) have no tombstone, so the timestamp comparison is the only thing that
     * propagates the deletion.
     */
    @Test
    fun testMergeChapters_localChaptersDroppedWhenDeletedOnRemote() {
        val merged = syncService.mergeChapters(
            localChapters = listOf(
                BackupChapter(
                    url = "/manga/1/chapter/gone",
                    name = "Removed On The Other Device",
                    lastModifiedAt = BEFORE_LAST_SYNC,
                    version = 1L,
                ),
            ),
            remoteChapters = emptyList(),
            lastSyncTime = LAST_SYNC_SECONDS,
            syncingChapters = true,
        )

        merged shouldHaveSize 0
    }

    @Test
    fun testMergeChapters_remoteTombstoneDetection() {
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
            lastSyncTime = LAST_SYNC_SECONDS,
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
            lastSyncTime = LAST_SYNC_SECONDS,
            syncingChapters = true,
        )

        merged shouldHaveSize 1
        merged.first().read shouldBe true
        merged.first().lastPageRead shouldBe 10L
        merged.first().version shouldBe 2L
    }

    @Test
    fun testMergeChapters_skippedWhenChapterSyncDisabled() {
        val merged = syncService.mergeChapters(
            localChapters = listOf(
                BackupChapter(url = "/manga/1/chapter/1", name = "Chapter 1", version = 1L),
            ),
            remoteChapters = listOf(
                BackupChapter(url = "/manga/1/chapter/2", name = "Chapter 2", version = 1L),
            ),
            lastSyncTime = LAST_SYNC_SECONDS,
            syncingChapters = false,
        )

        // Remote chapters are handed back untouched when chapter sync is off
        merged shouldHaveSize 1
        merged.first().url shouldBe "/manga/1/chapter/2"
    }

    @Test
    fun testMergeSyncData_fullMerge() {
        every { lastSyncTimestampPref.get() } returns LAST_SYNC_MILLIS

        val localManga = BackupManga(
            source = 1L,
            url = "/manga/local",
            title = "Local Manga",
            favorite = true,
            lastModifiedAt = JUST_ADDED,
            chapters = listOf(
                BackupChapter(
                    url = "/manga/local/c1",
                    name = "Chapter 1",
                    lastModifiedAt = JUST_ADDED, // stamped by the insert
                    version = 1L,
                ),
            ),
        )
        val remoteManga = BackupManga(
            source = 1L,
            url = "/manga/remote",
            title = "Remote Manga",
            favorite = true,
            lastModifiedAt = 2000L,
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
        // The locally added manga and its chapter survive the merge
        mergedBackup.backupManga.first { it.url == "/manga/local" }.chapters shouldHaveSize 1
    }

    private companion object {
        /** The last successful sync, 1,000 s after the epoch, stored in milliseconds. */
        const val LAST_SYNC_MILLIS = 1_000_000L
        const val LAST_SYNC_SECONDS = 1_000L

        /** Newer than [LAST_SYNC_SECONDS]: what mangas.sq/chapters.sq stamp on a fresh insert. */
        const val JUST_ADDED = 1_500L

        /** Older than [LAST_SYNC_SECONDS]: untouched since the last sync. */
        const val BEFORE_LAST_SYNC = 500L
    }
}
