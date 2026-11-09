package org.evsyukov.brook.data.social

import okhttp3.OkHttpClient
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.icu.text.BreakIterator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonArray
import java.time.Instant
import org.evsyukov.brook.data.remote.await
import org.jsoup.Jsoup
import java.io.IOException

/** Only fixed local error codes reach the UI: server bodies can contain credentials. */
class SocialFailure(val reason: String) : IOException(reason)

interface SocialPublisher {
    suspend fun connect(service: SocialService, server: String, user: String, secret: String): SocialConnection
    suspend fun refresh(connection: SocialConnection): SocialConnection
    suspend fun publish(connection: SocialConnection, delivery: Delivery): String
    suspend fun verify(connection: SocialConnection, delivery: Delivery): Boolean
}

class SocialApi(http: OkHttpClient) : SocialPublisher {
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    override suspend fun connect(service: SocialService, server: String, user: String, secret: String): SocialConnection {
        val base = serverUrl(server)
        if (secret.isBlank()) throw SocialFailure(Delivery.AUTH)
        return when (service) {
            SocialService.BLUESKY -> {
                val session = request(Request.Builder().url(base.resolve("xrpc/com.atproto.server.createSession")!!)
                    .post(jsonBody(buildJsonObject { put("identifier", user.trim()); put("password", secret) })).build())
                val did = session.text("did")
                SocialConnection("${service.name}|$base|$did", service, base.toString(), did, session.text("handle"), session.text("accessJwt"), session.text("refreshJwt"))
            }
            // linkding has one account per instance and no separate handle; the token alone identifies it.
            SocialService.LINKDING -> {
                request(Request.Builder().url(base.resolve("api/bookmarks/")!!.newBuilder().addQueryParameter("limit", "1").build())
                    .header("Authorization", "Token $secret").build())
                SocialConnection("${service.name}|$base|self", service, base.toString(), "self", base.host, secret)
            }
            SocialService.MASTODON -> {
                val account = request(Request.Builder().url(base.resolve("api/v1/accounts/verify_credentials")!!).header("Authorization", "Bearer $secret").build())
                val id = account.text("id")
                SocialConnection("${service.name}|$base|$id", service, base.toString(), id, account.text("acct"), secret)
            }
        }
    }
    override suspend fun refresh(connection: SocialConnection): SocialConnection {
        if (connection.service != SocialService.BLUESKY) return connection
        val session = request(builder(connection, "xrpc/com.atproto.server.refreshSession")
            .header("Authorization", "Bearer ${connection.refreshToken}").post(jsonBody(buildJsonObject {})).build())
        if (session.text("did") != connection.userId) throw SocialFailure(Delivery.AUTH)
        return SocialConnection(connection.id, connection.service, connection.server, connection.userId, session.text("handle"),
            session.text("accessJwt"), session.text("refreshJwt"), connection.enabled)
    }
    override suspend fun publish(connection: SocialConnection, delivery: Delivery): String {
        articleUrl(delivery.url)
        return when (connection.service) {
            SocialService.BLUESKY -> {
                val payload = buildJsonObject {
                    put("repo", connection.userId); put("collection", "app.bsky.feed.post"); put("rkey", delivery.id)
                    put("record", blueRecord(delivery)); put("validate", true)
                }
                request(builder(connection, "xrpc/com.atproto.repo.putRecord").post(jsonBody(payload)).build()).text("uri")
            }
            SocialService.LINKDING -> bookmark(connection, delivery)
            SocialService.MASTODON -> {
                val config = request(builder(connection, "api/v2/instance").build())["configuration"]?.jsonObject?.get("statuses")?.jsonObject
                val max = config?.get("max_characters")?.jsonPrimitive?.intOrNull ?: 500
                val reserved = config?.get("characters_reserved_per_url")?.jsonPrimitive?.intOrNull ?: 23
                val title = shorten(Jsoup.parse(delivery.title).text(), max - reserved - 1)
                val body = FormBody.Builder().add("status", "$title\n${delivery.url}").add("visibility", "public").build()
                request(builder(connection, "api/v1/statuses").header("Idempotency-Key", delivery.id).post(body).build()).text("id")
            }
        }
    }
    override suspend fun verify(connection: SocialConnection, delivery: Delivery): Boolean {
        return when (connection.service) {
            SocialService.BLUESKY -> {
                val url = serverUrl(connection.server).resolve("xrpc/com.atproto.repo.getRecord")!!.newBuilder()
                    .addQueryParameter("repo", connection.userId).addQueryParameter("collection", "app.bsky.feed.post")
                    .addQueryParameter("rkey", delivery.id).build()
                val response = request(Request.Builder().url(url).header("Authorization", authHeader(connection)).build())
                response.text("uri") == "at://${connection.userId}/app.bsky.feed.post/${delivery.id}" && response["value"] == blueRecord(delivery)
            }
            SocialService.LINKDING -> {
                val response = request(builder(connection, "api/bookmarks/${delivery.remoteId}/").build())
                response.text("id") == delivery.remoteId && response.text("url") == delivery.url
            }
            SocialService.MASTODON -> {
                val response = request(builder(connection, "api/v1/statuses/${delivery.remoteId}").build())
                val document = Jsoup.parse(response.text("content"))
                response.text("id") == delivery.remoteId && response["account"]?.jsonObject?.text("id") == connection.userId &&
                    document.select("a[href]").any { it.attr("href") == delivery.url }
            }
        }
    }

