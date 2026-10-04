package com.safechild.ai.utils

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

object QrCodeUtils {

    const val QR_PREFIX = "SAFECHILD_PAIR_V1:"

    /**
     * Constructs a secure QR string containing the minimum pairing token.
     */
    fun createQrString(requestId: String): String {
        return "$QR_PREFIX$requestId"
    }

    /**
     * Extracts the requestId from a scanned QR string payload if valid.
     */
    fun extractRequestId(qrString: String): String? {
        return if (qrString.startsWith(QR_PREFIX)) {
            val id = qrString.substringAfter(QR_PREFIX).trim()
            if (id.isNotEmpty()) id else null
        } else {
            null
        }
    }

    /**
     * Generates an Android Bitmap for a given QR string payload using ZXing.
     */
    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? {
        if (content.isEmpty()) return null
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)

            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
