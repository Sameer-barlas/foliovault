package com.example.services

import com.example.models.BookPage

/**
 * Storage abstraction for cover and page image bytes.
 *
 * Current Spark implementation: [FirestoreBytesPageImageStore] stores WebP bytes directly inside
 * Firestore documents (no Firebase Storage, no public URLs).
 * Moving to Blaze + Firebase Storage later only requires swapping this implementation.
 */
interface PageImageStore {
    suspend fun loadPageBatch(
        bookId: String,
        batchSize: Int = FirestoreConfig.PAGE_BATCH_SIZE,
        afterOrderIndex: Double? = null,
    ): Result<PageBatchResult>

    suspend fun savePageBytes(
        bookId: String,
        pageId: String?,
        orderIndex: Double,
        webpBytes: ByteArray,
        width: Int,
        height: Int,
        caption: String = "",
    ): Result<String>

    suspend fun deletePageBytes(
        bookId: String,
        pageId: String,
    ): Result<Int>
}

data class PageBatchResult(
    val pages: List<BookPage>,
    val lastOrderIndex: Double?,
    val hasMore: Boolean,
)
