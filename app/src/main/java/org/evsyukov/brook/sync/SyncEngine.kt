package org.evsyukov.brook.sync

import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.evsyukov.brook.data.db.BrookDatabase
import org.evsyukov.brook.data.db.CategoryEntity
import org.evsyukov.brook.data.db.EntryLabelEntity
import org.evsyukov.brook.data.db.LabelEntity
import org.evsyukov.brook.data.db.PendingActionEntity
import org.evsyukov.brook.data.db.PendingKind
import org.evsyukov.brook.data.db.SyncStateEntity
import org.evsyukov.brook.data.remote.ReaderApi
import org.evsyukov.brook.data.remote.Streams
import org.evsyukov.brook.data.search.SearchIndex
import java.util.concurrent.TimeUnit

/** What a sync changed: unread entries that came in, for the "N new" banner and notifications, and entries dropped. */
data class SyncResult(val newEntries: Int, val deletedIds: List<Long>)

/**
 * Brings one account's database in line with the server:
 *
 * 1. send the queued changes; when that fails, nothing else runs, so the server state read next
 *    already has them;
 * 2. replace the tree of categories, labels and feeds;
 * 3. fetch entries the feeds added or changed since the newest one seen (`ot`), for new texts of
 *    entries already here;
 * 4. read the sets of unread and starred identifiers, apply them to entries without queued
 *    changes, and fetch the unread and starred entries that are not here yet;
 * 5. drop read entries past [retentionMillis] and entries of feeds that are gone, unless starred;
 * 6. index for search the entries that came or changed.
 *
 * Changes made on other devices arrive through step 4: `ot` does not select entries whose state
 * the user changed (freshgo A18).
 */
