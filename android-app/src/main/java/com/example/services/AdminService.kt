package com.example.services

import com.example.BuildConfig
import com.example.models.AccessGrant
import com.example.models.AuthorizedUser
import com.example.models.Book
import com.example.models.BookPage
import com.example.models.ResetAuditLog
import com.example.models.StorageStats
import com.example.models.UserRole
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Admin Firestore & backend operations.
 * - Never writes subscriber emails into `books` documents.
 * - Never mutates `authorized_users` device-binding fields from client (always calls Express backend).
 * - Cascades `access` doc cleanup when removing a user or book.
 * - Keeps `pageCount` and `app_stats/storage` synchronized on every image mutation.
 */
class AdminService(
    private val db: FirebaseFirestore,
    private val pageImageStore: PageImageStore = FirestoreBytesPageImageStore(db),
    private val deviceApiService: DeviceApiService = DeviceApiService(),
) {

    // region Books

    suspend fun listBooks(): Result<List<Book>> {
        return try {
            val snap = db.collection(FirestoreConfig.COLLECTION_BOOKS)
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .get()
                .await()
            val books = snap.documents.mapNotNull { doc ->
                doc.toObject(Book::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    ?.copy(id = doc.id)
            }
            Result.success(books)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createBook(
        title: String,
        author: String,
        description: String,
        category: String,
        coverWebPBytes: ByteArray?,
        adminEmail: String,
    ): Result<String> {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty() || cleanTitle.length > 200) {
            return Result.failure(IllegalArgumentException("Book title must be 1–200 characters."))
        }
        if (coverWebPBytes != null && coverWebPBytes.size > FirestoreConfig.MAX_COVER_BYTES) {
            return Result.failure(IllegalArgumentException("Cover exceeds 60 KB limit."))
        }

        return try {
            val docRef = db.collection(FirestoreConfig.COLLECTION_BOOKS).document()
            val payload = hashMapOf<String, Any?>(
                "title" to cleanTitle,
                "author" to author.trim().take(150),
                "description" to description.trim().take(2000),
                "category" to category.trim().take(80),
                "coverBytes" to coverWebPBytes?.let { Blob.fromBytes(it) },
                "pageCount" to 0,
                "createdBy" to adminEmail.trim().lowercase(),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            docRef.set(payload).await()
            if (coverWebPBytes != null && coverWebPBytes.isNotEmpty()) {
                adjustStorageStats(coverWebPBytes.size.toLong())
            }
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateBook(
        book: Book,
        title: String,
        author: String,
        description: String,
        category: String,
        newCoverWebPBytes: ByteArray?,
    ): Result<Unit> {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty() || cleanTitle.length > 200) {
            return Result.failure(IllegalArgumentException("Book title must be 1–200 characters."))
        }
        if (newCoverWebPBytes != null && newCoverWebPBytes.size > FirestoreConfig.MAX_COVER_BYTES) {
            return Result.failure(IllegalArgumentException("Cover exceeds 60 KB limit."))
        }

        return try {
            val oldCoverSize = book.coverBytes?.toBytes()?.size ?: 0
            val finalCoverBlob = if (newCoverWebPBytes != null) {
                Blob.fromBytes(newCoverWebPBytes)
            } else {
                book.coverBytes
            }

            val docRef = db.collection(FirestoreConfig.COLLECTION_BOOKS).document(book.id)
            val updates = hashMapOf<String, Any?>(
                "title" to cleanTitle,
                "author" to author.trim().take(150),
                "description" to description.trim().take(2000),
                "category" to category.trim().take(80),
                "coverBytes" to finalCoverBlob,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            docRef.update(updates).await()

            if (newCoverWebPBytes != null) {
                val delta = newCoverWebPBytes.size.toLong() - oldCoverSize.toLong()
                if (delta != 0L) adjustStorageStats(delta)
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteBook(book: Book): Result<Unit> {
        return try {
            var removedBytes = (book.coverBytes?.toBytes()?.size ?: 0).toLong()

            // Delete pages in chunks
            val pagesCol = db.collection(FirestoreConfig.COLLECTION_BOOKS)
                .document(book.id)
                .collection(FirestoreConfig.SUBCOLLECTION_PAGES)
            while (true) {
                val pageSnap = pagesCol.limit(100).get().await()
                if (pageSnap.isEmpty) break
                val batch = db.batch()
                for (doc in pageSnap.documents) {
                    removedBytes += (doc.getBlob("imageBytes")?.toBytes()?.size ?: 0).toLong()
                    batch.delete(doc.reference)
                }
                batch.commit().await()
            }

            // Delete access grants pointing to this book
            val grantsSnap = db.collection(FirestoreConfig.COLLECTION_ACCESS)
                .whereEqualTo("bookId", book.id)
                .get()
                .await()
            if (!grantsSnap.isEmpty) {
                grantsSnap.documents.chunked(200).forEach { chunk ->
                    val batch = db.batch()
                    chunk.forEach { batch.delete(it.reference) }
                    batch.commit().await()
                }
            }

            db.collection(FirestoreConfig.COLLECTION_BOOKS).document(book.id).delete().await()
            if (removedBytes > 0L) {
                adjustStorageStats(-removedBytes)
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // endregion

    // region Pages & Fractional Ordering

    suspend fun loadPageBatch(
        bookId: String,
        afterOrderIndex: Double? = null,
    ): Result<PageBatchResult> = pageImageStore.loadPageBatch(
        bookId = bookId,
        batchSize = FirestoreConfig.PAGE_BATCH_SIZE,
        afterOrderIndex = afterOrderIndex,
    )

    suspend fun insertPageAtPosition(
        bookId: String,
        position: OrderIndexMath.InsertPosition,
        encoded: EncodedWebPImage,
        caption: String = "",
    ): Result<String> {
        return try {
            val pagesCol = db.collection(FirestoreConfig.COLLECTION_BOOKS)
                .document(bookId)
                .collection(FirestoreConfig.SUBCOLLECTION_PAGES)

            val orderedSnap = pagesCol.orderBy("orderIndex", Query.Direction.ASCENDING).get().await()
            val sortedDocs = orderedSnap.documents
            val sortedIndices = sortedDocs.map { (it.get("orderIndex") as? Number)?.toDouble() ?: 0.0 }

            val computed = OrderIndexMath.computeInsertion(sortedIndices, position)
            val savedId = pageImageStore.savePageBytes(
                bookId = bookId,
                pageId = null,
                orderIndex = computed.orderIndex,
                webpBytes = encoded.bytes,
                width = encoded.width,
                height = encoded.height,
                caption = caption,
            ).getOrThrow()

            if (computed.requiresRebalance) {
                rebalanceBookPages(bookId)
            }

            val newCount = sortedDocs.size + 1
            db.collection(FirestoreConfig.COLLECTION_BOOKS)
                .document(bookId)
                .update(
                    mapOf(
                        "pageCount" to newCount,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                )
                .await()

            adjustStorageStats(encoded.bytes.size.toLong())
            Result.success(savedId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deletePage(
        bookId: String,
        pageId: String,
    ): Result<Unit> {
        return try {
            val removedBytes = pageImageStore.deletePageBytes(bookId, pageId).getOrThrow()
            val remainingSnap = db.collection(FirestoreConfig.COLLECTION_BOOKS)
                .document(bookId)
                .collection(FirestoreConfig.SUBCOLLECTION_PAGES)
                .get()
                .await()

            db.collection(FirestoreConfig.COLLECTION_BOOKS)
                .document(bookId)
                .update(
                    mapOf(
                        "pageCount" to remainingSnap.size(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                )
                .await()

            if (removedBytes > 0) {
                adjustStorageStats(-removedBytes.toLong())
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun rebalanceBookPages(bookId: String) {
        val pagesCol = db.collection(FirestoreConfig.COLLECTION_BOOKS)
            .document(bookId)
            .collection(FirestoreConfig.SUBCOLLECTION_PAGES)

        val orderedSnap = pagesCol.orderBy("orderIndex", Query.Direction.ASCENDING).get().await()
        val rebalanced = OrderIndexMath.rebalancedIndices(orderedSnap.size())

        orderedSnap.documents.zip(rebalanced).chunked(250).forEach { chunk ->
            val batch = db.batch()
            for ((doc, newOrder) in chunk) {
                batch.update(
                    doc.reference,
                    mapOf(
                        "orderIndex" to newOrder,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                )
            }
            batch.commit().await()
        }
    }

    // endregion

    // region Users, Access Grants & Backend Reset

    suspend fun listUsers(): Result<List<AuthorizedUser>> {
        return try {
            val snap = db.collection(FirestoreConfig.COLLECTION_AUTHORIZED_USERS)
                .orderBy("email", Query.Direction.ASCENDING)
                .get()
                .await()
            val users = snap.documents.mapNotNull { doc ->
                doc.toObject(
                    AuthorizedUser::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                )?.copy(id = doc.id)
            }
            Result.success(users)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addReaderUser(email: String, displayName: String): Result<Unit> {
        val cleanEmail = email.trim().lowercase()
        if (!cleanEmail.contains("@") || cleanEmail.length < 5) {
            return Result.failure(IllegalArgumentException("Enter a valid Gmail address."))
        }
        return try {
            val docRef = db.collection(FirestoreConfig.COLLECTION_AUTHORIZED_USERS)
                .document(cleanEmail)
            val existing = docRef.get().await()
            if (existing.exists()) {
                return Result.failure(IllegalStateException("That email is already authorized."))
            }

            // Strictly matches firestore.rules allow create for authorized_users
            val payload = hashMapOf<String, Any?>(
                "email" to cleanEmail,
                "displayName" to displayName.trim().take(120),
                "role" to UserRole.READER.wireValue,
                "boundDeviceId" to null,
                "boundDeviceLabel" to null,
                "boundUid" to null,
                "boundAt" to null,
                "sessionVersion" to 1L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Removes a reader account AND cascades deletion of all their `access` documents.
     * Super-admin cannot be removed.
     */
    suspend fun removeUserAndAccess(email: String): Result<Unit> {
        val cleanEmail = email.trim().lowercase()
        if (cleanEmail == BuildConfig.SUPER_ADMIN_EMAIL.lowercase()) {
            return Result.failure(IllegalArgumentException("Super-admin cannot be deleted."))
        }
        return try {
            val grantsSnap = db.collection(FirestoreConfig.COLLECTION_ACCESS)
                .whereEqualTo("email", cleanEmail)
                .get()
                .await()

            grantsSnap.documents.chunked(200).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }

            db.collection(FirestoreConfig.COLLECTION_AUTHORIZED_USERS)
                .document(cleanEmail)
                .delete()
                .await()

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listAllAccessGrants(): Result<List<AccessGrant>> {
        return try {
            val snap = db.collection(FirestoreConfig.COLLECTION_ACCESS).get().await()
            val grants = snap.documents.mapNotNull { doc ->
                doc.toObject(
                    AccessGrant::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                )?.copy(id = doc.id)
            }
            Result.success(grants)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setBookAccess(
        targetEmail: String,
        bookId: String,
        granted: Boolean,
        adminEmail: String,
    ): Result<Unit> {
        val cleanEmail = targetEmail.trim().lowercase()
        val accessDocId = AccessGrant.docId(cleanEmail, bookId)
        val docRef = db.collection(FirestoreConfig.COLLECTION_ACCESS).document(accessDocId)

        return try {
            if (granted) {
                val payload = hashMapOf<String, Any>(
                    "email" to cleanEmail,
                    "bookId" to bookId,
                    "grantedBy" to adminEmail.trim().lowercase(),
                    "grantedAt" to FieldValue.serverTimestamp(),
                )
                docRef.set(payload).await()
            } else {
                docRef.delete().await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun requestDeviceReset(
        firebaseIdToken: String,
        targetEmail: String,
        reason: String,
        appCheckToken: String?,
    ): ResetDeviceOutcome {
        return deviceApiService.resetDevice(
            firebaseIdToken = firebaseIdToken,
            targetEmail = targetEmail,
            reason = reason,
            appCheckToken = appCheckToken,
        )
    }

    // endregion

    // region Audit Logs & Spark Storage Stats

    suspend fun listResetAuditLogs(limit: Long = 50L): Result<List<ResetAuditLog>> {
        return try {
            val snap = db.collection(FirestoreConfig.COLLECTION_RESET_AUDIT_LOGS)
                .orderBy("resetAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get()
                .await()
            val logs = snap.documents.mapNotNull { doc ->
                doc.toObject(
                    ResetAuditLog::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                )?.copy(id = doc.id)
            }
            Result.success(logs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchStorageStats(): Result<StorageStats> {
        return try {
            val snap = db.collection(FirestoreConfig.COLLECTION_APP_STATS)
                .document(FirestoreConfig.DOC_STORAGE_STATS)
                .get()
                .await()
            if (!snap.exists()) {
                Result.success(StorageStats(totalImageBytes = 0L))
            } else {
                val total = (snap.get("totalImageBytes") as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L
                Result.success(StorageStats(totalImageBytes = total, updatedAt = snap.getTimestamp("updatedAt")))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun adjustStorageStats(deltaBytes: Long) {
        val docRef = db.collection(FirestoreConfig.COLLECTION_APP_STATS)
            .document(FirestoreConfig.DOC_STORAGE_STATS)
        runCatching {
            val snap = docRef.get().await()
            val current = (snap.get("totalImageBytes") as? Number)?.toLong() ?: 0L
            val next = (current + deltaBytes).coerceAtLeast(0L)
            docRef.set(
                mapOf(
                    "totalImageBytes" to next,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
        }
    }

    // endregion
}
