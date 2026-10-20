package org.evsyukov.brook.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.brook.data.EntryActions
import org.evsyukov.brook.data.Filter
import org.evsyukov.brook.data.Scope
import org.evsyukov.brook.data.db.BrookDatabase
import org.evsyukov.brook.data.db.EntryEntity
import org.evsyukov.brook.data.remote.Streams
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SyncEngineTest {
    private lateinit var db: BrookDatabase
    private val server = FakeReaderApi()
    private var clock = 1_800_000_000_000L
    private lateinit var engine: SyncEngine
    private lateinit var actions: EntryActions

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BrookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        engine = SyncEngine(db, server, now = { clock })
        actions = EntryActions(db, now = { clock })
        server.feed("feed/1", "News")
        server.feed("feed/2", "Blogs")
    }

    @After
    fun tearDown() = db.close()

    private suspend fun local(id: Long): EntryEntity? = db.entryDao().entryNow(id)

    @Test
    fun firstSyncBringsUnreadAndStarredButNotOldReadEntries() = runBlocking<Unit> {
        val unread = (1..5).map { server.add("feed/1") }
        val readStarred = server.add("feed/2", read = true, starred = true)
        val readOnly = server.add("feed/2", read = true)

        val result = engine.sync()

        assertThat(result.newEntries).isEqualTo(5)
        assertThat(db.entryDao().allIds()).containsExactlyElementsIn(unread.map { it.id } + readStarred.id)
        assertThat(local(readOnly.id)).isNull()
        assertThat(local(readStarred.id)!!.isStarred).isTrue()
        assertThat(db.treeDao().feeds().first().map { it.categoryName }).containsExactly("News", "Blogs")
        assertThat(db.treeDao().unreadTotal().first()).isEqualTo(5)
    }

    @Test
    fun sendingTheQueueSendsOnlyItAndKeepsItWhenOffline() = runBlocking<Unit> {
        val a = server.add("feed/1")
        val b = server.add("feed/1")
        engine.sync()
        val later = server.add("feed/1")

        actions.setRead(listOf(a.id), true)
        server.offline = true
        assertThat(runCatching { engine.sendQueued() }.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(db.outboxDao().all()).hasSize(1)

        server.offline = false
        actions.setStarred(b.id, true)
        engine.sendQueued()

        assertThat(a.read).isTrue()
        assertThat(b.starred).isTrue()
        assertThat(db.outboxDao().all()).isEmpty()
        // Not a sync: what the server added is not fetched.
        assertThat(local(later.id)).isNull()
    }

    @Test
    fun changesMadeOfflineReachTheServerOnceItIsBack() = runBlocking<Unit> {
        val a = server.add("feed/1")
        val b = server.add("feed/1")
        val c = server.add("feed/1")
        engine.sync()
        server.offline = true

        actions.setRead(listOf(a.id), true)
        actions.setStarred(b.id, true)
        actions.setRead(listOf(c.id), true)
        actions.setRead(listOf(c.id), false)
        actions.setLabel(b.id, "later", true)

        // The screen shows the changes at once, with no server.
        assertThat(local(a.id)!!.isRead).isTrue()
        assertThat(local(b.id)!!.isStarred).isTrue()
        assertThat(runCatching { engine.sync() }.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(db.outboxDao().all()).hasSize(4)

        server.offline = false
        engine.sync()

        assertThat(a.read).isTrue()
        assertThat(b.starred).isTrue()
        assertThat(b.labels).containsExactly("later")
        assertThat(c.read).isFalse()
        assertThat(server.edits.map { it.first to (it.second + it.third) }).containsExactly(
            listOf(a.id) to listOf(Streams.READ),
            listOf(c.id) to listOf(Streams.READ),
            listOf(b.id) to listOf(Streams.STARRED),
            listOf(b.id) to listOf(Streams.label("later")),
        )
        assertThat(db.outboxDao().all()).isEmpty()
        // The state after the round trip is what the user set.
        assertThat(local(a.id)!!.isRead).isTrue()
        assertThat(local(c.id)!!.isRead).isFalse()
        assertThat(db.entryDao().labelsOf(b.id).first()).containsExactly("later")
    }

    @Test
    fun changesFromAnotherDeviceArrive() = runBlocking<Unit> {
        val x = server.add("feed/1")
        val y = server.add("feed/1")
        engine.sync()

        x.read = true
        y.starred = true
        engine.sync()

        assertThat(local(x.id)!!.isRead).isTrue()
        assertThat(local(x.id)!!.readAt).isEqualTo(clock)
        assertThat(local(y.id)!!.isStarred).isTrue()

        x.read = false
        engine.sync()
        assertThat(local(x.id)!!.isRead).isFalse()
        assertThat(local(x.id)!!.readAt).isNull()
    }

    @Test
    fun aChangeMadeDuringSyncIsNotUndoneByIt() = runBlocking<Unit> {
        val a = server.add("feed/1")
        engine.sync()
        server.duringIds = {
            server.duringIds = null
            actions.setRead(listOf(a.id), true)
        }

        engine.sync()

        assertThat(local(a.id)!!.isRead).isTrue()
        assertThat(db.outboxDao().all().single().entryId).isEqualTo(a.id)
        engine.sync()
        assertThat(a.read).isTrue()
    }

    @Test
    fun aSecondSyncWithoutChangesAddsAndWritesNothing() = runBlocking<Unit> {
        repeat(3) { server.add("feed/1") }
        engine.sync()
        val before = db.entryDao().states()

        val again = engine.sync()

        assertThat(again.newEntries).isEqualTo(0)
        assertThat(again.deletedIds).isEmpty()
        assertThat(db.entryDao().states()).containsExactlyElementsIn(before)
        assertThat(server.edits).isEmpty()
    }

    @Test
    fun newEntriesAndNewTextsOfKnownOnesArrive() = runBlocking<Unit> {
        val old = server.add("feed/1", title = "first title")
        engine.sync()

        val fresh = server.add("feed/2")
        old.title = "second title"
        old.read = true
        old.changedAt = fresh.changedAt
        val result = engine.sync()

        assertThat(result.newEntries).isEqualTo(1)
        assertThat(local(fresh.id)).isNotNull()
        assertThat(local(old.id)!!.title).isEqualTo("second title")
        assertThat(local(old.id)!!.isRead).isTrue()
    }

    @Test
    fun readEntriesAreDroppedAfterRetentionButStarredStay() = runBlocking<Unit> {
        val old = server.add("feed/1")
        val starred = server.add("feed/1", starred = true)
        val recent = server.add("feed/1")
        engine.sync()
        actions.setRead(listOf(old.id, starred.id), true)
        engine.sync()
        clock += TimeUnit.DAYS.toMillis(29)
        actions.setRead(listOf(recent.id), true)
        engine.sync()

        clock += TimeUnit.DAYS.toMillis(2)
        val result = engine.sync()

        assertThat(result.deletedIds).containsExactly(old.id)
        assertThat(local(old.id)).isNull()
        assertThat(local(starred.id)).isNotNull()
        assertThat(local(recent.id)).isNotNull()
    }

    @Test
    fun entriesOfAFeedThatIsGoneAreDroppedUnlessStarred() = runBlocking<Unit> {
        val plain = server.add("feed/2")
        val starred = server.add("feed/2", starred = true)
        engine.sync()

        server.feeds.remove("feed/2")
        engine.sync()

        assertThat(local(plain.id)).isNull()
        assertThat(local(starred.id)).isNotNull()
    }

    @Test
    fun markAllReadMarksTheScopeAndSendsTheseEntries() = runBlocking<Unit> {
        val news = (1..3).map { server.add("feed/1") }
        val blog = server.add("feed/2")
        engine.sync()

        val marked = actions.markAllRead(Scope.Category("News"), Filter.UNREAD)
        engine.sync()

        assertThat(marked).isEqualTo(3)
        assertThat(news.all { it.read }).isTrue()
        assertThat(blog.read).isFalse()
        assertThat(local(blog.id)!!.isRead).isFalse()
        assertThat(server.edits.single().first).containsExactlyElementsIn(news.map { it.id })
    }

    @Test
    fun titlesComeWithoutFullwidthCharacters() {
        assertThat("Tom ＆ Jerry ＜3".withoutFullwidth()).isEqualTo("Tom & Jerry <3")
    }
}
