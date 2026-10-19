package org.evsyukov.brook.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.evsyukov.brook.data.db.BrookDatabase
import org.evsyukov.brook.data.db.EntryEntity
import org.evsyukov.brook.data.db.FeedEntity
import org.evsyukov.brook.data.db.FeedSettingsEntity
import org.evsyukov.brook.data.fulltext.FullTextExtractor
import org.evsyukov.brook.data.images.ImageCache
import org.evsyukov.brook.data.remote.EnclosureDto
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class PrefetcherTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val requested = Collections.synchronizedList(mutableListOf<String>())
    private lateinit var db: BrookDatabase
    private lateinit var images: ImageCache
    private lateinit var prefetcher: Prefetcher

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                requested += path
                return when {
                    path.endsWith(".png") -> MockResponse.Builder()
                        .addHeader("Content-Type", "image/png")
                        .body(Buffer().write(byteArrayOf(1, 2, 3)))
                        .build()
                    path.startsWith("/page/") -> MockResponse.Builder()
                        .body(javaClass.getResource("/fulltext/article.html")!!.readText().replace("https://example.org/images/river.jpg", "/img/river.png"))
                        .build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BrookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val http = OkHttpClient()
        images = ImageCache(folder.newFolder("images"), http)
        prefetcher = Prefetcher(db, images, FullTextExtractor(http))
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private fun url(path: String) = server.url(path).toString()

    private fun entry(id: Long, feed: String = "feed/1", read: Boolean = false, starred: Boolean = false, picture: String? = null, page: Boolean = false) =
        EntryEntity(
            id = id, feedId = feed, title = "Entry $id", author = "",
            url = if (page) url("/page/$id") else url("/e/$id"),
            sortTime = id,
            content = "<p>Teaser $id</p>" + (picture?.let { "<img src=\"$it\">" } ?: ""),
            enclosuresJson = "", isRead = read, isStarred = starred, readAt = if (read) 1 else null,
        )

    @Test
    fun picturesOfUnreadAndStarredEntriesAreDownloadedButNotOfReadOnes() = runBlocking<Unit> {
        db.entryDao().insertIgnore(
            listOf(
                entry(1, picture = url("/img/unread.png")),
                entry(2, read = true, starred = true, picture = "/img/starred.png"),
                entry(3, read = true, picture = url("/img/read.png")),
            ),
        )

        val result = prefetcher.run()

        assertThat(result.pictures).isEqualTo(2)
        assertThat(images.cached(url("/img/unread.png"))).isNotNull()
        // A relative address is resolved against the address of the entry.
        assertThat(images.cached(url("/img/starred.png"))).isNotNull()
        assertThat(images.cached(url("/img/read.png"))).isNull()
    }

    @Test
    fun aSecondRunDownloadsNothingAgain() = runBlocking<Unit> {
        db.entryDao().insertIgnore(listOf(entry(1, picture = url("/img/a.png"))))
        prefetcher.run()
        requested.clear()

        val again = prefetcher.run()

        assertThat(again.pictures).isEqualTo(0)
        assertThat(requested).isEmpty()
    }

    @Test
    fun feedsSetToFullTextGetItForUnreadEntriesWithItsPictures() = runBlocking<Unit> {
        db.treeDao().upsertFeedSettings(FeedSettingsEntity("feed/full", alwaysFullText = true))
        db.entryDao().insertIgnore(
            listOf(
                entry(1, feed = "feed/full", page = true),
                entry(2, feed = "feed/full", read = true, starred = true, page = true),
                entry(3, feed = "feed/1", page = true),
            ),
        )

        val result = prefetcher.run()

        assertThat(result.fullTexts).isEqualTo(1)
        assertThat(db.entryDao().entryNow(1)!!.fullContent).contains("The rivers of the north freeze")
        assertThat(db.entryDao().entryNow(2)!!.fullContent).isNull()
        assertThat(db.entryDao().entryNow(3)!!.fullContent).isNull()
        assertThat(images.cached(url("/img/river.png"))).isNotNull()
    }

    @Test
    fun picturesOfEntriesNoLongerHereAreDeleted() = runBlocking<Unit> {
        db.entryDao().insertIgnore(listOf(entry(1, picture = url("/img/kept.png")), entry(2, picture = url("/img/gone.png"))))
        prefetcher.run()
        val gone = images.cached(url("/img/gone.png"))!!.file

        db.entryDao().delete(listOf(2))
        prefetcher.run()

        assertThat(gone.exists()).isFalse()
        assertThat(images.cached(url("/img/kept.png"))).isNotNull()
    }

    @Test
    fun iconsOfFeedsAreDownloadedAndOutliveTheSweep() = runBlocking<Unit> {
        db.treeDao().upsertFeeds(
            listOf(
                FeedEntity("feed/1", "One", "", "", url("/favicon/one.png"), null),
                FeedEntity("feed/2", "Two", "", "", url("/favicon/one.png"), null),
                FeedEntity("feed/3", "Three", "", "", "", null),
            ),
        )
        db.entryDao().insertIgnore(listOf(entry(1, picture = url("/img/a.png"))))

        val result = prefetcher.run()
        prefetcher.run()

        assertThat(result.pictures).isEqualTo(2)
        assertThat(requested.count { it == "/favicon/one.png" }).isEqualTo(1)
        assertThat(images.cached(url("/favicon/one.png"))).isNotNull()
    }

    @Test
    fun aStoppedRunLeavesAtOnce() = runBlocking<Unit> {
        db.entryDao().insertIgnore(listOf(entry(1, picture = url("/img/a.png"))))

        val result = prefetcher.run(stopped = { true })

        assertThat(result.pictures).isEqualTo(0)
        assertThat(requested).isEmpty()
    }

    @Test
    fun picturesComeFromBothTextsAndFromPictureEnclosures() {
        val e = EntryEntity(
            id = 1, feedId = "feed/1", title = "", author = "", url = "https://example.org/a/b.html", sortTime = 1,
            content = "<p><img src=\"x.png\"><img src=\"data:image/png;base64,AA\"></p>",
            enclosuresJson = encodeEnclosures(listOf(EnclosureDto("https://cdn.example.org/c.jpg", "image/jpeg"), EnclosureDto("https://cdn.example.org/a.mp3", "audio/mpeg"))),
            isRead = false, isStarred = false, readAt = null,
            fullContent = "<img src=\"https://example.org/full.png\">",
        )

        assertThat(imageUrls(e)).containsExactly(
            "https://example.org/a/x.png",
            "https://example.org/full.png",
            "https://cdn.example.org/c.jpg",
        )
    }
}
