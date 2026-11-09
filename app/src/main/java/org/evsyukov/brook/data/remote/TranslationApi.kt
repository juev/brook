package org.evsyukov.brook.data.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.FormBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Optional freshgo extension; no redirects, cookies, paid retries or downstream credentials. */
class TranslationApi(http: OkHttpClient, private val reader: GReaderClient) {
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .callTimeout(4, TimeUnit.MINUTES).readTimeout(4, TimeUnit.MINUTES).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val capabilityLock = Mutex()
    private var capability: Boolean? = null
    private var checkedAt = 0L

    @Serializable private data class Capability(val version: Int, val enabled: Boolean)

    suspend fun available(): Boolean = capabilityLock.withLock {
        capability?.takeIf { System.nanoTime() - checkedAt < TimeUnit.MINUTES.toNanos(5) }?.let { return@withLock it }
        val found = try {
            val result = json.decodeFromString<Capability>(request(""))
            result.version == 1 && result.enabled
        } catch (_: GReaderException.NotFound) {
            false
        } catch (_: GReaderException.BadRequest) {
            // Older Google Reader servers reject unknown extension paths with 400.
            false
        }
        capability = found
        checkedAt = System.nanoTime()
        found
    }

    suspend fun entry(id: Long, language: String): TranslationReply = decode(request("/entry", query = listOf("id" to id.toString(), "language" to language)), id.toString(), language)
    suspend fun step(reply: TranslationReply): TranslationReply = decode(request("/entry", form = listOf("id" to reply.id, "language" to reply.language, "revision" to reply.revision, "done" to reply.done.toString())), reply.id, reply.language)

    private fun decode(body: String, id: String, language: String): TranslationReply {
        val reply = try { json.decodeFromString<TranslationReply>(body) } catch (_: IllegalArgumentException) { throw IOException("Invalid translation response") }
        val hash = Regex("[0-9a-f]{64}")
        if (reply.id != id || reply.language != language || !hash.matches(reply.sourceHash) || !hash.matches(reply.revision) ||
            reply.done < 0 || reply.total < reply.done || reply.failed < 0 || reply.failed > reply.done ||
            reply.complete != (reply.exists && reply.done == reply.total) || (reply.sameLanguage && !reply.complete)) {
            throw IOException("Invalid translation identity or progress")
        }
        return reply
    }

    private suspend fun request(path: String, query: List<Pair<String,String>> = emptyList(), form: List<Pair<String,String>>? = null): String = suspendCancellableCoroutine { continuation ->
        val url = reader.apiBase.newBuilder().addPathSegments("api/freshgo/translation$path").apply { query.forEach { (k,v) -> addQueryParameter(k,v) } }.build()
        val request = Request.Builder().url(url).header("Authorization", "GoogleLogin auth=${reader.auth}").apply {
            if (form != null) post(FormBody.Builder().apply { form.forEach { (k,v) -> add(k,v) } }.build())
        }.build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.use { when (response.code) {
                200 -> {
                    val source = response.body.source()
                    if (source.request(8_388_609)) throw IOException("Translation response is too large")
                    source.readUtf8()
                }
                400 -> throw GReaderException.BadRequest("Unsupported translation request")
                401 -> throw GReaderException.Unauthorized()
                404 -> throw GReaderException.NotFound()
                409 -> throw GReaderException.Conflict()
                429 -> throw GReaderException.RateLimited(response.header("Retry-After")?.toLongOrNull())
                else -> throw IOException("Translation HTTP ${response.code}")
                    } }
                    continuation.resume(body)
                } catch (e: Exception) { continuation.resumeWithException(e) }
            }
        })
    }
}
