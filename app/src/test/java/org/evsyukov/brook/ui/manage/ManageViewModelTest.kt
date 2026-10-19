package org.evsyukov.brook.ui.manage

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
import okhttp3.OkHttpClient
import org.evsyukov.brook.data.EntryActions
import org.evsyukov.brook.data.TreeEditor
import org.evsyukov.brook.data.account.Account
import org.evsyukov.brook.data.account.AccountSession
import org.evsyukov.brook.data.db.BrookDatabase
import org.evsyukov.brook.data.db.CategoryEntity
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
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ManageViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var db: BrookDatabase
    private lateinit var vm: ManageViewModel
    private var syncs = 0

    @Before
    fun setUp() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BrookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.treeDao().replaceTree(listOf(CategoryEntity("News"), CategoryEntity("Blogs")), emptyList(), emptyList())
        val http = OkHttpClient()
        val api = GReaderClient(http, server.url("/api/greader.php"), "alice/x")
        val session = AccountSession(
            account = Account("a", server.url("/api/greader.php").toString(), "alice"),
            db = db,
            api = api,
            sync = SyncEngine(db, api),
            actions = EntryActions(db),
            editor = TreeEditor(db, api),
            images = ImageCache(folder.newFolder("images"), http),
            fullText = FullTextExtractor(http),
        )
        vm = ManageViewModel(session, sync = { syncs++ })
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
        Dispatchers.resetMain()
    }

    private fun answer(body: String, code: Int = 200) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private suspend fun settled(): ManageState = withTimeout(5_000) { vm.state.first { !it.busy && it.message != null } }

    @Test
    fun aChangeOnTheServerIsFollowedByASync() = runBlocking<Unit> {
        answer("""{"numResults":1,"query":"https://y.org/rss","streamId":"feed/7","streamName":"Y"}""")

        vm.subscribe("https://y.org/rss")

        assertThat(settled().message).isEqualTo(ManageMessage.Subscribed)
        assertThat(syncs).isEqualTo(1)
    }

    @Test
    fun aRefusalIsShownWithTheWordsOfTheServer() = runBlocking<Unit> {
        answer("Unknown feed", code = 400)

        vm.unsubscribe("feed/404")

        assertThat(settled().message).isEqualTo(ManageMessage.Refused("Unknown feed"))
        assertThat(syncs).isEqualTo(0)
    }

    @Test
    fun withoutTheServerNothingChanges() = runBlocking<Unit> {
        server.close()

        vm.renameFeed("feed/1", "One")

        assertThat(settled().message).isEqualTo(ManageMessage.NoNetwork)
        assertThat(syncs).isEqualTo(0)
    }

    @Test
    fun aTakenNameIsRefused() = runBlocking<Unit> {
        vm.renameCategory("News", "Blogs")

        assertThat(settled().message).isEqualTo(ManageMessage.NameTaken("Blogs"))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun exportWritesWhatTheServerGives() = runBlocking<Unit> {
        answer("<opml/>")
        var written: String? = null

        vm.exportOpml { written = it }

        assertThat(settled().message).isEqualTo(ManageMessage.Exported)
        assertThat(written).isEqualTo("<opml/>")
    }

    @Test
    fun anUnreadableFileIsNotSent() = runBlocking<Unit> {
        vm.importOpml { throw IOException("gone") }

        assertThat(settled().message).isEqualTo(ManageMessage.FileFailed)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun importPostsTheFileAndSyncs() = runBlocking<Unit> {
        answer("OK")

        vm.importOpml { "<opml/>" }

        assertThat(settled().message).isEqualTo(ManageMessage.Imported)
        assertThat(server.takeRequest().body!!.utf8()).isEqualTo("<opml/>")
        assertThat(syncs).isEqualTo(1)
    }
}
