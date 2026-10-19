package org.evsyukov.brook.data

import androidx.room.withTransaction
import org.evsyukov.brook.data.db.BrookDatabase
import org.evsyukov.brook.data.db.PendingKind
import org.evsyukov.brook.data.remote.GReaderClient

/** The server would not do what was asked; [detail] is its own words when it gave any. */
sealed class EditRefused(message: String) : Exception(message) {
    /** A category or label of that name exists; the server would answer OK and change nothing (freshgo A25). */
    class NameTaken(val name: String) : EditRefused("Name taken: $name")

    /** `quickadd` found no feed at the address (freshgo A27). */
    class NoFeed(val detail: String?) : EditRefused("No feed: $detail")
}

/**
 * Changes to subscriptions, categories and labels (R6). Unlike changes to entries they go to the
 * server at once and need the network; the next sync brings the tree they leave.
 *
 * The sync does not read the labels of entries it already has, so a renamed or deleted label is
 * changed here too, along with the changes of it still queued.
 */
class TreeEditor(private val db: BrookDatabase, private val api: GReaderClient) {
    private val tree = db.treeDao()

    /** Subscribes to the feed at [url]; returns the stream of the feed. */
    suspend fun subscribe(url: String): String {
        val answer = api.quickAdd(url.trim())
        if (answer.numResults == 0 || answer.streamId.isEmpty()) throw EditRefused.NoFeed(answer.error)
        return answer.streamId
    }

    suspend fun unsubscribe(feedId: String) = api.unsubscribe(feedId)

    suspend fun renameFeed(feedId: String, title: String) = api.renameSubscription(feedId, title.trim())

    /** Moves the feed into [category], which the server creates when it is new. */
    suspend fun moveFeed(feedId: String, category: String) {
        val target = category.trim()
        val from = tree.feed(feedId)?.categoryName
        if (from == target) return
        api.moveSubscription(feedId, from, target)
    }

    suspend fun renameCategory(name: String, newName: String) {
        val target = newName.trim()
        if (target == name) return
        if (tree.nameTaken(target)) throw EditRefused.NameTaken(target)
        api.renameTag(name, target)
    }

    /** The feeds of the category go to the default one. */
    suspend fun deleteCategory(name: String) = api.disableTag(name)

    suspend fun renameLabel(name: String, newName: String) {
        val target = newName.trim()
        if (target == name) return
        if (tree.nameTaken(target)) throw EditRefused.NameTaken(target)
        api.renameTag(name, target)
        db.withTransaction {
            tree.renameLabel(name, target)
            db.entryDao().renameLabel(name, target)
            db.outboxDao().renameArgument(name, target, LABEL_KINDS)
        }
    }

    /** The label comes off its entries, here and on the server. */
    suspend fun deleteLabel(name: String) {
        api.disableTag(name)
        db.withTransaction {
            tree.deleteLabel(name)
            db.entryDao().deleteEntryLabels(name)
            db.outboxDao().deleteWithArgument(name, LABEL_KINDS)
        }
    }

    suspend fun importOpml(opml: String) = api.importOpml(opml)

    suspend fun exportOpml(): String = api.exportOpml()

    private companion object {
        val LABEL_KINDS = listOf(PendingKind.LABEL_ADD, PendingKind.LABEL_REMOVE)
    }
}
