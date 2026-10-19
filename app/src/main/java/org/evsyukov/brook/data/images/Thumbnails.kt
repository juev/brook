package org.evsyukov.brook.data.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
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
        val bitmap = withContext(Dispatchers.IO) { images.get(url)?.let { decode(it.file, sizePx) } } ?: return null
        memory.put(key, bitmap)
        return bitmap
    }

    private companion object {
        const val MEMORY_BYTES = 16 * 1024 * 1024

        fun decode(file: File, sizePx: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
            return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
