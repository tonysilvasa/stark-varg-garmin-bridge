package com.tony.starkbridge

import java.util.UUID

/**
 * The BRIDGE BLE contract. These UUIDs and the 8-byte little-endian telemetry
 * packet MUST match byte-for-byte with the Garmin (Connect IQ) side.
 *
 * The Stark phone advertises the bridge service (CONNECTABLE) and exposes a
 * single telemetry characteristic (READ + NOTIFY) whose value is the packet
 * produced by [encodePacket].
 */
object BridgeContract {

    /** Bridge service UUID, advertised in the advertising data. */
    val SERVICE_UUID: UUID = UUID.fromString("4761726d-696e-4272-6964-676500000001")

    /** Telemetry characteristic UUID (READ + NOTIFY). */
    val TELEMETRY_CHAR_UUID: UUID = UUID.fromString("4761726d-696e-4272-6964-676500000002")

    /** Client Characteristic Configuration Descriptor (standard). */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Packet version byte. */
    const val VERSION: Int = 0x01

    /** Unknown sentinel for u8 fields. */
    const val UNKNOWN_U8: Int = 0xFF

    /** Unknown sentinel for u16 fields. */
    const val UNKNOWN_U16: Int = 0xFFFF

    // status_flags bit positions
    const val FLAG_ON: Int = 0
    const val FLAG_CHARGING: Int = 1
    const val FLAG_CHARGER_CONNECTED: Int = 2
    const val FLAG_IN_GEAR: Int = 3
    const val FLAG_FAULT_ACTIVE: Int = 4

    /**
     * Encode the current [BikeState] into the 8-byte little-endian telemetry
     * packet defined by the bridge contract:
     *
     *  [0] version      u8 = 0x01
     *  [1] battery_pct  u8   0-100; 0xFF unknown
     *  [2] mode_index   u8   active ride "map" index; 0xFF unknown
     *  [3] status_flags u8   bit0 on, bit1 charging, bit2 charger-connected,
     *                        bit3 in-gear, bit4 fault-active
     *  [4..5] speed_kmh_x10 u16 LE  km/h*10; 0xFFFF unknown
     *  [6] soh_pct      u8   0xFF unknown
     *  [7] seq          u8   increments each publish (liveness)
     */
    fun encodePacket(state: BikeState): ByteArray {
        val packet = ByteArray(8)

        packet[0] = VERSION.toByte()

        packet[1] = (state.batteryPct ?: UNKNOWN_U8).coerceIn(0, 0xFF).toByte()

        packet[2] = (state.modeIndex ?: UNKNOWN_U8).coerceIn(0, 0xFF).toByte()

        var flags = 0
        if (state.on) flags = flags or (1 shl FLAG_ON)
        if (state.charging) flags = flags or (1 shl FLAG_CHARGING)
        if (state.chargerConnected) flags = flags or (1 shl FLAG_CHARGER_CONNECTED)
        if (state.inGear) flags = flags or (1 shl FLAG_IN_GEAR)
        if (state.faultActive) flags = flags or (1 shl FLAG_FAULT_ACTIVE)
        packet[3] = flags.toByte()

        val speed = state.speedKmhX10 ?: UNKNOWN_U16
        packet[4] = (speed and 0xFF).toByte()
        packet[5] = ((speed shr 8) and 0xFF).toByte()

        packet[6] = (state.sohPct ?: UNKNOWN_U8).coerceIn(0, 0xFF).toByte()

        packet[7] = (state.seq and 0xFF).toByte()

        return packet
    }
}
