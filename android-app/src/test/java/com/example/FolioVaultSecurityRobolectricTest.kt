package com.example

import android.graphics.Bitmap
import android.view.WindowManager
import com.example.services.PageMemoryCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FolioVaultSecurityRobolectricTest {

    @Test
    fun mainActivity_setsFlagSecureOnWindow() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val flags = activity.window.attributes.flags
        assertTrue(
            "FLAG_SECURE must be active on MainActivity window",
            (flags and WindowManager.LayoutParams.FLAG_SECURE) != 0,
        )
        controller.pause().stop().destroy()
    }

    @Test
    fun pageMemoryCache_clearsOnBookExitAndFullRevocation() {
        PageMemoryCache.clear()
        val bmp1 = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val bmp2 = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val cover = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)

        PageMemoryCache.put(PageMemoryCache.pageKey("bookA", "p1"), bmp1)
        PageMemoryCache.put(PageMemoryCache.pageKey("bookB", "p1"), bmp2)
        PageMemoryCache.put(PageMemoryCache.coverKey("bookA"), cover)

        assertNotNull(PageMemoryCache.get(PageMemoryCache.pageKey("bookA", "p1")))
        assertEquals(3, PageMemoryCache.entryCount())

        // Leaving bookA clears only bookA pages
        PageMemoryCache.removeBookPages("bookA")
        assertNull(PageMemoryCache.get(PageMemoryCache.pageKey("bookA", "p1")))
        assertNotNull(PageMemoryCache.get(PageMemoryCache.pageKey("bookB", "p1")))
        assertNotNull(PageMemoryCache.get(PageMemoryCache.coverKey("bookA")))

        // Sign-out / live revocation clears entire RAM cache
        PageMemoryCache.clear()
        assertEquals(0, PageMemoryCache.entryCount())
        assertNull(PageMemoryCache.get(PageMemoryCache.pageKey("bookB", "p1")))
        assertNull(PageMemoryCache.get(PageMemoryCache.coverKey("bookA")))
    }
}
