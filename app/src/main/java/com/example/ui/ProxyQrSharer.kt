package com.example.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object ProxyQrSharer {
    suspend fun share(context: Context, proxyUrl: String) {
        val file = createShareFile(context, proxyUrl)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, proxyUrl)
            clipData = ClipData.newRawUri("ProxyX MTProto QR", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        withContext(Dispatchers.Main.immediate) {
            context.startActivity(
                Intent.createChooser(sendIntent, "Share MTProto QR")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    internal suspend fun createShareFile(context: Context, proxyUrl: String): File =
        withContext(Dispatchers.IO) {
            val matrix = QRCodeWriter().encode(
                proxyUrl,
                BarcodeFormat.QR_CODE,
                QR_SIZE,
                QR_SIZE,
                mapOf(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN to 2,
                    EncodeHintType.CHARACTER_SET to "UTF-8"
                )
            )
            val pixels = IntArray(QR_SIZE * QR_SIZE)
            for (y in 0 until QR_SIZE) {
                for (x in 0 until QR_SIZE) {
                    pixels[y * QR_SIZE + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            val bitmap = createBitmap(QR_SIZE, QR_SIZE)
            try {
                bitmap.setPixels(pixels, 0, QR_SIZE, 0, 0, QR_SIZE, QR_SIZE)
                val directory = File(context.cacheDir, "proxy-qrcodes").apply { mkdirs() }
                val cutoff = System.currentTimeMillis() - SHARE_RETENTION_MS
                directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach(File::delete)
                // Every granted URI must keep pointing to its original proxy image.
                val output = File.createTempFile("ProxyX-proxy-", ".png", directory)
                try {
                    FileOutputStream(output).use { stream ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                    }
                    output
                } catch (error: Exception) {
                    output.delete()
                    throw error
                }
            } finally {
                bitmap.recycle()
            }
        }

    private const val QR_SIZE = 768
    private const val SHARE_RETENTION_MS = 24 * 60 * 60 * 1_000L
}
