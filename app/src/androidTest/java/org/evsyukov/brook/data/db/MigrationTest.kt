package org.evsyukov.brook.data.db

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.evsyukov.brook.data.db.BrookDatabase.Companion.withSearchTriggers
import org.evsyukov.brook.data.search.SearchIndex
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A database of version 1 keeps its entries in version 2, and they become searchable (R7). */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), BrookDatabase::class.java)

    @After
    fun tearDown() {
        context.deleteDatabase(NAME)
    }

    @Test
    fun entriesOfVersionOneAreSearchedAfterTheMigration() = runBlocking<Unit> {
        helper.createDatabase(NAME, 1).use { db ->
            db.execSQL("INSERT INTO feeds VALUES ('feed/1', 'Feed', 'https://x/feed', '', '', NULL)")
            db.execSQL(
                "INSERT INTO entries (id, feedId, title, author, url, sortTime, content, enclosuresJson, isRead, isStarred) " +
                    "VALUES (1, 'feed/1', 'Старая запись', '', '', 100, '<p>Текст до миграции</p>', '', 0, 0)",
            )
        }
        helper.runMigrationsAndValidate(NAME, 2, true).close()

        val db = Room.databaseBuilder(context, BrookDatabase::class.java, NAME).withSearchTriggers().build()
        try {
            SearchIndex(db).indexMissing()
            assertThat(db.searchDao().unindexed(0, 10)).isEmpty()
            // The SQLite of the device folds the case of Cyrillic too.
            val source = db.entryDao().search(SearchIndex.matchQuery("МИГРАЦ")!!, null, null, null)
            val page = source.load(PagingSource.LoadParams.Refresh(null, 10, false)) as PagingSource.LoadResult.Page
            assertThat(page.data.map { it.id }).containsExactly(1L)

            // The triggers of the migration take a deleted entry out of the index.
            db.entryDao().delete(listOf(1L))
            val left = db.query("SELECT COUNT(*) FROM entries_fts", null).use { it.moveToFirst(); it.getInt(0) }
            assertThat(left).isEqualTo(0)
        } finally {
            db.close()
        }
    }

    private companion object {
        const val NAME = "migration-test.db"
    }
}
