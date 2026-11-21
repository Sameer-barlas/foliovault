package com.example.controllers

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.models.Book
import com.example.models.BookPage
import com.example.services.FirestoreBytesPageImageStore
import com.example.services.FirestoreConfig
import com.example.services.ImageCodecService
import com.example.services.PageImageStore
import com.example.services.PageMemoryCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReaderUiState(
    val book: Book? = null,
    val pages: List<BookPage> = emptyList(),
    val decodedBitmaps: Map<String, Bitmap> = emptyMap(),
    val currentVisibleIndex: Int = 0,
    val initialLoading: Boolean = true,
    val loadingNextBatch: Boolean = false,
    val hasMore: Boolean = true,
    val errorMessage: String? = null,
) {
    val totalDisplayCount: Int
        get() = maxOf(book?.pageCount ?: 0, pages.size)
}

/**
 * Pure pagination coordinator separated for deterministic JVM unit testing of:
 * - 12-page batching boundaries (empty, partial, exact 12, multi-batch)
 * - prefetch trigger within 3 pages of end
 * - zero duplicate reads when scrolling backward or re-entering already fetched ranges
 */
class PagePaginationCoordinator(
    private val store: PageImageStore,
    private val batchSize: Int = FirestoreConfig.PAGE_BATCH_SIZE,
    private val prefetchThreshold: Int = FirestoreConfig.PREFETCH_THRESHOLD_FROM_END,
) {
    private var bookId: String = ""
    private var lastCursor: Double? = null
    private var inFlight: Boolean = false
    private val loadedIds = LinkedHashSet<String>()
    private val loadedPages = mutableListOf<BookPage>()

    var hasMore: Boolean = true
        private set

    fun reset(newBookId: String) {
        bookId = newBookId
        lastCursor = null
        inFlight = false
        hasMore = true
        loadedIds.clear()
        loadedPages.clear()
    }

    fun currentPages(): List<BookPage> = loadedPages.toList()

    fun shouldPrefetch(visibleIndex: Int): Boolean {
        if (!hasMore || inFlight || loadedPages.isEmpty()) return false
        return visibleIndex >= (loadedPages.size - prefetchThreshold)
    }

    suspend fun loadNextBatch(): Result<List<BookPage>> {
        if (bookId.isBlank() || inFlight || !hasMore) {
            return Result.success(loadedPages.toList())
        }
        inFlight = true
        return try {
            val result = store.loadPageBatch(
                bookId = bookId,
                batchSize = batchSize,
                afterOrderIndex = lastCursor,
            )
            result.map { batch ->
                val newlyAdded = batch.pages.filter { loadedIds.add(it.id) }
                loadedPages.addAll(newlyAdded)
                if (batch.lastOrderIndex != null) {
                    lastCursor = batch.lastOrderIndex
                }
                hasMore = batch.hasMore && newlyAdded.isNotEmpty()
                loadedPages.toList()
            }
        } finally {
            inFlight = false
        }
    }
}

class ReaderController(
    application: Application,
) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var coordinator: PagePaginationCoordinator? = null
    private var activeBookId: String = ""

    fun openBook(book: Book) {
        if (activeBookId == book.id && _uiState.value.pages.isNotEmpty()) return

        activeBookId = book.id
        val store = FirestoreBytesPageImageStore(FirestoreConfig.firestore(appContext))
        val newCoordinator = PagePaginationCoordinator(store)
        newCoordinator.reset(book.id)
        coordinator = newCoordinator

        _uiState.value = ReaderUiState(
            book = book,
            pages = emptyList(),
            decodedBitmaps = emptyMap(),
            currentVisibleIndex = 0,
            initialLoading = true,
            loadingNextBatch = false,
            hasMore = true,
            errorMessage = null,
        )

        fetchNextBatch(isInitial = true)
    }

    fun onVisiblePageChanged(visibleIndex: Int) {
        _uiState.update { it.copy(currentVisibleIndex = visibleIndex.coerceAtLeast(0)) }
        if (coordinator?.shouldPrefetch(visibleIndex) == true) {
            fetchNextBatch(isInitial = false)
        }
    }

    fun retryLoad() {
        fetchNextBatch(isInitial = _uiState.value.pages.isEmpty())
    }

    private fun fetchNextBatch(isInitial: Boolean) {
        val activeCoordinator = coordinator ?: return
        if (!activeCoordinator.hasMore) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    initialLoading = isInitial,
                    loadingNextBatch = !isInitial,
                    errorMessage = null,
                )
            }
            val result = activeCoordinator.loadNextBatch()
            result.fold(
                onSuccess = { allPages ->
                    _uiState.update {
                        it.copy(
                            pages = allPages,
                            initialLoading = false,
                            loadingNextBatch = false,
                            hasMore = activeCoordinator.hasMore,
                            errorMessage = null,
                        )
                    }
                    decodeVisibleAndLoadedPages(activeBookId, allPages)
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            initialLoading = false,
                            loadingNextBatch = false,
                            errorMessage = err.message ?: "Unable to load book pages.",
                        )
                    }
                },
            )
        }
    }

    private fun decodeVisibleAndLoadedPages(bookId: String, pages: List<BookPage>) {
        pages.forEach { page ->
            if (_uiState.value.decodedBitmaps.containsKey(page.id)) return@forEach
            val bytes = page.imageBytes?.toBytes() ?: return@forEach
            viewModelScope.launch {
                val bmp = ImageCodecService.decodeToMemoryCache(
                    cacheKey = PageMemoryCache.pageKey(bookId, page.id),
                    bytes = bytes,
                )
                if (bmp != null && activeBookId == bookId) {
                    _uiState.update { state ->
                        state.copy(decodedBitmaps = state.decodedBitmaps + (page.id to bmp))
                    }
                }
            }
        }
    }

    /**
     * Clears RAM bitmap cache for this book when leaving the reader (Locked Decision #5).
     */
    fun closeBook() {
        if (activeBookId.isNotBlank()) {
            PageMemoryCache.removeBookPages(activeBookId)
        }
        activeBookId = ""
        coordinator = null
        _uiState.value = ReaderUiState(initialLoading = false, hasMore = false)
    }

    override fun onCleared() {
        closeBook()
        super.onCleared()
    }
}
