package com.winiks.printservice

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
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

    override fun onConnected() {
        super.onConnected()
        Log.i(TAG, "WiniksPrintService: onConnected() - Service bound to Android Print Spooler")
    }

    override fun onDisconnected() {
        super.onDisconnected()
        Log.i(TAG, "WiniksPrintService: onDisconnected() - Service unbound from Android Print Spooler")
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        return object : PrinterDiscoverySession() {
            private fun buildPrinterInfo(printerId: PrinterId, displayName: String, paperSize: String): PrinterInfo {
                return PrinterInfo.Builder(
                    printerId,
                    displayName,
                    PrinterInfo.STATUS_IDLE
                )
                    .setDescription("WINIKS Enterprise Direct Bluetooth Thermal Driver")
                    .setCapabilities(
                        android.print.PrinterCapabilitiesInfo.Builder(printerId)
                            .addMediaSize(PrintAttributes.MediaSize("ROLL_80MM", "80mm Roll", 3150, 11811), paperSize != "58")
                            .addMediaSize(PrintAttributes.MediaSize("ROLL_58MM", "58mm Roll", 2283, 11811), paperSize == "58")
                            .addMediaSize(PrintAttributes.MediaSize.ISO_A4, false)
                            .addResolution(PrintAttributes.Resolution("203DPI", "203 dpi", 203, 203), true)
                            .setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME)
                            .setMinMargins(PrintAttributes.Margins(0, 0, 0, 0))
                            .build()
                    )
                    .build()
            }

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
                    val btAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                    if (btAdapter != null && btAdapter.isEnabled) {
                        for (dev in btAdapter.bondedDevices) {
                            if (dev.address != prefMac) {
                                val pId = generatePrinterId(dev.address)
                                val dName = dev.name ?: "Thermal Printer"
                                list.add(buildPrinterInfo(pId, "$dName (${dev.address})", paperSize))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error enumerating bonded bluetooth printers", e)
                }

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

        Handler(Looper.getMainLooper()).post {
            try {
                if (printJob.isQueued) {
                    printJob.start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting print job on main thread", e)
            }
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
                val disconnectDelay = prefs.getInt(KEY_DISCONNECT_DELAY, 4000).toLong()

                // Fallback: If no preferred printer is set in prefs, use the localId from PrinterId
                if (mac.isBlank()) {
                    val localId = printJob.info.printerId.localId
                    if (android.bluetooth.BluetoothAdapter.checkBluetoothAddress(localId)) {
                        mac = localId
                    }
                }

                if (mac.isBlank()) {
                    Handler(Looper.getMainLooper()).post {
                        try {
                            printJob.fail("No Bluetooth printer selected. Please pair in WINIKS Print Service.")
                        } catch (_: Exception) {}
                    }
                    return@Thread
                }

                val doc = printJob.document
                val data = doc?.data ?: run {
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

                // 1. Kick Cash Drawer before printing if enabled
                if (openDrawer) {
                    combinedPayload.write(EscPosHelper.OPEN_CASH_DRAWER)
                }

                // 2. Stream incoming pipe to a seekable temporary cache file (Required for PdfRenderer on Android)
                val tempFile = File(cacheDir, "job_${System.currentTimeMillis()}.pdf")
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(data).use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
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
                } finally {
                    try { tempFile.delete() } catch (_: Exception) {}
                }

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

