package org.evsyukov.brook.ui.reader

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.evsyukov.brook.data.EntryActions
import org.evsyukov.brook.data.TreeEditor
import org.evsyukov.brook.data.account.Account
import org.evsyukov.brook.data.account.AccountSession
import org.evsyukov.brook.data.db.BrookDatabase
import org.evsyukov.brook.data.db.EntryEntity
import org.evsyukov.brook.data.db.LabelEntity
import org.evsyukov.brook.data.db.PendingKind
import org.evsyukov.brook.data.fulltext.FullTextExtractor
import org.evsyukov.brook.data.images.ImageCache
import org.evsyukov.brook.data.remote.GReaderClient
import org.evsyukov.brook.sync.SyncEngine
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var db: BrookDatabase
    private lateinit var vm: ReaderViewModel

    @Before
    fun setUp() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BrookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
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
        db.entryDao().insertIgnore(listOf(entry(1, server.url("/e/1").toString()), entry(2, "")))
        db.treeDao().insertLabels(listOf(LabelEntity("later")))
        vm = ReaderViewModel(session)
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
        Dispatchers.resetMain()
    }

    private fun entry(id: Long, url: String) = EntryEntity(
        id = id, feedId = "feed/1", title = "Entry $id", author = "", url = url, sortTime = id,
        content = "<p>Teaser</p>", enclosuresJson = "", isRead = false, isStarred = false, readAt = null,
    )

    private suspend fun state(predicate: (ReaderState) -> Boolean): ReaderState =
        withTimeout(5_000) { vm.state.first(predicate) }

    private fun article() = javaClass.getResource("/fulltext/article.html")!!.readText()

    @Test
    fun openingMarksReadAndQueuesIt() = runBlocking<Unit> {
        vm.show(1)

        val shown = state { it.entry?.isRead == true }
        assertThat(shown.entry!!.id).isEqualTo(1)
        assertThat(db.outboxDao().all().map { it.kind to it.entryId }).containsExactly(PendingKind.READ to 1L)
    }

    @Test
    fun starAndLabelsChangeTheEntry() = runBlocking<Unit> {
        vm.show(1)
        state { it.entry != null }

        vm.setStarred(true)
        vm.setLabel(" later ", true)
        vm.setLabel("new one", true)

        val changed = state { it.entry?.isStarred == true && it.labels.size == 2 }
        assertThat(changed.labels).containsExactly("later", "new one")
        assertThat(changed.allLabels).containsExactly("later", "new one").inOrder()
    }

    @Test
    fun fullTextIsExtractedStoredAndShownAgain() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().body(article()).build())
        vm.show(1)
        state { it.entry != null }

        vm.toggleFullText()

        val full = state { it.showFullText }
        assertThat(full.entry!!.fullContent).contains("The rivers of the north freeze")
        assertThat(db.entryDao().entryNow(1)!!.fullContent).contains("Summer is short and busy")

        vm.toggleFullText()
        assertThat(state { !it.showFullText }.hasFullText).isTrue()

        // Another entry and back: the stored text is shown again without asking the page.
        vm.show(2)
        state { it.entry?.id == 2L }
        vm.show(1)
        assertThat(state { it.entry?.id == 1L && it.showFullText }.showFullText).isTrue()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun aFailedExtractionKeepsTheServerTextAndSaysSo() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(500).build())
        vm.show(1)
        state { it.entry != null }

        vm.toggleFullText()

        withTimeout(5_000) { vm.fullTextFailures.first() }
        val after = state { !it.loadingFullText }
        assertThat(after.showFullText).isFalse()
        assertThat(after.entry!!.fullContent).isNull()
    }

    @Test
    fun anEntryWithoutAddressCannotHaveFullText() = runBlocking<Unit> {
        vm.show(2)
        state { it.entry != null }

        vm.toggleFullText()

        withTimeout(5_000) { vm.fullTextFailures.first() }
        assertThat(server.requestCount).isEqualTo(0)
    }
}
