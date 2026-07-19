package com.tony.starkbridge

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tony.starkbridge.databinding.ActivityMainBinding

/**
 * Bridge control UI:
 *  - VIN text field (persisted in SharedPreferences)
 *  - Start / Stop buttons (start/stop the foreground [BridgeService])
 *  - Live status (bike connected?, garmin connected?, battery%, mode, speed)
 *  - Derived PIN shown read-only (user can type it if Android prompts)
 *  - Scrolling log
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private val logBuffer = StringBuilder()
    private var running = false

    private val requiredPermissions: Array<String>
        get() {
            val perms = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                perms += Manifest.permission.BLUETOOTH_SCAN
                perms += Manifest.permission.BLUETOOTH_CONNECT
                perms += Manifest.permission.BLUETOOTH_ADVERTISE
            } else {
                @Suppress("DEPRECATION")
                perms += Manifest.permission.BLUETOOTH
                @Suppress("DEPRECATION")
                perms += Manifest.permission.BLUETOOTH_ADMIN
                // Legacy scanning needs location.
                perms += Manifest.permission.ACCESS_FINE_LOCATION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms += Manifest.permission.POST_NOTIFICATIONS
            }
            return perms.toTypedArray()
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val denied = result.filterValues { !it }.keys
        if (denied.isEmpty()) {
            appendLog("Permissions granted")
            actuallyStart()
        } else {
            Toast.makeText(this, "Permissions required: $denied", Toast.LENGTH_LONG).show()
            appendLog("Permissions denied: $denied")
        }
    }

    // ---- Broadcast receiver from the service -----------------------------

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BridgeService.ACTION_STATE -> renderState(intent)
                BridgeService.ACTION_LOG -> {
                    intent.getStringExtra(BridgeService.EXTRA_LOG)?.let { appendLog(it) }
                }
                BridgeService.ACTION_PIN -> {
                    val pin = intent.getStringExtra(BridgeService.EXTRA_PIN).orEmpty()
                    binding.pinValue.text = if (pin.isEmpty()) "—" else pin
                }
            }
        }
    }

    // ---- Lifecycle -------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val savedVin = prefs.getString(KEY_VIN, "").orEmpty()
        binding.vinInput.setText(savedVin)
        updatePinPreview(savedVin)

        binding.startButton.setOnClickListener { onStartClicked() }
        binding.stopButton.setOnClickListener { onStopClicked() }

        // Live-update the PIN preview as the VIN changes.
        binding.vinInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) updatePinPreview(binding.vinInput.text?.toString().orEmpty())
        }

        setControlsRunning(false)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(BridgeService.ACTION_STATE)
            addAction(BridgeService.ACTION_LOG)
            addAction(BridgeService.ACTION_PIN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(serviceReceiver, filter)
        }
    }

    override fun onStop() {
        try {
            unregisterReceiver(serviceReceiver)
        } catch (e: Exception) {
            // ignore
        }
        super.onStop()
    }

    // ---- Button handlers -------------------------------------------------

    private fun onStartClicked() {
        val vin = binding.vinInput.text?.toString()?.trim().orEmpty()
        if (vin.isEmpty()) {
            Toast.makeText(this, "Enter the bike VIN first", Toast.LENGTH_SHORT).show()
            return
        }
        prefs.edit().putString(KEY_VIN, vin).apply()
        updatePinPreview(vin)

        if (hasAllPermissions()) {
            actuallyStart()
        } else {
            appendLog("Requesting permissions…")
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun actuallyStart() {
        val vin = binding.vinInput.text?.toString()?.trim().orEmpty()
        if (vin.isEmpty()) return
        appendLog("Starting bridge…")
        BridgeService.start(this, vin)
        setControlsRunning(true)
    }

    private fun onStopClicked() {
        appendLog("Stopping bridge…")
        BridgeService.stop(this)
        setControlsRunning(false)
        binding.bikeStatus.text = getString(R.string.status_bike_disconnected)
        binding.garminStatus.text = getString(R.string.status_garmin_disconnected)
    }

    // ---- Rendering -------------------------------------------------------

    private fun renderState(intent: Intent) {
        val bikeConnected = intent.getBooleanExtra(BridgeService.EXTRA_BIKE_CONNECTED, false)
        val garminConnected = intent.getBooleanExtra(BridgeService.EXTRA_GARMIN_CONNECTED, false)
        val battery = intent.getIntExtra(BridgeService.EXTRA_BATTERY, -1)
        val soh = intent.getIntExtra(BridgeService.EXTRA_SOH, -1)
        val modeLabel = intent.getStringExtra(BridgeService.EXTRA_MODE_LABEL) ?: "—"
        val speedX10 = intent.getIntExtra(BridgeService.EXTRA_SPEED_X10, Int.MIN_VALUE)
        val charging = intent.getBooleanExtra(BridgeService.EXTRA_CHARGING, false)
        val inGear = intent.getBooleanExtra(BridgeService.EXTRA_IN_GEAR, false)
        val fault = intent.getBooleanExtra(BridgeService.EXTRA_FAULT, false)
        val on = intent.getBooleanExtra(BridgeService.EXTRA_ON, false)

        binding.bikeStatus.text =
            if (bikeConnected) getString(R.string.status_bike_connected)
            else getString(R.string.status_bike_disconnected)
        binding.garminStatus.text =
            if (garminConnected) getString(R.string.status_garmin_connected)
            else getString(R.string.status_garmin_disconnected)

        binding.batteryValue.text = if (battery in 0..100) "$battery%" else "—"
        binding.sohValue.text = if (soh in 0..100) "$soh%" else "—"
        binding.modeValue.text = modeLabel
        binding.speedValue.text =
            if (speedX10 != Int.MIN_VALUE) "%.1f km/h".format(speedX10 / 10.0) else "—"

        val flags = buildList {
            if (on) add("ON")
            if (charging) add("CHG")
            if (inGear) add("GEAR")
            if (fault) add("FAULT")
        }
        // DISCOVERY AID: raw status miscBits + walk/crawl nibble, so the crawl
        // forward/back values can be read straight off the bike.
        val miscBits = intent.getIntExtra(BridgeService.EXTRA_MISC_BITS, 0)
        val walkMode = intent.getIntExtra(BridgeService.EXTRA_WALK_MODE, 0)
        val diag = "misc:0x%04X walk:%d".format(miscBits, walkMode)
        binding.flagsValue.text =
            (if (flags.isEmpty()) "—" else flags.joinToString(" · ")) + "\n" + diag
    }

    private fun updatePinPreview(vin: String) {
        val trimmed = vin.trim()
        binding.pinValue.text = if (trimmed.isEmpty()) {
            "—"
        } else {
            try {
                StarkProtocol.getVehiclePin(trimmed)
            } catch (e: Exception) {
                "—"
            }
        }
    }

    private fun setControlsRunning(isRunning: Boolean) {
        running = isRunning
        binding.startButton.isEnabled = !isRunning
        binding.stopButton.isEnabled = isRunning
        binding.vinInput.isEnabled = !isRunning
    }

    private fun appendLog(line: String) {
        logBuffer.append(line).append('\n')
        // Cap the buffer so it does not grow unbounded.
        if (logBuffer.length > 8000) {
            logBuffer.delete(0, logBuffer.length - 6000)
        }
        binding.logView.text = logBuffer.toString()
        binding.logScroll.post {
            binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    // ---- Permissions -----------------------------------------------------

    private fun hasAllPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val PREFS = "stark_bridge_prefs"
        private const val KEY_VIN = "vin"
    }
}
