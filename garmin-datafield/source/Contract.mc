//
// Contract.mc
//
// The BLE contract shared byte-for-byte with the Android bridge app.
// Only the *bridge* UUIDs and the 8-byte telemetry packet live here; the
// Garmin never talks to the bike directly, so the Stark GATT is not modeled.
//
// Telemetry packet (value of the telemetry characteristic), 8 bytes, LITTLE-ENDIAN:
//   [0] version      u8   = 0x01
//   [1] battery_pct  u8   0-100; 0xFF unknown
//   [2] mode_index   u8   active ride "map" index; 0xFF unknown
//   [3] status_flags u8   bit0 on, bit1 charging, bit2 charger-connected,
//                         bit3 in-gear, bit4 fault-active
//   [4..5] speed_x10 u16 LE  km/h*10; 0xFFFF unknown
//   [6] soh_pct      u8   0xFF unknown
//   [7] seq          u8   increments each publish (liveness)
//

import Toybox.Lang;
using Toybox.BluetoothLowEnergy as Ble;
using Toybox.Application;
using Toybox.Application.Properties;

module Contract {

    // ---- Protocol constants -------------------------------------------------

    const PACKET_LEN = 8;
    const PROTOCOL_VERSION = 0x01;

    const UNKNOWN_U8  = 0xFF;
    const UNKNOWN_U16 = 0xFFFF;

    // Status flag bit masks (byte [3]).
    const FLAG_ON                = 0x01; // bit0
    const FLAG_CHARGING          = 0x02; // bit1
    const FLAG_CHARGER_CONNECTED = 0x04; // bit2
    const FLAG_IN_GEAR           = 0x08; // bit3
    const FLAG_FAULT_ACTIVE      = 0x10; // bit4

    // ---- Bridge UUIDs -------------------------------------------------------
    // Built lazily from strings so the module has no init-order dependency on
    // the BLE subsystem. stringToUuid accepts the canonical 8-4-4-4-12 form.

    function serviceUuid() as Ble.Uuid {
        return Ble.stringToUuid("4761726d-696e-4272-6964-676500000001");
    }

    // 16-bit discovery beacon (0x4761) advertised alongside the real service.
    // Connect IQ reliably reads 16-bit UUIDs from an advert but is buggy at
    // 128-bit ones, so we scan-match on this. Must equal GarminServer's
    // BEACON_UUID_16 on the Android side.
    function beaconUuid() as Ble.Uuid {
        return Ble.stringToUuid("00004761-0000-1000-8000-00805f9b34fb");
    }

    function telemetryCharUuid() as Ble.Uuid {
        return Ble.stringToUuid("4761726d-696e-4272-6964-676500000002");
    }

    // The CCCD (0x2902) used to enable notifications. cccdUuid() is the SDK
    // helper that returns the 00002902-0000-1000-8000-00805f9b34fb UUID.
    function cccdUuid() as Ble.Uuid {
        return Ble.cccdUuid();
    }

