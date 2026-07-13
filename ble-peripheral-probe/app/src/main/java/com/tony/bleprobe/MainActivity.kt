package com.tony.bleprobe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID

/**
 * Throwaway Phase-0 probe: does THIS phone (the Stark "ARKENSTONE") support the BLE
 * PERIPHERAL role — advertise a custom 128-bit service + serve a NOTIFY characteristic?
 *
 * It does NOT touch the bike. It only tests the phone-side capability the Garmin path needs.
 * Watch the on-screen log. The load-bearing lines are:
 *   getBluetoothLeAdvertiser() non-null   AND   "ADVERTISING STARTED"
 * Then connect from nRF Connect (2nd phone) or the Garmin CIQ app and confirm notifications tick.
 */
@SuppressLint("MissingPermission") // perms are requested at runtime and guarded before each call
class MainActivity : Activity() {

    // Arbitrary custom UUIDs for the probe (distinct from the bike's "Stark Future" UUIDs).
    private val serviceUuid: UUID = UUID.fromString("a1b2c3d4-0001-4a5b-8c6d-000000000001")
    private val charUuid: UUID = UUID.fromString("a1b2c3d4-0002-4a5b-8c6d-000000000001")
    private val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private lateinit var log: TextView
    private lateinit var scroll: ScrollView

    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private val subscribers = mutableSetOf<BluetoothDevice>()

    private val handler = Handler(Looper.getMainLooper())
    private var tick = 0
    private var ticking = false

    private val REQ_CODE = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = TextView(this).apply {
            textSize = 13f
            setPadding(28, 56, 28, 28)
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(log) }
        setContentView(scroll)

        line("=== Stark → Garmin :: BLE peripheral probe ===")
        line("Device: ${Build.MANUFACTURER} ${Build.MODEL}  (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")

        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_ADVERTISE
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) runProbe()
        else requestPermissions(missing.toTypedArray(), REQ_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            runProbe()
        } else {
            line("\n❌ Permissions denied. Grant BLUETOOTH_ADVERTISE + BLUETOOTH_CONNECT and relaunch.")
        }
    }

    private fun runProbe() {
        val mgr = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = mgr?.adapter
        if (adapter == null) { line("\n❌ No Bluetooth adapter on this device."); return }
        if (!adapter.isEnabled) { line("\n❌ Bluetooth is OFF — turn it on and relaunch."); return }

        line("\n— Capability flags —")
        line("isMultipleAdvertisementSupported : ${safe { adapter.isMultipleAdvertisementSupported }}")
        line("isLeExtendedAdvertisingSupported : ${safe { adapter.isLeExtendedAdvertisingSupported }}")
        line("isLe2MPhySupported               : ${safe { adapter.isLe2MPhySupported }}")

        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            line("\ngetBluetoothLeAdvertiser()       : NULL")
            line("\n❌ FAIL — this chipset cannot act as a BLE peripheral.")
            line("   ⇒ Move the bridge to a dedicated device (spare Android or ESP32/svag-mini).")
            line("   Bike-side and Garmin-side plans are unchanged.")
            return
        }
        line("getBluetoothLeAdvertiser()       : non-null ✅")

        startGattServer(mgr)
        startAdvertising()
    }

    private fun startGattServer(mgr: BluetoothManager) {
        gattServer = mgr.openGattServer(this, serverCallback)
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val ch = BluetoothGattCharacteristic(
            charUuid,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        ch.addDescriptor(
            BluetoothGattDescriptor(
                cccdUuid,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        ch.value = payload()
        service.addCharacteristic(ch)
        notifyChar = ch
        val ok = gattServer?.addService(service) ?: false
        line("openGattServer + addService      : ${if (ok) "ok ✅" else "FAILED ❌"}")
    }

    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        // A 128-bit UUID is 16 bytes; it fills the 31-byte primary PDU, so keep the name out of it
        // and put it in the scan response instead.
        val advData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(serviceUuid))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        advertiser?.startAdvertising(settings, advData, scanResponse, advCallback)
    }

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            line("\n✅✅ ADVERTISING STARTED — this phone CAN be a BLE peripheral.")
            line("   Advertising service : $serviceUuid")
            line("   Notify characteristic: $charUuid")
            line("\n   Next: on a 2nd phone open nRF Connect ▸ SCANNER, find that service UUID,")
            line("   Connect, enable notifications on the characteristic — value should tick every 2s.")
            line("   (Same chain the Garmin Connect IQ data field will use.)")
        }

        override fun onStartFailure(errorCode: Int) {
            val reason = when (errorCode) {
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED ← chipset can't advertise"
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE (adv packet too big)"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
                ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
                else -> "code $errorCode"
            }
            line("\n❌ Advertising FAILED: $reason")
            if (errorCode == ADVERTISE_FAILED_FEATURE_UNSUPPORTED) {
                line("   ⇒ Peripheral mode unsupported. Use a dedicated bridge device (spare Android / ESP32).")
            }
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> line("\n🔗 Central connected: ${device.address}")
                BluetoothProfile.STATE_DISCONNECTED -> {
                    line("… central disconnected: ${device.address}")
                    subscribers.remove(device)
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic
        ) {
            line("📖 read request from ${device.address}")
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, payload())
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (descriptor.uuid == cccdUuid) {
                val enable = value.isNotEmpty() && value[0].toInt() != 0
                if (enable) {
                    subscribers.add(device)
                    line("🔔 ${device.address} SUBSCRIBED — pushing notifications every 2s")
                    startTicker()
                } else {
                    subscribers.remove(device)
                    line("🔕 ${device.address} unsubscribed")
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    private val tickRunnable = object : Runnable {
        @Suppress("DEPRECATION")
        override fun run() {
            if (subscribers.isEmpty()) { ticking = false; return }
            tick = (tick + 1) and 0xFF
            val c = notifyChar ?: return
            c.value = payload()
            subscribers.toList().forEach { d -> gattServer?.notifyCharacteristicChanged(d, c, false) }
            handler.postDelayed(this, 2000)
        }
    }

    private fun startTicker() {
        if (!ticking) { ticking = true; handler.post(tickRunnable) }
    }

    /** Fake payload mirroring the real design: [battery%, mode/tick, flags]. */
    private fun payload(): ByteArray = byteArrayOf(84.toByte(), tick.toByte(), 0)

    private inline fun safe(block: () -> Any?): String =
        try { block()?.toString() ?: "n/a" } catch (e: Throwable) { "err(${e.javaClass.simpleName})" }

    private fun line(s: String) = runOnUiThread {
        log.append(s + "\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { advertiser?.stopAdvertising(advCallback) } catch (_: Throwable) {}
        try { gattServer?.close() } catch (_: Throwable) {}
    }
}
