package com.example.services

import com.example.models.AccessGrant
import com.example.models.Book
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Access-scoped book lookup service for readers and catalog queries for admins.
 * Readers strictly query `access` where `email == own email`, then `get` each `books/{bookId}`.
 */
class BookService(
    private val db: FirebaseFirestore,
    val pageImageStore: PageImageStore = FirestoreBytesPageImageStore(db),
) {

    suspend fun fetchAssignedBooksForReader(userEmail: String): Result<List<Book>> {
        val cleanEmail = userEmail.trim().lowercase()
        if (cleanEmail.isBlank()) return Result.success(emptyList())

        return try {
            val grantsSnap = db.collection(FirestoreConfig.COLLECTION_ACCESS)
                .whereEqualTo("email", cleanEmail)
                .get()
                .await()

            val bookIds = grantsSnap.documents.mapNotNull { doc ->
                doc.toObject(
                    AccessGrant::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                )?.bookId?.takeIf { it.isNotBlank() }
            }.distinct()

            val books = mutableListOf<Book>()
            for (bookId in bookIds) {
                val bookSnap = runCatching {
                    db.collection(FirestoreConfig.COLLECTION_BOOKS)
                        .document(bookId)
                        .get()
                        .await()
                }.getOrNull()

                if (bookSnap != null && bookSnap.exists()) {
                    bookSnap.toObject(
                        Book::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                    )?.copy(id = bookSnap.id)?.let { books.add(it) }
                }
            }

            Result.success(books.sortedBy { it.title.lowercase() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchAllBooksForAdmin(): Result<List<Book>> {
        return try {
            val snap = db.collection(FirestoreConfig.COLLECTION_BOOKS)
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .get()
                .await()

            val books = snap.documents.mapNotNull { doc ->
                doc.toObject(
                    Book::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                )?.copy(id = doc.id)
            }
            Result.success(books)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getBook(bookId: String): Result<Book?> {
        return try {
            val snap = db.collection(FirestoreConfig.COLLECTION_BOOKS)
                .document(bookId)
                .get()
                .await()
            val book = if (snap.exists()) {
                snap.toObject(
                    Book::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                )?.copy(id = snap.id)
            } else {
                null
            }
            Result.success(book)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