    /**
     * A bookmark is saved once per URL and stays private: linkding updates an existing bookmark on
     * POST, so an existing one is reported as delivered instead of overwriting its title or tags.
     */
    private suspend fun bookmark(connection: SocialConnection, delivery: Delivery): String {
        val check = Request.Builder()
            .url(serverUrl(connection.server).resolve("api/bookmarks/check/")!!.newBuilder().addQueryParameter("url", delivery.url).build())
            .header("Authorization", authHeader(connection)).build()
        val existing = request(check)["bookmark"]
        if (existing != null && existing !is JsonNull) return existing.jsonObject.text("id")
        val payload = buildJsonObject {
            put("url", delivery.url); put("title", Jsoup.parse(delivery.title).text()); put("description", "")
            put("is_archived", false); put("unread", false); put("shared", false)
        }
        return request(builder(connection, "api/bookmarks/").post(jsonBody(payload)).build()).text("id")
    }

    private fun authHeader(c: SocialConnection) = if (c.service == SocialService.LINKDING) "Token ${c.accessToken}" else "Bearer ${c.accessToken}"

    private fun builder(c: SocialConnection, path: String) = Request.Builder().url(serverUrl(c.server).resolve(path)!!).header("Authorization", authHeader(c))
    private fun jsonBody(value: JsonObject) = value.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

    private fun blueRecord(d: Delivery): JsonObject {
        val title = shorten(Jsoup.parse(d.title).text(), 299 - graphemes(d.url))
        val text = "$title\n${d.url}"
        if (text.toByteArray().size > 3000) throw SocialFailure(Delivery.INVALID)
        val start = "$title\n".toByteArray().size
        return buildJsonObject {
            put("\$type", "app.bsky.feed.post"); put("text", text); put("createdAt", Instant.ofEpochMilli(d.createdAt).toString())
            put("facets", buildJsonArray {
                add(buildJsonObject {
                    put("index", buildJsonObject { put("byteStart", start); put("byteEnd", text.toByteArray().size) })
                    put("features", buildJsonArray { add(buildJsonObject { put("\$type", "app.bsky.richtext.facet#link"); put("uri", d.url) }) })
                })
            })
        }
    }

    private fun graphemes(text: String): Int {
        val iterator = BreakIterator.getCharacterInstance().apply { setText(text) }
        var count = 0
        while (iterator.next() != BreakIterator.DONE) count++
        return count
    }

    private fun articleUrl(value: String) {
        val url = value.toHttpUrlOrNull() ?: throw SocialFailure(Delivery.INVALID)
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) throw SocialFailure(Delivery.INVALID)
    }

    private fun shorten(text: String, limit: Int): String {
        if (limit < 1) throw SocialFailure(Delivery.INVALID)
        val iterator = BreakIterator.getCharacterInstance().apply { setText(text) }
        var end = iterator.first()
        repeat(limit) {
            val next = iterator.next()
            if (next == BreakIterator.DONE) return text
            end = next
        }
        return text.substring(0, end)
    }

    private fun serverUrl(server: String): HttpUrl {
        val url = server.trim().toHttpUrlOrNull() ?: throw SocialFailure(Delivery.INVALID)
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null || url.encodedPath != "/") {
            throw SocialFailure(Delivery.INVALID)
        }
        return url
    }

    private suspend fun request(request: Request): JsonObject = client.newCall(request).await().use { response ->
        if (!response.isSuccessful) throw SocialFailure(when (response.code) {
            401, 403 -> Delivery.AUTH
            429 -> "retry"
            in 500..599 -> "retry"
            else -> Delivery.INVALID
        })
        try {
            Json.parseToJsonElement(response.body.string()).jsonObject
        } catch (_: IllegalArgumentException) {
            throw SocialFailure(Delivery.INVALID)
        }
    }

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: throw SocialFailure(Delivery.INVALID)
}