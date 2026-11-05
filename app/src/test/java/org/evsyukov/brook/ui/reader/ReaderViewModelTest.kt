package org.evsyukov.brook.ui.reader

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
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
import org.junit.Assert
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
    private lateinit var session: AccountSession
    private val viewModels = mutableListOf<ReaderViewModel>()
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
        session = AccountSession(
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
        viewModels += vm
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        // A view model outlives the test unless its scope is stopped: a write still in flight when
        // the database closes would throw after the test and be reported by the next one.
        viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        db.close()
        server.close()
        Dispatchers.resetMain()
    }

    private fun entry(id: Long, url: String) = EntryEntity(
        id = id, feedId = "feed/1", title = "Entry $id", author = "", url = url, sortTime = id,
        content = "<p>Teaser</p>", enclosuresJson = "", isRead = false, isStarred = false, readAt = null,
    )

    /** The reader state once [predicate] holds; a timeout names what the test waited for. */
    private suspend fun state(
        what: String,
        flow: StateFlow<ReaderState> = vm.state,
        predicate: (ReaderState) -> Boolean,
    ): ReaderState =
        try {
            withTimeout(WAIT) { flow.first(predicate) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("The reader never showed $what; the last state was ${flow.value}", e)
        }

    private fun article() = javaClass.getResource("/fulltext/article.html")!!.readText()

    @Test
    fun openingMarksReadAndQueuesIt() = runBlocking<Unit> {
        vm.show(1)

        val shown = state("the entry marked read") { it.entry?.isRead == true }
        assertThat(shown.entry!!.id).isEqualTo(1)
        assertThat(db.outboxDao().all().map { it.kind to it.entryId }).containsExactly(PendingKind.READ to 1L)
    }

    @Test
    fun starAndLabelsChangeTheEntry() = runBlocking<Unit> {
        vm.show(1)
        state("the entry") { it.entry != null }

        vm.setStarred(true)
        vm.setLabel(" later ", true)
        vm.setLabel("new one", true)

        val changed = state("the entry starred with two labels") { it.entry?.isStarred == true && it.labels.size == 2 }
        assertThat(changed.labels).containsExactly("later", "new one")
        assertThat(changed.allLabels).containsExactly("later", "new one").inOrder()
    }

    @Test
    fun fullTextIsExtractedStoredAndShownAgain() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().body(article()).build())
        vm.show(1)
        state("the entry") { it.entry != null }

        vm.toggleFullText()

        val full = state("the extracted full text") { it.showFullText }
        assertThat(full.entry!!.fullContent).contains("The rivers of the north freeze")
        assertThat(db.entryDao().entryNow(1)!!.fullContent).contains("Summer is short and busy")

        vm.toggleFullText()
        assertThat(state("the server text after full text is turned off") { !it.showFullText }.hasFullText).isTrue()

        // Another entry and back: the stored text is shown again without asking the page.
        vm.show(2)
        state("the second entry") { it.entry?.id == 2L }
        vm.show(1)
        assertThat(state("the first entry with its stored full text") { it.entry?.id == 1L && it.showFullText }.showFullText).isTrue()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun aFailedExtractionKeepsTheServerTextAndSaysSo() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(500).build())
        vm.show(1)
        state("the entry") { it.entry != null }

        vm.toggleFullText()

        withTimeout(WAIT) { vm.fullTextFailures.first() }
        val after = state("the failed extraction settled") { !it.loadingFullText }
        assertThat(after.showFullText).isFalse()
        assertThat(after.entry!!.fullContent).isNull()
    }

    @Test
    fun anEntryWithoutAddressCannotHaveFullText() = runBlocking<Unit> {
        vm.show(2)
        state("the entry without an address") { it.entry != null }

        vm.toggleFullText()

        withTimeout(WAIT) { vm.fullTextFailures.first() }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun aTextTurnedOffWhileTheStoreReturnsStaysOff() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().body(article()).build())
        val release = CompletableDeferred<Unit>()
        // The text is committed, then the store is held: the state shows the text while the request
        // that produced it has not returned yet, which is exactly the window a reader taps in.
        val gated = ReaderViewModel(session) { id, html ->
            db.entryDao().setFullContent(id, html)
            release.await()
        }
        viewModels += gated
        try {
            gated.show(1)
            state("the entry", gated.state) { it.entry != null }

            val before = gated.viewModelScope.coroutineContext[Job]!!.children.toSet()
            gated.toggleFullText()
            val extraction = gated.viewModelScope.coroutineContext[Job]!!.children.first { it !in before }

            assertThat(state("the extracted full text", gated.state) { it.showFullText }.hasFullText).isTrue()

            gated.toggleFullText()
            assertThat(state("the text turned off", gated.state) { !it.showFullText }.hasFullText).isTrue()

            release.complete(Unit)
            extraction.join()

            // The request has ended; the choice made while it was still running is the final one.
            val settled = state("the request settled", gated.state) { !it.loadingFullText }
            Assert.assertFalse(FULL_TEXT_OFF_MARKER, settled.showFullText)
            assertThat(db.entryDao().entryNow(1)!!.fullContent).contains("Summer is short and busy")
        } finally {
            release.complete(Unit)
        }
    }

    private companion object {
        /** Generous for a loaded runner; the waits are woken by the state, not by the clock. */
        const val WAIT = 5_000L

        /**
         * The message of the one assertion the mutation proof watches: the old order of the choice
         * and the store must fail exactly here.
         */
        const val FULL_TEXT_OFF_MARKER = "FULL_TEXT_TOGGLE_OFF_MUST_SURVIVE_STORE_COMPLETION"
    }
}
