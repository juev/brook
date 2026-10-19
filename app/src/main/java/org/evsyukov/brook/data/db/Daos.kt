package org.evsyukov.brook.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A row of the list: the entry without its text, with the name of its feed. */
data class EntryListItem(
    val id: Long,
    val feedId: String,
    val feedTitle: String?,
    val title: String,
    val author: String,
    val url: String,
    val sortTime: Long,
    val isRead: Boolean,
    val isStarred: Boolean,
    /** The first part of the text, for the preview line. */
    val excerpt: String,
    /** The icon of the feed; null when the feed is no longer subscribed. */
    val feedIconUrl: String?,
    /** The text from its first `<img` on, for the thumbnail; null without one. */
    val imageTag: String?,
    val enclosuresJson: String,
)

data class UnreadCount(val key: String, val count: Int)

data class EntryState(val id: Long, val isRead: Boolean, val isStarred: Boolean)

@Dao
interface TreeDao {
    @Query("SELECT * FROM categories ORDER BY name COLLATE NOCASE")
    fun categories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM labels ORDER BY name COLLATE NOCASE")
    fun labels(): Flow<List<LabelEntity>>

    @Query("SELECT * FROM labels")
    suspend fun labelList(): List<LabelEntity>

    @Query("SELECT * FROM feeds ORDER BY title COLLATE NOCASE")
    fun feeds(): Flow<List<FeedEntity>>

    @Query("SELECT * FROM feeds WHERE id = :id")
    suspend fun feed(id: String): FeedEntity?

    @Query("SELECT f.id AS `key`, COUNT(e.id) AS count FROM feeds f JOIN entries e ON e.feedId = f.id AND e.isRead = 0 GROUP BY f.id")
    fun unreadByFeed(): Flow<List<UnreadCount>>

    @Query("SELECT l.label AS `key`, COUNT(e.id) AS count FROM entry_labels l JOIN entries e ON e.id = l.entryId AND e.isRead = 0 GROUP BY l.label")
    fun unreadByLabel(): Flow<List<UnreadCount>>

    @Query("SELECT COUNT(*) FROM entries e JOIN feeds f ON f.id = e.feedId WHERE e.isRead = 0")
    fun unreadTotal(): Flow<Int>

    @Query("SELECT COUNT(*) FROM entries WHERE isStarred = 1")
    fun starredTotal(): Flow<Int>

    @Query("DELETE FROM categories")
    suspend fun clearCategories()

    @Query("DELETE FROM labels")
    suspend fun clearLabels()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLabels(labels: List<LabelEntity>)

    @Query("DELETE FROM feeds WHERE id NOT IN (:keep)")
    suspend fun deleteFeedsExcept(keep: List<String>)

    @Upsert
    suspend fun upsertFeeds(feeds: List<FeedEntity>)

    @Transaction
    suspend fun replaceTree(categories: List<CategoryEntity>, labels: List<LabelEntity>, feeds: List<FeedEntity>) {
        clearCategories()
        insertCategories(categories)
        clearLabels()
        insertLabels(labels)
        deleteFeedsExcept(feeds.map { it.id })
        upsertFeeds(feeds)
    }

    /** Categories and labels share the names of `user/-/label/<name>`. */
    @Query("SELECT EXISTS(SELECT 1 FROM categories WHERE name = :name) OR EXISTS(SELECT 1 FROM labels WHERE name = :name)")
    suspend fun nameTaken(name: String): Boolean

    @Query("UPDATE OR IGNORE labels SET name = :newName WHERE name = :name")
    suspend fun renameLabel(name: String, newName: String)

    @Query("DELETE FROM labels WHERE name = :name")
    suspend fun deleteLabel(name: String)

    @Query("SELECT * FROM feed_settings WHERE feedId = :feedId")
    fun feedSettings(feedId: String): Flow<FeedSettingsEntity?>

    @Query("SELECT feedId FROM feed_settings WHERE alwaysFullText = 1")
    suspend fun fullTextFeeds(): List<String>

    @Upsert
    suspend fun upsertFeedSettings(settings: FeedSettingsEntity)
}

