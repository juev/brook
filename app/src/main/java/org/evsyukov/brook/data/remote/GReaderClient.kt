package org.evsyukov.brook.data.remote

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * A client of the Google Reader API as freshgo and FreshRSS serve it (freshgo `docs/specs/greader-api.md`).
 *
 * [apiBase] is the address the API answers at: the server root or `<server>/api/greader.php`.
 * [auth] is the `Auth=` value of `ClientLogin`; it does not expire until the API password changes.
 */
class GReaderClient(
    private val http: OkHttpClient,
    val apiBase: HttpUrl,
    private val auth: String,
) : ReaderApi {
    private val writeTokenLock = Mutex()
    private var writeToken: String? = null

    suspend fun userInfo(): UserInfoDto = getJson(url("reader/api/0/user-info"))

    override suspend fun tagList(): List<TagDto> =
        getJson<TagListDto>(url("reader/api/0/tag/list") { addQueryParameter("output", "json") }).tags

    override suspend fun subscriptions(): List<SubscriptionDto> =
        getJson<SubscriptionListDto>(url("reader/api/0/subscription/list") { addQueryParameter("output", "json") }).subscriptions

    override suspend fun unreadCounts(): UnreadCountsDto =
        getJson(url("reader/api/0/unread-count") { addQueryParameter("output", "json") })

    /**
     * Items of [streamId], newest first unless [oldestFirst]. [count] ≤ 0 means no limit.
     * [newerThanSeconds] is `ot`: entries added, or changed by their feed, at that second or later.
     */
    override suspend fun streamContents(
        streamId: String,
        count: Int,
        continuation: String?,
        oldestFirst: Boolean,
        exclude: String?,
        include: String?,
        newerThanSeconds: Long?,
    ): StreamContentsDto = getJson(
        url("reader/api/0/stream/contents") {
            addStreamPath(streamId)
            addQueryParameter("n", count.toString())
            continuation?.let { addQueryParameter("c", it) }
            if (oldestFirst) addQueryParameter("r", "o")
            exclude?.let { addQueryParameter("xt", it) }
            include?.let { addQueryParameter("it", it) }
            newerThanSeconds?.let { addQueryParameter("ot", it.toString()) }
        },
    )

    /** Identifiers of [streamId]; `xt=read` with the reading list gives the unread ones. */
    suspend fun itemIds(
        streamId: String,
        count: Int = 10_000,
        continuation: String? = null,
        exclude: String? = null,
        include: String? = null,
    ): ItemIdsDto = getJson(
        url("reader/api/0/stream/items/ids") {
            addQueryParameter("s", streamId)
            addQueryParameter("n", count.toString())
            continuation?.let { addQueryParameter("c", it) }
            exclude?.let { addQueryParameter("xt", it) }
            include?.let { addQueryParameter("it", it) }
        },
    )

    /** Every identifier of [streamId], following continuations. */
    override suspend fun allItemIds(streamId: String, exclude: String?, pageSize: Int): List<Long> {
        val ids = ArrayList<Long>()
        var continuation: String? = null
        do {
            val page = itemIds(streamId, pageSize, continuation, exclude)
            page.itemRefs.mapNotNullTo(ids) { ItemIds.parse(it.id) }
            continuation = page.continuation?.takeIf { it.isNotEmpty() && page.itemRefs.isNotEmpty() }
        } while (continuation != null)
        return ids
    }

    override suspend fun itemContents(ids: Collection<Long>): List<ItemDto> {
        if (ids.isEmpty()) return emptyList()
        val body = ids.map { "i" to it.toString() }
        return json.decodeFromString<StreamContentsDto>(post(url("reader/api/0/stream/items/contents"), body)).items
    }

    /** Adds and removes `…/read`, `…/starred` or `user/-/label/<name>` on [ids]. */
    override suspend fun editTag(ids: Collection<Long>, add: List<String>, remove: List<String>) {
        if (ids.isEmpty() || (add.isEmpty() && remove.isEmpty())) return
        val form = ids.map { "i" to it.toString() } + add.map { "a" to it } + remove.map { "r" to it }
        postWithToken(url("reader/api/0/edit-tag"), form)
    }

    /** Marks [streamId] read up to the entry [upToId] (an entry identifier), or everything when null. */
    suspend fun markAllAsRead(streamId: String, upToId: Long?) {
        val form = buildList {
            add("s" to streamId)
            upToId?.let { add("ts" to it.toString()) }
        }
        postWithToken(url("reader/api/0/mark-all-as-read"), form)
    }

    /** Renames a category or label; both names are plain, without `user/-/label/`. */
    suspend fun renameTag(name: String, newName: String) {
        postWithToken(url("reader/api/0/rename-tag"), listOf("s" to Streams.label(name), "dest" to Streams.label(newName)))
    }

    /** Deletes a category (its feeds go to the default one) or a label. */
    suspend fun disableTag(name: String) {
        postWithToken(url("reader/api/0/disable-tag"), listOf("s" to Streams.label(name)))
    }

    suspend fun quickAdd(feedUrl: String): QuickAddDto =
        json.decodeFromString(post(url("reader/api/0/subscription/quickadd"), listOf("quickadd" to feedUrl)))

    suspend fun unsubscribe(feedStreamId: String) =
        subscriptionEdit(listOf("ac" to "unsubscribe", "s" to feedStreamId))

    suspend fun renameSubscription(feedStreamId: String, title: String) =
        subscriptionEdit(listOf("ac" to "edit", "s" to feedStreamId, "t" to title))

    /** Moves the feed into [category], created when missing; null moves it to the default one. */
    suspend fun moveSubscription(feedStreamId: String, fromCategory: String?, category: String?) {
        val form = buildList {
            add("ac" to "edit")
            add("s" to feedStreamId)
            if (category != null) add("a" to Streams.label(category))
            if (fromCategory != null) add("r" to Streams.label(fromCategory))
        }
        subscriptionEdit(form)
    }

    suspend fun exportOpml(): String = get(url("reader/api/0/subscription/export"))

    suspend fun importOpml(opml: String) {
        val request = Request.Builder()
            .url(url("reader/api/0/subscription/import"))
            .header(AUTHORIZATION, authHeader())
            .post(opml.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()
        execute(request)
    }

    private suspend fun subscriptionEdit(form: List<Pair<String, String>>) {
        post(url("reader/api/0/subscription/edit"), form)
    }

    private suspend fun writeToken(): String = writeTokenLock.withLock {
        writeToken ?: get(url("reader/api/0/token")).trim().also { writeToken = it }
    }

    /** `T` is checked against the token of the moment; a stale one is fetched again once. */
    private suspend fun postWithToken(url: HttpUrl, form: List<Pair<String, String>>): String {
        val token = writeToken()
        return try {
            post(url, form + ("T" to token))
        } catch (e: GReaderException.Unauthorized) {
            writeTokenLock.withLock { if (writeToken == token) writeToken = null }
            post(url, form + ("T" to writeToken()))
        }
    }

    private suspend inline fun <reified T> getJson(url: HttpUrl): T {
        val body = get(url)
        return try {
            json.decodeFromString<T>(body)
        } catch (e: IllegalArgumentException) {
            throw GReaderException.Malformed("Unexpected answer from ${url.encodedPath}: ${e.message}")
        }
    }

    private suspend fun get(url: HttpUrl): String =
        execute(Request.Builder().url(url).header(AUTHORIZATION, authHeader()).get().build())

    private suspend fun post(url: HttpUrl, form: List<Pair<String, String>>): String {
        val request = Request.Builder()
            .url(url)
            .header(AUTHORIZATION, authHeader())
            .post(encodeForm(form).toRequestBody(FORM))
            .build()
        return execute(request)
    }

    private suspend fun execute(request: Request): String = executeRequest(http, request)

    private fun authHeader() = "GoogleLogin auth=$auth"

    private fun url(path: String, block: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        apiBase.newBuilder().addPathSegments(path).apply(block).build()

    companion object {
        private const val AUTHORIZATION = "Authorization"
        private val FORM = "application/x-www-form-urlencoded".toMediaType()

        internal val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }

        /**
         * Signs in and finds where the API answers: `<server>/api/greader.php` (FreshRSS, freshgo) is tried
         * first, then the address as given (Miniflux and others serve the API at their root).
         */
        suspend fun login(http: OkHttpClient, server: HttpUrl, user: String, password: String): GReaderClient {
            var lastNotFound: GReaderException? = null
            for (base in candidateBases(server)) {
                val request = Request.Builder()
                    .url(base.newBuilder().addPathSegments("accounts/ClientLogin").build())
                    .post(encodeForm(listOf("Email" to user, "Passwd" to password)).toRequestBody(FORM))
                    .build()
                val body = try {
                    executeRequest(http, request)
                } catch (e: GReaderException.NotFound) {
                    lastNotFound = e
                    continue
                }
                val auth = body.lineSequence().firstOrNull { it.startsWith("Auth=") }?.removePrefix("Auth=")?.trim()
                    ?: throw GReaderException.Malformed("ClientLogin answered without Auth")
                return GReaderClient(http, base, auth)
            }
            throw lastNotFound ?: GReaderException.NotFound()
        }

        internal fun candidateBases(server: HttpUrl): List<HttpUrl> {
            val root = server.newBuilder().apply {
                // Drop an empty last segment so that "https://h/" and "https://h" are the same.
                if (server.pathSegments.lastOrNull() == "") removePathSegment(server.pathSegments.size - 1)
            }.build()
            return if (root.pathSegments.lastOrNull() == "greader.php") {
                listOf(root)
            } else {
                listOf(root.newBuilder().addPathSegments("api/greader.php").build(), root)
            }
        }

        /**
         * Parses what a person types as a server address: the scheme defaults to https, and
         * a trailing slash or `/api/greader.php` may be there or not.
         */
        fun parseServer(input: String): HttpUrl? {
            val trimmed = input.trim()
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
            val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            val url = withScheme.toHttpUrlOrNull() ?: return null
            return url.newBuilder().query(null).fragment(null).build()
        }

        /** Names unencoded, values percent-encoded: freshgo reads repeated fields from the raw body. */
        internal fun encodeForm(form: List<Pair<String, String>>): String =
            form.joinToString("&") { (name, value) -> name + "=" + URLEncoder.encode(value, Charsets.UTF_8) }

        private suspend fun executeRequest(http: OkHttpClient, request: Request): String {
            http.newCall(request).await().use { response ->
                val body = response.body.string()
                when (response.code) {
                    in 200..299 -> return body
                    400 -> throw GReaderException.BadRequest(body.trim())
                    401 -> throw GReaderException.Unauthorized()
                    404 -> throw GReaderException.NotFound()
                    429 -> throw GReaderException.RateLimited(response.header("Retry-After")?.trim()?.toLongOrNull())
                    else -> throw GReaderException.Http(response.code, body.take(500))
                }
            }
        }
    }
}

/**
 * Puts a stream identifier into the path. Fixed parts stay segments; the name of a feed address,
 * category or label is one segment, so a slash in it is encoded (freshgo A14).
 */
internal fun HttpUrl.Builder.addStreamPath(streamId: String) {
    when {
        streamId.startsWith(Streams.LABEL_PREFIX) -> {
            addPathSegments("user/-/label")
            addPathSegment(streamId.removePrefix(Streams.LABEL_PREFIX))
        }
        streamId.startsWith(Streams.FEED_PREFIX) -> {
            addPathSegment("feed")
            addPathSegment(streamId.removePrefix(Streams.FEED_PREFIX))
        }
        else -> addPathSegments(streamId)
    }
}
