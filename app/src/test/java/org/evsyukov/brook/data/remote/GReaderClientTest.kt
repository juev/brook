package org.evsyukov.brook.data.remote

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

class GReaderClientTest {
    private val server = MockWebServer()
    private val http = OkHttpClient()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun fixture(name: String) =
        requireNotNull(javaClass.classLoader!!.getResource("greader/$name.txt")) { name }.readText()

    private fun answer(body: String, code: Int = 200) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun client() = GReaderClient(http, server.url("/api/greader.php"), "alice/5dedb3a1")

    private fun take(): RecordedRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))

    private fun RecordedRequest.form(): List<Pair<String, String>> =
        body!!.utf8().split("&").map {
            val (k, v) = it.split("=", limit = 2)
            k to URLDecoder.decode(v, Charsets.UTF_8)
        }

    @Test
    fun loginTriesTheFreshRssAddressFirstAndKeepsTheAuthValue() = runTest {
        answer(fixture("login"))

        val client = GReaderClient.login(http, server.url("/"), "alice", "alice-api-password")

        val request = take()
        assertThat(request.url.encodedPath).isEqualTo("/api/greader.php/accounts/ClientLogin")
        assertThat(request.form()).containsExactly("Email" to "alice", "Passwd" to "alice-api-password").inOrder()
        assertThat(client.apiBase.encodedPath).isEqualTo("/api/greader.php")

        answer(fixture("user-info"))
        client.userInfo()
        assertThat(take().headers["Authorization"]).isEqualTo("GoogleLogin auth=alice/5dedb3a1c23e7ffb4e3234c3d04cefc2e2b4f7db")
    }

    @Test
    fun loginFallsBackToTheRootWhenThereIsNoGreaderPhp() = runTest {
        answer("not here", code = 404)
        answer(fixture("login"))

        val client = GReaderClient.login(http, server.url("/miniflux/"), "alice", "pw")

        assertThat(take().url.encodedPath).isEqualTo("/miniflux/api/greader.php/accounts/ClientLogin")
        assertThat(take().url.encodedPath).isEqualTo("/miniflux/accounts/ClientLogin")
        assertThat(client.apiBase.encodedPath).isEqualTo("/miniflux")
    }

    @Test
    fun loginWithAWrongPasswordIsUnauthorized() = runTest {
        answer(fixture("login-wrong-password"), code = 401)

        val error = runCatching { GReaderClient.login(http, server.url("/"), "alice", "wrong") }.exceptionOrNull()

        assertThat(error).isInstanceOf(GReaderException.Unauthorized::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun tooManyAttemptsCarriesRetryAfter() = runTest {
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "30").build())

        val error = runCatching { GReaderClient.login(http, server.url("/"), "alice", "x") }.exceptionOrNull()

        assertThat((error as GReaderException.RateLimited).retryAfterSeconds).isEqualTo(30)
    }

    @Test
    fun serverAddressIsNormalized() {
        assertThat(GReaderClient.parseServer("rss.example.org").toString()).isEqualTo("https://rss.example.org/")
        assertThat(GReaderClient.parseServer(" http://10.0.2.2:8080 ").toString()).isEqualTo("http://10.0.2.2:8080/")
        assertThat(GReaderClient.parseServer("has space.org")).isNull()
        assertThat(GReaderClient.parseServer("")).isNull()

        val withAlias = GReaderClient.parseServer("https://h.org/api/greader.php")!!
        assertThat(GReaderClient.candidateBases(withAlias).map { it.toString() }).containsExactly("https://h.org/api/greader.php")
        val root = GReaderClient.parseServer("https://h.org/")!!
        assertThat(GReaderClient.candidateBases(root).map { it.toString() })
            .containsExactly("https://h.org/api/greader.php", "https://h.org/").inOrder()
    }

    @Test
    fun listsAreParsed() = runTest {
        answer(fixture("tag-list"))
        answer(fixture("subscription-list"))
        answer(fixture("unread-count"))
        val client = client()

        val tags = client.tagList()
        val subscriptions = client.subscriptions()
        val counts = client.unreadCounts()

        assertThat(tags.filter { it.type == "folder" }.map { Streams.labelName(it.id) })
            .containsExactly("Blogs", "Scraped & parsed", "Uncategorized")
        assertThat(tags.first { it.id == "user/-/label/later" }.unreadCount).isEqualTo(2)
        assertThat(take().url.queryParameter("output")).isEqualTo("json")

        val atom = subscriptions.first { it.id == "feed/1" }
        assertThat(atom.title).isEqualTo("Atom corpus")
        assertThat(atom.categories.single().label).isEqualTo("Blogs")
        assertThat(atom.url).isEqualTo("http://feeds.freshgo.test/atom.xml")

        assertThat(counts.max).isEqualTo(18)
        assertThat(counts.counts.first { it.id == Streams.READING_LIST }.newestItemTimestampUsec).isEqualTo("1791274998925411")
    }

    @Test
    fun streamContentsSendsParametersAndParsesItems() = runTest {
        answer(fixture("reading-list-first-page"))

        val page = client().streamContents(Streams.READING_LIST, count = 5, exclude = Streams.READ, newerThanSeconds = 1791274998)

        val url = take().url
        assertThat(url.encodedPath).isEqualTo("/api/greader.php/reader/api/0/stream/contents/user/-/state/com.google/reading-list")
        assertThat(url.queryParameter("n")).isEqualTo("5")
        assertThat(url.queryParameter("xt")).isEqualTo(Streams.READ)
        assertThat(url.queryParameter("ot")).isEqualTo("1791274998")
        assertThat(page.items).hasSize(5)
        assertThat(page.continuation).isNotEmpty()
        val first = page.items.first()
        assertThat(ItemIds.parse(first.id)).isEqualTo(1791274998925411L)
        assertThat(first.title).isEqualTo("Guid on a domain from the custom force-https list")
        assertThat(first.origin!!.streamId).isEqualTo("feed/2")
        assertThat(first.published).isEqualTo(1788609600)
    }

    @Test
    fun emptyStreamHasNoContinuation() = runTest {
        answer(fixture("reading-list-continuation-past-the-end"))

        val page = client().streamContents(Streams.READING_LIST, continuation = "1")

        assertThat(page.items).isEmpty()
        assertThat(page.continuation).isNull()
    }

    @Test
    fun labelWithASlashIsOneEncodedSegment() = runTest {
        answer("""{"items":[]}""")

        client().streamContents(Streams.label("a/b & c"))

        assertThat(take().url.encodedPath).endsWith("/stream/contents/user/-/label/a%2Fb%20&%20c")
    }

    @Test
    fun allItemIdsFollowsContinuations() = runTest {
        answer(fixture("ids-first-page"))
        answer(fixture("ids-third-page"))

        val ids = client().allItemIds(Streams.READING_LIST, exclude = Streams.READ, pageSize = 7)

        assertThat(ids).hasSize(12)
        assertThat(ids.first()).isEqualTo(1791274998925411L)
        take()
        val second = take().url
        assertThat(second.queryParameter("c")).isEqualTo("1791274998925404")
        assertThat(second.queryParameter("xt")).isEqualTo(Streams.READ)
        assertThat(second.queryParameter("s")).isEqualTo(Streams.READING_LIST)
    }

    @Test
    fun itemContentsPostsRepeatedIds() = runTest {
        answer(fixture("items-in-three-forms"))

        val items = client().itemContents(listOf(1791274998925400L, 1791274998925395L))

        val request = take()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.body!!.utf8()).isEqualTo("i=1791274998925400&i=1791274998925395")
        val opaque = items.first { it.title == "Opaque guid" }
        assertThat(opaque.categories).contains(Streams.STARRED)
        assertThat(opaque.enclosure.single().type).isEqualTo("audio/mpeg")
        assertThat(opaque.summary!!.content).contains("Escaped HTML description")
    }

    @Test
    fun editTagSendsTheWriteTokenAndRawRepeatedFields() = runTest {
        answer(fixture("token"))
        answer("OK")

        client().editTag(listOf(1L, 2L), add = listOf(Streams.READ, Streams.label("work & play")), remove = listOf(Streams.STARRED))

        assertThat(take().url.encodedPath).endsWith("/reader/api/0/token")
        val edit = take()
        assertThat(edit.url.encodedPath).endsWith("/reader/api/0/edit-tag")
        assertThat(edit.form()).containsExactly(
            "i" to "1",
            "i" to "2",
            "a" to Streams.READ,
            "a" to "user/-/label/work & play",
            "r" to Streams.STARRED,
            "T" to "5dedb3a1c23e7ffb4e3234c3d04cefc2e2b4f7dbZZZZZZZZZZZZZZZZZ",
        ).inOrder()
    }

    @Test
    fun staleWriteTokenIsFetchedAgainOnce() = runTest {
        answer("OLD\n")
        answer("Unauthorized!", code = 401)
        answer("NEW\n")
        answer("OK")

        client().markAllAsRead(Streams.feed("3"), upToId = 1791274998925411L)

        take()
        assertThat(take().form()).containsExactly("s" to "feed/3", "ts" to "1791274998925411", "T" to "OLD").inOrder()
        take()
        assertThat(take().form().last()).isEqualTo("T" to "NEW")
    }

    @Test
    fun badRequestKeepsTheMessage() = runTest {
        answer(fixture("token"))
        answer("Bad Request!", code = 400)

        assertThrows(GReaderException.BadRequest::class.java) {
            kotlinx.coroutines.runBlocking { client().renameTag("Blogs", "Weblogs") }
        }
    }

    @Test
    fun renameAndDeleteUseLabelStreams() = runTest {
        answer(fixture("token"))
        answer("OK")
        answer("OK")
        val client = client()

        client.renameTag("Blogs", "Web logs")
        client.disableTag("later")

        take()
        assertThat(take().form().take(2)).containsExactly("s" to "user/-/label/Blogs", "dest" to "user/-/label/Web logs").inOrder()
        assertThat(take().form().first()).isEqualTo("s" to "user/-/label/later")
    }

    @Test
    fun subscriptionEdits() = runTest {
        answer("""{"numResults":1,"query":"https://x.org/feed","streamId":"feed/9","streamName":"X"}""")
        answer("OK")
        answer("OK")
        answer("OK")
        val client = client()

        val added = client.quickAdd("https://x.org/feed")
        client.renameSubscription("feed/9", "Ex")
        client.moveSubscription("feed/9", fromCategory = "Blogs", category = "News")
        client.unsubscribe("feed/9")

        assertThat(added.streamId).isEqualTo("feed/9")
        assertThat(take().form()).containsExactly("quickadd" to "https://x.org/feed")
        assertThat(take().form()).containsExactly("ac" to "edit", "s" to "feed/9", "t" to "Ex").inOrder()
        assertThat(take().form())
            .containsExactly("ac" to "edit", "s" to "feed/9", "a" to "user/-/label/News", "r" to "user/-/label/Blogs").inOrder()
        assertThat(take().form()).containsExactly("ac" to "unsubscribe", "s" to "feed/9").inOrder()
    }

    @Test
    fun itemIdsInEveryForm() {
        assertThat(ItemIds.parse("tag:google.com,2005:reader/item/00065d27b6018863")).isEqualTo(1791274998925411L)
        assertThat(ItemIds.parse("00065d27b6018863")).isEqualTo(1791274998925411L)
        assertThat(ItemIds.parse("1791274998925411")).isEqualTo(1791274998925411L)
        assertThat(ItemIds.parse("nonsense")).isNull()
    }
}
