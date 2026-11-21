package com.example.services

import com.example.models.BookPage
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Spark-tier [PageImageStore] storing compressed WebP bytes directly in Firestore documents
 * (`books/{bookId}/pages/{pageId}`). Zero Firebase Storage usage, zero public URLs.
 */
class FirestoreBytesPageImageStore(
    private val db: FirebaseFirestore,
) : PageImageStore {

    override suspend fun loadPageBatch(
        bookId: String,
        batchSize: Int,
        afterOrderIndex: Double?,
    ): Result<PageBatchResult> {
        return try {
            var query: Query = db.collection(FirestoreConfig.COLLECTION_BOOKS)
                .document(bookId)
                .collection(FirestoreConfig.SUBCOLLECTION_PAGES)
                .orderBy("orderIndex", Query.Direction.ASCENDING)
                .limit(batchSize.toLong())

            if (afterOrderIndex != null) {
                query = query.startAfter(afterOrderIndex)
            }

            val snapshot = query.get().await()
            val pages = snapshot.documents.mapNotNull { doc ->
                doc.toObject(
                    BookPage::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                )?.copy(id = doc.id)
            }

            val lastOrder = pages.lastOrNull()?.orderIndex
            Result.success(
                PageBatchResult(
                    pages = pages,
                    lastOrderIndex = lastOrder,
                    hasMore = pages.size >= batchSize,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun savePageBytes(
        bookId: String,
        pageId: String?,
        orderIndex: Double,
        webpBytes: ByteArray,
        width: Int,
        height: Int,
        caption: String,
    ): Result<String> {
        if (webpBytes.isEmpty() || webpBytes.size > FirestoreConfig.MAX_PAGE_BYTES) {
            return Result.failure(
                IllegalArgumentException(
                    "Page image must be between 1 byte and ${FirestoreConfig.MAX_PAGE_BYTES / 1024} KB (got ${webpBytes.size / 1024} KB)."
                )
            )
        }
        return try {
            val pagesCol = db.collection(FirestoreConfig.COLLECTION_BOOKS)
                .document(bookId)
                .collection(FirestoreConfig.SUBCOLLECTION_PAGES)

            val docRef = if (pageId.isNullOrBlank()) pagesCol.document() else pagesCol.document(pageId)
            val payload = hashMapOf<String, Any>(
                "orderIndex" to orderIndex,
                "imageBytes" to Blob.fromBytes(webpBytes),
                "width" to width.coerceIn(1, 4096),
                "height" to height.coerceIn(1, 8192),
                "caption" to caption.trim().take(500),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deletePageBytes(
        bookId: String,
        pageId: String,
    ): Result<Int> {
        return try {
            val docRef = db.collection(FirestoreConfig.COLLECTION_BOOKS)
                .document(bookId)
                .collection(FirestoreConfig.SUBCOLLECTION_PAGES)
                .document(pageId)

            val snap = docRef.get().await()
            val removedBytes = (snap.getBlob("imageBytes")?.toBytes()?.size) ?: 0
            docRef.delete().await()
            Result.success(removedBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
