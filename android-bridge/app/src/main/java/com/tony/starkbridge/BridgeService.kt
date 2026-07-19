package com.tony.starkbridge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service (type connectedDevice) that owns [BikeClient] and
 * [GarminServer]. It wires bike state -> peripheral notify and maintains a
 * persistent notification showing live battery%/mode/connection state.
 *
 * A PAIRING_REQUEST receiver auto-supplies the derived PIN for
 * PAIRING_VARIANT_PIN, and logs other variants (handled by the OS dialog).
 */
class BridgeService : LifecycleService() {

    private lateinit var bikeClient: BikeClient
    private lateinit var garminServer: GarminServer

    private var vin: String = ""
    private var derivedPin: String = ""
    private var seq: Int = 0
    private var wireJob: Job? = null

    /** Live log buffer, mirrored to the UI via the broadcast below. */
    private fun log(msg: String) {
        val line = "${timeStamp()} $msg"
        android.util.Log.d(TAG, line)
        val i = Intent(ACTION_LOG).setPackage(packageName).putExtra(EXTRA_LOG, line)
        sendBroadcast(i)
    }

    // ---- Pairing receiver ------------------------------------------------

    @SuppressLint("MissingPermission")
    private val pairingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_PAIRING_REQUEST) return
            val device: BluetoothDevice? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
            val variant = intent.getIntExtra(EXTRA_PAIRING_VARIANT, -1)
            log("Pairing request variant=$variant from ${device?.address}")

            if (variant == PAIRING_VARIANT_PIN && device != null && derivedPin.isNotEmpty()) {
                try {
                    val ok = device.setPin(derivedPin.toByteArray(Charsets.UTF_8))
                    log("Auto-supplied PIN ($derivedPin): $ok")
                    // Best-effort: prevent the default dialog from also asking.
                    try {
                        abortBroadcast()
                    } catch (e: Exception) {
                        // ordered broadcast not always available; ignore
                    }
                } catch (e: Exception) {
                    log("setPin failed: ${e.message} — enter $derivedPin manually")
                }
            } else {
                log("Non-PIN variant — OS dialog will handle. PIN if asked: $derivedPin")
            }
        }
    }

    // ---- Lifecycle -------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        bikeClient = BikeClient(applicationContext) { log(it) }
        garminServer = GarminServer(applicationContext) { log(it) }
        // PAIRING_REQUEST is a protected system broadcast; register NOT_EXPORTED.
        ContextCompat.registerReceiver(
            this,
            pairingReceiver,
            filterPriorityHigh(),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val requestedVin = intent.getStringExtra(EXTRA_VIN)?.trim().orEmpty()
                startBridge(requestedVin)
            }
            ACTION_STOP -> {
                stopBridge()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopBridge()
        try {
            unregisterReceiver(pairingReceiver)
        } catch (e: Exception) {
            // ignore
        }
        super.onDestroy()
    }

    // ---- Bridge control --------------------------------------------------

    private fun startBridge(requestedVin: String) {
        if (requestedVin.isEmpty()) {
            log("Cannot start: VIN empty")
            stopSelf()
            return
        }
        vin = requestedVin
        derivedPin = try {
            StarkProtocol.getVehiclePin(vin)
        } catch (e: Exception) {
            log("PIN derivation failed: ${e.message}")
            ""
        }
        log("Starting bridge for VIN=$vin (PIN=$derivedPin)")

        startForegroundInternal(BikeState(vin = vin), garminConnected = false)

        val peripheralOk = garminServer.start()
        if (!peripheralOk) {
            log("Peripheral failed to start — check BLE advertising support")
        }
        bikeClient.start(vin)

        // Broadcast the derived PIN so the UI can show it read-only.
        sendBroadcast(
            Intent(ACTION_PIN).setPackage(packageName).putExtra(EXTRA_PIN, derivedPin)
        )

        // Wire: whenever bike state OR garmin-connection changes, publish +
        // refresh the notification.
        wireJob?.cancel()
        wireJob = lifecycleScope.launch {
            combine(
                bikeClient.state,
                garminServer.garminConnected,
            ) { bike, garmin -> bike to garmin }.collect { (bike, garmin) ->
                seq = (seq + 1) and 0xFF
                val published = bike.copy(seq = seq)
                garminServer.publish(published)
                updateNotification(published, garmin)
                broadcastState(published, garmin)
            }
        }
    }

    private fun stopBridge() {
        wireJob?.cancel()
        wireJob = null
        try {
            bikeClient.stop()
        } catch (e: Exception) {
            // ignore
        }
        try {
            garminServer.stop()
        } catch (e: Exception) {
            // ignore
        }
        log("Bridge stopped")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    // ---- Notification ----------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Stark → Garmin Bridge",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Live bridge status"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(state: BikeState, garminConnected: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val battery = state.batteryPct?.let { "$it%" } ?: "?"
        val bike = if (state.bikeConnected) "bike ✓" else "bike ✗"
        val garmin = if (garminConnected) "garmin ✓" else "garmin ✗"
        val title = "Bridge: $bike  $garmin"
        val text = "Batt $battery · ${state.modeLabel}" +
            (state.speedKmh?.let { " · ${"%.1f".format(it)} km/h" } ?: "")

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startForegroundInternal(state: BikeState, garminConnected: Boolean) {
        val notification = buildNotification(state, garminConnected)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(state: BikeState, garminConnected: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(state, garminConnected))
    }

    // ---- State broadcast to UI -------------------------------------------

    private fun broadcastState(state: BikeState, garminConnected: Boolean) {
        val i = Intent(ACTION_STATE).setPackage(packageName).apply {
            putExtra(EXTRA_BIKE_CONNECTED, state.bikeConnected)
            putExtra(EXTRA_GARMIN_CONNECTED, garminConnected)
            putExtra(EXTRA_BATTERY, state.batteryPct ?: -1)
            putExtra(EXTRA_SOH, state.sohPct ?: -1)
            putExtra(EXTRA_MODE_LABEL, state.modeLabel)
            putExtra(EXTRA_MODE_INDEX, state.modeIndex ?: -1)
            putExtra(EXTRA_SPEED_X10, state.speedKmhX10 ?: Int.MIN_VALUE)
            putExtra(EXTRA_ON, state.on)
            putExtra(EXTRA_CHARGING, state.charging)
            putExtra(EXTRA_IN_GEAR, state.inGear)
            putExtra(EXTRA_FAULT, state.faultActive)
            putExtra(EXTRA_SEQ, state.seq)
            putExtra(EXTRA_MISC_BITS, state.miscBits)
            putExtra(EXTRA_WALK_MODE, state.walkMode)
        }
        sendBroadcast(i)
    }

    private fun filterPriorityHigh(): IntentFilter {
        return IntentFilter(ACTION_PAIRING_REQUEST).apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }
    }

    private fun timeStamp(): String {
        val now = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        return now.format(java.util.Date())
    }

    companion object {
        private const val TAG = "BridgeService"
        private const val CHANNEL_ID = "stark_garmin_bridge"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "com.tony.starkbridge.action.START"
        const val ACTION_STOP = "com.tony.starkbridge.action.STOP"
        const val EXTRA_VIN = "com.tony.starkbridge.extra.VIN"

        // UI broadcast channel
        const val ACTION_STATE = "com.tony.starkbridge.action.STATE"
        const val ACTION_LOG = "com.tony.starkbridge.action.LOG"
        const val ACTION_PIN = "com.tony.starkbridge.action.PIN"
        const val EXTRA_LOG = "log"
        const val EXTRA_PIN = "pin"
        const val EXTRA_BIKE_CONNECTED = "bikeConnected"
        const val EXTRA_GARMIN_CONNECTED = "garminConnected"
        const val EXTRA_BATTERY = "battery"
        const val EXTRA_SOH = "soh"
        const val EXTRA_MODE_LABEL = "modeLabel"
        const val EXTRA_MODE_INDEX = "modeIndex"
        const val EXTRA_SPEED_X10 = "speedX10"
        const val EXTRA_ON = "on"
        const val EXTRA_CHARGING = "charging"
        const val EXTRA_IN_GEAR = "inGear"
        const val EXTRA_FAULT = "fault"
        const val EXTRA_SEQ = "seq"
        const val EXTRA_MISC_BITS = "miscBits"
        const val EXTRA_WALK_MODE = "walkMode"

        // Android pairing constants (avoid hidden-API references).
        private const val ACTION_PAIRING_REQUEST = "android.bluetooth.device.action.PAIRING_REQUEST"
        private const val EXTRA_PAIRING_VARIANT = "android.bluetooth.device.extra.PAIRING_VARIANT"
        private const val PAIRING_VARIANT_PIN = 0

        fun start(context: Context, vin: String) {
            val i = Intent(context, BridgeService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_VIN, vin)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            val i = Intent(context, BridgeService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }
    }
}
