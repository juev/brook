package org.evsyukov.brook.data.translation

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import org.evsyukov.brook.data.db.EntryEntity
import org.evsyukov.brook.data.remote.TranslationApi
import org.evsyukov.brook.data.remote.TranslationReply
import java.io.IOException
import java.security.MessageDigest
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import org.jsoup.nodes.Document

class TranslationUnavailable : IOException()
class TranslationSourceMismatch : IOException()

class TranslationReader(private val api: TranslationApi, private val cache: TranslationCache) {
    suspend fun translate(entry: EntryEntity, fullText: Boolean, language: String, progress: (TranslationReply) -> Unit = {}): TranslationReply = withTimeout(10 * 60_000L) {
        val hash = fingerprint(entry.title, if (fullText) entry.fullContent.orEmpty() else entry.content)
        cache.read(entry.id,fullText,hash,language)?.takeIf { it.complete }?.let { return@withTimeout it }
        if (!cache.preferences().consent) throw IOException("Translation consent is required")
        if (!api.available()) throw TranslationUnavailable()
        var reply = api.entry(entry.id,language)
        if (reply.sourceHash != hash) throw TranslationSourceMismatch()
        val revision = reply.revision
        var steps = 0
        while (!reply.complete && steps++ < 128) {
            progress(reply)
            val next = api.step(reply)
            if (next.sourceHash != hash || next.revision != revision || (!next.complete && next.done <= reply.done)) throw IOException("Translation source or progress changed")
            reply = next
        }
        if (!reply.complete) throw IOException("Translation step limit reached; retry to resume")
        reply = reply.copy(content = Jsoup.clean(reply.content, entry.url, Safelist.relaxed().addTags("figure", "figcaption", "picture", "source"), Document.OutputSettings().prettyPrint(false)))
        currentCoroutineContext().ensureActive()
        cache.save(entry.id,fullText,reply)
        reply
    }

    companion object {
        fun fingerprint(title: String, html: String): String = MessageDigest.getInstance("SHA-256")
            .digest((title+"\u0000"+html).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