    // ---- Packet parsing -----------------------------------------------------
    //
    // Returns a Dictionary on success, or null if the buffer is malformed
    // (too short, or wrong protocol version). Unknown sentinel values are
    // preserved as null in the returned dictionary so the view can render "??".
    //
    // Keys:
    //   :battery  Number 0-100, or null if unknown
    //   :mode     Number mode index, or null if unknown
    //   :speedX10 Number km/h*10 (can be negative -> treated as 0), or null
    //   :soh      Number 0-100, or null if unknown
    //   :seq      Number 0-255 (liveness counter)
    //   :on, :charging, :chargerConnected, :inGear, :faultActive  Boolean
    //
    function parsePacket(bytes as ByteArray?) as Dictionary? {
        if (bytes == null || bytes.size() < PACKET_LEN) {
            return null;
        }
        if ((bytes[0] & 0xFF) != PROTOCOL_VERSION) {
            return null;
        }

        var batteryRaw = bytes[1] & 0xFF;
        var modeRaw    = bytes[2] & 0xFF;
        var flags      = bytes[3] & 0xFF;
        var speedRaw   = (bytes[4] & 0xFF) | ((bytes[5] & 0xFF) << 8); // u16 LE
        var sohRaw     = bytes[6] & 0xFF;
        var seq        = bytes[7] & 0xFF;

        var battery = (batteryRaw == UNKNOWN_U8) ? null : clamp(batteryRaw, 0, 100);
        var mode    = (modeRaw == UNKNOWN_U8)    ? null : modeRaw;
        var soh     = (sohRaw == UNKNOWN_U8)     ? null : clamp(sohRaw, 0, 100);

        var speedX10 = null;
        if (speedRaw != UNKNOWN_U16) {
            // Bridge sends km/h*10 as an unsigned field here; the bike's own
            // speed is signed, but a coasting/negative reading is meaningless
            // for a battery/mode display, so clamp to >= 0.
            var signed = (speedRaw >= 0x8000) ? (speedRaw - 0x10000) : speedRaw;
            speedX10 = (signed < 0) ? 0 : signed;
        }

        return {
            :battery          => battery,
            :mode             => mode,
            :speedX10         => speedX10,
            :soh              => soh,
            :seq              => seq,
            :on               => (flags & FLAG_ON) != 0,
            :charging         => (flags & FLAG_CHARGING) != 0,
            :chargerConnected => (flags & FLAG_CHARGER_CONNECTED) != 0,
            :inGear           => (flags & FLAG_IN_GEAR) != 0,
            :faultActive      => (flags & FLAG_FAULT_ACTIVE) != 0
        };
    }

    // ---- Mode labels --------------------------------------------------------
    //
    // Labels are user-configurable via the "ModeLabels" setting: a
    // comma-separated string, index 0 first. modeLabel() falls back to a
    // generic "Mode N" if the setting is blank or the index is out of range.

    function modeLabels() as Array<String> {
        var raw = null;
        try {
            raw = Properties.getValue("ModeNames");
        } catch (e) {
            raw = null;
        }
        if (raw == null || !(raw instanceof String) || (raw as String).length() == 0) {
            return [] as Array<String>;   // no labels -> modeLabel() shows the raw number
        }
        var parts = splitAndTrim(raw as String, ',');
        if (parts.size() == 0) {
            return [] as Array<String>;   // no labels -> modeLabel() shows the raw number
        }
        return parts;
    }

    function modeLabel(index as Number?) as String {
        if (index == null) {
            return "??";
        }
        var labels = modeLabels();
        if (index >= 0 && index < labels.size()) {
            var label = labels[index];
            if (label != null && label.length() > 0) {
                return label;
            }
        }
        return index.toString();
    }

    // ---- Small helpers ------------------------------------------------------

    function clamp(v as Number, lo as Number, hi as Number) as Number {
        if (v < lo) { return lo; }
        if (v > hi) { return hi; }
        return v;
    }

    // Splits on a single-character delimiter and trims ASCII spaces from each
    // piece. Monkey C String has no built-in split, so do it by hand.
    function splitAndTrim(s as String, delimChar as Char) as Array<String> {
        var out = [] as Array<String>;
        var chars = s.toCharArray();
        var current = "";
        for (var i = 0; i < chars.size(); i++) {
            var c = chars[i];
            if (c == delimChar) {
                out.add(trim(current));
                current = "";
            } else {
                current += c.toString();
            }
        }
        out.add(trim(current));
        return out;
    }

    function trim(s as String) as String {
        var chars = s.toCharArray();
        var start = 0;
        var end = chars.size();
        while (start < end && isSpace(chars[start])) { start++; }
        while (end > start && isSpace(chars[end - 1])) { end--; }
        var result = "";
        for (var i = start; i < end; i++) {
            result += chars[i].toString();
        }
        return result;
    }

    function isSpace(c as Char) as Boolean {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }
}
