package org.evsyukov.brook.ui.reader

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.evsyukov.brook.data.EntryActions
import org.evsyukov.brook.data.TreeEditor
import org.evsyukov.brook.data.account.Account
import org.evsyukov.brook.data.account.AccountSession
import org.evsyukov.brook.data.db.BrookDatabase
import org.evsyukov.brook.data.db.EntryEntity
import org.evsyukov.brook.data.fulltext.FullTextExtractor
import org.evsyukov.brook.data.images.ImageCache
import org.evsyukov.brook.data.remote.*
import org.evsyukov.brook.data.translation.*
import org.evsyukov.brook.sync.SyncEngine
import org.junit.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderTranslationTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = MockWebServer()
    private lateinit var db: BrookDatabase
    private lateinit var vm: ReaderViewModel
    private lateinit var session: AccountSession
    private val entry = EntryEntity(1,"feed/1","Original","","https://example.org",1,"<p>Original</p>","",true,false,1)
    private lateinit var collector: Job
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before fun setup() = runBlocking<Unit> {
        Dispatchers.setMain(mainDispatcher)
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<android.content.Context>(),BrookDatabase::class.java).allowMainThreadQueries().build()
        val http = OkHttpClient()
        val api = GReaderClient(http,server.url("/api/greader.php"),"fixture")
        session = AccountSession(Account("a",api.apiBase.toString(),"alice"),db,api,SyncEngine(db,api),EntryActions(db),TreeEditor(db,api),ImageCache(folder.newFolder(),http),FullTextExtractor(http),TranslationApi(http,api))
        db.entryDao().insertIgnore(listOf(entry,entry.copy(id=2,title="Other")))
        TranslationCache(db).configure("ru",true)
        vm = ReaderViewModel(session)
        collector = CoroutineScope(Dispatchers.Main).launch { vm.state.collect {} }
        vm.show(1)
        withTimeout(5_000) { vm.state.first { it.entry?.id == 1L } }
    }

    @After fun cleanup() = runBlocking<Unit> {
        session.translationLifetime.cancelAndJoin()
        vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        collector.cancelAndJoin()
        server.close(); db.close(); Dispatchers.resetMain()
    }

    private fun answers(delay: Boolean = false) {
        val source = TranslationReply("1","ru",sourceHash=TranslationReader.fingerprint(entry.title,entry.content),revision="a".repeat(64))
        server.enqueue(MockResponse.Builder().body("""{"version":1,"enabled":true}""").build())
        server.enqueue(MockResponse.Builder().body(Json.encodeToString(source)).build())
        server.enqueue(MockResponse.Builder().body(Json.encodeToString(source.copy(exists=true,complete=true,title="Перевод",content="<p>Текст</p>",done=1,total=1))).bodyDelay(if (delay) 1 else 0,java.util.concurrent.TimeUnit.SECONDS).build())
    }

    @Test fun automaticFullTextSourceChangeCancelsInflightTranslation() = runBlocking<Unit> {
        answers(delay=true)
        val job=vm.translate()
        withContext(Dispatchers.IO) { repeat(3) { requireNotNull(server.takeRequest(5,java.util.concurrent.TimeUnit.SECONDS)) } }
        db.entryDao().setFullContent(1,"<p>Full article</p>")
        withTimeout(5_000) { vm.state.first { it.showFullText } }
        job.join()
        assertThat(job.isCancelled).isTrue()
        assertThat(vm.state.value.translation).isNull()
    }

    @Test fun terminalSameLanguageAndPartialResultsHaveExplicitNotices() = runBlocking<Unit> {
        val hash = TranslationReader.fingerprint(entry.title,entry.content)
        for ((reply,message) in listOf(
            TranslationReply("1","ru",sourceHash=hash,revision="a".repeat(64),exists=true,complete=true,sameLanguage=true,title=entry.title,content=entry.content) to org.evsyukov.brook.R.string.translation_same,
            TranslationReply("1","ru",sourceHash=hash,revision="a".repeat(64),exists=true,complete=true,title="Partial",content="body",done=1,total=1,failed=1) to org.evsyukov.brook.R.string.translation_partial,
        )) {
            TranslationCache(db).save(1,false,reply)
            val notice = async(Dispatchers.Unconfined) { withTimeoutOrNull(5_000) { vm.translationNotices.first() } }
            vm.translate().join()
            assertThat(notice.await()).isEqualTo(message)
        }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun configurationIsRememberedAndStartsTranslationForTheCurrentArticle() = runBlocking<Unit> {
        TranslationCache(db).configure("en",false)
        answers()
        vm.configureTranslation("ru",true).join()
        assertThat(TranslationCache(db).preferences()).isEqualTo(TranslationPreferences("ru",true))
        val shown = withTimeoutOrNull(5_000) { vm.state.first { it.translation != null } }
        assertThat(shown?.translation?.title).isEqualTo("Перевод")
    }

    @Test fun firstUseRequestsSetupWithoutContactingServer() = runBlocking<Unit> {
        TranslationCache(db).configure("",false)
        server.enqueue(MockResponse.Builder().code(404).build())
        vm.translate().join()
        val setup = withTimeoutOrNull(5_000) { vm.state.first { it.translationSetup } }
        assertThat(setup?.translationSetup).isTrue()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun unsupportedServerProducesActionableNoticeInsteadOfCrashing() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(404).build())
        val notice = async(Dispatchers.Unconfined) { withTimeoutOrNull(5_000) { vm.translationNotices.first() } }
        vm.translate().join()
        assertThat(notice.await()).isEqualTo(org.evsyukov.brook.R.string.translation_unavailable)
        assertThat(vm.state.value.translation).isNull()
    }

    @Test fun reportsProgressWhileRequestIsInFlight() = runBlocking<Unit> {
        answers(delay=true)
        val job = vm.translate()
        withContext(Dispatchers.IO) { repeat(3) { requireNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) } }
        mainDispatcher.scheduler.runCurrent()
        assertThat(vm.state.value.loadingTranslation).isTrue()
        job.join()
        withTimeout(5_000) { vm.state.first { it.translation != null && !it.loadingTranslation } }
    }

    @Test fun retiringAccountCancelsAndJoinsItsInflightTranslation() = runBlocking<Unit> {
        answers(delay=true)
        val job = vm.translate()
        withContext(Dispatchers.IO) { repeat(3) { requireNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) } }
        session.translationLifetime.cancelAndJoin()
        job.join()
        assertThat(job.isCancelled).isTrue()
        assertThat(TranslationCache(db).read(1,false,TranslationReader.fingerprint(entry.title,entry.content),"ru")).isNull()
    }

    @Test fun showOriginalCancelsInflightTranslation() = runBlocking<Unit> {
        answers(delay=true)
        val job = vm.translate()
        withContext(Dispatchers.IO) { repeat(3) { requireNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) } }
        vm.showOriginal()
        job.join()
        assertThat(job.isCancelled).isTrue()
        assertThat(vm.state.value.translation).isNull()
    }

    @Test fun translationDoesNotFollowNavigationOrFullTextSourceChanges() = runBlocking<Unit> {
        answers(); vm.translate().join()
        withTimeout(5_000) { vm.state.first { it.translation != null } }
        vm.show(2)
        val other = withTimeout(5_000) { vm.state.first { it.entry?.id == 2L } }
        assertThat(other.translation).isNull()
        vm.show(1)
        withTimeout(5_000) { vm.state.first { it.entry?.id == 1L } }
        vm.translate().join()
        withTimeout(5_000) { vm.state.first { it.translation != null } }
        db.entryDao().setFullContent(1,"<p>Full</p>")
        val full = withTimeout(5_000) { vm.state.first { it.showFullText } }
        assertThat(full.translation).isNull()
    }

    @Test fun translatesInPlaceAndCanShowOriginalWithoutChangingFlagsOrFullText() = runBlocking<Unit> {
        answers()
        vm.translate().join()
        val shown = withTimeoutOrNull(5_000) { vm.state.first { it.translation != null } }
        assertThat(shown?.translation?.title).isEqualTo("Перевод")
        assertThat(vm.state.value.entry?.title).isEqualTo("Original")
        vm.showOriginal()
        mainDispatcher.scheduler.runCurrent()
        assertThat(vm.state.value.translation).isNull()
        assertThat(db.entryDao().entryNow(1)?.content).isEqualTo(entry.content)
        assertThat(db.entryDao().entryNow(1)?.fullContent).isNull()
        assertThat(db.entryDao().entryNow(1)?.isStarred).isFalse()
    }
}
