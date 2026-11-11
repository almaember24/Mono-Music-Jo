package com.prism.music.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Pictures the listener picks for Prism (a Blend's cover, their profile picture): read from the photo picker, cut to
 * a centred square at most [size] px, and kept as a JPEG in Prism's own files (so they're in backups). The original
 * is never touched.
 */
object Pictures {
    suspend fun saveSquare(context: Context, uri: Uri, dest: File, size: Int = 1080): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = decode(context, uri, size) ?: return@runCatching false
            val side = min(bitmap.width, bitmap.height)
            val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
            val out = if (side > size) Bitmap.createScaledBitmap(square, size, size, true) else square
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            tmp.renameTo(dest) || run { dest.delete(); tmp.renameTo(dest) }
        }.getOrDefault(false)
    }

    /** The picture, turned the right way up (ImageDecoder reads the camera's rotation), about [size] px on its short side. */
    private fun decode(context: Context, uri: Uri, size: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val short = min(info.size.width, info.size.height)
                if (short > size) {
                    val k = size.toFloat() / short
                    decoder.setTargetSize(max(1, (info.size.width * k).toInt()), max(1, (info.size.height * k).toInt()))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }
}
