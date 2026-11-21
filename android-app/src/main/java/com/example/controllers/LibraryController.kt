package com.example.controllers

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.models.Book
import com.example.services.BookService
import com.example.services.FirestoreConfig
import com.example.services.ImageCodecService
import com.example.services.PageMemoryCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val books: List<Book> = emptyList(),
    val searchQuery: String = "",
    val coverBitmaps: Map<String, Bitmap> = emptyMap(),
    val errorMessage: String? = null,
) {
    val filteredBooks: List<Book>
        get() {
            val q = searchQuery.trim()
            if (q.isEmpty()) return books
            return books.filter {
                it.title.contains(q, ignoreCase = true) ||
                    it.author.contains(q, ignoreCase = true) ||
                    it.category.contains(q, ignoreCase = true)
            }
        }
}

class LibraryController(
    application: Application,
) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private var activeEmail: String = ""
    private var previewAllBooks: Boolean = false

    fun load(userEmail: String, adminPreviewAll: Boolean = false, isRefresh: Boolean = false) {
        activeEmail = userEmail
        previewAllBooks = adminPreviewAll

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    loading = !isRefresh && it.books.isEmpty(),
                    refreshing = isRefresh,
                    errorMessage = null,
                )
            }
            if (!FirestoreConfig.isFirebaseConfigured(appContext)) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        errorMessage = "Firebase is not configured.",
                    )
                }
                return@launch
            }

            val service = BookService(FirestoreConfig.firestore(appContext))
            val result = if (adminPreviewAll) {
                service.fetchAllBooksForAdmin()
            } else {
                service.fetchAssignedBooksForReader(userEmail)
            }

            result.fold(
                onSuccess = { books ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            books = books,
                            errorMessage = null,
                        )
                    }
                    decodeBookCovers(books)
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            errorMessage = err.message ?: "Unable to load your library.",
                        )
                    }
                },
            )
        }
    }

    fun refresh() {
        load(activeEmail, previewAllBooks, isRefresh = true)
    }

    fun updateSearch(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    private fun decodeBookCovers(books: List<Book>) {
        books.forEach { book ->
            val raw = book.coverBytes?.toBytes() ?: return@forEach
            viewModelScope.launch {
                val bmp = ImageCodecService.decodeToMemoryCache(
                    cacheKey = PageMemoryCache.coverKey(book.id),
                    bytes = raw,
                )
                if (bmp != null) {
                    _uiState.update { state ->
                        state.copy(coverBitmaps = state.coverBitmaps + (book.id to bmp))
                    }
                }
            }
        }
    }
}
