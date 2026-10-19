package org.evsyukov.brook.data.search

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.evsyukov.brook.data.Scope
import org.evsyukov.brook.data.ScopeQuery
import org.evsyukov.brook.data.db.BrookDatabase
import org.evsyukov.brook.data.db.BrookDatabase.Companion.withSearchTriggers
import org.evsyukov.brook.data.db.CategoryEntity
import org.evsyukov.brook.data.db.EntryEntity
import org.evsyukov.brook.data.db.EntryLabelEntity
import org.evsyukov.brook.data.db.FeedEntity
import org.evsyukov.brook.data.db.EntryListItem
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchIndexTest {
    private lateinit var db: BrookDatabase
    private lateinit var index: SearchIndex
    private val dao get() = db.entryDao()

    @Before
    fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BrookDatabase::class.java)
            .withSearchTriggers()
            .allowMainThreadQueries()
            .build()
        index = SearchIndex(db)
        db.treeDao().replaceTree(
            categories = listOf(CategoryEntity("News"), CategoryEntity("Blogs")),
            labels = emptyList(),
            feeds = listOf(feed("feed/1", "News"), feed("feed/2", "Blogs")),
        )
        dao.insertIgnore(
            listOf(
                entry(1, "feed/1", "Kotlin coroutines", "<p>Structured <b>concurrency</b> explained</p>", time = 100),
                entry(2, "feed/2", "Погода в Москве", "<div class=\"forecast\">Завтра дождь</div>", time = 200, read = true),
                entry(3, "feed/1", "Release notes", "<p>Kotlin 2.3 is out</p>", time = 300),
            ),
        )
        dao.insertLabels(listOf(EntryLabelEntity(3, "later")))
        index.indexMissing()
    }

    @After
    fun tearDown() = db.close()

    private fun feed(id: String, category: String) = FeedEntity(id, "Feed $id", "https://x/$id", "", "", category)

    private fun entry(id: Long, feed: String, title: String, content: String, time: Long, read: Boolean = false) = EntryEntity(
        id = id, feedId = feed, title = title, author = "", url = "", sortTime = time, content = content,
        enclosuresJson = "", isRead = read, isStarred = false, readAt = if (read) 1 else null,
    )

    private suspend fun search(input: String, scope: Scope = Scope.All): List<Long> {
        val q = ScopeQuery(scope)
        val match = SearchIndex.matchQuery(input) ?: return emptyList()
        val source: PagingSource<Int, EntryListItem> = dao.search(match, q.feedId, q.category, q.label)
        val page = source.load(PagingSource.LoadParams.Refresh(null, 100, false)) as PagingSource.LoadResult.Page
        return page.data.map { it.id }
    }

    @Test
    fun findsWordsOfTitleAndTextNewestFirst() = runBlocking<Unit> {
        assertThat(search("kotlin")).containsExactly(3L, 1L).inOrder()
        assertThat(search("concurrency")).containsExactly(1L)
        // Every word must be there.
        assertThat(search("kotlin release")).containsExactly(3L)
    }

    @Test
    fun markupIsNotText() = runBlocking<Unit> {
        assertThat(search("forecast")).isEmpty()
        assertThat(search("div")).isEmpty()
    }

    @Test
    fun caseAndPrefixOfAnyScript() = runBlocking<Unit> {
        // Read entries are found too: the search covers everything on the device.
        assertThat(search("МОСКВ")).containsExactly(2L)
        assertThat(search("дожд")).containsExactly(2L)
        assertThat(search("corout")).containsExactly(1L)
    }

    @Test
    fun scopeNarrowsTheSearch() = runBlocking<Unit> {
        assertThat(search("kotlin", Scope.Feed("feed/2"))).isEmpty()
        assertThat(search("kotlin", Scope.Category("News"))).containsExactly(3L, 1L).inOrder()
        assertThat(search("kotlin", Scope.Label("later"))).containsExactly(3L)
    }

    @Test
    fun changedTextIsIndexedAgain() = runBlocking<Unit> {
        dao.updateFromServer(1, "feed/1", "Kotlin coroutines", "", "", 100, "<p>Flows instead</p>", "")
        dao.setFullContent(3, "<article>Compose Multiplatform</article>")

        index.indexMissing()

        assertThat(search("concurrency")).isEmpty()
        assertThat(search("flows")).containsExactly(1L)
        // The full text is searched along with the text of the server.
        assertThat(search("multiplatform")).containsExactly(3L)
        assertThat(search("notes")).containsExactly(3L)
    }

    @Test
    fun stateChangesKeepTheIndex() = runBlocking<Unit> {
        dao.setRead(listOf(1L), true, now = 5)

        assertThat(db.searchDao().unindexed(0, 10)).isEmpty()
    }

    @Test
    fun deletedEntriesLeaveTheIndex() = runBlocking<Unit> {
        dao.delete(listOf(1L))
        dao.insertIgnore(listOf(entry(1, "feed/1", "Other", "<p>Nothing alike</p>", time = 100)))

        assertThat(search("concurrency")).isEmpty()
    }

    @Test
    fun whatIsTypedBecomesWordPrefixes() {
        assertThat(SearchIndex.matchQuery("  Kotlin  2.3 ")).isEqualTo("\"Kotlin*\" \"2*\" \"3*\"")
        assertThat(SearchIndex.matchQuery("a\" OR b*")).isEqualTo("\"a*\" \"OR*\" \"b*\"")
        assertThat(SearchIndex.matchQuery(" -*\" ")).isNull()
    }
}