class SyncEngine(
    private val db: BrookDatabase,
    private val api: ReaderApi,
    private val retentionMillis: () -> Long = { TimeUnit.DAYS.toMillis(30) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val entries = db.entryDao()
    private val outbox = db.outboxDao()
    private val search = SearchIndex(db)

    /** Runs a sync, or waits for the one already running and runs another after it. */
    suspend fun sync(): SyncResult = mutex.withLock {
        pushOutbox()
        val labelNames = syncTree()
        val state = db.syncStateDao().get() ?: SyncStateEntity()
        var newEntries = 0
        if (state.newestId > 0) newEntries += fetchChangedSince(state.newestId, labelNames)
        newEntries += reconcile(labelNames)
        val deleted = prune()
        val newest = maxOf(state.newestId, entries.allIds().maxOrNull() ?: 0)
        db.syncStateDao().put(SyncStateEntity(newestId = newest, lastSyncAt = now()))
        search.indexMissing()
        SyncResult(newEntries, deleted)
    }

    /** Sends the queued changes and nothing else, so a mark reaches the server before the next sync. */
    suspend fun sendQueued() = mutex.withLock { pushOutbox() }

    private suspend fun pushOutbox() {
        val actions = outbox.all()
        if (actions.isEmpty()) return
        val groups = actions.groupBy { it.kind to it.argument }
        for ((key, group) in groups) {
            val (kind, argument) = key
            val ids = group.map { it.entryId }.distinct()
            for (chunk in ids.chunked(EDIT_CHUNK)) {
                when (kind) {
                    PendingKind.READ -> api.editTag(chunk, add = listOf(Streams.READ))
                    PendingKind.UNREAD -> api.editTag(chunk, remove = listOf(Streams.READ))
                    PendingKind.STAR -> api.editTag(chunk, add = listOf(Streams.STARRED))
                    PendingKind.UNSTAR -> api.editTag(chunk, remove = listOf(Streams.STARRED))
                    PendingKind.LABEL_ADD -> api.editTag(chunk, add = listOf(Streams.label(argument)))
                    PendingKind.LABEL_REMOVE -> api.editTag(chunk, remove = listOf(Streams.label(argument)))
                }
            }
            // Only the rows sent: a change queued while this ran stays for the next sync.
            outbox.delete(group.map(PendingActionEntity::id))
        }
    }

    private suspend fun syncTree(): Set<String> {
        val tags = api.tagList()
        val subscriptions = api.subscriptions()
        val categories = tags.filter { it.type == "folder" }.mapNotNull { Streams.labelName(it.id) }
        val labels = tags.filter { it.type == "tag" }.mapNotNull { Streams.labelName(it.id) }
        db.treeDao().replaceTree(
            categories = categories.map(::CategoryEntity),
            labels = labels.map(::LabelEntity),
            feeds = subscriptions.map { it.toEntity() },
        )
        return labels.toSet()
    }

    private suspend fun fetchChangedSince(newestId: Long, labelNames: Set<String>): Int {
        // A margin of a minute: `ot` is in seconds and an entry is stored a moment after its id is taken.
        val since = newestId / 1_000_000 - 60
        var added = 0
        var continuation: String? = null
        do {
            val page = api.streamContents(Streams.READING_LIST, count = PAGE, continuation = continuation, newerThanSeconds = since)
            added += store(page.items.mapNotNull { it.toMapped(labelNames, now()) }, keepLocalState = true)
            continuation = page.continuation?.takeIf { it.isNotEmpty() && page.items.isNotEmpty() }
        } while (continuation != null)
        return added
    }

    private suspend fun reconcile(labelNames: Set<String>): Int {
        val unread = api.allItemIds(Streams.READING_LIST, exclude = Streams.READ).toHashSet()
        val starred = api.allItemIds(Streams.STARRED).toHashSet()

        db.withTransaction {
            val pending = outbox.pendingEntryIds().toHashSet()
            // Only what differs is written, so lists on screen are not invalidated for nothing.
            val local = entries.states().filterNot { it.id in pending }
            val becameRead = local.filter { !it.isRead && it.id !in unread }.map { it.id }
            val becameUnread = local.filter { it.isRead && it.id in unread }.map { it.id }
            val becameStarred = local.filter { !it.isStarred && it.id in starred }.map { it.id }
            val becameUnstarred = local.filter { it.isStarred && it.id !in starred }.map { it.id }
            becameRead.chunked(SQL_CHUNK).forEach { entries.setRead(it, true, now()) }
            becameUnread.chunked(SQL_CHUNK).forEach { entries.setRead(it, false, now()) }
            becameStarred.chunked(SQL_CHUNK).forEach { entries.setStarred(it, true) }
            becameUnstarred.chunked(SQL_CHUNK).forEach { entries.setStarred(it, false) }
        }

        val missing = (unread + starred).toMutableSet()
        missing.removeAll(entries.allIds().toSet())
        var added = 0
        for (chunk in missing.sortedDescending().chunked(PAGE)) {
            added += store(api.itemContents(chunk).mapNotNull { it.toMapped(labelNames, now()) }, keepLocalState = false)
        }
        return added
    }

    /**
     * Inserts new entries and refreshes the texts of known ones. With [keepLocalState] the read and
     * starred state of known entries stays as it is here: reconcile owns it. Returns the number of
     * unread entries inserted.
     */
    private suspend fun store(items: List<MappedItem>, keepLocalState: Boolean): Int {
        if (items.isEmpty()) return 0
        return db.withTransaction {
            val inserted = entries.insertIgnore(items.map { it.entry })
            var count = 0
            items.forEachIndexed { i, item ->
                val e = item.entry
                if (inserted[i] != -1L) {
                    if (!e.isRead) count++
                    entries.insertLabels(item.labels.map { EntryLabelEntity(e.id, it) })
                } else {
                    entries.updateFromServer(e.id, e.feedId, e.title, e.author, e.url, e.sortTime, e.content, e.enclosuresJson)
                    if (!keepLocalState) {
                        entries.setRead(listOf(e.id), e.isRead, now())
                        entries.setStarred(listOf(e.id), e.isStarred)
                    }
                }
            }
            count
        }
    }

    private suspend fun prune(): List<Long> = db.withTransaction {
        val ids = entries.expiredIds(now() - retentionMillis()) + entries.orphanIds()
        ids.chunked(SQL_CHUNK).forEach {
            entries.deleteLabelsOf(it)
            entries.delete(it)
        }
        ids
    }

    private companion object {
        const val PAGE = 250
        const val EDIT_CHUNK = 250
        const val SQL_CHUNK = 500
    }
}