@Dao
interface EntryDao {
    /**
     * The list of a scope and filter, newest first. Exactly one of [feedId], [category], [label] is set
     * for a narrower scope; none for all subscriptions. Starred entries of feeds no longer subscribed stay listed.
     */
    @Query(
        """
        SELECT e.id, e.feedId, f.title AS feedTitle, e.title, e.author, e.url, e.sortTime, e.isRead, e.isStarred,
               substr(e.content, 1, 600) AS excerpt, f.iconUrl AS feedIconUrl,
               CASE WHEN instr(e.content, '<img') > 0 THEN substr(e.content, instr(e.content, '<img'), 1000) END AS imageTag,
               e.enclosuresJson
        FROM entries e LEFT JOIN feeds f ON f.id = e.feedId
        WHERE (:feedId IS NULL OR e.feedId = :feedId)
          AND (:category IS NULL OR f.categoryName = :category)
          AND (:label IS NULL OR e.id IN (SELECT entryId FROM entry_labels WHERE label = :label))
          AND (:onlyUnread = 0 OR e.isRead = 0)
          AND (:onlyStarred = 0 OR e.isStarred = 1)
          AND (f.id IS NOT NULL OR e.isStarred = 1)
        ORDER BY e.sortTime DESC, e.id DESC
        """,
    )
    fun list(feedId: String?, category: String?, label: String?, onlyUnread: Boolean, onlyStarred: Boolean): PagingSource<Int, EntryListItem>

    /**
     * The entry after the one at ([time], [id]) in the order of [list], or before it when [older] is
     * false. With [onlyUnread], entries of [keep] count as unread: the reader read them on the way.
     */
    @Query(
        """
        SELECT e.id FROM entries e LEFT JOIN feeds f ON f.id = e.feedId
        WHERE (:feedId IS NULL OR e.feedId = :feedId)
          AND (:category IS NULL OR f.categoryName = :category)
          AND (:label IS NULL OR e.id IN (SELECT entryId FROM entry_labels WHERE label = :label))
          AND (:onlyUnread = 0 OR e.isRead = 0 OR e.id IN (:keep))
          AND (:onlyStarred = 0 OR e.isStarred = 1)
          AND (f.id IS NOT NULL OR e.isStarred = 1)
          AND CASE WHEN :older THEN e.sortTime < :time OR (e.sortTime = :time AND e.id < :id)
                   ELSE e.sortTime > :time OR (e.sortTime = :time AND e.id > :id) END
        ORDER BY
          CASE WHEN :older THEN e.sortTime END DESC, CASE WHEN :older THEN e.id END DESC,
          CASE WHEN :older THEN NULL ELSE e.sortTime END ASC, CASE WHEN :older THEN NULL ELSE e.id END ASC
        LIMIT 1
        """,
    )
    fun neighbour(
        time: Long, id: Long, older: Boolean,
        feedId: String?, category: String?, label: String?, onlyUnread: Boolean, onlyStarred: Boolean, keep: List<Long>,
    ): Flow<Long?>

    /** As [neighbour], among the entries of [search] that match [match]. */
    @Query(
        """
        SELECT e.id FROM entries e LEFT JOIN feeds f ON f.id = e.feedId
        WHERE e.id IN (SELECT rowid FROM entries_fts WHERE entries_fts MATCH :match)
          AND (:feedId IS NULL OR e.feedId = :feedId)
          AND (:category IS NULL OR f.categoryName = :category)
          AND (:label IS NULL OR e.id IN (SELECT entryId FROM entry_labels WHERE label = :label))
          AND (f.id IS NOT NULL OR e.isStarred = 1)
          AND CASE WHEN :older THEN e.sortTime < :time OR (e.sortTime = :time AND e.id < :id)
                   ELSE e.sortTime > :time OR (e.sortTime = :time AND e.id > :id) END
        ORDER BY
          CASE WHEN :older THEN e.sortTime END DESC, CASE WHEN :older THEN e.id END DESC,
          CASE WHEN :older THEN NULL ELSE e.sortTime END ASC, CASE WHEN :older THEN NULL ELSE e.id END ASC
        LIMIT 1
        """,
    )
    fun searchNeighbour(time: Long, id: Long, older: Boolean, match: String, feedId: String?, category: String?, label: String?): Flow<Long?>

