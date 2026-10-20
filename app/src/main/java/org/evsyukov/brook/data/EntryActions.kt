package org.evsyukov.brook.data

import androidx.room.withTransaction
import org.evsyukov.brook.data.db.BrookDatabase
import org.evsyukov.brook.data.db.EntryLabelEntity
import org.evsyukov.brook.data.db.PendingActionEntity
import org.evsyukov.brook.data.db.PendingKind

/**
 * Changes the user makes to entries. Each one changes the database and queues the same change for
 * the server in one transaction, so the screen shows it at once. After the transaction [onQueued]
 * asks for the queue to be sent; whatever is not sent then goes with the next sync.
 * Of opposite changes to one entry only the last stays queued.
 */
class EntryActions(
    private val db: BrookDatabase,
    private val now: () -> Long = System::currentTimeMillis,
    private val onQueued: () -> Unit = {},
) {
    private val entries = db.entryDao()
    private val outbox = db.outboxDao()

    suspend fun setRead(ids: Collection<Long>, read: Boolean) {
        if (db.withTransaction { queueRead(ids, read) }) onQueued()
    }

    suspend fun setStarred(id: Long, starred: Boolean) {
        db.withTransaction {
            entries.setStarred(listOf(id), starred)
            outbox.deleteFor(id, listOf(PendingKind.STAR, PendingKind.UNSTAR))
            outbox.insert(PendingActionEntity(kind = if (starred) PendingKind.STAR else PendingKind.UNSTAR, entryId = id))
        }
        onQueued()
    }

    suspend fun setLabel(id: Long, label: String, on: Boolean) {
        db.withTransaction {
            if (on) entries.insertLabels(listOf(EntryLabelEntity(id, label))) else entries.deleteLabel(id, label)
            outbox.deleteFor(id, listOf(PendingKind.LABEL_ADD, PendingKind.LABEL_REMOVE), label)
            outbox.insert(PendingActionEntity(kind = if (on) PendingKind.LABEL_ADD else PendingKind.LABEL_REMOVE, entryId = id, argument = label))
        }
        onQueued()
    }

    /**
     * Marks read what [scope] and [filter] list now; entries a later sync brings stay unread. The server is told the very entries, not the stream: its
     * mark-all-as-read of a category leaves out important feeds and that of starred is not narrowed
     * to a feed (freshgo A24). Returns the number of entries marked.
     */
    suspend fun markAllRead(scope: Scope, filter: Filter): Int {
        val marked = db.withTransaction {
            val q = ScopeQuery(scope)
            val ids = entries.unreadIds(q.feedId, q.category, q.label, onlyStarred = filter == Filter.STARRED)
            queueRead(ids, true)
            ids.size
        }
        if (marked > 0) onQueued()
        return marked
    }

    /**
     * Marks read the entries [scope] and [filter] list above the one at ([time], [id]) when [above],
     * below it otherwise; the entry itself keeps its state. Returns the number of entries marked.
     */
    suspend fun markReadBeside(scope: Scope, filter: Filter, time: Long, id: Long, above: Boolean): Int {
        val marked = db.withTransaction {
            val q = ScopeQuery(scope)
            val ids = entries.unreadIdsBeside(time, id, newer = above, q.feedId, q.category, q.label, onlyStarred = filter == Filter.STARRED)
            queueRead(ids, true)
            ids.size
        }
        if (marked > 0) onQueued()
        return marked
    }

    /** Changes and queues the entries whose state differs; called inside a transaction. Returns whether any did. */
    private suspend fun queueRead(ids: Collection<Long>, read: Boolean): Boolean {
        if (ids.isEmpty()) return false
        val changed = entries.entries(ids.toList()).filter { it.isRead != read }.map { it.id }
        if (changed.isEmpty()) return false
        entries.setRead(changed, read, now())
        for (id in changed) {
            outbox.deleteFor(id, listOf(PendingKind.READ, PendingKind.UNREAD))
            outbox.insert(PendingActionEntity(kind = if (read) PendingKind.READ else PendingKind.UNREAD, entryId = id))
        }
        return true
    }
}

/** The columns of a scope as the queries of [org.evsyukov.brook.data.db.EntryDao] take them. */
data class ScopeQuery(val feedId: String?, val category: String?, val label: String?) {
    constructor(scope: Scope) : this(
        feedId = (scope as? Scope.Feed)?.feedId,
        category = (scope as? Scope.Category)?.name,
        label = (scope as? Scope.Label)?.name,
    )
}
