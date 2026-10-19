package org.evsyukov.brook.data.images

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A picture as the reader screen hands it to the WebView. */
class CachedImage(val file: File, val mimeType: String)

/**
 * Pictures of entries on disk, one file per address under [dir]. The reader screen serves them to
 * the WebView from here; the download for offline reading (step 6) fills it ahead of time.
 */
class ImageCache(private val dir: File, private val http: OkHttpClient) {
    /** The picture from disk, or null when it was never downloaded. */
    fun cached(url: String): CachedImage? {
        val file = file(url)
        if (!file.isFile) return null
        val type = typeFile(file).takeIf { it.isFile }?.readText()?.trim().orEmpty()
        return CachedImage(file, type.ifEmpty { DEFAULT_TYPE })
    }

    /**
     * The picture from disk, downloaded first when missing. Blocks: WebView asks from a thread of
     * its own. Null when it cannot be had, offline or not a picture.
     */
    fun get(url: String): CachedImage? = cached(url) ?: download(url)

    /** Downloads [url] unless it is here already; false when that failed. */
    fun download(url: String): CachedImage? {
        cached(url)?.let { return it }
        val request = try {
            Request.Builder().url(url).get().build()
        } catch (e: IllegalArgumentException) {
            return null
        }
        return try {
            http.newCall(request).execute().use { response ->
                val type = response.header("Content-Type")?.substringBefore(';')?.trim().orEmpty()
                if (!response.isSuccessful || !type.startsWith("image/")) return null
                dir.mkdirs()
                val file = file(url)
                val partial = File(dir, file.name + ".part")
                partial.outputStream().use { response.body.byteStream().copyTo(it) }
                typeFile(file).writeText(type)
                if (!partial.renameTo(file)) return null
                CachedImage(file, type)
            }
        } catch (e: IOException) {
            null
        }
    }

    /** Deletes the pictures of addresses no longer needed. */
    fun remove(urls: Collection<String>) {
        for (url in urls) {
            val file = file(url)
            file.delete()
            typeFile(file).delete()
        }
    }

    /** Deletes every picture but those of [urls], and downloads left half done. */
    fun retainOnly(urls: Set<String>) {
        val keep = urls.mapTo(HashSet()) { sha256(it) }
        dir.listFiles()?.forEach { file ->
            val key = file.name.removeSuffix(".type")
            if (key !in keep || file.name.endsWith(".part")) file.delete()
        }
    }

    private fun file(url: String) = File(dir, sha256(url))

    private fun typeFile(file: File) = File(file.path + ".type")

    private companion object {
        const val DEFAULT_TYPE = "image/*"

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
