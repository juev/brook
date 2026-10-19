package org.evsyukov.brook.ui.home

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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.evsyukov.brook.data.EntryActions
import org.evsyukov.brook.data.Scope
import org.evsyukov.brook.data.TreeEditor
import org.evsyukov.brook.data.account.Account
import org.evsyukov.brook.data.account.AccountSession
import org.evsyukov.brook.data.db.BrookDatabase
import org.evsyukov.brook.data.db.CategoryEntity
import org.evsyukov.brook.data.db.FeedEntity
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
}
