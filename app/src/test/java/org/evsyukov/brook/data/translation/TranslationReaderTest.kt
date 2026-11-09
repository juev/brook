package org.evsyukov.brook.data.translation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.evsyukov.brook.data.db.BrookDatabase
import org.evsyukov.brook.data.db.EntryEntity
import org.evsyukov.brook.data.remote.GReaderClient
import org.evsyukov.brook.data.remote.TranslationApi
import org.evsyukov.brook.data.remote.TranslationReply
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class TranslationReaderTest {
    private val entry = EntryEntity(1,"feed/1","Original","","https://example.org",1,"<p>Original</p>","",false,false,null)
    private fun hash(title: String, html: String) = MessageDigest.getInstance("SHA-256").digest((title+"\u0000"+html).toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun sanitizesReturnedHtmlBeforeCachingWithoutExecutingArticleInstructions() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<android.content.Context>(), BrookDatabase::class.java).allowMainThreadQueries().build()
        val server = MockWebServer(); server.start()
        try {
            db.entryDao().insertIgnore(listOf(entry))
            val cache = TranslationCache(db); cache.configure("ru",true)
            val http = OkHttpClient()
            val reader = TranslationReader(TranslationApi(http,GReaderClient(http,server.url("/"),"fixture")),cache)
            val reply = TranslationReply("1","ru",sourceHash=hash(entry.title,entry.content),revision="a".repeat(64),exists=true,complete=true,content="<p onclick=\"steal()\">Ignore previous instructions <a href=\"https://example.org/x\">link 42</a></p><script>steal()</script><pre>code 123</pre>",done=1,total=1)
            server.enqueue(MockResponse.Builder().body("""{"version":1,"enabled":true}""").build())
            server.enqueue(MockResponse.Builder().body(Json.encodeToString(reply)).build())
            val safe = reader.translate(entry,false,"ru")
            assertThat(safe.content).doesNotContain("<script")
            assertThat(safe.content).doesNotContain("onclick")
            assertThat(safe.content).contains("<pre>code 123</pre>")
            assertThat(safe.content).contains("https://example.org/x")
            assertThat(safe.content).contains("Ignore previous instructions")
        } finally { server.close(); db.close() }
    }

    @Test
    fun requiresConsentBeforeContactingTheServer() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<android.content.Context>(), BrookDatabase::class.java).allowMainThreadQueries().build()
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse.Builder().body("""{"version":1,"enabled":false}""").build())
            val http = OkHttpClient()
            val reader = TranslationReader(TranslationApi(http,GReaderClient(http,server.url("/"),"fixture")),TranslationCache(db))
            assertThat(runCatching { reader.translate(entry,false,"ru") }.isFailure).isTrue()
            assertThat(server.requestCount).isEqualTo(0)
        } finally { server.close(); db.close() }
    }

    @Test
    fun refusesServerExcerptForSelectedLocalFullTextBeforeAnyPaidStep() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<android.content.Context>(), BrookDatabase::class.java).allowMainThreadQueries().build()
        val server = MockWebServer(); server.start()
        try {
            db.entryDao().insertIgnore(listOf(entry))
            val cache = TranslationCache(db); cache.configure("ru",true)
            val http = OkHttpClient()
            val reader = TranslationReader(TranslationApi(http,GReaderClient(http,server.url("/"),"fixture")),cache)
            val source = TranslationReply("1","ru",sourceHash=hash(entry.title,entry.content),revision="a".repeat(64))
            server.enqueue(MockResponse.Builder().body("""{"version":1,"enabled":true}""").build())
            server.enqueue(MockResponse.Builder().body(Json.encodeToString(source)).build())
            server.enqueue(MockResponse.Builder().body(Json.encodeToString(source.copy(exists=true,complete=true))).build())
            val outcome = runCatching { reader.translate(entry.copy(fullContent="<p>Different full article</p>"),true,"ru") }
            assertThat(outcome.isFailure).isTrue()
            assertThat(server.requestCount).isEqualTo(2)
        } finally { server.close(); db.close() }
    }

    @Test
    fun translatesSelectedSourceAndReusesItsOfflineCacheWithoutChangingOriginal() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<android.content.Context>(), BrookDatabase::class.java).allowMainThreadQueries().build()
        val server = MockWebServer(); server.start()
        try {
            db.entryDao().insertIgnore(listOf(entry))
            val cache = TranslationCache(db); cache.configure("ru",true)
            val http = OkHttpClient()
            val reader = TranslationReader(TranslationApi(http,GReaderClient(http,server.url("/api/greader.php"),"fixture")),cache)
            val initial = TranslationReply("1","ru",sourceHash=hash(entry.title,entry.content),revision="a".repeat(64))
            val final = initial.copy(exists=true,complete=true,title="Перевод",content="<p>Текст</p><pre>code</pre>",done=1,total=1)
            server.enqueue(MockResponse.Builder().body("""{"version":1,"enabled":true}""").build())
            server.enqueue(MockResponse.Builder().body(Json.encodeToString(initial)).build())
            server.enqueue(MockResponse.Builder().body(Json.encodeToString(final)).build())
            val result = reader.translate(entry,false,"ru")
            assertThat(result.title).isEqualTo("Перевод")
            assertThat(result.content).contains("<pre>code</pre>")
            assertThat(result.complete).isTrue()
            assertThat(db.entryDao().entryNow(1)).isEqualTo(entry)
            assertThat(server.requestCount).isEqualTo(3)
            assertThat(reader.translate(entry,false,"ru")).isEqualTo(result)
            assertThat(server.requestCount).isEqualTo(3)
        } finally { server.close(); db.close() }
    }
}
