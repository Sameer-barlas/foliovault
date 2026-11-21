package com.example

import com.example.controllers.PagePaginationCoordinator
import com.example.models.BookPage
import com.example.services.OrderIndexMath
import com.example.services.PageBatchResult
import com.example.services.PageImageStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePageImageStore(
    private val allPages: List<BookPage>,
) : PageImageStore {
    var batchCallCount: Int = 0
        private set

    override suspend fun loadPageBatch(
        bookId: String,
        batchSize: Int,
        afterOrderIndex: Double?,
    ): Result<PageBatchResult> {
        batchCallCount++
        val filtered = if (afterOrderIndex == null) {
            allPages
        } else {
            allPages.filter { it.orderIndex > afterOrderIndex }
        }
        val slice = filtered.take(batchSize)
        return Result.success(
            PageBatchResult(
                pages = slice,
                lastOrderIndex = slice.lastOrNull()?.orderIndex,
                hasMore = slice.size >= batchSize,
            )
        )
    }

    override suspend fun savePageBytes(
        bookId: String,
        pageId: String?,
        orderIndex: Double,
        webpBytes: ByteArray,
        width: Int,
        height: Int,
        caption: String,
    ): Result<String> = Result.success(pageId ?: "fake")

    override suspend fun deletePageBytes(bookId: String, pageId: String): Result<Int> =
        Result.success(0)
}

class FolioVaultUnitTests {

    private fun fakePages(count: Int): List<BookPage> =
        (1..count).map { idx ->
            BookPage(
                id = "p_$idx",
                orderIndex = idx * 1000.0,
                width = 1080,
                height = 1440,
                caption = "Page $idx",
            )
        }

    @Test
    fun pagination_emptyBook_loadsZeroAndStops() = runTest {
        val store = FakePageImageStore(emptyList())
        val coordinator = PagePaginationCoordinator(store, batchSize = 12, prefetchThreshold = 3)
        coordinator.reset("book_empty")

        val res = coordinator.loadNextBatch().getOrThrow()
        assertEquals(0, res.size)
        assertFalse(coordinator.hasMore)
        assertFalse(coordinator.shouldPrefetch(0))
        assertEquals(1, store.batchCallCount)
    }

    @Test
    fun pagination_exact12Pages_terminatesCleanlyWithoutDuplicates() = runTest {
        val store = FakePageImageStore(fakePages(12))
        val coordinator = PagePaginationCoordinator(store, batchSize = 12, prefetchThreshold = 3)
        coordinator.reset("book_12")

        val batch1 = coordinator.loadNextBatch().getOrThrow()
        assertEquals(12, batch1.size)
        assertTrue(coordinator.hasMore)

        // Prefetch triggers at index 9 (12 - 3)
        assertFalse(coordinator.shouldPrefetch(8))
        assertTrue(coordinator.shouldPrefetch(9))

        val batch2 = coordinator.loadNextBatch().getOrThrow()
        assertEquals(12, batch2.size)
        assertFalse(coordinator.hasMore)

        // Scrolling back never triggers additional reads
        assertFalse(coordinator.shouldPrefetch(2))
        coordinator.loadNextBatch()
        assertEquals(2, store.batchCallCount)
    }

    @Test
    fun pagination_27Pages_loads12Plus12Plus3AndNoDuplicateReadsOnScrollBack() = runTest {
        val store = FakePageImageStore(fakePages(27))
        val coordinator = PagePaginationCoordinator(store, batchSize = 12, prefetchThreshold = 3)
        coordinator.reset("book_27")

        assertEquals(12, coordinator.loadNextBatch().getOrThrow().size)
        assertFalse(coordinator.shouldPrefetch(5))
        assertTrue(coordinator.shouldPrefetch(9))

        assertEquals(24, coordinator.loadNextBatch().getOrThrow().size)
        assertFalse(coordinator.shouldPrefetch(20))
        assertTrue(coordinator.shouldPrefetch(21))

        assertEquals(27, coordinator.loadNextBatch().getOrThrow().size)
        assertFalse(coordinator.hasMore)
        assertEquals(3, store.batchCallCount)

        // Scrolling back to start or end never triggers extra reads
        assertFalse(coordinator.shouldPrefetch(0))
        assertFalse(coordinator.shouldPrefetch(26))
        coordinator.loadNextBatch()
        assertEquals(3, store.batchCallCount)
    }

    @Test
    fun orderIndexMath_insertStartMiddleEndAndRebalanceThreshold() {
        val empty = OrderIndexMath.computeInsertion(emptyList(), OrderIndexMath.InsertPosition.End)
        assertEquals(1000.0, empty.orderIndex, 0.0001)
        assertFalse(empty.requiresRebalance)

        val base = listOf(1000.0, 2000.0, 3000.0)
        val atStart = OrderIndexMath.computeInsertion(base, OrderIndexMath.InsertPosition.Start)
        assertEquals(500.0, atStart.orderIndex, 0.0001)
        assertFalse(atStart.requiresRebalance)

        val atEnd = OrderIndexMath.computeInsertion(base, OrderIndexMath.InsertPosition.End)
        assertEquals(4000.0, atEnd.orderIndex, 0.0001)
        assertFalse(atEnd.requiresRebalance)

        val between = OrderIndexMath.computeInsertion(base, OrderIndexMath.InsertPosition.After(0))
        assertEquals(1500.0, between.orderIndex, 0.0001)
        assertFalse(between.requiresRebalance)

        val beforeLast = OrderIndexMath.computeInsertion(base, OrderIndexMath.InsertPosition.Before(2))
        assertEquals(2500.0, beforeLast.orderIndex, 0.0001)
        assertFalse(beforeLast.requiresRebalance)

        // Tight gap (< 0.001) triggers rebalance flag
        val tight = listOf(1000.0, 1000.001)
        val squeezed = OrderIndexMath.computeInsertion(tight, OrderIndexMath.InsertPosition.After(0))
        assertTrue(squeezed.requiresRebalance)

        val rebalanced = OrderIndexMath.rebalancedIndices(4)
        assertEquals(listOf(1000.0, 2000.0, 3000.0, 4000.0), rebalanced)
    }
}
