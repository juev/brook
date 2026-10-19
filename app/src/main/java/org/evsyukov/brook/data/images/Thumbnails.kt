package org.evsyukov.brook.data.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.LruCache
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pictures of the list, feed icons and thumbnails, scaled down from [images]: from disk when the
 * download for offline reading got them, from the network otherwise. The last ones drawn stay in memory.
 */
class Thumbnails(private val images: ImageCache) {
    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** The picture at [url] at least [sizePx] on its shorter side where it is that large; null when it cannot be had. */
    suspend fun load(url: String, sizePx: Int): Bitmap? {
        val key = "$sizePx $url"
        memory.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            images.get(url)?.let { if (it.mimeType == SVG_TYPE) decodeSvg(it.file, sizePx) else decode(it.file, sizePx) }
        } ?: return null
        memory.put(key, bitmap)
        return bitmap
    }

    private companion object {
        const val MEMORY_BYTES = 16 * 1024 * 1024
        const val SVG_TYPE = "image/svg+xml"

        fun decode(file: File, sizePx: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
            return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }

        /** Draws an SVG picture into a square of [sizePx]; some sites have no icon in another format. */
        fun decodeSvg(file: File, sizePx: Int): Bitmap? {
            // The file comes from any site; a picture it cannot draw is no picture, not a crash of the list.
            return try {
                val svg = file.inputStream().use { SVG.getFromInputStream(it) }
                // Without a view box the picture would keep its own size instead of filling the square.
                if (svg.documentViewBox == null && svg.documentWidth > 0 && svg.documentHeight > 0) {
                    svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
                }
                svg.setDocumentWidth(sizePx.toFloat())
                svg.setDocumentHeight(sizePx.toFloat())
                Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { svg.renderToCanvas(Canvas(it)) }
            } catch (e: SVGParseException) {
                null
            } catch (e: RuntimeException) {
                null
            }
        }
    }
}
