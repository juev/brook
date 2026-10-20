package org.evsyukov.brook.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class OutboxSenderTest {
    @Test
    fun requestsCloseTogetherGoAsOneSendPerAccount() = runTest {
        val sent = mutableListOf<String>()
        val sender = OutboxSender(backgroundScope, send = { sent += it }, fallback = {}, delayMillis = 1_000)

        sender.request("a1")
        advanceTimeBy(500)
        sender.request("a1")
        sender.request("a2")
        runCurrent()
        assertThat(sent).isEmpty()

        advanceTimeBy(501)
        runCurrent()
        assertThat(sent).containsExactly("a1")

        advanceTimeBy(1_000)
        runCurrent()
        // The request after the first started waiting is sent too, after its own delay.
        assertThat(sent).containsExactly("a1", "a2", "a1")
    }

    @Test
    fun aFailedSendGoesToTheFallbackAndLaterRequestsStillSend() = runTest {
        var offline = true
        val sent = mutableListOf<String>()
        val fallback = mutableListOf<String>()
        val sender = OutboxSender(
            backgroundScope,
            send = { if (offline) throw IOException("offline") else sent += it },
            fallback = { fallback += it },
            delayMillis = 1_000,
        )

        sender.request("a1")
        advanceTimeBy(1_001)
        runCurrent()
        assertThat(fallback).containsExactly("a1")

        offline = false
        sender.request("a1")
        advanceTimeBy(1_001)
        runCurrent()
        assertThat(sent).containsExactly("a1")
        assertThat(fallback).containsExactly("a1")
    }
}
