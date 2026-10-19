package org.evsyukov.brook.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.evsyukov.brook.data.db.BrookDatabase
import org.evsyukov.brook.data.db.CategoryEntity
import org.evsyukov.brook.data.db.EntryEntity
import org.evsyukov.brook.data.db.FeedEntity
import org.evsyukov.brook.data.db.PendingKind
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EntryActionsTest {
    private lateinit var db: BrookDatabase
    private lateinit var actions: EntryActions

    @Before
    fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BrookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.treeDao().replaceTree(
            listOf(CategoryEntity("News")),
            emptyList(),
            listOf(FeedEntity("feed/1", "One", "", "", "", "News"), FeedEntity("feed/2", "Two", "", "", "", null)),
        )
        actions = EntryActions(db)
    }

    @After
    fun tearDown() = db.close()

    private fun entry(id: Long, time: Long, feed: String = "feed/1", starred: Boolean = false) = EntryEntity(
        id = id, feedId = feed, title = "Entry $id", author = "", url = "", sortTime = time, content = "",
        enclosuresJson = "", isRead = false, isStarred = starred, readAt = null,
    )

    private suspend fun unread() = db.entryDao().states().filter { !it.isRead }.map { it.id }

    @Test
    fun entriesAboveAnEntryAreMarkedReadButNotItNorOtherScopes() = runBlocking<Unit> {
        // Newest first: 4, 5 and 3 at one time (higher id first), 2, 1; 6 is of another feed.
        db.entryDao().insertIgnore(listOf(entry(1, 100), entry(2, 200), entry(3, 300), entry(5, 300), entry(4, 400), entry(6, 500, feed = "feed/2")))

        val marked = actions.markReadBeside(Scope.Category("News"), Filter.UNREAD, time = 300, id = 3, above = true)

        assertThat(marked).isEqualTo(2)
        assertThat(unread()).containsExactly(1L, 2L, 3L, 6L)
        assertThat(db.outboxDao().all().map { it.entryId to it.kind }).containsExactly(4L to PendingKind.READ, 5L to PendingKind.READ)
    }

    @Test
    fun entriesBelowFollowTheFilter() = runBlocking<Unit> {
        db.entryDao().insertIgnore(listOf(entry(1, 100, starred = true), entry(2, 200), entry(3, 300)))

        val marked = actions.markReadBeside(Scope.All, Filter.STARRED, time = 300, id = 3, above = false)

        assertThat(marked).isEqualTo(1)
        assertThat(unread()).containsExactly(2L, 3L)
    }
}
