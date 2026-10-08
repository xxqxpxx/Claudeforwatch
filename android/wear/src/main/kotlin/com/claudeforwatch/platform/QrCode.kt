package com.claudeforwatch.platform

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders [text] (the ~450-char authorize URL) as a QR bitmap at [sizePx] native pixels.
 * Low error correction keeps modules large enough to scan from a watch (PROTOCOL §2).
 */
object QrCode {
    fun render(text: String, sizePx: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            val row = y * matrix.width
            for (x in 0 until matrix.width) pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }
}
