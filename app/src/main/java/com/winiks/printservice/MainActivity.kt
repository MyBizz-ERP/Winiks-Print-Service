package com.winiks.printservice

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.winiks.printservice.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    companion object {
        private const val REQ_BT_PERMS = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(WiniksPrintService.PREFS_NAME, MODE_PRIVATE)

        checkPermissions()
        loadPreferences()
        setupListeners()
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val perms = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
            val missing = perms.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_BT_PERMS)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun loadPreferences() {
        val mac = prefs.getString(WiniksPrintService.KEY_MAC, "") ?: ""
        val name = prefs.getString(WiniksPrintService.KEY_NAME, "") ?: ""

        if (mac.isNotBlank()) {
            binding.tvPrinterStatus.text = "Connected: $name ($mac)"
            binding.tvPrinterStatus.setTextColor(ContextCompat.getColor(this, R.color.text_accent))
        } else {
            binding.tvPrinterStatus.text = "Status: No Printer Selected"
            binding.tvPrinterStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        when (prefs.getString(WiniksPrintService.KEY_SIZE, "80")) {
            "58" -> binding.rb58mm.isChecked = true
            "104" -> binding.rb104mm.isChecked = true
            else -> binding.rb80mm.isChecked = true
        }

        binding.switchAutoCut.isChecked = prefs.getBoolean(WiniksPrintService.KEY_AUTO_CUT, true)
        binding.switchCashDrawer.isChecked = prefs.getBoolean(WiniksPrintService.KEY_CASH_DRAWER, false)

        val feedLines = prefs.getInt(WiniksPrintService.KEY_FEED_LINES, 2)
        binding.sbFeedLines.progress = feedLines
        binding.tvFeedLines.text = "Blank Lines to Feed before Cut: $feedLines"

        val scale = prefs.getInt(WiniksPrintService.KEY_SCALE, 100)
        binding.sbWidthScale.progress = scale - 80 // 80..110 mapped to 0..30
        binding.tvWidthScale.text = "Width Scale: $scale%"
    }

    @SuppressLint("MissingPermission", "SetTextI18n")
    private fun setupListeners() {
        binding.btnSetPrinter.setOnClickListener {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!adapter.isEnabled) {
                Toast.makeText(this, "Please enable Bluetooth first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val pairedDevices: Set<BluetoothDevice> = adapter.bondedDevices ?: emptySet()
            if (pairedDevices.isEmpty()) {
                Toast.makeText(this, "No paired Bluetooth devices found. Pair printer in Android Bluetooth settings first.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val deviceNames = pairedDevices.map { "${it.name ?: "Unknown"} (${it.address})" }.toTypedArray()
            val deviceMacs = pairedDevices.map { it.address }.toTypedArray()

            AlertDialog.Builder(this)
                .setTitle("Select Preferred Thermal Printer")
                .setItems(deviceNames) { _, which ->
                    val selectedName = pairedDevices.elementAt(which).name ?: "Thermal Printer"
                    val selectedMac = deviceMacs[which]

                    prefs.edit()
                        .putString(WiniksPrintService.KEY_MAC, selectedMac)
                        .putString(WiniksPrintService.KEY_NAME, selectedName)
                        .apply()

                    binding.tvPrinterStatus.text = "Connected: $selectedName ($selectedMac)"
                    binding.tvPrinterStatus.setTextColor(ContextCompat.getColor(this, R.color.text_accent))
                    Toast.makeText(this, "Printer set: $selectedName", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.rgPaperSize.setOnCheckedChangeListener { _, checkedId ->
            val size = when (checkedId) {
                R.id.rb58mm -> "58"
                R.id.rb104mm -> "104"
                else -> "80"
            }
            prefs.edit().putString(WiniksPrintService.KEY_SIZE, size).apply()
        }

        binding.switchAutoCut.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(WiniksPrintService.KEY_AUTO_CUT, isChecked).apply()
        }

        binding.switchCashDrawer.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(WiniksPrintService.KEY_CASH_DRAWER, isChecked).apply()
        }

        binding.sbFeedLines.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val lines = progress.coerceAtLeast(1)
                binding.tvFeedLines.text = "Blank Lines to Feed before Cut: $lines"
                prefs.edit().putInt(WiniksPrintService.KEY_FEED_LINES, lines).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.sbWidthScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val scale = 80 + progress // 80% to 110%
                binding.tvWidthScale.text = "Width Scale: $scale%"
                prefs.edit().putInt(WiniksPrintService.KEY_SCALE, scale).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnTestPrint.setOnClickListener {
            val mac = prefs.getString(WiniksPrintService.KEY_MAC, "") ?: ""
            if (mac.isBlank()) {
                Toast.makeText(this, "Please select a preferred printer first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val paperSize = prefs.getString(WiniksPrintService.KEY_SIZE, "80") ?: "80"
            val widthDots = if (paperSize == "58") 384 else 576
            val autoCut = prefs.getBoolean(WiniksPrintService.KEY_AUTO_CUT, true)
            val feedLines = prefs.getInt(WiniksPrintService.KEY_FEED_LINES, 2)

            Toast.makeText(this, "Sending test print to printer...", Toast.LENGTH_SHORT).show()

            Thread {
                val testBitmap = EscPosHelper.createTestReceiptBitmap(widthDots)
                val raster = EscPosHelper.bitmapToEscPosRaster(testBitmap, widthDots)
                val feed = EscPosHelper.feedLines(feedLines)
                val cut = if (autoCut) EscPosHelper.FULL_CUT else byteArrayOf()

                val payload = raster + feed + cut
                val success = BluetoothPrintManager.sendBytesToPrinter(this, mac, payload, 3000)

                runOnUiThread {
                    if (success) {
                        Toast.makeText(this, "Test Print Sent Successfully! Check Printer.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Print Failed. Ensure Bluetooth is ON and printer is near.", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }

        binding.btnOpenSettings.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_PRINT_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open Android Print Settings directly. Go to Settings -> Printing.", Toast.LENGTH_LONG).show()
            }
        }
    }
}

