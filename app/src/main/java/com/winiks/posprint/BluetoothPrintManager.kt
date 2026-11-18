package com.winiks.posprint

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.OutputStream
import java.util.UUID

object BluetoothPrintManager {
    private const val TAG = "WiniksBT"

    // Standard SPP (Serial Port Profile) UUID used by Bluetooth Thermal POS Printers
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @SuppressLint("MissingPermission")
    fun sendBytesToPrinter(
        context: Context,
        printerMacAddress: String,
        data: ByteArray,
        disconnectDelayMs: Long = 2000
    ): Boolean {
        if (printerMacAddress.isBlank()) {
            Log.e(TAG, "Printer MAC address is blank")
            return false
        }

        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            Log.e(TAG, "Device does not support Bluetooth")
            return false
        }

        if (!adapter.isEnabled) {
            Log.e(TAG, "Bluetooth is disabled")
            return false
        }

        var socket: BluetoothSocket? = null
        var outputStream: OutputStream? = null

        return try {
            val device: BluetoothDevice = adapter.getRemoteDevice(printerMacAddress)

            // Cancel discovery before connecting as active scanning degrades RFCOMM throughput
            try { adapter.cancelDiscovery() } catch (_: Exception) {}

            // Strategy 1: Insecure RFCOMM (standard POS printer compatibility)
            var connectedSocket: BluetoothSocket? = null
            try {
                val s = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                s.connect()
                connectedSocket = s
            } catch (e1: Exception) {
                Log.w(TAG, "Insecure RFCOMM failed (${e1.message}), trying secure RFCOMM...")
                try {
                    val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                    s.connect()
                    connectedSocket = s
                } catch (e2: Exception) {
                    Log.w(TAG, "Secure RFCOMM failed (${e2.message}), trying reflection channel 1...")
                    try {
                        val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        val s = m.invoke(device, 1) as? BluetoothSocket
                        s?.connect()
                        connectedSocket = s
                    } catch (e3: Exception) {
                        Log.e(TAG, "All RFCOMM connection strategies failed", e3)
                    }
                }
            }

            socket = connectedSocket ?: return false
            outputStream = socket.outputStream

            // Safe 512-byte chunk streaming to prevent printer buffer overflow
            val chunkSize = 512
            var offset = 0
            while (offset < data.size) {
                val length = (data.size - offset).coerceAtMost(chunkSize)
                outputStream.write(data, offset, length)
                outputStream.flush()
                offset += length
                if (offset < data.size) {
                    Thread.sleep(15) // Micro-pause for hardware printhead
                }
            }

            // Brief hold to allow printer buffer to complete burning
            if (disconnectDelayMs > 0) {
                Thread.sleep(disconnectDelayMs)
            }

            Log.i(TAG, "Successfully sent ${data.size} bytes to printer $printerMacAddress")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to print via Bluetooth to $printerMacAddress", e)
            false
        } finally {
            try { outputStream?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
        }
    }
}
