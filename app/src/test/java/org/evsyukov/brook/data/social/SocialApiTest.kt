package org.evsyukov.brook.data.social

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.net.URLDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@RunWith(AndroidJUnit4::class)
class SocialApiTest {
    private val server = MockWebServer().apply { start() }
    // Rewrite only in tests: production accepts HTTPS and never follows credential-bearing redirects.
    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().url(chain.request().url.newBuilder().scheme("http").build()).build())
    }.build()
    private val api = SocialApi(http)
    private fun endpoint() = server.url("/").toString().replace("http:", "https:")
    @After fun close() = server.close()

    @Test
    fun linkdingConnectionChecksTheTokenAndDoesNotEnableAutomaticSaving() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().body("""{"count":2,"next":null,"previous":null,"results":[]}""").build())
        val c = api.connect(SocialService.LINKDING, endpoint(), "", "api-token")
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertThat(request.url.encodedPath).isEqualTo("/api/bookmarks/")
        assertThat(request.headers["Authorization"]).isEqualTo("Token api-token")
        assertThat(c.service).isEqualTo(SocialService.LINKDING)
        assertThat(c.userId).isEqualTo("self")
        assertThat(c.enabled).isFalse()
        assertThat(c.toString()).doesNotContain("api-token")
    }

    @Test
    fun linkdingSavesAPrivateBookmarkWithADeclaredSharingState() = runBlocking<Unit> {
        val c = SocialConnection("l", SocialService.LINKDING, endpoint(), "self", "links.example.org", "api-token", enabled = true)
        val d = Delivery(id = "3jzfcijpj2z2a", entryId = 1, destination = "l", title = "<b>Title &amp; more</b>", url = "https://example.org/story", createdAt = 1000)
        server.enqueue(MockResponse.Builder().body("""{"bookmark":null,"metadata":{},"auto_tags":[]}""").build())
        server.enqueue(MockResponse.Builder().code(201).body("""{"id":41,"url":"https://example.org/story","title":"Title & more"}""").build())
        val remote = api.publish(c, d)
        assertThat(remote).isEqualTo("41")
        val check = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertThat(check.url.encodedPath).isEqualTo("/api/bookmarks/check/")
        assertThat(check.url.queryParameter("url")).isEqualTo("https://example.org/story")
        assertThat(check.headers["Authorization"]).isEqualTo("Token api-token")
        val create = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertThat(create.url.encodedPath).isEqualTo("/api/bookmarks/")
        assertThat(create.method).isEqualTo("POST")
        val payload = Json.parseToJsonElement(create.body!!.utf8()).jsonObject
        assertThat(payload["url"]!!.jsonPrimitive.content).isEqualTo("https://example.org/story")
        assertThat(payload["title"]!!.jsonPrimitive.content).isEqualTo("Title & more")
        assertThat(payload["description"]!!.jsonPrimitive.content).isEmpty()
        assertThat(payload["shared"]!!.jsonPrimitive.content).isEqualTo("false")
        assertThat(payload["is_archived"]!!.jsonPrimitive.content).isEqualTo("false")
        assertThat(payload["unread"]!!.jsonPrimitive.content).isEqualTo("false")
    }

    @Test
    fun linkdingReusesAnExistingBookmarkInsteadOfOverwritingIt() = runBlocking<Unit> {
        val c = SocialConnection("l", SocialService.LINKDING, endpoint(), "self", "links.example.org", "api-token")
        val d = Delivery(id = "3jzfcijpj2z2a", entryId = 1, destination = "l", title = "Title", url = "https://example.org/story", createdAt = 1000)
        server.enqueue(MockResponse.Builder().body("""{"bookmark":{"id":7,"url":"https://example.org/story","title":"My own title"},"metadata":{},"auto_tags":[]}""").build())
        assertThat(api.publish(c, d)).isEqualTo("7")
        assertThat(server.takeRequest(1, TimeUnit.SECONDS)!!.url.encodedPath).isEqualTo("/api/bookmarks/check/")
        assertThat(server.takeRequest(1, TimeUnit.SECONDS)).isNull()
    }

    @Test
    fun linkdingVerifiesTheStoredBookmarkByItsId() = runBlocking<Unit> {
        val c = SocialConnection("l", SocialService.LINKDING, endpoint(), "self", "links.example.org", "api-token")
        val d = Delivery(id = "3jzfcijpj2z2a", entryId = 1, destination = "l", title = "Title", url = "https://example.org/story", createdAt = 1000, remoteId = "41")
        server.enqueue(MockResponse.Builder().body("""{"id":41,"url":"https://example.org/story","title":"Title"}""").build())
        assertThat(api.verify(c, d)).isTrue()
        assertThat(server.takeRequest(1, TimeUnit.SECONDS)!!.url.encodedPath).isEqualTo("/api/bookmarks/41/")
        server.enqueue(MockResponse.Builder().body("""{"id":42,"url":"https://example.org/other","title":"Other"}""").build())
        assertThat(api.verify(c, d)).isFalse()
    }

    @Test
    fun mastodonConnectionVerifiesTheTokenAndDoesNotEnablePublishing() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().body("""{"id":"42","acct":"alice"}""").build())
        val c = api.connect(SocialService.MASTODON, endpoint(), "", "token")
        assertThat(c.userId).isEqualTo("42")
        assertThat(c.displayName).isEqualTo("alice")
        assertThat(c.enabled).isFalse()
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertThat(request.url.encodedPath).isEqualTo("/api/v1/accounts/verify_credentials")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer token")
    }

    @Test
    fun blueskyUsesAnAppPasswordSessionAndKeepsRefreshCredentialsNotThePassword() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().body("""{"id":"old","acct":"old","did":"did:plc:alice","handle":"alice.test","accessJwt":"access","refreshJwt":"refresh"}""").build())
        val c = api.connect(SocialService.BLUESKY, endpoint(), "alice.test", "app-password")
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertThat(request.url.encodedPath).isEqualTo("/xrpc/com.atproto.server.createSession")
        assertThat(request.body!!.utf8()).contains("\"password\":\"app-password\"")
        assertThat(c.userId).isEqualTo("did:plc:alice")
        assertThat(c.accessToken).isEqualTo("access")
        assertThat(c.refreshToken).isEqualTo("refresh")
        assertThat(c.enabled).isFalse()
        assertThat(c.toString()).doesNotContain("app-password")
    }

    @Test
    fun blueskyPostsUseADurableTidAndUtf8LinkFacet() = runBlocking<Unit> {
        val c = SocialConnection("b", SocialService.BLUESKY, endpoint(), "did:plc:alice", "alice.test", "access", "refresh", true)
        val d = Delivery(entryId = 1, destination = "b", title = "Привет 🌊", url = "https://example.org/story", createdAt = 1000)
        // Valid for the old Mastodon path too: assert the actual route before payload fields.
        server.enqueue(MockResponse.Builder().body("""{"id":"77","uri":"at://did:plc:alice/app.bsky.feed.post/${d.id}"}""").build())
        server.enqueue(MockResponse.Builder().body("""{"id":"77"}""").build())
        val remote = api.publish(c, d)
        val post = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertThat(post.url.encodedPath).isEqualTo("/xrpc/com.atproto.repo.putRecord")
        val payload = Json.parseToJsonElement(post.body!!.utf8()).jsonObject
        assertThat(payload["rkey"]!!.jsonPrimitive.content).isEqualTo(d.id)
        assertThat(d.id).matches("[234567abcdefghij][234567abcdefghijklmnopqrstuvwxyz]{12}")
        assertThat(payload["record"]!!.jsonObject["text"]!!.jsonPrimitive.content).isEqualTo("Привет 🌊\nhttps://example.org/story")
        assertThat(payload.toString()).contains("\"byteStart\":18")
        assertThat(remote).isEqualTo("at://did:plc:alice/app.bsky.feed.post/${d.id}")
    }

    @Test
    fun blueskyConfirmsTheExactRecordInsteadOfTrustingTheWriteResponse() = runBlocking<Unit> {
        val c = SocialConnection("b", SocialService.BLUESKY, endpoint(), "did:plc:alice", "alice.test", "access")
        val d = Delivery(id = "3jzfcijpj2z2a", entryId = 1, destination = "b", title = "Title", url = "https://example.org/story", createdAt = 1000,
            remoteId = "at://did:plc:alice/app.bsky.feed.post/3jzfcijpj2z2a")
        val record = """{"${'$'}type":"app.bsky.feed.post","text":"Title\nhttps://example.org/story","createdAt":"1970-01-01T00:00:01Z","facets":[{"index":{"byteStart":6,"byteEnd":31},"features":[{"${'$'}type":"app.bsky.richtext.facet#link","uri":"https://example.org/story"}]}]}"""
        server.enqueue(MockResponse.Builder().body("""{"id":"old","content":"old","uri":"${d.remoteId}","value":$record}""").build())
        assertThat(api.verify(c, d)).isTrue()
        assertThat(server.takeRequest(1, TimeUnit.SECONDS)!!.url.encodedPath).isEqualTo("/xrpc/com.atproto.repo.getRecord")
    }

    @Test
    fun blueskyRefreshRotatesBothTokensAndPreservesIdentityAndSelection() = runBlocking<Unit> {
        val c = SocialConnection("b", SocialService.BLUESKY, endpoint(), "did:plc:alice", "alice.test", "old-access", "old-refresh", true)
        server.enqueue(MockResponse.Builder().body("""{"did":"did:plc:alice","handle":"alice.test","accessJwt":"new-access","refreshJwt":"new-refresh"}""").build())
        val refreshed = api.refresh(c)
        assertThat(refreshed.accessToken).isEqualTo("new-access")
        assertThat(refreshed.refreshToken).isEqualTo("new-refresh")
        assertThat(refreshed.id).isEqualTo(c.id)
        assertThat(refreshed.enabled).isTrue()
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertThat(request.url.encodedPath).isEqualTo("/xrpc/com.atproto.server.refreshSession")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer old-refresh")
    }

    @Test
    fun mastodonPostHasAnIdempotencyKeyExplicitVisibilityAndReadback() = runBlocking<Unit> {
        val c = SocialConnection("m", SocialService.MASTODON, endpoint(), "42", "alice", "token", enabled = true)
        val d = Delivery(id = "stable-key", entryId = 1, destination = "m", title = "<b>Title &amp; link</b>", url = "https://example.org/story", createdAt = 1000)
        server.enqueue(MockResponse.Builder().body("""{"configuration":{"statuses":{"max_characters":500,"characters_reserved_per_url":23}}}""").build())
        server.enqueue(MockResponse.Builder().body("""{"id":"77"}""").build())
        val remote = api.publish(c, d)
        assertThat(remote).isEqualTo("77")
        server.takeRequest(1, TimeUnit.SECONDS)!!
        val post = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertThat(post.headers["Idempotency-Key"]).isEqualTo(d.id)
        val form = URLDecoder.decode(post.body!!.utf8(), Charsets.UTF_8)
        assertThat(form).contains("status=Title & link\nhttps://example.org/story")
        assertThat(form).contains("visibility=public")
        server.enqueue(MockResponse.Builder().body("""{"id":"77","account":{"id":"42"},"content":"<p>Title &amp; link<br><a href=\"https://example.org/story\">link</a></p>"}""").build())
        assertThat(api.verify(c, d.copy(remoteId = remote))).isTrue()
        assertThat(server.takeRequest(1, TimeUnit.SECONDS)!!.url.encodedPath).isEqualTo("/api/v1/statuses/77")
    }
}