package org.evsyukov.brook

import android.content.Intent
import android.os.BadParcelableException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Browsers share either a URL or a title followed by a URL. Do not rewrite the address. */
internal fun Intent.sharedFeedUrl(): String? {
    if (action != Intent.ACTION_SEND || type != "text/plain") return null
    val text = try {
        getCharSequenceExtra(Intent.EXTRA_TEXT)
    } catch (_: BadParcelableException) {
        return null
    } ?: return null
    if (text.length > MAX_SHARED_TEXT_LENGTH) return null
    return text.splitToSequence(whitespace).lastOrNull(String::isSharedFeedAddress)
}

internal fun String.isSharedFeedAddress(): Boolean {
    if (length > MAX_SHARED_TEXT_LENGTH || !httpAddress.matches(this) || any { it.isISOControl() || it == '\\' }) return false
    val url = toHttpUrlOrNull() ?: return false
    return url.encodedUsername.isEmpty() && url.encodedPassword.isEmpty()
}

private const val MAX_SHARED_TEXT_LENGTH = 16_384
private val whitespace = Regex("\\s+")
private val httpAddress = Regex("https?://[^/\\s]\\S*", RegexOption.IGNORE_CASE)
