package org.evsyukov.brook.ui.home

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import androidx.paging.testing.asSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.evsyukov.brook.data.EntryActions
import org.evsyukov.brook.data.Filter
import org.evsyukov.brook.data.Scope
import org.evsyukov.brook.data.TreeEditor
import org.evsyukov.brook.data.account.Account
import org.evsyukov.brook.data.account.AccountSession
import org.evsyukov.brook.data.db.BrookDatabase
import org.evsyukov.brook.data.db.CategoryEntity
import org.evsyukov.brook.data.db.EntryEntity
import org.evsyukov.brook.data.db.FeedEntity
import org.evsyukov.brook.data.fulltext.FullTextExtractor
import org.evsyukov.brook.data.images.ImageCache
import org.evsyukov.brook.data.remote.GReaderClient
import org.evsyukov.brook.data.search.SearchIndex
import org.evsyukov.brook.sync.SyncEngine
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class HomeViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var db: BrookDatabase
    private lateinit var vm: HomeViewModel

    @Before
    fun setUp() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BrookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.treeDao().replaceTree(listOf(CategoryEntity("News")), emptyList(), listOf(FeedEntity("feed/1", "One", "", "", "", "News")))
        val http = OkHttpClient()
        val api = GReaderClient(http, "http://localhost/api/greader.php".toHttpUrl(), "alice/x")
        val session = AccountSession(
            account = Account("a", "http://localhost/api/greader.php", "alice"),
            db = db,
            api = api,
            sync = SyncEngine(db, api),
            actions = EntryActions(db),
            editor = TreeEditor(db, api),
            images = ImageCache(folder.newFolder("images"), http),
            fullText = FullTextExtractor(http),
        )
        vm = HomeViewModel(session, onFullTextChanged = {})
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun aScopeThatLeavesTheTreeGivesWayToAll() = runBlocking<Unit> {
        vm.select(Scope.Feed("feed/1"))
        assertThat(vm.selection.value.scope).isEqualTo(Scope.Feed("feed/1"))

        // A sync after the feed was unsubscribed elsewhere.
        db.treeDao().replaceTree(listOf(CategoryEntity("News")), emptyList(), emptyList())

        withTimeout(5_000) { vm.selection.first { it.scope == Scope.All } }
    }

    @Test
    fun aScopeStillThereStays() = runBlocking<Unit> {
        vm.select(Scope.Category("News"))
        db.treeDao().replaceTree(listOf(CategoryEntity("News"), CategoryEntity("Blogs")), emptyList(), emptyList())
        db.treeDao().feeds().first { it.isEmpty() }

        assertThat(vm.selection.value.scope).isEqualTo(Scope.Category("News"))
    }

    private fun entry(id: Long, time: Long, read: Boolean = false, starred: Boolean = false, title: String = "Entry $id") = EntryEntity(
        id = id, feedId = "feed/1", title = title, author = "", url = "", sortTime = time, content = "<p>Text $id</p>",
        enclosuresJson = "", isRead = read, isStarred = starred, readAt = null,
    )

    private suspend fun neighbours(id: Long) = withTimeout(5_000) { vm.neighbours(id).first() }

    @Test
    fun neighboursFollowTheOrderAndFilterOfTheList() = runBlocking<Unit> {
        // Newest first: 4, 3 (read), 2 and 5 at the same time (higher id first), 1.
        db.entryDao().insertIgnore(listOf(entry(1, 100), entry(2, 200), entry(5, 200), entry(3, 300, read = true), entry(4, 400)))

        assertThat(neighbours(4)).isEqualTo(Neighbours(newer = null, older = 5))
        assertThat(neighbours(5)).isEqualTo(Neighbours(newer = 4, older = 2))
        assertThat(neighbours(1)).isEqualTo(Neighbours(newer = 2, older = null))

        vm.select(Filter.ALL)
        assertThat(neighbours(4)).isEqualTo(Neighbours(newer = null, older = 3))
    }

    @Test
    fun entriesReadOnTheWayKeepTheirPlacesInAnUnreadList() = runBlocking<Unit> {
        db.entryDao().insertIgnore(listOf(entry(1, 100), entry(2, 200), entry(3, 300)))
        vm.opened(3)
        EntryActions(db).setRead(listOf(3L), true)
        vm.opened(2)
        EntryActions(db).setRead(listOf(2L), true)

        assertThat(neighbours(2)).isEqualTo(Neighbours(newer = 3, older = 1))

        // Another filter starts afresh.
        vm.select(Filter.STARRED)
        vm.select(Filter.UNREAD)
        assertThat(neighbours(1)).isEqualTo(Neighbours(newer = null, older = null))
    }

    @Test
    fun anEntryOpenedFromAnUnreadListStaysInItUntilTheFilterChanges() = runBlocking<Unit> {
        db.entryDao().insertIgnore(listOf(entry(1, 100), entry(2, 200)))
        val unread = vm.list(vm.selection.value)
        assertThat(unread.asSnapshot().map { it.id }).containsExactly(2L, 1L).inOrder()

        vm.opened(2)
        EntryActions(db).setRead(listOf(2L), true)
        // Room tells the list about the change on its own thread; wait for the list that shows it.
        val after = withTimeout(5_000) {
            var rows = unread.asSnapshot()
            while (rows.none { it.id == 2L && it.isRead } && rows.any { it.id == 2L }) rows = unread.asSnapshot()
            rows
        }
        assertThat(after.map { it.id to it.isRead }).containsExactly(2L to true, 1L to false).inOrder()

        // The screen asks for the list of every selection it shows.
        vm.select(Filter.ALL)
        vm.list(vm.selection.value)
        vm.select(Filter.UNREAD)
        assertThat(vm.list(vm.selection.value).asSnapshot().map { it.id }).containsExactly(1L)
    }

    @Test
    fun entriesReadOnTheWayLeaveAnUnreadListAllButTheLastOne() = runBlocking<Unit> {
        db.entryDao().insertIgnore(listOf(entry(1, 100), entry(2, 200), entry(3, 300)))
        val unread = vm.list(vm.selection.value)
        assertThat(unread.asSnapshot().map { it.id }).containsExactly(3L, 2L, 1L).inOrder()
        vm.opened(3)
        EntryActions(db).setRead(listOf(3L), true)
        vm.opened(2)
        EntryActions(db).setRead(listOf(2L), true)
        // Room tells the list about the change on its own thread; wait for the list that shows it.
        withTimeout(5_000) {
            var rows = unread.asSnapshot()
            while (rows.none { it.id == 2L && it.isRead }) rows = unread.asSnapshot()
        }
        assertThat(unread.asSnapshot().map { it.id }).containsExactly(3L, 2L, 1L).inOrder()

        // Back from the background, or a pull to refresh.
        vm.forgetReadOnTheWay()

        val after = withTimeout(5_000) {
            var rows = unread.asSnapshot()
            while (rows.any { it.id == 3L }) rows = unread.asSnapshot()
            rows
        }
        assertThat(after.map { it.id }).containsExactly(2L, 1L).inOrder()
        assertThat(neighbours(2)).isEqualTo(Neighbours(newer = null, older = 1))
    }

    @Test
    fun whileSearchingNeighboursAreSearchResults() = runBlocking<Unit> {
        db.entryDao().insertIgnore(listOf(entry(1, 100, title = "Kotlin one"), entry(2, 200, title = "Other"), entry(3, 300, title = "Kotlin two")))
        SearchIndex(db).indexMissing()
        vm.setQuery("kotlin")

        assertThat(neighbours(3)).isEqualTo(Neighbours(newer = null, older = 1))
    }
}
