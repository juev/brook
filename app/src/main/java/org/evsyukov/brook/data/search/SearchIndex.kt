package org.evsyukov.brook.data.search

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.evsyukov.brook.data.db.BrookDatabase
import org.evsyukov.brook.data.db.EntryFtsEntity
import org.evsyukov.brook.data.db.EntryText
import org.jsoup.Jsoup

/**
 * Keeps the search index of an account (R7) up to date. Triggers take an entry out of it when the
 * entry goes or its text changes; [indexMissing] puts in what is not there, with HTML stripped.
 */
class SearchIndex(private val db: BrookDatabase) {
    private val dao = db.searchDao()

    /** Indexes the entries the index lacks; cheap when nothing is missing. */
    suspend fun indexMissing() = withContext(Dispatchers.Default) {
        var after = 0L
        while (true) {
            // Read and write in one transaction, so a text changed meanwhile is not indexed stale.
            val batch = db.withTransaction {
                val entries = dao.unindexed(after, BATCH)
                dao.insert(entries.map { it.toRow() })
                entries
            }
            if (batch.size < BATCH) break
            after = batch.last().id
        }
    }

    companion object {
        private const val BATCH = 200

        internal fun EntryText.toRow() = EntryFtsEntity(
            rowId = id,
            title = title,
            text = listOfNotNull(content, fullContent).joinToString(" ") { Jsoup.parse(it).text() },
        )

        /**
         * Turns what a person types into an FTS query: every word must be there, as a word or the
         * start of one. Quotes and operators are taken as text. Null when there is nothing to look for.
         */
        fun matchQuery(input: String): String? {
            val words = input.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return null
            return words.joinToString(" ") { "\"$it*\"" }
        }
    }
}
