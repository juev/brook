package org.evsyukov.brook.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.evsyukov.brook.data.db.BrookDatabase
import org.evsyukov.brook.data.db.CategoryEntity
import org.evsyukov.brook.data.db.EntryEntity
import org.evsyukov.brook.data.db.EntryLabelEntity
import org.evsyukov.brook.data.db.FeedEntity
import org.evsyukov.brook.data.db.LabelEntity
import org.evsyukov.brook.data.db.PendingKind
import org.evsyukov.brook.data.remote.GReaderClient
import org.evsyukov.brook.data.remote.GReaderException
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** R6: what the editor asks of the server and what it changes here. */
@RunWith(AndroidJUnit4::class)
class TreeEditorTest {
    private val server = MockWebServer()
    private lateinit var db: BrookDatabase
    private lateinit var editor: TreeEditor

    @Before
    fun setUp() = runBlocking<Unit> {
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BrookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        editor = TreeEditor(db, GReaderClient(OkHttpClient(), server.url("/api/greader.php"), "alice/x"))
        db.treeDao().replaceTree(
            categories = listOf(CategoryEntity("News"), CategoryEntity("Blogs")),
            labels = listOf(LabelEntity("later"), LabelEntity("keep")),
            feeds = listOf(FeedEntity("feed/1", "One", "https://x/1", "", "", "News")),
        )
        db.entryDao().insertIgnore(listOf(entry(1), entry(2)))
        db.entryDao().insertLabels(listOf(EntryLabelEntity(1, "later"), EntryLabelEntity(2, "later"), EntryLabelEntity(2, "keep")))
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private fun entry(id: Long) = EntryEntity(
        id = id, feedId = "feed/1", title = "e$id", author = "", url = "", sortTime = id, content = "",
        enclosuresJson = "", isRead = false, isStarred = false, readAt = null,
    )

    private fun answer(body: String, code: Int = 200) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun take(): RecordedRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))

    private fun RecordedRequest.path() = url.encodedPath.removePrefix("/api/greader.php/reader/api/0/")

    private fun RecordedRequest.form(): List<Pair<String, String>> =
        body!!.utf8().split("&").map {
            val (k, v) = it.split("=", limit = 2)
            k to URLDecoder.decode(v, Charsets.UTF_8)
        }

    @Test
    fun subscribeGivesTheStreamOfTheFeed() = runBlocking<Unit> {
        answer("""{"numResults":1,"query":"https://y.org/rss","streamId":"feed/7","streamName":"Y"}""")

        assertThat(editor.subscribe("  https://y.org/rss ")).isEqualTo("feed/7")
        assertThat(take().form()).containsExactly("quickadd" to "https://y.org/rss")
    }

    @Test
    fun noFeedAtTheAddressIsRefused() = runBlocking<Unit> {
        answer("""{"numResults":0,"query":"https://y.org","error":"not a feed"}""")

        val refused = assertThrows(EditRefused.NoFeed::class.java) { runBlocking { editor.subscribe("https://y.org") } }
        assertThat(refused.detail).isEqualTo("not a feed")
    }

    @Test
    fun movingTellsTheCategoryTheFeedLeaves() = runBlocking<Unit> {
        answer("OK")

        editor.moveFeed("feed/1", " Blogs ")
        // Moving into the category it is in asks nothing.
        editor.moveFeed("feed/1", "News")

        assertThat(take().form())
            .containsExactly("ac" to "edit", "s" to "feed/1", "a" to "user/-/label/Blogs", "r" to "user/-/label/News").inOrder()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun aNameInUseIsRefusedBeforeAsking() = runBlocking<Unit> {
        assertThrows(EditRefused.NameTaken::class.java) { runBlocking { editor.renameCategory("News", "Blogs") } }
        // Categories and labels share their names.
        assertThrows(EditRefused.NameTaken::class.java) { runBlocking { editor.renameLabel("later", "News") } }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun renamingALabelRenamesItOnEntriesAndInTheQueue() = runBlocking<Unit> {
        db.entryDao().insertIgnore(listOf(entry(3)))
        EntryActions(db).setLabel(3, "later", on = true)
        answer("token")
        answer("OK")

        editor.renameLabel("later", "Later on")

        take()
        val rename = take()
        assertThat(rename.path()).isEqualTo("rename-tag")
        assertThat(rename.form().take(2)).containsExactly("s" to "user/-/label/later", "dest" to "user/-/label/Later on").inOrder()
        assertThat(db.treeDao().labels().first().map { it.name }).containsExactly("keep", "Later on")
        assertThat(db.entryDao().labelsOf(2).first()).containsExactly("keep", "Later on")
        assertThat(db.outboxDao().all().map { Triple(it.kind, it.entryId, it.argument) })
            .containsExactly(Triple(PendingKind.LABEL_ADD, 3L, "Later on"))
    }

    @Test
    fun deletingALabelTakesItOffEntries() = runBlocking<Unit> {
        EntryActions(db).setLabel(1, "later", on = false)
        answer("token")
        answer("OK")

        editor.deleteLabel("later")

        take()
        assertThat(take().form().first()).isEqualTo("s" to "user/-/label/later")
        assertThat(db.treeDao().labels().first().map { it.name }).containsExactly("keep")
        assertThat(db.entryDao().labelsOf(2).first()).containsExactly("keep")
        assertThat(db.outboxDao().all()).isEmpty()
    }

    @Test
    fun aRefusedDeleteChangesNothingHere() = runBlocking<Unit> {
        answer("token")
        answer("unknown", code = 400)

        assertThrows(GReaderException.BadRequest::class.java) { runBlocking { editor.deleteLabel("later") } }
        assertThat(db.entryDao().labelsOf(1).first()).containsExactly("later")
    }
}
