package com.sharefiles.app.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {
    @Test
    fun decodesPairLinkFromLuminanceWithRowStride() {
        val text = "sharefiles://pair?id=abc&name=PC&fp=${"a".repeat(64)}&secret=${"x".repeat(43)}&port=48920&addrs=192.168.50.211"
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 600, 600)
        val stride = 640
        val plane = ByteArray(stride * 600) { -1 }
        for (y in 0 until 600) {
            for (x in 0 until 600) {
                plane[y * stride + x] = if (matrix.get(x, y)) 0 else -1
            }
        }

        assertEquals(text, QrDecoder().decode(plane, stride, 600, 600))
    }

    @Test
    fun returnsNullWithoutACode() {
        assertNull(QrDecoder().decode(ByteArray(100 * 100) { 80 }, 100, 100, 100))
    }
}
