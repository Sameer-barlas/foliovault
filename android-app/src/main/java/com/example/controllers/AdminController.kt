package com.example.controllers

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.models.AccessGrant
import com.example.models.AuthorizedUser
import com.example.models.Book
import com.example.models.BookPage
import com.example.models.ResetAuditLog
import com.example.models.StorageStats
import com.example.services.AdminService
import com.example.services.DebugSeedService
import com.example.services.FirestoreConfig
import com.example.services.ImageCodecService
import com.example.services.OrderIndexMath
import com.example.services.PageMemoryCache
import com.example.services.ResetDeviceOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class AdminUiState(
    val loading: Boolean = true,
    val busyMessage: String? = null,
    val books: List<Book> = emptyList(),
    val users: List<AuthorizedUser> = emptyList(),
    val accessGrants: List<AccessGrant> = emptyList(),
    val auditLogs: List<ResetAuditLog> = emptyList(),
    val storageStats: StorageStats = StorageStats(),
    val selectedBookForPages: Book? = null,
    val filmstripPages: List<BookPage> = emptyList(),
    val filmstripBitmaps: Map<String, Bitmap> = emptyMap(),
    val filmstripHasMore: Boolean = false,
    val filmstripLoading: Boolean = false,
    val snackbarMessage: String? = null,
)

