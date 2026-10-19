package org.evsyukov.brook.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.evsyukov.brook.data.EntryActions
import org.evsyukov.brook.data.db.BrookDatabase
import org.evsyukov.brook.data.remote.GReaderClient
import org.evsyukov.brook.data.remote.Streams
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Sync against a live server from `scripts/dev-freshgo.sh`; skipped unless `-Pbrook.live=<server>` is given. */
@RunWith(AndroidJUnit4::class)
class LiveSyncTest {
    private val server = System.getProperty("brook.live").orEmpty()
    private lateinit var db: BrookDatabase
    private lateinit var client: GReaderClient

    @Before
    fun setUp() {
        assumeTrue("set -Pbrook.live to run", server.isNotEmpty())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BrookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        client = runBlocking { GReaderClient.login(OkHttpClient(), GReaderClient.parseServer(server)!!, "alice", "brook-dev-pw") }
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun syncsAndSendsAReadBack() = runBlocking<Unit> {
        val engine = SyncEngine(db, client)
        val unreadOnServer = client.allItemIds(Streams.READING_LIST, exclude = Streams.READ)

        engine.sync()

        assertThat(db.treeDao().unreadTotal().first()).isEqualTo(unreadOnServer.size)
        assertThat(db.treeDao().feeds().first().map { it.title }).contains("Brook dev feed")
        val first = unreadOnServer.first()
        assertThat(db.entryDao().entryNow(first)!!.content).isNotEmpty()

        EntryActions(db).setRead(listOf(first), true)
        engine.sync()
        assertThat(client.allItemIds(Streams.READING_LIST, exclude = Streams.READ)).doesNotContain(first)

        EntryActions(db).setRead(listOf(first), false)
        engine.sync()
        assertThat(client.allItemIds(Streams.READING_LIST, exclude = Streams.READ)).contains(first)
        assertThat(db.outboxDao().all()).isEmpty()
    }
}
