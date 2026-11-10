package com.winiks.printservice

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import java.io.ByteArrayOutputStream

class WiniksPrintService : PrintService() {

    companion object {
        private const val TAG = "WiniksPrintService"
        const val PREFS_NAME = "winiks_print_prefs"
        const val KEY_MAC = "pref_printer_mac"
        const val KEY_NAME = "pref_printer_name"
        const val KEY_SIZE = "pref_paper_size" // "58" or "80"
        const val KEY_AUTO_CUT = "pref_auto_cut"
        const val KEY_FEED_LINES = "pref_feed_lines"
        const val KEY_CASH_DRAWER = "pref_cash_drawer"
        const val KEY_SCALE = "pref_width_scale" // 90, 95, 100
        const val KEY_DISCONNECT_DELAY = "pref_disconnect_delay"
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        return object : PrinterDiscoverySession() {
            override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val printerName = prefs.getString(KEY_NAME, "WINIKS Thermal POS") ?: "WINIKS Thermal POS"
                val printerId = generatePrinterId("winiks_default_thermal")

                val printerInfo = PrinterInfo.Builder(
                    printerId,
                    printerName,
                    PrinterInfo.STATUS_IDLE
                )
                    .setDescription("WINIKS Enterprise Direct Bluetooth Thermal Driver")
                    .setCapabilities(
                        android.print.PrinterCapabilitiesInfo.Builder(printerId)
                            .addMediaSize(PrintAttributes.MediaSize.ISO_A4, false)
                            .addMediaSize(PrintAttributes.MediaSize("ROLL_80MM", "80mm Roll", 3150, 11811), true)
                            .addResolution(PrintAttributes.Resolution("203DPI", "203 dpi", 203, 203), true)
                            .setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME)
                            .build()
                    )
                    .build()

                addPrinters(listOf(printerInfo))
            }

            override fun onStopPrinterDiscovery() {}
            override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {}
            override fun onStartPrinterStateTracking(printerId: PrinterId) {}
            override fun onStopPrinterStateTracking(printerId: PrinterId) {}
            override fun onDestroy() {}
        }
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        printJob.cancel()
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        if (!printJob.isQueued) return

        printJob.start()

        Thread {
            try {
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val mac = prefs.getString(KEY_MAC, "") ?: ""
                val paperSize = prefs.getString(KEY_SIZE, "80") ?: "80"
                val autoCut = prefs.getBoolean(KEY_AUTO_CUT, true)
                val feedLines = prefs.getInt(KEY_FEED_LINES, 2)
                val openDrawer = prefs.getBoolean(KEY_CASH_DRAWER, false)
                val scalePercent = prefs.getInt(KEY_SCALE, 100)
                val disconnectDelay = prefs.getInt(KEY_DISCONNECT_DELAY, 4000).toLong()

                if (mac.isBlank()) {
                    printJob.fail("No preferred Bluetooth printer configured in WINIKS Print Service")
                    return@Thread
                }

                val doc = printJob.document
                val data = doc?.data ?: run {
                    printJob.fail("Document data is null")
                    return@Thread
                }

                val targetWidthDots = if (paperSize == "58") 384 else 576
                val finalWidthDots = (targetWidthDots * (scalePercent / 100.0)).toInt()

                val combinedPayload = ByteArrayOutputStream()

                // 1. Kick Cash Drawer before printing if enabled
                if (openDrawer) {
                    combinedPayload.write(EscPosHelper.OPEN_CASH_DRAWER)
                }

                // 2. Render each page of PDF to 1-bit ESC/POS Raster
                val pfd = ParcelFileDescriptor.dup(data.fileDescriptor)
                val pdfRenderer = PdfRenderer(pfd)

                for (pageIndex in 0 until pdfRenderer.pageCount) {
                    val page = pdfRenderer.openPage(pageIndex)
                    val aspectRatio = page.height.toFloat() / page.width.toFloat()
                    val renderHeight = (finalWidthDots * aspectRatio).toInt()

                    val bitmap = Bitmap.createBitmap(finalWidthDots, renderHeight, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    page.close()

                    val rasterBytes = EscPosHelper.bitmapToEscPosRaster(bitmap, finalWidthDots)
                    combinedPayload.write(rasterBytes)
                    bitmap.recycle()
                }

                pdfRenderer.close()
                pfd.close()

                // 3. Feed blank lines
                combinedPayload.write(EscPosHelper.feedLines(feedLines))

                // 4. Auto Cut if enabled
                if (autoCut) {
                    combinedPayload.write(EscPosHelper.FULL_CUT)
                }

                // 5. Send over Bluetooth to the thermal printer
                val success = BluetoothPrintManager.sendBytesToPrinter(
                    this,
                    mac,
                    combinedPayload.toByteArray(),
                    disconnectDelay
                )

                if (success) {
                    printJob.complete()
                    Log.i(TAG, "Print job completed successfully")
                } else {
                    printJob.fail("Bluetooth transmission failed. Ensure printer is turned on and paired.")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error executing print job", e)
                printJob.fail(e.message ?: "Unknown print error")
            }
        }.start()
    }
}