    /** Unread entries of a scope, for mark-all-read. */
    @Query(
        """
        SELECT e.id FROM entries e LEFT JOIN feeds f ON f.id = e.feedId
        WHERE e.isRead = 0
          AND (:feedId IS NULL OR e.feedId = :feedId)
          AND (:category IS NULL OR f.categoryName = :category)
          AND (:label IS NULL OR e.id IN (SELECT entryId FROM entry_labels WHERE label = :label))
          AND (:onlyStarred = 0 OR e.isStarred = 1)
          AND (f.id IS NOT NULL OR e.isStarred = 1)
        """,
    )
    suspend fun unreadIds(feedId: String?, category: String?, label: String?, onlyStarred: Boolean): List<Long>

    /**
     * Entries of a scope whose title or text matches [match], an FTS query, newest first. The scope
     * columns are those of [list]; the filter of the list does not apply: the search covers every
     * entry on the device (R7).
     */
    @Query(
        """
        SELECT e.id, e.feedId, f.title AS feedTitle, e.title, e.author, e.url, e.sortTime, e.isRead, e.isStarred,
               substr(e.content, 1, 600) AS excerpt, f.iconUrl AS feedIconUrl,
               CASE WHEN instr(e.content, '<img') > 0 THEN substr(e.content, instr(e.content, '<img'), 1000) END AS imageTag,
               e.enclosuresJson
        FROM entries e LEFT JOIN feeds f ON f.id = e.feedId
        WHERE e.id IN (SELECT rowid FROM entries_fts WHERE entries_fts MATCH :match)
          AND (:feedId IS NULL OR e.feedId = :feedId)
          AND (:category IS NULL OR f.categoryName = :category)
          AND (:label IS NULL OR e.id IN (SELECT entryId FROM entry_labels WHERE label = :label))
          AND (f.id IS NOT NULL OR e.isStarred = 1)
        ORDER BY e.sortTime DESC, e.id DESC
        """,
    )
    fun search(match: String, feedId: String?, category: String?, label: String?): PagingSource<Int, EntryListItem>

    @Query("SELECT * FROM entries WHERE id = :id")
    fun entry(id: Long): Flow<EntryEntity?>

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun entryNow(id: Long): EntryEntity?

    @Query("SELECT label FROM entry_labels WHERE entryId = :id ORDER BY label COLLATE NOCASE")
    fun labelsOf(id: Long): Flow<List<String>>

    @Query("SELECT id FROM entries")
    suspend fun allIds(): List<Long>

    @Query("SELECT id, isRead, isStarred FROM entries")
    suspend fun states(): List<EntryState>

    @Query("SELECT id FROM entries WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<Long>): List<Long>

    @Query("SELECT * FROM entries WHERE id IN (:ids)")
    suspend fun entries(ids: List<Long>): List<EntryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entries: List<EntryEntity>): List<Long>

    @Query(
        """
        UPDATE entries SET feedId = :feedId, title = :title, author = :author, url = :url, sortTime = :sortTime,
               content = :content, enclosuresJson = :enclosuresJson
        WHERE id = :id
        """,
    )
    suspend fun updateFromServer(id: Long, feedId: String, title: String, author: String, url: String, sortTime: Long, content: String, enclosuresJson: String)

    @Query("UPDATE entries SET isRead = :read, readAt = CASE WHEN :read THEN COALESCE(readAt, :now) ELSE NULL END WHERE id IN (:ids)")
    suspend fun setRead(ids: List<Long>, read: Boolean, now: Long)

    @Query("UPDATE entries SET isStarred = :starred WHERE id IN (:ids)")
    suspend fun setStarred(ids: List<Long>, starred: Boolean)

    @Query("UPDATE entries SET fullContent = :html WHERE id = :id")
    suspend fun setFullContent(id: Long, html: String?)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLabels(labels: List<EntryLabelEntity>)

