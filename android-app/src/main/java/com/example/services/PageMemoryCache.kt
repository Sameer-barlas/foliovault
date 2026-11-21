package com.example.services

import android.graphics.Bitmap
import android.util.LruCache

/**
 * RAM-only decoded bitmap cache for book covers and pages.
 * Never persists bytes to disk and is flushed on reader exit, sign-out, or live revocation.
 */
object PageMemoryCache {
    private const val MAX_CACHE_KB = 48 * 1024 // 48 MiB cap inside JVM heap budget

    private val maxKb: Int = run {
        val runtimeMaxKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
        (runtimeMaxKb / 6).coerceIn(8 * 1024, MAX_CACHE_KB)
    }

    private val cache = object : LruCache<String, Bitmap>(maxKb) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
    }

    fun pageKey(bookId: String, pageId: String): String = "page:$bookId:$pageId"

    fun coverKey(bookId: String): String = "cover:$bookId"

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }

    fun removeBookPages(bookId: String) {
        val prefix = "page:$bookId:"
        val snapshotKeys = cache.snapshot().keys.filter { it.startsWith(prefix) }
        snapshotKeys.forEach { cache.remove(it) }
    }

    fun clear() {
        cache.evictAll()
    }

    fun entryCount(): Int = cache.snapshot().size
}
