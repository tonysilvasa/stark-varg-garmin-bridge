package com.tony.starkbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BLE PERIPHERAL exposing the bridge service so a Garmin can read/subscribe to
 * telemetry.
 *
 * - Hosts a [BluetoothGattServer] with the bridge service + telemetry char
 *   (READ + NOTIFY) + CCCD.
 * - Advertises CONNECTABLE with the 128-bit bridge service UUID.
 * - Tracks subscribed centrals and pushes the latest packet via
 *   notifyCharacteristicChanged whenever the bike state updates.
 */
@SuppressLint("MissingPermission")
class GarminServer(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var telemetryChar: BluetoothGattCharacteristic? = null

    /** Latest packet payload served on reads/notifies. */
    @Volatile
    private var latestPacket: ByteArray = BridgeContract.encodePacket(BikeState())

    /** Centrals that have enabled notifications on the telemetry char. */
    private val subscribers: MutableSet<BluetoothDevice> =
        Collections.synchronizedSet(mutableSetOf())

    private val _garminConnected = MutableStateFlow(false)
    val garminConnected: StateFlow<Boolean> = _garminConnected.asStateFlow()

    @Volatile
    private var advertising = false

    fun start(): Boolean {
        val a = adapter
        if (a == null || !a.isEnabled) {
            log("Bluetooth OFF — cannot start peripheral")
            return false
        }
        if (a.bluetoothLeAdvertiser == null) {
            log("This device does not support BLE advertising")
            return false
        }
        // Advertising is started from onServiceAdded so the GATT service is
        // guaranteed to be registered before centrals can connect.
        openGattServer()
        return true
    }

    fun stop() {
        stopAdvertising()
        subscribers.clear()
        _garminConnected.value = false
        try {
            gattServer?.close()
        } catch (e: Exception) {
            // ignore
        }
        gattServer = null
        telemetryChar = null
    }

    // ---- GATT server -----------------------------------------------------

    private fun openGattServer() {
        val server = bluetoothManager?.openGattServer(context, serverCallback)
        if (server == null) {
            log("Failed to open GATT server")
            return
        }
        gattServer = server

        val service = BluetoothGattService(
            BridgeContract.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )

        val char = BluetoothGattCharacteristic(
            BridgeContract.TELEMETRY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )

        val cccd = BluetoothGattDescriptor(
            BridgeContract.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        char.addDescriptor(cccd)
        service.addCharacteristic(char)
        telemetryChar = char

        val ok = server.addService(service)
        log("GATT server service added: $ok")
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt_SUCCESS) {
                log("Bridge service registered; advertising")
                startAdvertising()
            } else {
                log("addService failed status=$status")
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            // IMPORTANT: this callback fires for ANY ACL link to the phone —
            // including the bike (our own BikeClient central link). It does NOT
            // mean the Garmin connected. "Garmin connected" is driven ONLY by an
            // actual telemetry SUBSCRIBE (onDescriptorWriteRequest), which the
            // bike never does. So we do not touch _garminConnected here.
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("central link up: ${device.address}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("central link down: ${device.address}")
                    subscribers.remove(device)
                    _garminConnected.value = subscribers.isNotEmpty()
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == BridgeContract.TELEMETRY_CHAR_UUID) {
                val value = latestPacket
                val out = if (offset > value.size) ByteArray(0)
                else value.copyOfRange(offset, value.size)
                gattServer?.sendResponse(device, requestId, BluetoothGatt_SUCCESS, offset, out)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt_FAILURE, offset, null)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            if (descriptor.uuid == BridgeContract.CCCD_UUID) {
                val subscribed = subscribers.contains(device)
                val value = if (subscribed) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                gattServer?.sendResponse(device, requestId, BluetoothGatt_SUCCESS, offset, value)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt_FAILURE, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (descriptor.uuid == BridgeContract.CCCD_UUID) {
                val enable = value != null &&
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (enable) {
                    subscribers.add(device)
                    _garminConnected.value = true
                    log("Garmin SUBSCRIBED: ${device.address}")
                } else {
                    subscribers.remove(device)
                    _garminConnected.value = subscribers.isNotEmpty()
                    log("Garmin unsubscribed: ${device.address}")
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt_SUCCESS, offset, value)
                }
                // Push the current value immediately on subscribe.
                if (enable) notifyOne(device)
            } else if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt_FAILURE, offset, null)
            }
        }
    }

    // ---- Advertising -----------------------------------------------------

    private fun startAdvertising() {
        if (advertising) return
        val a = adapter ?: return
        advertiser = a.bluetoothLeAdvertiser
        val adv = advertiser
        if (adv == null) {
            log("Advertiser unavailable")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // Discovery marker: a unique "STARK" signature in manufacturer-specific
        // data (company 0xFFFF). The Garmin finds these 5 bytes in the raw
        // advert — the most reliable path, since Connect IQ's UUID parsing is
        // flaky but raw-advert reads work. The 16-bit beacon is kept as a
        // secondary marker. The real GATT service is still the 128-bit
        // SERVICE_UUID, discovered after connecting.
        val advData = AdvertiseData.Builder()
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(SIGNATURE_COMPANY_ID, SIGNATURE)
            .addServiceUuid(ParcelUuid.fromString(BEACON_UUID_16))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        try {
            adv.startAdvertising(settings, advData, scanResponse, advertiseCallback)
        } catch (e: Exception) {
            log("startAdvertising failed: ${e.message}")
        }
    }

    private fun stopAdvertising() {
        if (!advertising && advertiser == null) return
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            // ignore
        }
        advertising = false
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
            log("Advertising bridge service")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            log("Advertising failed code=$errorCode")
        }
    }

    // ---- Publish ---------------------------------------------------------

    /** Update the served packet and notify all subscribers. */
    fun publish(state: BikeState) {
        val packet = BridgeContract.encodePacket(state)
        latestPacket = packet
        val char = telemetryChar ?: return
        val snapshot: List<BluetoothDevice> = synchronized(subscribers) { subscribers.toList() }
        for (device in snapshot) {
            notifyDevice(device, char, packet)
        }
    }

    private fun notifyOne(device: BluetoothDevice) {
        val char = telemetryChar ?: return
        notifyDevice(device, char, latestPacket)
    }

    @Suppress("DEPRECATION")
    private fun notifyDevice(
        device: BluetoothDevice,
        char: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        val server = gattServer ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, char, false, value)
            } else {
                char.value = value
                server.notifyCharacteristicChanged(device, char, false)
            }
        } catch (e: Exception) {
            log("notify failed: ${e.message}")
        }
    }

    companion object {
        // Local aliases to avoid importing BluetoothGatt just for status codes.
        private const val BluetoothGatt_SUCCESS = android.bluetooth.BluetoothGatt.GATT_SUCCESS
        private const val BluetoothGatt_FAILURE = android.bluetooth.BluetoothGatt.GATT_FAILURE

        // 16-bit discovery beacon (0x4761) in full Bluetooth-base form. Must
        // match Contract.beaconUuid() in the Garmin Connect IQ app.
        const val BEACON_UUID_16 = "00004761-0000-1000-8000-00805f9b34fb"

        // Manufacturer-data discovery signature = ASCII "STARK", under the
        // reserved-for-testing company id 0xFFFF. Must match Contract.SIG on
        // the Garmin side.
        const val SIGNATURE_COMPANY_ID = 0xFFFF
        val SIGNATURE = byteArrayOf(0x53, 0x54, 0x41, 0x52, 0x4B) // "STARK"
    }
}
