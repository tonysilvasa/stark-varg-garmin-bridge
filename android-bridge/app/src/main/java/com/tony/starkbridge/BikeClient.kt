package com.tony.starkbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BLE CENTRAL for the Stark Varg bike.
 *
 * Flow: scan for a device advertising name == VIN -> connect -> discover
 * services -> enable notifications on the 4 Stark characteristics (SOC, map,
 * speed, status) -> decode notifications into [BikeState].
 *
 * Auto-reconnects on disconnect. All permission-guarded calls are annotated
 * with @SuppressLint("MissingPermission"); callers ensure runtime perms are
 * granted before [start] is invoked.
 */
@SuppressLint("MissingPermission")
class BikeClient(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var targetVin: String = ""

    @Volatile
    private var running = false

    @Volatile
    private var scanning = false

    private val _state = MutableStateFlow(BikeState())
    val state: StateFlow<BikeState> = _state.asStateFlow()

    // Serialize descriptor writes: only one GATT operation may be in flight.
    private val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private var opInFlight = false

    private val charsToNotify = listOf(
        StarkProtocol.BATTERY_SERVICE to StarkProtocol.SOC_CHAR,
        StarkProtocol.LIVE_SERVICE to StarkProtocol.MAP_CHAR,
        StarkProtocol.LIVE_SERVICE to StarkProtocol.SPEED_CHAR,
        StarkProtocol.BIKE_SERVICE to StarkProtocol.STATUS_CHAR,
    )

    fun start(vin: String) {
        targetVin = vin.trim()
        running = true
        if (adapter == null) {
            log("BLE adapter unavailable")
            return
        }
        if (!adapter.isEnabled) {
            log("Bluetooth is OFF — enable it")
            return
        }
        // Seed VIN into state so the UI/PIN can display immediately.
        update { it.copy(vin = targetVin) }
        beginConnect()
    }

    // ---- Connect strategy ------------------------------------------------
    //
    // Prefer a DIRECT connect if the bike is already bonded or already
    // connected (e.g. the stock Stark app is holding the link). On Android all
    // apps share one ACL link + the bond, so a second GATT client attaches over
    // the existing connection and the bike still sees only ONE connection. This
    // lets the bridge piggyback on the stock app without killing it, and works
    // even though a connected bike has stopped advertising (so a scan can't see
    // it). Falls back to scan-by-name for a free, advertising bike.

    private fun beginConnect() {
        if (!running) return
        if (tryDirectConnect()) return
        startScan()
    }

    private fun tryDirectConnect(): Boolean {
        val a = adapter ?: return false
        val vin = targetVin
        val connected = try {
            bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val bonded = try {
            a.bondedDevices?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val device = (connected + bonded).firstOrNull { d ->
            val name = try { d.name } catch (e: SecurityException) { null }
            name == vin
        }
        if (device == null) return false
        val already = connected.any { it.address == device.address }
        log("Bike ${device.address} ${if (already) "already connected (piggyback)" else "bonded"} — direct connect, no scan")
        connect(device)
        return true
    }

    fun stop() {
        running = false
        stopScan()
        closeGatt()
        update { it.copy(bikeConnected = false) }
    }

    // ---- Scanning --------------------------------------------------------

    private fun startScan() {
        if (!running || scanning) return
        val a = adapter ?: return
        scanner = a.bluetoothLeScanner
        val s = scanner
        if (s == null) {
            log("LE scanner unavailable; retrying")
            handler.postDelayed({ startScan() }, 2000)
            return
        }
        // Bike advertises name == VIN. Filter by device name.
        val filters = listOf(
            ScanFilter.Builder().setDeviceName(targetVin).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanning = true
        log("Scanning for bike VIN=$targetVin")
        try {
            s.startScan(filters, settings, scanCallback)
        } catch (e: Exception) {
            scanning = false
            log("startScan failed: ${e.message}")
            handler.postDelayed({ startScan() }, 3000)
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            log("stopScan failed: ${e.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = try {
                device.name
            } catch (e: SecurityException) {
                null
            }
            if (name == targetVin) {
                log("Found bike ${device.address} ($name)")
                stopScan()
                connect(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            log("Scan failed code=$errorCode; retrying")
            if (running) handler.postDelayed({ startScan() }, 3000)
        }
    }

    // ---- Connection ------------------------------------------------------

    private fun connect(device: BluetoothDevice) {
        closeGatt()
        log("Connecting to ${device.address}")
        gatt = device.connectGatt(
            context,
            /* autoConnect = */ false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
        )
    }

    private fun closeGatt() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: Exception) {
            // ignore
        }
        gatt = null
        opInFlight = false
        notifyQueue.clear()
    }

    private fun scheduleReconnect() {
        if (!running) return
        log("Reconnecting in 3s…")
        handler.postDelayed({
            if (running) beginConnect()
        }, 3000)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("Bike connected; discovering services")
                    update { it.copy(bikeConnected = true) }
                    handler.post { g.discoverServices() }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("Bike disconnected (status=$status)")
                    update { it.copy(bikeConnected = false) }
                    closeGatt()
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed status=$status")
                closeGatt()
                scheduleReconnect()
                return
            }
            log("Services discovered")
            // VIN is already known (it is the scan filter / user input), so we
            // go straight to enabling notifications on the 4 Stark chars.
            enqueueNotifications(g)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            handleNotification(characteristic.uuid, characteristic.value ?: ByteArray(0))
        }

        // API 33+ overload with explicit value.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(characteristic.uuid, value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == StarkProtocol.VIN_CHAR) {
                val vin = StarkProtocol.decodeVin(characteristic.value ?: ByteArray(0))
                if (vin.isNotEmpty()) update { it.copy(vin = vin) }
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("CCCD write failed for ${descriptor.characteristic.uuid} status=$status")
            }
            opInFlight = false
            processNextNotification(g)
        }
    }

    // ---- Notification enablement (serialized) ----------------------------

    private fun enqueueNotifications(g: BluetoothGatt) {
        notifyQueue.clear()
        opInFlight = false
        for ((serviceUuid, charUuid) in charsToNotify) {
            val ch = findChar(g, serviceUuid, charUuid)
            if (ch == null) {
                log("Missing char $charUuid in $serviceUuid")
                continue
            }
            notifyQueue.add(ch)
        }
        processNextNotification(g)
    }

    private fun processNextNotification(g: BluetoothGatt) {
        if (opInFlight) return
        val ch = notifyQueue.poll() ?: run {
            log("All notifications enabled")
            return
        }
        opInFlight = true
        val ok = g.setCharacteristicNotification(ch, true)
        if (!ok) log("setCharacteristicNotification failed for ${ch.uuid}")
        val cccd = ch.getDescriptor(StarkProtocol.CCCD_UUID)
        if (cccd == null) {
            log("No CCCD on ${ch.uuid}")
            opInFlight = false
            processNextNotification(g)
            return
        }
        writeCccd(g, cccd)
    }

    @Suppress("DEPRECATION")
    private fun writeCccd(g: BluetoothGatt, cccd: BluetoothGattDescriptor) {
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val rc = g.writeDescriptor(cccd, enable)
            if (rc != BluetoothGatt.GATT_SUCCESS) {
                log("writeDescriptor rc=$rc for ${cccd.characteristic.uuid}")
                opInFlight = false
                processNextNotification(g)
            }
        } else {
            cccd.value = enable
            val ok = g.writeDescriptor(cccd)
            if (!ok) {
                log("writeDescriptor failed for ${cccd.characteristic.uuid}")
                opInFlight = false
                processNextNotification(g)
            }
        }
    }

    private fun findChar(
        g: BluetoothGatt,
        service: UUID,
        char: UUID,
    ): BluetoothGattCharacteristic? = g.getService(service)?.getCharacteristic(char)

    // ---- Notification decode ---------------------------------------------

    private fun handleNotification(uuid: UUID, value: ByteArray) {
        try {
            when (uuid) {
                StarkProtocol.SOC_CHAR -> {
                    val soc = StarkProtocol.decodeSoc(value)
                    update { it.copy(batteryPct = soc.batteryPct, sohPct = soc.sohPct) }
                }
                StarkProtocol.MAP_CHAR -> {
                    val mode = StarkProtocol.decodeMode(value)
                    update { it.copy(modeIndex = mode) }
                }
                StarkProtocol.SPEED_CHAR -> {
                    val sp = StarkProtocol.decodeSpeedKmhX10(value)
                    update { it.copy(speedKmhX10 = sp) }
                }
                StarkProtocol.STATUS_CHAR -> {
                    val st = StarkProtocol.decodeStatus(value)
                    update {
                        it.copy(
                            on = st.on,
                            charging = st.charging,
                            chargerConnected = st.chargerConnected,
                            inGear = st.inGear,
                            faultActive = st.faultActive,
                        )
                    }
                }
                else -> { /* ignore */ }
            }
        } catch (e: Exception) {
            log("Decode error for $uuid: ${e.message}")
        }
    }

    // ---- State helper ----------------------------------------------------

    private inline fun update(transform: (BikeState) -> BikeState) {
        _state.value = transform(_state.value)
    }
}
