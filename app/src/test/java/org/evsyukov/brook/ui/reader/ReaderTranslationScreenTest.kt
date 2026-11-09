package org.evsyukov.brook.ui.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.evsyukov.brook.data.EntryActions
import org.evsyukov.brook.data.TreeEditor
import org.evsyukov.brook.data.account.*
import org.evsyukov.brook.data.db.*
import org.evsyukov.brook.data.fulltext.FullTextExtractor
import org.evsyukov.brook.data.images.ImageCache
import org.evsyukov.brook.data.remote.*
import org.evsyukov.brook.data.translation.*
import org.evsyukov.brook.sync.SyncEngine
import org.junit.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReaderTranslationScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val folder = TemporaryFolder()
    private lateinit var vm: ReaderViewModel
    private lateinit var page: ReaderPage

    @Test fun overflowActionUsesServerAndLoadsTranslationIntoTheExistingPage() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<android.content.Context>(),BrookDatabase::class.java).allowMainThreadQueries().build()
        val server = MockWebServer(); server.start()
        val http = OkHttpClient()
        val api = GReaderClient(http,server.url("/api/greader.php"),"fixture")
        val images = ImageCache(folder.newFolder(),http)
        val session = AccountSession(Account("a",api.apiBase.toString(),"alice"),db,api,SyncEngine(db,api),EntryActions(db),TreeEditor(db,api),images,FullTextExtractor(http),TranslationApi(http,api))
        val entry = EntryEntity(1,"feed/1","Original","","https://example.org",1,"<p>Original</p>","",true,false,1)
        runBlocking { db.entryDao().insertIgnore(listOf(entry)); TranslationCache(db).configure("ru",true) }
        val source=TranslationReply("1","ru",sourceHash=TranslationReader.fingerprint(entry.title,entry.content),revision="a".repeat(64))
        server.enqueue(MockResponse.Builder().body("""{"version":1,"enabled":true}""").build())
        server.enqueue(MockResponse.Builder().body(Json.encodeToString(source)).build())
        server.enqueue(MockResponse.Builder().body(Json.encodeToString(source.copy(exists=true,complete=true,title="Перевод",content="<p>Переведённый текст</p>",done=1,total=1))).build())
        try {
            compose.setContent {
                val context = LocalContext.current
                vm = remember { ReaderViewModel(session).also { it.show(1) } }
                page = remember { ReaderPage(context,images,100f) }
                MaterialTheme { ReaderScreen(vm,page,onBack={}) }
            }
            compose.waitUntil(5_000) { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); vm.state.value.entry != null }
            compose.onNodeWithTag(ReaderTags.MORE).performClick()
            compose.onNodeWithTag("reader-translate").performClick()
            compose.waitUntil(5_000) { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); server.requestCount >= 3 }
            compose.waitUntil(5_000) { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); vm.state.value.translation != null }
            compose.runOnIdle {
                val web = page.attach()
                val loaded = Shadows.shadowOf(web).lastLoadDataWithBaseURL.data
                assertThat(loaded).contains("Переведённый текст")
                assertThat(loaded).contains("Перевод")
            }
        } finally {
            if (::vm.isInitialized) {
                val viewModelJob = vm.viewModelScope.coroutineContext[Job]!!
                compose.runOnIdle { session.translationLifetime.cancel(); viewModelJob.cancel() }
                compose.waitUntil(5_000) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    session.translationLifetime.isCompleted && viewModelJob.isCompleted
                }
            }
            if (::page.isInitialized) compose.runOnIdle { page.destroy() }
            server.close(); db.close()
        }
    }
}