    @Query("DELETE FROM entry_labels WHERE entryId = :entryId AND label = :label")
    suspend fun deleteLabel(entryId: Long, label: String)

    /** A label renamed: its entries carry the new name; an entry that had both keeps one. */
    @Transaction
    suspend fun renameLabel(name: String, newName: String) {
        renameEntryLabels(name, newName)
        deleteEntryLabels(name)
    }

    @Query("UPDATE OR IGNORE entry_labels SET label = :newName WHERE label = :name")
    suspend fun renameEntryLabels(name: String, newName: String)

    @Query("DELETE FROM entry_labels WHERE label = :name")
    suspend fun deleteEntryLabels(name: String)

    @Query("DELETE FROM entry_labels WHERE entryId IN (:ids)")
    suspend fun deleteLabelsOf(ids: List<Long>)

    /** Read entries past retention that are not starred and have nothing pending. */
    @Query(
        """
        SELECT id FROM entries
        WHERE isRead = 1 AND isStarred = 0 AND readAt IS NOT NULL AND readAt < :before
          AND id NOT IN (SELECT entryId FROM pending_actions)
        """,
    )
    suspend fun expiredIds(before: Long): List<Long>

    /** Entries of feeds that are gone, unless starred. */
    @Query("SELECT id FROM entries WHERE isStarred = 0 AND feedId NOT IN (SELECT id FROM feeds) AND id NOT IN (SELECT entryId FROM pending_actions)")
    suspend fun orphanIds(): List<Long>

    @Query("DELETE FROM entries WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    /** Every entry after [after] in identifier order, a page at a time. */
    @Query("SELECT * FROM entries WHERE id > :after ORDER BY id LIMIT :limit")
    suspend fun after(after: Long, limit: Int): List<EntryEntity>

    /** Unread and starred entries newer than [after], for the download of pictures and full text. */
    @Query("SELECT * FROM entries WHERE (isRead = 0 OR isStarred = 1) AND id > :after ORDER BY id LIMIT :limit")
    suspend fun keptForOffline(after: Long, limit: Int): List<EntryEntity>
}

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(action: PendingActionEntity)

    @Query("DELETE FROM pending_actions WHERE entryId = :entryId AND kind IN (:kinds)")
    suspend fun deleteFor(entryId: Long, kinds: List<PendingKind>)

    @Query("DELETE FROM pending_actions WHERE entryId = :entryId AND kind IN (:kinds) AND argument = :argument")
    suspend fun deleteFor(entryId: Long, kinds: List<PendingKind>, argument: String)

    @Query("UPDATE pending_actions SET argument = :newName WHERE argument = :name AND kind IN (:kinds)")
    suspend fun renameArgument(name: String, newName: String, kinds: List<PendingKind>)

    @Query("DELETE FROM pending_actions WHERE argument = :name AND kind IN (:kinds)")
    suspend fun deleteWithArgument(name: String, kinds: List<PendingKind>)

    @Query("SELECT * FROM pending_actions ORDER BY id")
    suspend fun all(): List<PendingActionEntity>

    @Query("SELECT DISTINCT entryId FROM pending_actions")
    suspend fun pendingEntryIds(): List<Long>

    @Query("SELECT COUNT(*) FROM pending_actions")
    fun count(): Flow<Int>

    @Query("DELETE FROM pending_actions WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE `key` = 0")
    suspend fun get(): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE `key` = 0")
    fun observe(): Flow<SyncStateEntity?>

    @Upsert
    suspend fun put(state: SyncStateEntity)
}

/** What the search index is built from. */
data class EntryText(val id: Long, val title: String, val content: String, val fullContent: String?)

@Dao
interface SearchDao {
    /** Entries not in the index yet, after [after] in identifier order. */
    @Query(
        """
        SELECT id, title, content, fullContent FROM entries
        WHERE id > :after AND id NOT IN (SELECT rowid FROM entries_fts)
        ORDER BY id LIMIT :limit
        """,
    )
    suspend fun unindexed(after: Long, limit: Int): List<EntryText>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rows: List<EntryFtsEntity>)
}
