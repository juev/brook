package org.evsyukov.brook.data.remote

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs the client against a live server started by `scripts/dev-freshgo.sh`
 * (user alice, password brook-dev-pw). Skipped unless `-Pbrook.live=<server>` is given.
 */
class LiveGReaderTest {
    private val server = System.getProperty("brook.live").orEmpty()
    private lateinit var client: GReaderClient

    @Before
    fun setUp() {
        assumeTrue("set -Pbrook.live to run", server.isNotEmpty())
        client = runBlocking { GReaderClient.login(OkHttpClient(), GReaderClient.parseServer(server)!!, "alice", "brook-dev-pw") }
    }

    @Test
    fun readsTheTreeAndTheStream() = runBlocking {
        assertThat(client.apiBase.encodedPath).isEqualTo("/api/greader.php")
        assertThat(client.tagList().map { it.id }).contains(Streams.label("Dev"))
        val feed = client.subscriptions().first { it.title == "Brook dev feed" }

        val page = client.streamContents(feed.id, count = 10)

        assertThat(page.items).hasSize(10)
        assertThat(page.continuation).isNotNull()
        val next = client.streamContents(feed.id, count = 10, continuation = page.continuation)
        assertThat(next.items.map { it.id }).containsNoneIn(page.items.map { it.id })
    }

    @Test
    fun readAndStarRoundTrip() = runBlocking {
        val id = ItemIds.parse(client.streamContents(Streams.READING_LIST, count = 1).items.single().id)!!

        client.editTag(listOf(id), add = listOf(Streams.READ, Streams.STARRED, Streams.label("brook test")))
        val after = client.itemContents(listOf(id)).single()
        assertThat(after.categories).containsAtLeast(Streams.READ, Streams.STARRED, Streams.label("brook test"))
        assertThat(client.allItemIds(Streams.STARRED)).contains(id)
        assertThat(client.allItemIds(Streams.READING_LIST, exclude = Streams.READ)).doesNotContain(id)

        client.editTag(listOf(id), remove = listOf(Streams.READ, Streams.STARRED, Streams.label("brook test")))
        val back = client.itemContents(listOf(id)).single()
        assertThat(back.categories).containsNoneOf(Streams.READ, Streams.STARRED, Streams.label("brook test"))
        assertThat(client.allItemIds(Streams.READING_LIST, exclude = Streams.READ)).contains(id)
    }

    @Test
    fun markAllReadUpToAnEntry() = runBlocking {
        val ids = client.allItemIds(Streams.READING_LIST)
        val boundary = ids[ids.size / 2]

        client.markAllAsRead(Streams.READING_LIST, upToId = boundary)

        val unread = client.allItemIds(Streams.READING_LIST, exclude = Streams.READ)
        assertThat(unread).isNotEmpty()
        assertThat(unread.all { it > boundary }).isTrue()
        client.editTag(ids, remove = listOf(Streams.READ))
        assertThat(client.allItemIds(Streams.READING_LIST, exclude = Streams.READ)).hasSize(ids.size)
    }

    @Test
    fun unreadCountsCarryTheNewestEntry() = runBlocking {
        val counts = client.unreadCounts()
        val newest = client.allItemIds(Streams.READING_LIST).max()

        assertThat(counts.counts.first { it.id == Streams.READING_LIST }.newestItemTimestampUsec).isEqualTo(newest.toString())
    }
}
