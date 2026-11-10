package com.winiks.printservice

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

    // Standard SPP (Serial Port Profile) UUID used by all Bluetooth Thermal Printers
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @SuppressLint("MissingPermission")
    fun sendBytesToPrinter(
        context: Context,
        printerMacAddress: String,
        data: ByteArray,
        disconnectDelayMs: Long = 4000
    ): Boolean {
        if (printerMacAddress.isBlank()) {
            Log.e(TAG, "Printer MAC address is blank")
            return false
        }

        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        if (!adapter.isEnabled) {
            Log.e(TAG, "Bluetooth is disabled")
            return false
        }

        var socket: BluetoothSocket? = null
        var outputStream: OutputStream? = null

        return try {
            val device: BluetoothDevice = adapter.getRemoteDevice(printerMacAddress)

            // Cancel discovery before connecting as it slows down connection
            try { adapter.cancelDiscovery() } catch (_: Exception) {}

            // Try 1: Insecure RFCOMM (compatible with 99% of POS Bluetooth printers)
            socket = try {
                device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            } catch (e1: Exception) {
                try {
                    device.createRfcommSocketToServiceRecord(SPP_UUID)
                } catch (e2: Exception) {
                    val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    m.invoke(device, 1) as BluetoothSocket
                }
            }

            try {
                socket.connect()
            } catch (connEx: Exception) {
                Log.w(TAG, "Primary connect failed, trying reflection fallback: ${connEx.message}")
                try {
                    socket.close()
                } catch (_: Exception) {}
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                socket = m.invoke(device, 1) as BluetoothSocket
                socket.connect()
            }

            outputStream = socket.outputStream

            // Send in safe 512-byte chunks to prevent Bluetooth buffer overflow on thermal printers
            val chunkSize = 512
            var offset = 0
            while (offset < data.size) {
                val length = (data.size - offset).coerceAtMost(chunkSize)
                outputStream.write(data, offset, length)
                outputStream.flush()
                offset += length
                if (offset < data.size) {
                    Thread.sleep(15) // Micro-pause to let thermal head burn pixels
                }
            }

            // Keep socket alive for disconnectDelayMs to prevent premature cancellation
            if (disconnectDelayMs > 0) {
                Thread.sleep(disconnectDelayMs)
            }

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

