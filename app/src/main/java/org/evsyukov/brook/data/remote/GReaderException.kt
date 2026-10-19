package org.evsyukov.brook.data.remote

import java.io.IOException

/** A server answer the client cannot use. Network failures stay plain [IOException]s. */
sealed class GReaderException(message: String) : IOException(message) {
    /** 401: wrong user name or API password, or a token the server no longer accepts. */
    class Unauthorized : GReaderException("Unauthorized")

    /** 429: too many failed logins; [retryAfterSeconds] comes from `Retry-After`. */
    class RateLimited(val retryAfterSeconds: Long?) : GReaderException("Too many attempts")

    /** 400: the server refused the request, e.g. an existing subscription. */
    class BadRequest(val body: String) : GReaderException("Bad request: $body")

    /** No Google Reader API at the address. */
    class NotFound : GReaderException("No Google Reader API at this address")

    class Http(val code: Int, val body: String) : GReaderException("HTTP $code: $body")

    /** The answer was not what the API promises. */
    class Malformed(message: String) : GReaderException(message)
}
