package com.example.services

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

data class EncodedWebPImage(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
)

/**
 * Background WebP decoder and on-device compressor.
 * Decoded bitmaps live strictly in RAM ([PageMemoryCache]) — zero disk writes.
 */
object ImageCodecService {

    suspend fun decodeToMemoryCache(
        cacheKey: String,
        bytes: ByteArray,
    ): Bitmap? {
        PageMemoryCache.get(cacheKey)?.let { return it }
        return withContext(Dispatchers.Default) {
            PageMemoryCache.get(cacheKey)?.let { return@withContext it }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
            PageMemoryCache.put(cacheKey, decoded)
            decoded
        }
    }

    suspend fun compressUriForPage(
        contentResolver: ContentResolver,
        uri: Uri,
    ): Result<EncodedWebPImage> = withContext(Dispatchers.Default) {
        runCatching {
            val source = decodeSampledFromUri(contentResolver, uri, FirestoreConfig.TARGET_PAGE_WIDTH_PX)
                ?: error("Unable to read selected image.")
            try {
                compressBitmapToBudget(
                    source = source,
                    maxWidthPx = FirestoreConfig.TARGET_PAGE_WIDTH_PX,
                    targetBytes = FirestoreConfig.TARGET_PAGE_BYTES,
                    hardMaxBytes = FirestoreConfig.MAX_PAGE_BYTES,
                )
            } finally {
                if (!source.isRecycled) source.recycle()
            }
        }
    }

    suspend fun compressUriForCover(
        contentResolver: ContentResolver,
        uri: Uri,
    ): Result<EncodedWebPImage> = withContext(Dispatchers.Default) {
        runCatching {
            val source = decodeSampledFromUri(contentResolver, uri, 540)
                ?: error("Unable to read selected cover image.")
            try {
                compressBitmapToBudget(
                    source = source,
                    maxWidthPx = 540,
                    targetBytes = 45 * 1024,
                    hardMaxBytes = FirestoreConfig.MAX_COVER_BYTES,
                )
            } finally {
                if (!source.isRecycled) source.recycle()
            }
        }
    }

    fun compressBitmapToBudget(
        source: Bitmap,
        maxWidthPx: Int,
        targetBytes: Int,
        hardMaxBytes: Int,
    ): EncodedWebPImage {
        var working = scaleToMaxWidth(source, maxWidthPx)
        val qualities = listOf(82, 72, 62, 52, 42, 32, 24)
        var bestBytes: ByteArray? = null
        var bestW = working.width
        var bestH = working.height

        var scalePass = 0
        while (scalePass < 3) {
            for (q in qualities) {
                val encoded = encodeWebP(working, q)
                if (bestBytes == null || encoded.size < bestBytes.size) {
                    bestBytes = encoded
                    bestW = working.width
                    bestH = working.height
                }
                if (encoded.size <= targetBytes) {
                    if (working !== source) working.recycle()
                    return EncodedWebPImage(encoded, bestW, bestH)
                }
            }
            if (bestBytes != null && bestBytes.size <= hardMaxBytes) {
                if (working !== source) working.recycle()
                return EncodedWebPImage(bestBytes, bestW, bestH)
            }
            // Downscale by 20% and retry to respect hard byte ceiling
            val nextWidth = (working.width * 0.8f).roundToInt().coerceAtLeast(360)
            val nextBitmap = scaleToMaxWidth(working, nextWidth)
            if (working !== source && working !== nextBitmap) {
                working.recycle()
            }
            working = nextBitmap
            scalePass++
        }

        if (working !== source) working.recycle()
        val finalBytes = bestBytes ?: error("Failed to encode image as WebP.")
        require(finalBytes.size <= hardMaxBytes) {
            "Compressed image (${finalBytes.size / 1024} KB) exceeds ${hardMaxBytes / 1024} KB hard limit."
        }
        return EncodedWebPImage(finalBytes, bestW, bestH)
    }

    private fun decodeSampledFromUri(
        contentResolver: ContentResolver,
        uri: Uri,
        reqWidth: Int,
    ): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOpts)
        } ?: return null

        var sampleSize = 1
        if (boundsOpts.outWidth > reqWidth * 2) {
            while ((boundsOpts.outWidth / (sampleSize * 2)) >= reqWidth) {
                sampleSize *= 2
            }
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        }
    }

    private fun scaleToMaxWidth(bitmap: Bitmap, maxWidth: Int): Bitmap {
        if (bitmap.width <= maxWidth) return bitmap
        val ratio = maxWidth.toFloat() / bitmap.width.toFloat()
        val targetHeight = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, maxWidth, targetHeight, true)
    }

    @Suppress("DEPRECATION")
    private fun encodeWebP(bitmap: Bitmap, quality: Int): ByteArray {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(format, quality, out)
        return out.toByteArray()
    }
}
