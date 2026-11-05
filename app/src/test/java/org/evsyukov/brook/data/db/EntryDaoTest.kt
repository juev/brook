package org.evsyukov.brook.data.db

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    private suspend fun list(scope: Scope, filter: Filter, keep: List<Long> = emptyList()): List<Long> {
        val q = ScopeQuery(scope)
        val source = dao.list(q.feedId, q.category, q.label, filter == Filter.UNREAD, filter == Filter.STARRED, keep)
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
    fun anUnreadListKeepsTheEntriesReadOnTheWay() = runBlocking<Unit> {
        // Read entries opened from the list stay in it; one of another scope does not join it.
        assertThat(list(Scope.All, Filter.UNREAD, keep = listOf(5L))).containsExactly(5L, 4L, 3L, 1L).inOrder()
        assertThat(list(Scope.Category("Blogs"), Filter.UNREAD, keep = listOf(5L, 2L))).containsExactly(5L, 4L).inOrder()
        // Other filters list what they list.
        assertThat(list(Scope.All, Filter.STARRED, keep = listOf(5L))).containsExactly(6L, 2L, 3L).inOrder()
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
    fun feedCountsKeepSubscriptionsWithoutEntriesAndReadTheRealFlags() = runBlocking<Unit> {
        db.treeDao().upsertFeeds(listOf(feed("feed/4", "Blogs")))

        val counts = db.treeDao().feedCounts().first()

        assertThat(counts.map { it.id }).containsExactly("feed/1", "feed/2", "feed/3", "feed/4").inOrder()
        val byId = counts.associateBy { it.id }
        assertThat(byId.getValue("feed/1").unread).isEqualTo(1)
        assertThat(byId.getValue("feed/1").starred).isEqualTo(1)
        assertThat(byId.getValue("feed/2").unread).isEqualTo(1)
        assertThat(byId.getValue("feed/2").starred).isEqualTo(1)
        assertThat(byId.getValue("feed/3").unread).isEqualTo(1)
        assertThat(byId.getValue("feed/3").starred).isEqualTo(0)
        // A feed with no cached entry is still a subscription, with no counts.
        assertThat(byId.getValue("feed/4").unread).isEqualTo(0)
        assertThat(byId.getValue("feed/4").starred).isEqualTo(0)
        // Entries of an unsubscribed feed do not invent a feed.
        assertThat(counts.map { it.id }).doesNotContain("feed/9")
    }

    @Test
    fun unreadCountOfAScopeFollowsTheStarredRestriction() = runBlocking<Unit> {
        assertThat(dao.unreadCount(null, null, null, onlyStarred = false).first()).isEqualTo(3)
        // Starred entries that are already read do not count; of the unread ones only entry 3 is starred.
        assertThat(dao.unreadCount(null, null, null, onlyStarred = true).first()).isEqualTo(1)
        assertThat(dao.unreadCount(null, "News", null, onlyStarred = false).first()).isEqualTo(2)
        assertThat(dao.unreadCount(null, "News", null, onlyStarred = true).first()).isEqualTo(1)
        assertThat(dao.unreadCount("feed/3", null, null, onlyStarred = false).first()).isEqualTo(1)
        assertThat(dao.unreadCount(null, null, "later", onlyStarred = false).first()).isEqualTo(1)
        assertThat(dao.unreadCount(null, null, "later", onlyStarred = true).first()).isEqualTo(0)
    }

    @Test
    fun theCountsFollowReadingAndStarring() = runBlocking<Unit> {
        val unread = dao.unreadCount(null, null, null, onlyStarred = false)
        assertThat(unread.first()).isEqualTo(3)

        dao.setRead(listOf(1L), read = true, now = 1_000)
        withTimeout(5_000) { unread.first { it == 2 } }

        val starred = db.treeDao().feedCounts()
        dao.setStarred(listOf(2L), starred = false)
        withTimeout(5_000) {
            starred.first { counts -> counts.first { it.id == "feed/1" }.starred == 0 }
        }
    }

    @Test
    fun replacingTheTreeDropsFeedsThatAreGone() = runBlocking<Unit> {
        db.treeDao().replaceTree(listOf(CategoryEntity("News")), emptyList(), listOf(feed("feed/1", "News")))

        assertThat(db.treeDao().feeds().first().map { it.id }).containsExactly("feed/1")
        assertThat(db.treeDao().categories().first().map { it.name }).containsExactly("News")
        assertThat(db.treeDao().labels().first()).isEmpty()
    }
}
