package com.winiks.posprint

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class WiniksPrintService : PrintService() {

    companion object {
        private const val TAG = "WiniksPrintService"
        const val PREFS_NAME = "winiks_pos_print_prefs"
        const val KEY_MAC = "pref_printer_mac"
        const val KEY_NAME = "pref_printer_name"
        const val KEY_SIZE = "pref_paper_size" // "58" or "80"
        const val KEY_AUTO_CUT = "pref_auto_cut"
        const val KEY_FEED_LINES = "pref_feed_lines"
        const val KEY_CASH_DRAWER = "pref_cash_drawer"
        const val KEY_SCALE = "pref_width_scale" // 80 to 110
        const val KEY_DISCONNECT_DELAY = "pref_disconnect_delay"
    }

    override fun onConnected() {
        super.onConnected()
        Log.i(TAG, "WiniksPrintService: onConnected() - Successfully bound to Android Print Spooler")
    }

    override fun onDisconnected() {
        super.onDisconnected()
        Log.i(TAG, "WiniksPrintService: onDisconnected() - Unbound from Android Print Spooler")
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        return object : PrinterDiscoverySession() {
            private fun buildPrinterInfo(printerId: PrinterId, displayName: String, paperSize: String): PrinterInfo {
                val is80mm = paperSize != "58"
                return PrinterInfo.Builder(
                    printerId,
                    displayName,
                    PrinterInfo.STATUS_IDLE
                )
                    .setDescription("WINIKS Enterprise Direct Bluetooth Thermal Driver")
                    .setCapabilities(
                        PrinterCapabilitiesInfo.Builder(printerId)
                            .addMediaSize(PrintAttributes.MediaSize("ROLL_80MM", "80mm Roll (Thermal)", 3150, 11811), is80mm)
                            .addMediaSize(PrintAttributes.MediaSize("ROLL_58MM", "58mm Roll (Thermal)", 2283, 11811), !is80mm)
                            .addMediaSize(PrintAttributes.MediaSize.ISO_A4, false)
                            .addResolution(PrintAttributes.Resolution("203DPI", "203 dpi", 203, 203), true)
                            .setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME)
                            .setMinMargins(PrintAttributes.Margins(0, 0, 0, 0))
                            .build()
                    )
                    .build()
            }

            @SuppressLint("MissingPermission")
            private fun getDiscoveredPrinters(): List<PrinterInfo> {
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val prefName = prefs.getString(KEY_NAME, "WINIKS Thermal POS") ?: "WINIKS Thermal POS"
                val prefMac = prefs.getString(KEY_MAC, "") ?: ""
                val paperSize = prefs.getString(KEY_SIZE, "80") ?: "80"

                val list = mutableListOf<PrinterInfo>()

                // 1. Configured Preferred Printer
                if (prefMac.isNotBlank()) {
                    val pId = generatePrinterId(prefMac)
                    list.add(buildPrinterInfo(pId, "$prefName ($prefMac)", paperSize))
                }

                // 2. Bonded Bluetooth Devices
                try {
                    val btAdapter = BluetoothAdapter.getDefaultAdapter()
                    if (btAdapter != null && btAdapter.isEnabled) {
                        for (dev in btAdapter.bondedDevices) {
                            if (dev.address != prefMac) {
                                val pId = generatePrinterId(dev.address)
                                val dName = dev.name ?: "Thermal POS"
                                list.add(buildPrinterInfo(pId, "$dName (${dev.address})", paperSize))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not enumerate bonded Bluetooth devices", e)
                }

                // 3. Fallback printer if list is empty
                if (list.isEmpty()) {
                    val fallbackId = generatePrinterId("winiks_default_thermal")
                    list.add(buildPrinterInfo(fallbackId, prefName, paperSize))
                }

                return list
            }

            override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
                addPrinters(getDiscoveredPrinters())
            }

            override fun onStopPrinterDiscovery() {}

            override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
                addPrinters(getDiscoveredPrinters())
            }

            override fun onStartPrinterStateTracking(printerId: PrinterId) {
                addPrinters(getDiscoveredPrinters())
            }

            override fun onStopPrinterStateTracking(printerId: PrinterId) {}
            override fun onDestroy() {}
        }
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        Handler(Looper.getMainLooper()).post {
            try {
                printJob.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling print job", e)
            }
        }
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        if (!printJob.isQueued) return

        // 1. MUST start job synchronously on main thread so getDocument() is valid!
        val started = try {
            printJob.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start print job on main thread", e)
            false
        }

        if (!started && !printJob.isStarted) {
            Log.w(TAG, "printJob could not be transitioned to started (state: ${printJob.info.state})")
            return
        }

        Thread {
            try {
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                var mac = prefs.getString(KEY_MAC, "") ?: ""
                val paperSize = prefs.getString(KEY_SIZE, "80") ?: "80"
                val autoCut = prefs.getBoolean(KEY_AUTO_CUT, true)
                val feedLines = prefs.getInt(KEY_FEED_LINES, 2)
                val openDrawer = prefs.getBoolean(KEY_CASH_DRAWER, false)
                val scalePercent = prefs.getInt(KEY_SCALE, 100)
                val disconnectDelay = prefs.getInt(KEY_DISCONNECT_DELAY, 2000).toLong()

                // Fallback: If no preferred printer is set in prefs, extract MAC from printerId
                if (mac.isBlank()) {
                    val localId = printJob.info.printerId?.localId ?: ""
                    if (localId.isNotBlank() && BluetoothAdapter.checkBluetoothAddress(localId)) {
                        mac = localId
                    }
                }

                if (mac.isBlank()) {
                    Handler(Looper.getMainLooper()).post {
                        try {
                            printJob.fail("No Bluetooth printer selected. Please open WINIKS POS Print to select printer.")
                        } catch (_: Exception) {}
                    }
                    return@Thread
                }

                val doc = printJob.document
                val data = doc?.data ?: run {
                    Log.e(TAG, "Document data descriptor is null")
                    Handler(Looper.getMainLooper()).post {
                        try {
                            printJob.fail("Document data is null")
                        } catch (_: Exception) {}
                    }
                    return@Thread
                }

                val targetWidthDots = if (paperSize == "58") 384 else 576
                val finalWidthDots = (targetWidthDots * (scalePercent / 100.0)).toInt()

                val combinedPayload = ByteArrayOutputStream()

                // 1. Kick Cash Drawer if configured
                if (openDrawer) {
                    combinedPayload.write(EscPosHelper.OPEN_CASH_DRAWER)
                }

                // 2. Stream incoming pipe to a seekable temporary cache file (Mandatory for Android PdfRenderer)
                val tempFile = File(cacheDir, "job_${System.currentTimeMillis()}.pdf")
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(data).use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    if (tempFile.length() == 0L) {
                        Log.e(TAG, "Streamed PDF file is empty (0 bytes)")
                        Handler(Looper.getMainLooper()).post {
                            try { printJob.fail("Printed document is empty") } catch (_: Exception) {}
                        }
                        return@Thread
                    }

                    val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    val pdfRenderer = PdfRenderer(pfd)

                    for (pageIndex in 0 until pdfRenderer.pageCount) {
                        val page = pdfRenderer.openPage(pageIndex)
                        val aspectRatio = page.height.toFloat() / page.width.toFloat()
                        val renderHeight = (finalWidthDots * aspectRatio).toInt()

                        val bitmap = Bitmap.createBitmap(finalWidthDots, renderHeight, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(Color.WHITE) // Ensure opaque white background
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        page.close()

                        val rasterBytes = EscPosHelper.bitmapToEscPosRaster(bitmap, finalWidthDots)
                        combinedPayload.write(rasterBytes)
                        bitmap.recycle()
                    }

                    pdfRenderer.close()
                    pfd.close()
                } finally {
                    try { tempFile.delete() } catch (_: Exception) {}
                }

                // 3. Feed blank lines
                combinedPayload.write(EscPosHelper.feedLines(feedLines))

                // 4. Auto Cut if enabled
                if (autoCut) {
                    combinedPayload.write(EscPosHelper.FULL_CUT)
                }

                // 5. Transmit to Thermal Printer via Bluetooth
                val success = BluetoothPrintManager.sendBytesToPrinter(
                    this,
                    mac,
                    combinedPayload.toByteArray(),
                    disconnectDelay
                )

                Handler(Looper.getMainLooper()).post {
                    if (success) {
                        try {
                            printJob.complete()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error completing print job", e)
                        }
                        Log.i(TAG, "Print job completed successfully")
                    } else {
                        val err = "Bluetooth transmission failed. Ensure printer is ON and paired."
                        try {
                            printJob.fail(err)
                        } catch (_: Exception) {}
                        Toast.makeText(applicationContext, "WINIKS Print: $err", Toast.LENGTH_LONG).show()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error executing print job", e)
                val errMsg = e.message ?: "Unknown print error"
                Handler(Looper.getMainLooper()).post {
                    try {
                        printJob.fail(errMsg)
                    } catch (_: Exception) {}
                    Toast.makeText(applicationContext, "WINIKS Print Error: $errMsg", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
