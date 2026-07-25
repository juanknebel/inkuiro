package dev.zero.inkchat.data.images

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Downscales and stores images picked by the user as JPEG under app-internal
 * storage, so provider requests can attach them without holding onto a
 * content:// Uri (which may not survive process death or app restarts).
 */
object ImageStore {

    const val MIME_TYPE = "image/jpeg"

    /** Anthropic/OpenAI/Gemini all treat larger images as diminishing returns; this keeps request size sane. */
    private const val MAX_DIMENSION = 1568
    private const val JPEG_QUALITY = 85

    fun store(context: Context, uri: Uri): String {
        val source = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: throw IOException("Could not decode image")
        val scaled = downscale(source, MAX_DIMENSION)
        val dir = File(context.filesDir, "images").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        FileOutputStream(file).use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
        if (scaled !== source) source.recycle()
        scaled.recycle()
        return file.absolutePath
    }

    fun delete(path: String) {
        runCatching { File(path).delete() }
    }

    fun readBase64(path: String): String =
        Base64.encodeToString(File(path).readBytes(), Base64.NO_WRAP)

    private fun downscale(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDimension && height <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / maxOf(width, height)
        return Bitmap.createScaledBitmap(bitmap, (width * scale).toInt(), (height * scale).toInt(), true)
    }
}
