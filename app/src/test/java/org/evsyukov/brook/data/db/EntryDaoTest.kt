package org.evsyukov.brook.data.db

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.brook.data.Filter
import org.evsyukov.brook.data.Scope
import org.evsyukov.brook.data.ScopeQuery
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EntryDaoTest {
    private lateinit var db: BrookDatabase
    private val dao get() = db.entryDao()

    @Before
    fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BrookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.treeDao().replaceTree(
            categories = listOf(CategoryEntity("News"), CategoryEntity("Blogs")),
            labels = listOf(LabelEntity("later")),
            feeds = listOf(feed("feed/1", "News"), feed("feed/2", "News"), feed("feed/3", "Blogs")),
        )
        dao.insertIgnore(
            listOf(
                entry(1, "feed/1", read = false, starred = false, time = 100),
                entry(2, "feed/1", read = true, starred = true, time = 300),
                entry(3, "feed/2", read = false, starred = true, time = 200),
                entry(4, "feed/3", read = false, starred = false, time = 400),
                entry(5, "feed/3", read = true, starred = false, time = 500),
                // A starred entry of a feed no longer subscribed, and a plain one.
                entry(6, "feed/9", read = true, starred = true, time = 600),
                entry(7, "feed/9", read = false, starred = false, time = 700),
            ),
        )
        dao.insertLabels(listOf(EntryLabelEntity(1, "later"), EntryLabelEntity(5, "later")))
    }

    @After
    fun tearDown() = db.close()

    private fun feed(id: String, category: String) = FeedEntity(id, "Feed $id", "https://x/$id", "", "", category)

    private fun entry(id: Long, feed: String, read: Boolean, starred: Boolean, time: Long) = EntryEntity(
        id = id, feedId = feed, title = "e$id", author = "", url = "", sortTime = time, content = "<p>text</p>",
        enclosuresJson = "", isRead = read, isStarred = starred, readAt = if (read) 1 else null,
    )

    private suspend fun list(scope: Scope, filter: Filter): List<Long> {
        val q = ScopeQuery(scope)
        val source = dao.list(q.feedId, q.category, q.label, filter == Filter.UNREAD, filter == Filter.STARRED)
        val page = source.load(PagingSource.LoadParams.Refresh(null, 100, false)) as PagingSource.LoadResult.Page
        return page.data.map { it.id }
    }

    @Test
    fun everyScopeAndFilter() = runBlocking<Unit> {
        // Newest first; starred entries of a feed that is gone stay in All and Starred.
        assertThat(list(Scope.All, Filter.ALL)).containsExactly(6L, 5L, 4L, 2L, 3L, 1L).inOrder()
        assertThat(list(Scope.All, Filter.UNREAD)).containsExactly(4L, 3L, 1L).inOrder()
        assertThat(list(Scope.All, Filter.STARRED)).containsExactly(6L, 2L, 3L).inOrder()

        assertThat(list(Scope.Category("News"), Filter.ALL)).containsExactly(2L, 3L, 1L).inOrder()
        assertThat(list(Scope.Category("News"), Filter.UNREAD)).containsExactly(3L, 1L).inOrder()
        assertThat(list(Scope.Category("News"), Filter.STARRED)).containsExactly(2L, 3L).inOrder()

        assertThat(list(Scope.Feed("feed/1"), Filter.ALL)).containsExactly(2L, 1L).inOrder()
        assertThat(list(Scope.Feed("feed/1"), Filter.UNREAD)).containsExactly(1L)

        assertThat(list(Scope.Label("later"), Filter.ALL)).containsExactly(5L, 1L).inOrder()
        assertThat(list(Scope.Label("later"), Filter.UNREAD)).containsExactly(1L)
        assertThat(list(Scope.Label("later"), Filter.STARRED)).isEmpty()
    }

    @Test
    fun rowsCarryTheFeedTitle() = runBlocking<Unit> {
        val q = ScopeQuery(Scope.Feed("feed/1"))
        val source = dao.list(q.feedId, q.category, q.label, false, false)
        val page = source.load(PagingSource.LoadParams.Refresh(null, 10, false)) as PagingSource.LoadResult.Page

        assertThat(page.data.first().feedTitle).isEqualTo("Feed feed/1")
        assertThat(page.data.first().excerpt).isEqualTo("<p>text</p>")
    }

    @Test
    fun unreadCountsOfTheTree() = runBlocking<Unit> {
        val tree = db.treeDao()

        assertThat(tree.unreadTotal().first()).isEqualTo(3)
        assertThat(tree.starredTotal().first()).isEqualTo(3)
        assertThat(tree.unreadByFeed().first()).containsExactly(UnreadCount("feed/1", 1), UnreadCount("feed/2", 1), UnreadCount("feed/3", 1))
        assertThat(tree.unreadByLabel().first()).containsExactly(UnreadCount("later", 1))
    }

    @Test
    fun replacingTheTreeDropsFeedsThatAreGone() = runBlocking<Unit> {
        db.treeDao().replaceTree(listOf(CategoryEntity("News")), emptyList(), listOf(feed("feed/1", "News")))

        assertThat(db.treeDao().feeds().first().map { it.id }).containsExactly("feed/1")
        assertThat(db.treeDao().categories().first().map { it.name }).containsExactly("News")
        assertThat(db.treeDao().labels().first()).isEmpty()
    }
}
