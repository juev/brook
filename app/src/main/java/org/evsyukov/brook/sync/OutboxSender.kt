package org.evsyukov.brook.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends an account's queued changes soon after they are made, not with the next sync. Requests that
 * come within [delayMillis] of each other go as one send: scrolling through a list marks many entries.
 * A send that fails leaves the queue as it is and hands the account to [fallback], which sends it
 * once the network is back; the next sync sends it too.
 */
class OutboxSender(
    private val scope: CoroutineScope,
    private val send: suspend (accountId: String) -> Unit,
    private val fallback: (accountId: String) -> Unit,
    private val delayMillis: Long = 1_000,
) {
    private val requests = ConcurrentHashMap<String, Channel<Unit>>()

    fun request(accountId: String) {
        requests.computeIfAbsent(accountId) { id ->
            Channel<Unit>(Channel.CONFLATED).also { channel ->
                scope.launch {
                    for (request in channel) {
                        delay(delayMillis)
                        trySend(id)
                    }
                }
            }
        }.trySend(Unit)
    }

    private suspend fun trySend(accountId: String) {
        try {
            send(accountId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            fallback(accountId)
        }
    }
}
