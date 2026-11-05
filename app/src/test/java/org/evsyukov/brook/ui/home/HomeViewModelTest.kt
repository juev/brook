package org.evsyukov.brook.ui.home

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import androidx.paging.testing.asSnapshot
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
import org.evsyukov.brook.data.db.PendingKind
import org.evsyukov.brook.data.fulltext.FullTextExtractor
import org.evsyukov.brook.data.images.ImageCache
import org.evsyukov.brook.data.remote.GReaderClient
import org.evsyukov.brook.data.search.SearchIndex
import org.evsyukov.brook.sync.SyncEngine
import org.evsyukov.brook.ui.list.EntryRow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class HomeViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var db: BrookDatabase
    private lateinit var vm: HomeViewModel

    /** Every view model the test made, so its scope stops before the database closes. */
    private val vms = mutableListOf<HomeViewModel>()

    @Before
    fun setUp() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BrookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.treeDao().replaceTree(listOf(CategoryEntity("News")), emptyList(), listOf(FeedEntity("feed/1", "One", "", "", "", "News")))
        vm = homeViewModel(EntryActions(db))
    }

    private fun homeViewModel(actions: EntryActions): HomeViewModel {
        val http = OkHttpClient()
        val api = GReaderClient(http, "http://localhost/api/greader.php".toHttpUrl(), "alice/x")
        val session = AccountSession(
            account = Account("a", "http://localhost/api/greader.php", "alice"),
            db = db,
            api = api,
            sync = SyncEngine(db, api),
            actions = actions,
            editor = TreeEditor(db, api),
            images = ImageCache(folder.newFolder(), http),
            fullText = FullTextExtractor(http),
        )
        return HomeViewModel(session, onFullTextChanged = {}).also { vms += it }
    }

    /** Holds the bulk read inside its transaction so a test can act while it runs. */
    private class BulkGate {
        val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)

        fun now(): Long {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "the bulk read was not released" }
            return System.currentTimeMillis()
        }

        fun release() = release.countDown()
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        vms.forEach { it.viewModelScope.cancel() }
        vms.forEach { it.viewModelScope.coroutineContext[Job]?.join() }
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
    fun theFlatMembershipFollowsReadingStarringAndSync() = runBlocking<Unit> {
        db.treeDao().upsertFeeds(listOf(FeedEntity("feed/2", "Two", "", "", "", "News")))
        db.entryDao().insertIgnore(
            listOf(
                entry(1, 100, read = true, starred = true),
                entry(2, 200, feed = "feed/2"),
            ),
        )

        val tree = withTimeout(5_000) { vm.navTree.first { it.feeds.size == 2 } }
        assertThat(tree.feedsFor(Filter.ALL).map { it.id }).containsExactly("feed/1", "feed/2").inOrder()
        assertThat(tree.feedsFor(Filter.UNREAD).map { it.id }).containsExactly("feed/2")
        // A feed with a starred entry stays in Starred though the entry is read.
        assertThat(tree.feedsFor(Filter.STARRED).map { it.id }).containsExactly("feed/1")

        EntryActions(db).setStarred(1L, false)
        withTimeout(5_000) { vm.navTree.first { it.feedsFor(Filter.STARRED).isEmpty() } }

        EntryActions(db).setRead(listOf(2L), true)
        withTimeout(5_000) { vm.navTree.first { it.feedsFor(Filter.UNREAD).isEmpty() } }
    }

    @Test
    fun aSelectedFeedWithNoUnreadStaysSelectedWhileThePickerHidesIt() = runBlocking<Unit> {
        db.entryDao().insertIgnore(listOf(entry(1, 100, read = true)))
        vm.select(Scope.Feed("feed/1"))
        // The state flow starts empty; wait for the one feed rather than taking the first emission.
        withTimeout(5_000) { vm.navTree.first { it.feeds.size == 1 && it.feeds[0].unread == 0 } }

        assertThat(vm.selection.value.scope).isEqualTo(Scope.Feed("feed/1"))
        assertThat(vm.navTree.value.feedsFor(Filter.UNREAD)).isEmpty()
        assertThat(vm.navTree.value.has(Scope.Feed("feed/1"))).isTrue()
    }

    @Test
    fun theBulkIsAvailableForUnreadEntriesAndForRetainedReadOnes() = runBlocking<Unit> {
        db.entryDao().insertIgnore(listOf(entry(1, 100)))
        withTimeout(5_000) { vm.bulkAvailability.first { it?.canMark == true } }

        // A read entry kept by the unread reading session is still something to clean up.
        vm.opened(1)
        EntryActions(db).setRead(listOf(1L), true)
        withTimeout(5_000) { vm.navTree.first { it.unreadTotal == 0 } }
        assertThat(vm.bulkAvailability.first { it != null }?.canMark).isTrue()

        // Another filter starts a session of its own: nothing unread, nothing retained.
        vm.select(Filter.ALL)
        withTimeout(5_000) { vm.bulkAvailability.first { it?.canMark == false } }
    }

    @Test
    fun theBulkIsOffOnceNothingIsUnreadAndNothingIsRetained() = runBlocking<Unit> {
        db.entryDao().insertIgnore(listOf(entry(1, 100)))
        withTimeout(5_000) { vm.bulkAvailability.first { it?.canMark == true } }

        EntryActions(db).setRead(listOf(1L), true)

        withTimeout(5_000) { vm.bulkAvailability.first { it?.canMark == false } }
    }

    @Test
    fun theBulkOfTheStarredModeCountsOnlyStarredEntries() = runBlocking<Unit> {
        db.entryDao().insertIgnore(listOf(entry(1, 100, read = true, starred = true), entry(2, 200)))
        vm.select(Filter.STARRED)

        // A non-starred unread entry is outside the Starred scope.
        withTimeout(5_000) { vm.bulkAvailability.first { it?.canMark == false } }

        EntryActions(db).setRead(listOf(1L), false)
        withTimeout(5_000) { vm.bulkAvailability.first { it?.canMark == true } }
    }

    @Test
    fun theBulkAvailabilityIsTaggedWithTheSelectionItCounted() = runBlocking<Unit> {
        db.treeDao().upsertFeeds(listOf(FeedEntity("feed/2", "Two", "", "", "", "News")))
        db.entryDao().insertIgnore(listOf(entry(1, 100)))

        vm.select(Scope.Feed("feed/1"))
        val first = withTimeout(5_000) {
            vm.bulkAvailability.first { it?.selection?.scope == Scope.Feed("feed/1") }
        }
        assertThat(checkNotNull(first).canMark).isTrue()

        vm.select(Scope.Feed("feed/2"))
        val next = withTimeout(5_000) {
            vm.bulkAvailability.first { it?.selection?.scope == Scope.Feed("feed/2") }
        }
        // feed/2 has no entries: the count belongs to feed/2 and says there is nothing to mark.
        assertThat(checkNotNull(next).canMark).isFalse()
    }

    @Test
    fun aScopeStillThereStays() = runBlocking<Unit> {
        vm.select(Scope.Category("News"))
        db.treeDao().replaceTree(listOf(CategoryEntity("News"), CategoryEntity("Blogs")), emptyList(), emptyList())
        db.treeDao().feeds().first { it.isEmpty() }

        assertThat(vm.selection.value.scope).isEqualTo(Scope.Category("News"))
    }

    private fun entry(id: Long, time: Long, read: Boolean = false, starred: Boolean = false, title: String = "Entry $id", feed: String = "feed/1") = EntryEntity(
        id = id, feedId = feed, title = title, author = "", url = "", sortTime = time, content = "<p>Text $id</p>",
        enclosuresJson = "", isRead = read, isStarred = starred, readAt = null,
    )

    private suspend fun neighbours(id: Long) = withTimeout(5_000) { vm.neighbours(id).first() }

    /** A snapshot with a bound: one taken while Room invalidates the list can wait for ever. */
    private suspend fun Flow<PagingData<EntryRow>>.snapshot(): List<EntryRow> =
        PagingSnapshotRetry.retry { withTimeout(2_000) { asSnapshot() } }

    private fun awaitEntered(gate: BulkGate) {
        val entered = gate.entered.await(5, TimeUnit.SECONDS)
        if (!entered) gate.release()
        assertThat(entered).isTrue()
    }

    /**
     * Marks [id] read and returns the rows once the list shows it read. A new Room PagingSource starts
     * watching its tables on a coroutine of its own after it is made (room-paging 2.8.5), and a write
     * before that passes it unseen; the row is written again until the list shows it.
     */
    private suspend fun Flow<PagingData<EntryRow>>.readAndAwait(id: Long): List<EntryRow> {
        EntryActions(db).setRead(listOf(id), true)
        return awaitRows(again = { db.entryDao().setRead(listOf(id), true, System.currentTimeMillis()) }) { rows ->
            rows.any { it.id == id && it.isRead }
        }
    }

    /**
     * The rows of the list once [until] holds for them, with [again] run before each new look. A snapshot
     * taken while Room invalidates the list can wait for ever (seen on CI with paging-testing 3.5.3), so
     * each look gets two seconds.
     */
    private suspend fun Flow<PagingData<EntryRow>>.awaitRows(
        what: String = "the expected rows",
        again: suspend () -> Unit = {},
        until: (List<EntryRow>) -> Boolean,
    ): List<EntryRow> = try {
        withTimeout(10_000) {
            var rows = snapshotOnce()
            while (rows == null || !until(rows)) {
                delay(50)
                again()
                rows = snapshotOnce()
            }
            rows
        }
    } catch (e: TimeoutCancellationException) {
        // The message names the wait that failed, so a setup timeout cannot pass for the bulk wait.
        throw AssertionError("$what did not appear within 10 s", e)
    }

    /** One look at the list; only a paging-testing generation race is tried again. */
    private suspend fun Flow<PagingData<EntryRow>>.snapshotOnce(): List<EntryRow>? =
        PagingSnapshotRetry.retry { withTimeoutOrNull(2_000) { asSnapshot() } }

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
        val after = unread.readAndAwait(2)
        assertThat(after.map { it.id to it.isRead }).containsExactly(2L to true, 1L to false).inOrder()

        // The screen asks for the list of every selection it shows.
        vm.select(Filter.ALL)
        vm.list(vm.selection.value)
        vm.select(Filter.UNREAD)
        assertThat(vm.list(vm.selection.value).asSnapshot().map { it.id }).containsExactly(1L)
    }

    @Test
    fun entriesReadOnTheWayLeaveAnUnreadListAllButTheOneOnScreen() = runBlocking<Unit> {
        db.entryDao().insertIgnore(listOf(entry(1, 100), entry(2, 200), entry(3, 300)))
        val unread = vm.list(vm.selection.value)
        assertThat(unread.asSnapshot().map { it.id }).containsExactly(3L, 2L, 1L).inOrder()
        // Each read waits for the list that shows it: Room tells the list on its own thread.
        vm.opened(3)
        unread.readAndAwait(3)
        vm.opened(2)
        val before = unread.readAndAwait(2)
        assertThat(before.map { it.id }).containsExactly(3L, 2L, 1L).inOrder()

        // Back from the background, or a pull to refresh.
        vm.forgetReadOnTheWay()

        val after = unread.awaitRows { rows -> rows.none { it.id == 3L } }
        assertThat(after.map { it.id }).containsExactly(2L, 1L).inOrder()
        assertThat(neighbours(2)).isEqualTo(Neighbours(newer = null, older = 1))
    }

    @Test
    fun aRefreshAfterGoingBackToTheListTakesEveryEntryReadOnTheWayOut() = runBlocking<Unit> {
        db.entryDao().insertIgnore(listOf(entry(1, 100), entry(2, 200), entry(3, 300)))
        val unread = vm.list(vm.selection.value)
        assertThat(unread.asSnapshot().map { it.id }).containsExactly(3L, 2L, 1L).inOrder()
        vm.opened(2)
        unread.readAndAwait(2)

        // Back on the phone the entry leaves the screen; then a pull to refresh.
        vm.closed(2)
        vm.forgetReadOnTheWay()

        val after = unread.awaitRows { rows -> rows.none { it.id == 2L } }
        assertThat(after.map { it.id }).containsExactly(3L, 1L).inOrder()
    }

    @Test
    fun whileSearchingNeighboursAreSearchResults() = runBlocking<Unit> {
        db.entryDao().insertIgnore(listOf(entry(1, 100, title = "Kotlin one"), entry(2, 200, title = "Other"), entry(3, 300, title = "Kotlin two")))
        SearchIndex(db).indexMissing()
        vm.setQuery("kotlin")

        assertThat(neighbours(3)).isEqualTo(Neighbours(newer = null, older = 1))
    }

    @Test
    fun markAllReadTakesTheEntriesReadOnTheWayOutOfAnUnreadList() = runBlocking<Unit> {
        db.treeDao().upsertFeeds(listOf(FeedEntity("feed/2", "Two", "", "", "", "News")))
        db.entryDao().insertIgnore(listOf(entry(1, 100), entry(2, 200), entry(3, 300), entry(6, 600, feed = "feed/2")))
        vm.select(Scope.Feed("feed/1"))
        val unread = vm.list(vm.selection.value)
        assertThat(unread.snapshot().map { it.id }).containsExactly(3L, 2L, 1L).inOrder()

        // Two entries read on the way stay in the list until the bulk read.
        vm.opened(3)
        unread.readAndAwait(3)
        vm.opened(2)
        unread.readAndAwait(2)
        vm.closed(2)
        assertThat(unread.snapshot().map { it.id }).containsExactly(3L, 2L, 1L).inOrder()

        vm.markAllRead()

        assertThat(unread.awaitRows(what = "the unread list after the bulk read") { it.isEmpty() }).isEmpty()
        assertThat(db.entryDao().states().filter { it.id != 6L }.all { it.isRead }).isTrue()
        // Another feed is outside the selected scope and stays unread and unsent.
        assertThat(db.entryDao().states().first { it.id == 6L }.isRead).isFalse()
        assertThat(db.outboxDao().all().map { it.entryId to it.kind }).containsExactly(
            3L to PendingKind.READ, 2L to PendingKind.READ, 1L to PendingKind.READ,
        )
        assertThat(neighbours(1)).isEqualTo(Neighbours())
    }

    @Test
    fun markAllReadEmptiesTheListWhenNothingIsLeftUnread() = runBlocking<Unit> {
        db.entryDao().insertIgnore(listOf(entry(1, 100), entry(2, 200), entry(3, 300)))
        val unread = vm.list(vm.selection.value)
        vm.opened(3)
        unread.readAndAwait(3)
        vm.opened(2)
        unread.readAndAwait(2)
        vm.opened(1)
        unread.readAndAwait(1)
        vm.closed(1)
        assertThat(unread.snapshot().map { it.id }).containsExactly(3L, 2L, 1L).inOrder()
        assertThat(db.entryDao().states().none { !it.isRead }).isTrue()
        val queued = db.outboxDao().all().map { it.entryId to it.kind }

        // Every visible row was read in this session, so the bulk marks nothing new.
        vm.markAllRead()

        assertThat(unread.awaitRows(what = "the unread list after the bulk read") { it.isEmpty() }).isEmpty()
        assertThat(db.outboxDao().all().map { it.entryId to it.kind }).isEqualTo(queued)
        assertThat(neighbours(1)).isEqualTo(Neighbours())
    }

    @Test
    fun aStaleBulkOfAFeedDoesNotMarkTheSelectionTheViewModelMovedTo() = runBlocking<Unit> {
        db.treeDao().upsertFeeds(listOf(FeedEntity("feed/2", "Two", "", "", "", "News")))
        db.entryDao().insertIgnore(listOf(entry(1, 100), entry(2, 200, feed = "feed/2")))
        vm.select(Scope.Feed("feed/1"))
        val stale = vm.selection.value
        vm.select(Scope.All)

        // The confirmation still names feed/1, but the view model already moved to All.
        withTimeout(5_000) { vm.markAllRead(stale).join() }

        assertThat(db.entryDao().states().none { it.isRead }).isTrue()
        assertThat(db.outboxDao().all()).isEmpty()

        // A confirmation of the selection the view model actually holds still marks it.
        withTimeout(5_000) { vm.markAllRead(vm.selection.value).join() }
        assertThat(db.entryDao().states().all { it.isRead }).isTrue()
        assertThat(db.outboxDao().all().map { it.entryId }).containsExactly(1L, 2L)
    }

    @Test
    fun markAllReadForgetsTheOpenEntryToo() = runBlocking<Unit> {
        db.entryDao().insertIgnore(listOf(entry(1, 100), entry(2, 200)))
        val unread = vm.list(vm.selection.value)
        vm.opened(2)
        unread.readAndAwait(2)

        // On a tablet the entry stays on screen while the bulk runs; a later cleanup must not bring it back.
        withTimeout(5_000) { vm.markAllRead().join() }
        vm.forgetReadOnTheWay()

        assertThat(unread.awaitRows(what = "the unread list after the bulk read") { it.isEmpty() }).isEmpty()
    }

    @Test
    fun markAllReadLeavesTheAllAndStarredFiltersAsTheyAre() = runBlocking<Unit> {
        db.entryDao().insertIgnore(listOf(entry(1, 100, starred = true), entry(2, 200)))
        vm.select(Filter.ALL)
        val all = vm.list(vm.selection.value)
        assertThat(all.snapshot().map { it.id }).containsExactly(2L, 1L).inOrder()

        withTimeout(5_000) { vm.markAllRead().join() }

        assertThat(db.entryDao().states().all { it.isRead }).isTrue()
        assertThat(all.snapshot().map { it.id }).containsExactly(2L, 1L).inOrder()

        // New unread rows: the starred filter takes only the starred one.
        db.entryDao().insertIgnore(listOf(entry(3, 300, starred = true), entry(4, 400)))
        vm.select(Filter.STARRED)
        val starred = vm.list(vm.selection.value)
        assertThat(starred.snapshot().map { it.id }).containsExactly(3L, 1L).inOrder()

        withTimeout(5_000) { vm.markAllRead().join() }

        assertThat(db.entryDao().states().first { it.id == 3L }.isRead).isTrue()
        assertThat(db.entryDao().states().first { it.id == 4L }.isRead).isFalse()
        assertThat(db.outboxDao().all().map { it.entryId }).containsExactly(1L, 2L, 3L)
        assertThat(starred.snapshot().map { it.id }).containsExactly(3L, 1L).inOrder()
    }

    @Test
    fun aBulkReadThatFinishesAfterTheSelectionChangesKeepsTheNewSessionPlace() = runBlocking<Unit> {
        db.entryDao().insertIgnore(listOf(entry(1, 100), entry(2, 200)))
        val gate = BulkGate()
        vm = homeViewModel(EntryActions(db, now = gate::now))

        vm.select(Scope.Feed("feed/1"))
        val old = vm.list(vm.selection.value)
        vm.opened(2)
        old.readAndAwait(2)

        val bulk = vm.markAllRead()
        try {
            awaitEntered(gate)
            // The reader moves to all feeds and opens the same entry there while the bulk still runs.
            vm.select(Scope.All)
            vm.opened(2)
        } finally {
            gate.release()
            withTimeout(5_000) { bulk.join() }
        }

        // Entry 2 was opened in the new session and keeps its place; entry 1 is read now.
        val now = vm.list(vm.selection.value)
        assertThat(now.snapshot().map { it.id }).containsExactly(2L)
    }

    @Test
    fun anEntryOpenedWhileTheBulkReadRunsKeepsItsPlace() = runBlocking<Unit> {
        db.entryDao().insertIgnore(listOf(entry(1, 100), entry(2, 200)))
        val gate = BulkGate()
        vm = homeViewModel(EntryActions(db, now = gate::now))
        val unread = vm.list(vm.selection.value)

        val bulk = vm.markAllRead()
        try {
            awaitEntered(gate)
            // The reader starts reading while the bulk is still running.
            vm.opened(2)
        } finally {
            gate.release()
            withTimeout(5_000) { bulk.join() }
        }

        val after = unread.awaitRows { rows -> rows.none { it.id == 1L } }
        assertThat(after.map { it.id }).containsExactly(2L)
        assertThat(db.entryDao().states().all { it.isRead }).isTrue()
        assertThat(db.outboxDao().all().map { it.entryId }).containsExactly(1L, 2L)
    }

    @Test
    fun anEntryReopenedWhileTheBulkReadRunsKeepsItsPlace() = runBlocking<Unit> {
        db.entryDao().insertIgnore(listOf(entry(1, 100), entry(2, 200)))
        val gate = BulkGate()
        vm = homeViewModel(EntryActions(db, now = gate::now))
        val unread = vm.list(vm.selection.value)
        vm.opened(2)
        unread.readAndAwait(2)

        val bulk = vm.markAllRead()
        try {
            awaitEntered(gate)
            // The same entry is opened again: the retained set does not change, the opening does.
            vm.opened(2)
        } finally {
            gate.release()
            withTimeout(5_000) { bulk.join() }
        }

        val after = unread.awaitRows { rows -> rows.none { it.id == 1L } }
        assertThat(after.map { it.id }).containsExactly(2L)
    }

    @Test
    fun aBulkReadClearsEntriesOpenedBeforeItWhenTheyAreNotReopened() = runBlocking<Unit> {
        db.entryDao().insertIgnore(listOf(entry(1, 100), entry(2, 200)))
        val gate = BulkGate()
        vm = homeViewModel(EntryActions(db, now = gate::now))
        val unread = vm.list(vm.selection.value)
        vm.opened(2)
        unread.readAndAwait(2)

        val bulk = vm.markAllRead()
        try {
            awaitEntered(gate)
        } finally {
            gate.release()
            withTimeout(5_000) { bulk.join() }
        }

        assertThat(unread.awaitRows(what = "the unread list after the bulk read") { it.isEmpty() }).isEmpty()
    }
}
