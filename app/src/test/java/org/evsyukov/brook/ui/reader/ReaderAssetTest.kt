package org.evsyukov.brook.ui.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderAssetTest {
    private val assets = ApplicationProvider.getApplicationContext<Context>().assets

    @Test
    fun bundledFontsAreServedToThePageOfAnyOrigin() {
        for (name in listOf("literata.woff2", "literata-italic.woff2")) {
            val response = readerAsset("${READER_ASSETS}fonts/$name", assets::open)!!

            assertThat(response.mimeType).isEqualTo("font/woff2")
            assertThat(response.responseHeaders).containsEntry("Access-Control-Allow-Origin", "*")
            // A woff2 file starts with its signature.
            assertThat(response.data.use { it.readNBytes(4) }.decodeToString()).isEqualTo("wOF2")
        }
    }

    @Test
    fun nothingButFontsComesFromTheAssets() {
        assertThat(readerAsset("${READER_ASSETS}fonts/LITERATA-OFL.txt", assets::open)).isNull()
        assertThat(readerAsset("${READER_ASSETS}fonts/../fonts/literata.woff2", assets::open)).isNull()
        assertThat(readerAsset("${READER_ASSETS}fonts/missing.woff2", assets::open)).isNull()
    }
}
