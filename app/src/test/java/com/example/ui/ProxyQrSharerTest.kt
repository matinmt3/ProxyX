package com.example.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProxyQrSharerTest {
    @get:Rule val folder = TemporaryFolder()

    private fun context(): Context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getCacheDir(): File = folder.root
    }

    @Test fun consecutiveSharesKeepBothOriginalQrImages() = runBlocking {
        val context = context()
        val firstUrl = "tg://proxy?server=first.example&port=443&secret=0123456789abcdef0123456789abcdef"
        val secondUrl = firstUrl.replace("first.example", "second.example")
        val first = ProxyQrSharer.createShareFile(context, firstUrl)
        val second = ProxyQrSharer.createShareFile(context, secondUrl)

        assertNotEquals(first, second)
        assertEquals(firstUrl, decode(first))
        assertEquals(secondUrl, decode(second))
    }

    @Test fun cleanupRetainsRecentSharesAndRemovesExpiredImages() = runBlocking {
        val directory = File(folder.root, "proxy-qrcodes").apply { mkdirs() }
        val expired = File(directory, "expired.png").apply {
            writeText("old image")
            assertTrue(setLastModified(System.currentTimeMillis() - 2 * 86_400_000L))
        }
        val recent = File(directory, "recent.png").apply { writeText("recent image") }
        ProxyQrSharer.createShareFile(context(), "tg://proxy?server=test.example&port=443&secret=0123456789abcdef0123456789abcdef")

        assertFalse(expired.exists())
        assertTrue(recent.exists())
    }

    private fun decode(file: File): String {
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path))
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
        } finally {
            bitmap.recycle()
        }
    }
}
