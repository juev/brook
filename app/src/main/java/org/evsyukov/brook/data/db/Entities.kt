package org.evsyukov.brook.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/** A folder of feeds; the name is what `user/-/label/<name>` carries. */
@Entity(tableName = "categories")
data class CategoryEntity(@PrimaryKey val name: String)

/** A label of the user on entries. */
@Entity(tableName = "labels")
data class LabelEntity(@PrimaryKey val name: String)

@Entity(tableName = "feeds", indices = [Index("categoryName")])
data class FeedEntity(
    /** `feed/<id>`, the stream of the feed. */
    @PrimaryKey val id: String,
    val title: String,
    val url: String,
    val htmlUrl: String,
    val iconUrl: String,
    val categoryName: String?,
)

/** Settings of a feed that live in Brook only. */
@Entity(tableName = "feed_settings")
data class FeedSettingsEntity(
    @PrimaryKey val feedId: String,
    val alwaysFullText: Boolean = false,
)

@Entity(
    tableName = "entries",
    indices = [Index("feedId"), Index("isRead"), Index("isStarred"), Index("sortTime")],
)
data class EntryEntity(
    /** The entry identifier of the server: microseconds, growing. */
    @PrimaryKey val id: Long,
    val feedId: String,
    val title: String,
    val author: String,
    val url: String,
    /** Seconds; the publication time or, without one, when the server got the entry. */
    val sortTime: Long,
    /** HTML as the server sends it. */
    val content: String,
    /** JSON list of enclosures (href, type, length). */
    val enclosuresJson: String,
    val isRead: Boolean,
    val isStarred: Boolean,
    /** Milliseconds when the entry became read here or first came read; retention counts from it. */
    val readAt: Long?,
    /** HTML extracted on the device from the page of the entry. */
    val fullContent: String? = null,
)

@Entity(
    tableName = "entry_labels",
    primaryKeys = ["entryId", "label"],
    indices = [Index("label")],
)
data class EntryLabelEntity(val entryId: Long, val label: String)

/** A change the user made that the server has not been told about yet. */
@Entity(tableName = "pending_actions", indices = [Index("entryId")])
data class PendingActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: PendingKind,
    val entryId: Long,
    /** The label of LABEL_ADD and LABEL_REMOVE. */
    val argument: String = "",
)

enum class PendingKind { READ, UNREAD, STAR, UNSTAR, LABEL_ADD, LABEL_REMOVE }

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: Int = 0,
    /** The greatest entry identifier seen; the next sync asks for entries from its second on. */
    val newestId: Long = 0,
    /** Milliseconds of the last sync that finished. */
    val lastSyncAt: Long = 0,
)

/**
 * The search index (R7): the title and the text without HTML of an entry, under its identifier.
 * Triggers drop the row when the entry goes or its text changes; [org.evsyukov.brook.data.search.SearchIndex]
 * puts it back. unicode61 folds the case of every script, not only of Latin letters.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "entries_fts")
data class EntryFtsEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val title: String,
    val text: String,
)
