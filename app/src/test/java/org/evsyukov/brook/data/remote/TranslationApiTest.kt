package org.evsyukov.brook.data.remote

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Test
import java.util.concurrent.TimeUnit

class TranslationApiTest {
    @Test
    fun cachesSuccessfulCapabilityDiscoveryPerConnection() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("""{"version":1,"enabled":true}""").build())
            server.enqueue(MockResponse.Builder().body("""{"version":1,"enabled":false}""").build())
            val http = OkHttpClient()
            val api = TranslationApi(http,GReaderClient(http,server.url("/"),"fixture"))
            assertThat(api.available()).isTrue()
            assertThat(api.available()).isTrue()
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun cancellationClosesTheSocketWhileTheResponseBodyIsStillArriving() = kotlinx.coroutines.runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val headers = kotlinx.coroutines.CompletableDeferred<Unit>()
            val http = OkHttpClient.Builder().eventListener(object : okhttp3.EventListener() {
                override fun responseBodyStart(call: okhttp3.Call) { headers.complete(Unit) }
            }).build()
            server.enqueue(MockResponse.Builder().body("{}").throttleBody(1,2,TimeUnit.SECONDS).build())
            val api = TranslationApi(http,GReaderClient(http,server.url("/"),"fixture"))
            val task = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { api.entry(12,"ru") }
            try {
                kotlinx.coroutines.withTimeout(5_000) { headers.await() }
                task.cancel()
                val stopped = kotlinx.coroutines.withTimeoutOrNull(500) { task.join(); true }
                assertThat(stopped).isTrue()
            } finally { task.cancel(); task.join() }
        }
    }

    @Test
    fun rejectsInvalidOrForeignRepresentationsInsteadOfAdmittingThemAsTranslations() = runTest {
        MockWebServer().use { server ->
            server.start()
            val hash = "a".repeat(64)
            val valid = """{"id":"12","language":"ru","sourceHash":"$hash","revision":"$hash","exists":true,"complete":true,"sameLanguage":false,"title":"Title","content":"<p>Body</p>","done":1,"total":1,"failed":0}"""
            val http = OkHttpClient()
            val api = TranslationApi(http, GReaderClient(http, server.url("/"), "fixture"))
            for (body in listOf(valid.replace(hash, ""), valid.replace("\"id\":\"12\"", "\"id\":\"13\""), valid.replace("\"language\":\"ru\"", "\"language\":\"en\""), valid.replace("\"done\":1", "\"done\":-1"), valid.replace("\"total\":1", "\"total\":2"), "{}")) {
                server.enqueue(MockResponse.Builder().body(body).build())
                assertThat(runCatching { api.entry(12, "ru") }.isFailure).isTrue()
            }
        }
    }

    @Test
    fun retrievesTranslationAndPostsOnlyTheObservedRevisionAndProgress() = runTest {
        MockWebServer().use { server ->
            server.start()
            val hash = "a".repeat(64)
            val initial = """{"id":"12","language":"ru","sourceHash":"$hash","revision":"$hash","exists":false,"complete":false,"sameLanguage":false,"title":"","content":"","done":0,"total":0,"failed":0}"""
            server.enqueue(MockResponse.Builder().body(initial).build())
            server.enqueue(MockResponse.Builder().body(initial.replace("\"exists\":false", "\"exists\":true").replace("\"complete\":false", "\"complete\":true").replace("\"title\":\"\"", "\"title\":\"Перевод\"").replace("\"content\":\"\"", "\"content\":\"<p>Текст</p>\"")).build())
            val http = OkHttpClient()
            val api = TranslationApi(http, GReaderClient(http, server.url("/api/greader.php"), "fixture"))
            val before = api.entry(12, "ru")
            assertThat(before.revision).isEqualTo(hash)
            val after = api.step(before)
            assertThat(after.complete).isTrue()
            assertThat(after.title).isEqualTo("Перевод")
            val get = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertThat(get.url.queryParameter("id")).isEqualTo("12")
            assertThat(get.url.queryParameter("language")).isEqualTo("ru")
            val post = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertThat(post.method).isEqualTo("POST")
            assertThat(post.body!!.utf8()).isEqualTo("id=12&language=ru&revision=$hash&done=0")
            assertThat(post.headers["Authorization"]).isEqualTo("GoogleLogin auth=fixture")
        }
    }

    @Test
    fun discoversEnabledCapabilityAtTheAccountApiBaseUsingOnlyReaderAuthorization() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("""{"version":1,"enabled":true}""").build())
            val http = OkHttpClient()
            val api = TranslationApi(http, GReaderClient(http, server.url("/prefix/api/greader.php"), "alice/fixture"))
            assertThat(api.available()).isTrue()
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertThat(request.url.encodedPath).isEqualTo("/prefix/api/greader.php/api/freshgo/translation")
            assertThat(request.headers["Authorization"]).isEqualTo("GoogleLogin auth=alice/fixture")
            assertThat(request.method).isEqualTo("GET")
        }
    }
}
