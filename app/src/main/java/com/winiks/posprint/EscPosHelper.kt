package com.winiks.posprint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EscPosHelper {

    // ESC/POS Command Constants
    val INIT_PRINTER = byteArrayOf(0x1B, 0x40) // Initialize (ESC @)
    val FULL_CUT = byteArrayOf(0x1D, 0x56, 0x00) // Full Cut (GS V 0)
    val PARTIAL_CUT = byteArrayOf(0x1D, 0x56, 0x01) // Partial Cut (GS V 1)
    val OPEN_CASH_DRAWER = byteArrayOf(0x1B, 0x70, 0x00, 0x19, 0xFA.toByte()) // Kick drawer pin 2

    fun feedLines(n: Int): ByteArray {
        val lines = n.coerceIn(1, 10).toByte()
        return byteArrayOf(0x1B, 0x64, lines) // ESC d n
    }

    /**
     * Converts any Android Bitmap into monochrome 1-bit ESC/POS Raster Bit Image (GS v 0).
     * Slices the bitmap into micro-strips (max 128 vertical dots) to ensure 100% compatibility
     * with low-cost thermal POS printer hardware buffers and prevent RFCOMM Bluetooth drops.
     * Accurately handles alpha transparency (treats transparent as white background).
     */
    fun bitmapToEscPosRaster(bitmap: Bitmap, targetWidthDots: Int = 576): ByteArray {
        val scale = targetWidthDots.toFloat() / bitmap.width.toFloat()
        val scaledHeight = (bitmap.height * scale).toInt()
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetWidthDots, scaledHeight, true)

        val width = scaledBitmap.width
        val height = scaledBitmap.height
        val widthBytes = (width + 7) / 8

        val out = ByteArrayOutputStream()
        out.write(INIT_PRINTER)

        val pixels = IntArray(width * height)
        scaledBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Micro-strip slicing (128 dots max per ESC/POS raster block)
        val maxSliceHeight = 128
        val threshold = 180

        for (startY in 0 until height step maxSliceHeight) {
            val currentSliceHeight = (height - startY).coerceAtMost(maxSliceHeight)

            // GS v 0 m xL xH yL yH
            val xL = (widthBytes and 0xFF).toByte()
            val xH = ((widthBytes shr 8) and 0xFF).toByte()
            val yL = (currentSliceHeight and 0xFF).toByte()
            val yH = ((currentSliceHeight shr 8) and 0xFF).toByte()

            val header = byteArrayOf(0x1D, 0x76, 0x30, 0x00, xL, xH, yL, yH)
            out.write(header)

            val sliceData = ByteArray(widthBytes * currentSliceHeight)
            var byteIndex = 0

            for (y in startY until (startY + currentSliceHeight)) {
                var currentByte = 0
                var bitCount = 0

                for (x in 0 until width) {
                    val pixel = pixels[y * width + x]
                    val a = (pixel shr 24) and 0xFF
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF

                    // Transparent pixels (a < 128) are strictly background (WHITE - no thermal burn dot)
                    if (a >= 128) {
                        val luminance = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
                        if (luminance < threshold) {
                            currentByte = currentByte or (1 shl (7 - bitCount))
                        }
                    }

                    bitCount++
                    if (bitCount == 8) {
                        sliceData[byteIndex++] = currentByte.toByte()
                        currentByte = 0
                        bitCount = 0
                    }
                }

                if (bitCount > 0) {
                    sliceData[byteIndex++] = currentByte.toByte()
                }
            }

            out.write(sliceData)
        }

        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }

        return out.toByteArray()
    }

    /**
     * Generates a sleek, branded test receipt bitmap for self-testing.
     */
    fun createTestReceiptBitmap(widthDots: Int = 576): Bitmap {
        val height = 480
        val bitmap = Bitmap.createBitmap(widthDots, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint().apply {
            color = Color.BLACK
            isAntiAlias = true
        }

        // Header
        paint.textSize = 34f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("WINIKS ENTERPRISE", (widthDots / 2).toFloat(), 60f, paint)

        paint.textSize = 24f
        paint.typeface = Typeface.DEFAULT
        canvas.drawText("POS Thermal Print Service v2.0", (widthDots / 2).toFloat(), 100f, paint)
        canvas.drawText("Status: Connected & Ready", (widthDots / 2).toFloat(), 135f, paint)

        // Divider
        paint.strokeWidth = 3f
        canvas.drawLine(20f, 160f, (widthDots - 20).toFloat(), 160f, paint)

        // Details
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 22f
        val sdf = SimpleDateFormat("dd-MMM-yyyy hh:mm a", Locale.getDefault())
        canvas.drawText("Date: ${sdf.format(Date())}", 30f, 200f, paint)
        canvas.drawText("Paper Size: ${if (widthDots >= 576) "80mm (3 Inch)" else "58mm (2 Inch)"}", 30f, 235f, paint)
        canvas.drawText("Resolution: 203 DPI Hardware Print", 30f, 270f, paint)
        canvas.drawText("Marathi & English 100% OK", 30f, 305f, paint)

        // Divider
        canvas.drawLine(20f, 330f, (widthDots - 20).toFloat(), 330f, paint)

        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 26f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("TEST PRINT SUCCESSFUL!", (widthDots / 2).toFloat(), 380f, paint)

        paint.textSize = 18f
        paint.typeface = Typeface.DEFAULT
        canvas.drawText("Winiks Enterprise Wholesale ERP", (widthDots / 2).toFloat(), 420f, paint)

        return bitmap
    }
}