class AdminController(
    application: Application,
) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val _uiState = MutableStateFlow(AdminUiState())
    val uiState: StateFlow<AdminUiState> = _uiState.asStateFlow()

    private var adminEmail: String = ""
    private var isAdminSession: Boolean = false
    private var filmstripLastOrderIndex: Double? = null

    private fun service(): AdminService = AdminService(FirestoreConfig.firestore(appContext))

    fun initialize(email: String, isAdmin: Boolean) {
        adminEmail = email.trim().lowercase()
        isAdminSession = isAdmin
        refreshAll()
    }

    fun consumeSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun refreshAll() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            if (!FirestoreConfig.isFirebaseConfigured(appContext)) {
                _uiState.update { it.copy(loading = false, snackbarMessage = "Firebase is not configured.") }
                return@launch
            }
            val svc = service()
            val booksRes = svc.listBooks()
            val usersRes = svc.listUsers()
            val grantsRes = svc.listAllAccessGrants()
            val logsRes = svc.listResetAuditLogs()
            val statsRes = svc.fetchStorageStats()

            val firstErr = listOf(booksRes, usersRes, grantsRes, logsRes, statsRes)
                .firstNotNullOfOrNull { it.exceptionOrNull()?.message }

            _uiState.update { state ->
                val updatedBooks = booksRes.getOrDefault(state.books)
                val updatedSelected = state.selectedBookForPages?.let { sel ->
                    updatedBooks.firstOrNull { it.id == sel.id }
                }
                state.copy(
                    loading = false,
                    books = updatedBooks,
                    users = usersRes.getOrDefault(state.users),
                    accessGrants = grantsRes.getOrDefault(state.accessGrants),
                    auditLogs = logsRes.getOrDefault(state.auditLogs),
                    storageStats = statsRes.getOrDefault(state.storageStats),
                    selectedBookForPages = updatedSelected,
                    snackbarMessage = firstErr ?: state.snackbarMessage,
                )
            }
        }
    }

    // region Books & Covers

    fun createBook(
        title: String,
        author: String,
        description: String,
        category: String,
        coverUri: Uri?,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(busyMessage = "Saving book...") }
            val coverBytes = if (coverUri != null) {
                ImageCodecService.compressUriForCover(appContext.contentResolver, coverUri)
                    .getOrElse { err ->
                        _uiState.update { it.copy(busyMessage = null, snackbarMessage = err.message) }
                        return@launch
                    }.bytes
            } else {
                null
            }

            service().createBook(
                title = title,
                author = author,
                description = description,
                category = category,
                coverWebPBytes = coverBytes,
                adminEmail = adminEmail,
            ).fold(
                onSuccess = {
                    _uiState.update { it.copy(busyMessage = null, snackbarMessage = "Book created.") }
                    refreshAll()
                },
                onFailure = { err ->
                    _uiState.update { it.copy(busyMessage = null, snackbarMessage = err.message ?: "Failed to create book.") }
                },
            )
        }
    }

    fun updateBook(
        book: Book,
        title: String,
        author: String,
        description: String,
        category: String,
        newCoverUri: Uri?,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(busyMessage = "Updating book...") }
            val newCoverBytes = if (newCoverUri != null) {
                ImageCodecService.compressUriForCover(appContext.contentResolver, newCoverUri)
                    .getOrElse { err ->
                        _uiState.update { it.copy(busyMessage = null, snackbarMessage = err.message) }
                        return@launch
                    }.bytes
            } else {
                null
            }

            service().updateBook(
                book = book,
                title = title,
                author = author,
                description = description,
                category = category,
                newCoverWebPBytes = newCoverBytes,
            ).fold(
                onSuccess = {
                    _uiState.update { it.copy(busyMessage = null, snackbarMessage = "Book updated.") }
                    refreshAll()
                },
                onFailure = { err ->
                    _uiState.update { it.copy(busyMessage = null, snackbarMessage = err.message ?: "Update failed.") }
                },
            )
        }
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch {
            _uiState.update { it.copy(busyMessage = "Deleting book and pages...") }
            service().deleteBook(book).fold(
                onSuccess = {
                    if (_uiState.value.selectedBookForPages?.id == book.id) {
                        closePageFilmstrip()
                    }
                    _uiState.update { it.copy(busyMessage = null, snackbarMessage = "Deleted \"${book.title}\".") }
                    refreshAll()
                },
                onFailure = { err ->
                    _uiState.update { it.copy(busyMessage = null, snackbarMessage = err.message ?: "Delete failed.") }
                },
            )
        }
    }

    // endregion

    // region Page Filmstrip (12-page batches) & Positional Insert/Delete

    fun openPageFilmstrip(book: Book) {
        filmstripLastOrderIndex = null
        _uiState.update {
            it.copy(
                selectedBookForPages = book,
                filmstripPages = emptyList(),
                filmstripBitmaps = emptyMap(),
                filmstripHasMore = true,
                filmstripLoading = true,
            )
        }
        loadNextFilmstripBatch(reset = true)
    }

    fun closePageFilmstrip() {
        _uiState.value.selectedBookForPages?.let { PageMemoryCache.removeBookPages(it.id) }
        filmstripLastOrderIndex = null
        _uiState.update {
            it.copy(
                selectedBookForPages = null,
                filmstripPages = emptyList(),
                filmstripBitmaps = emptyMap(),
                filmstripHasMore = false,
                filmstripLoading = false,
            )
        }
    }

    fun loadNextFilmstripBatch(reset: Boolean = false) {
        val book = _uiState.value.selectedBookForPages ?: return
        if (!reset && (!_uiState.value.filmstripHasMore || _uiState.value.filmstripLoading)) return

        viewModelScope.launch {
            _uiState.update { it.copy(filmstripLoading = true) }
            val cursor = if (reset) null else filmstripLastOrderIndex
            service().loadPageBatch(book.id, afterOrderIndex = cursor).fold(
                onSuccess = { batch ->
                    val combined = if (reset) {
                        batch.pages
                    } else {
                        val existingIds = _uiState.value.filmstripPages.map { it.id }.toSet()
                        _uiState.value.filmstripPages + batch.pages.filter { it.id !in existingIds }
                    }
                    filmstripLastOrderIndex = batch.lastOrderIndex
                    _uiState.update {
                        it.copy(
                            filmstripPages = combined,
                            filmstripHasMore = batch.hasMore && batch.pages.isNotEmpty(),
                            filmstripLoading = false,
                        )
                    }
                    decodeFilmstripPages(book.id, batch.pages)
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            filmstripLoading = false,
                            snackbarMessage = err.message ?: "Unable to load pages.",
                        )
                    }
                },
            )
        }
    }

    private fun decodeFilmstripPages(bookId: String, pages: List<BookPage>) {
        pages.forEach { page ->
            val raw = page.imageBytes?.toBytes() ?: return@forEach
            viewModelScope.launch {
                val bmp = ImageCodecService.decodeToMemoryCache(
                    cacheKey = PageMemoryCache.pageKey(bookId, page.id),
                    bytes = raw,
                )
                if (bmp != null) {
                    _uiState.update { state ->
                        state.copy(filmstripBitmaps = state.filmstripBitmaps + (page.id to bmp))
                    }
                }
            }
        }
    }

    fun insertPageFromPhotoPicker(
        uri: Uri,
        position: OrderIndexMath.InsertPosition,
        caption: String = "",
    ) {
        val book = _uiState.value.selectedBookForPages ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(busyMessage = "Compressing page to WebP...") }
            val encoded = ImageCodecService.compressUriForPage(appContext.contentResolver, uri)
                .getOrElse { err ->
                    _uiState.update { it.copy(busyMessage = null, snackbarMessage = err.message ?: "Compression failed.") }
                    return@launch
                }

            _uiState.update { it.copy(busyMessage = "Uploading page (${encoded.bytes.size / 1024} KB)...") }
            service().insertPageAtPosition(
                bookId = book.id,
                position = position,
                encoded = encoded,
                caption = caption,
            ).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            busyMessage = null,
                            snackbarMessage = "Page added (${encoded.bytes.size / 1024} KB WebP).",
                        )
                    }
                    refreshAll()
                    loadNextFilmstripBatch(reset = true)
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            busyMessage = null,
                            snackbarMessage = err.message ?: "Failed to save page.",
                        )
                    }
                },
            )
        }
    }

    fun deletePage(page: BookPage) {
        val book = _uiState.value.selectedBookForPages ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(busyMessage = "Deleting page...") }
            service().deletePage(book.id, page.id).fold(
                onSuccess = {
                    _uiState.update { it.copy(busyMessage = null, snackbarMessage = "Page deleted.") }
                    refreshAll()
                    loadNextFilmstripBatch(reset = true)
                },
                onFailure = { err ->
                    _uiState.update { it.copy(busyMessage = null, snackbarMessage = err.message ?: "Failed to delete page.") }
                },
            )
        }
    }

    // endregion

    // region Users, Grants & Backend Device Reset

    fun addReader(email: String, displayName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(busyMessage = "Adding reader...") }
            service().addReaderUser(email, displayName).fold(
                onSuccess = {
                    _uiState.update { it.copy(busyMessage = null, snackbarMessage = "Reader authorized.") }
                    refreshAll()
                },
                onFailure = { err ->
                    _uiState.update { it.copy(busyMessage = null, snackbarMessage = err.message ?: "Failed to add reader.") }
                },
            )
        }
    }

    fun removeUser(email: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(busyMessage = "Removing user and access grants...") }
            service().removeUserAndAccess(email).fold(
                onSuccess = {
                    _uiState.update { it.copy(busyMessage = null, snackbarMessage = "Removed $email and revoked grants.") }
                    refreshAll()
                },
                onFailure = { err ->
                    _uiState.update { it.copy(busyMessage = null, snackbarMessage = err.message ?: "Failed to remove user.") }
                },
            )
        }
    }

    fun toggleBookAccess(targetEmail: String, bookId: String, granted: Boolean) {
        viewModelScope.launch {
            service().setBookAccess(
                targetEmail = targetEmail,
                bookId = bookId,
                granted = granted,
                adminEmail = adminEmail,
            ).fold(
                onSuccess = {
                    refreshAll()
                },
                onFailure = { err ->
                    _uiState.update { it.copy(snackbarMessage = err.message ?: "Failed to update book access.") }
                },
            )
        }
    }

    fun resetUserDevice(targetEmail: String, reason: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(busyMessage = "Resetting bound device via server...") }
            val firebaseToken = runCatching {
                FirestoreConfig.auth(appContext).currentUser?.getIdToken(true)?.await()?.token
            }.getOrNull()

            if (firebaseToken.isNullOrBlank()) {
                _uiState.update {
                    it.copy(busyMessage = null, snackbarMessage = "Session token unavailable. Sign in again.")
                }
                return@launch
            }

            val appCheckToken = FirestoreConfig.fetchAppCheckTokenOrNull()
            when (
                val outcome = service().requestDeviceReset(
                    firebaseIdToken = firebaseToken,
                    targetEmail = targetEmail,
                    reason = reason,
                    appCheckToken = appCheckToken,
                )
            ) {
                is ResetDeviceOutcome.Success -> {
                    _uiState.update {
                        it.copy(
                            busyMessage = null,
                            snackbarMessage = "Device reset for ${outcome.targetEmail}. Previous device revoked.",
                        )
                    }
                    refreshAll()
                }

                is ResetDeviceOutcome.RateLimited -> {
                    val minutes = (outcome.retryAfterSeconds / 60).coerceAtLeast(1)
                    _uiState.update {
                        it.copy(
                            busyMessage = null,
                            snackbarMessage = "${outcome.message} Retry in ~$minutes min.",
                        )
                    }
                }

                is ResetDeviceOutcome.Failure -> {
                    _uiState.update {
                        it.copy(busyMessage = null, snackbarMessage = outcome.message)
                    }
                }
            }
        }
    }

    // endregion

    // region Debug-Only Seed Loader

    fun runDebugSeedLoader() {
        if (!BuildConfig.DEBUG || !isAdminSession) return
        viewModelScope.launch {
            _uiState.update { it.copy(busyMessage = "Starting debug seed loader...") }
            val result = DebugSeedService.seedSampleLibrary(
                isAdmin = isAdminSession,
                adminEmail = adminEmail,
                adminService = service(),
                onProgress = { msg -> _uiState.update { it.copy(busyMessage = msg) } },
            )
            result.fold(
                onSuccess = { count ->
                    _uiState.update {
                        it.copy(
                            busyMessage = null,
                            snackbarMessage = "Loaded $count sample books (15 WebP pages each).",
                        )
                    }
                    refreshAll()
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            busyMessage = null,
                            snackbarMessage = err.message ?: "Seed loader failed.",
                        )
                    }
                },
            )
        }
    }

    // endregion
}
