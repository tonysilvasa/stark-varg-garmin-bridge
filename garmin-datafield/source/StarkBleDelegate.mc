//
// StarkBleDelegate.mc
//
// BleDelegate subclass acting as the BLE central state machine:
//
//   scan  --onScanResults(match service uuid)-->  pairDevice
//   pair  --onConnectedStateChanged(CONNECTED)-->  find service+char,
//                                                   write CCCD [0x01,0x00] = notify
//   notify--onCharacteristicChanged-->  parse packet -> app.onTelemetry
//   drop  --onConnectedStateChanged(DISCONNECTED)-->  resume scanning
//
// Callback signatures (confirmed against developer.garmin.com):
//   onScanResults(scanResults as Ble.Iterator)
//   onConnectedStateChanged(device as Ble.Device, state as Ble.ConnectionState)
//   onCharacteristicChanged(characteristic as Ble.Characteristic, value as ByteArray)
//   onDescriptorWrite(descriptor as Ble.Descriptor, status as Ble.Status)
//   onProfileRegister(uuid as Ble.Uuid, status as Ble.Status)
//   onScanStateChange(scanState as Ble.ScanState, status as Ble.Status)
//

import Toybox.Lang;
using Toybox.BluetoothLowEnergy as Ble;
using Toybox.System;

class StarkBleDelegate extends Ble.BleDelegate {

    private var _app as StarkBridgeApp;

    // The peripheral we are (or were) connected to.
    private var _device as Ble.Device?;

    // Guard so we only pair with one advertiser at a time.
    private var _pairing as Boolean = false;

    // Strongest RSSI seen so far, for the on-screen probe diagnostic.
    private var _maxRssi as Number = -999;

    function initialize(app as StarkBridgeApp) {
        BleDelegate.initialize();
        _app = app;
    }

    // ---- Profile registration ----------------------------------------------

    function onProfileRegister(uuid as Ble.Uuid, status as Ble.Status) as Void {
        _app.dbgSetProfile(status == Ble.STATUS_SUCCESS ? "OK" : ("E" + status));
        if (status != Ble.STATUS_SUCCESS) {
            System.println("Profile register failed, status=" + status);
        }
    }

    // ---- Scanning -----------------------------------------------------------

    function onScanStateChange(scanState as Ble.ScanState, status as Ble.Status) as Void {
        System.println("Scan state=" + scanState + " status=" + status);
    }

    function onScanResults(scanResults as Ble.Iterator) as Void {
        if (_pairing || _device != null) {
            return; // already committed to a device
        }

        var count = 0;
        for (var result = scanResults.next(); result != null; result = scanResults.next()) {
            count++;
            var sr = result as Ble.ScanResult;
            captureProbe(sr);
            if (advertisesBridge(sr)) {
                _app.dbgAddScan(count, true);
                _pairing = true;
                // Stop scanning while we bring up the link; some devices object
                // to scanning and connecting simultaneously.
                try {
                    Ble.setScanState(Ble.SCAN_STATE_OFF);
                } catch (e) {
                    // ignore
                }
                try {
                    // pairDevice returns the Device; the definitive signal is
                    // still onConnectedStateChanged.
                    _device = Ble.pairDevice(sr);
                } catch (e) {
                    System.println("pairDevice failed: " + e.getErrorMessage());
                    _pairing = false;
                    _device = null;
                    resumeScanning();
                }
                return;
            }
        }
        _app.dbgAddScan(count, false);
    }

    // True if the advertising packet lists our 128-bit bridge service UUID.
    //
    // Primary match is getServiceUuids(). On real hardware the Connect IQ
    // advertising-parse layer has a documented bug where getServiceUuids()
    // returns an empty iterator for 128-bit service UUIDs, so we also scan the
    // raw advertising bytes for the UUID as a fallback. The bridge puts the
    // 128-bit UUID in the primary advertisement (not just the scan response),
    // so it is present in the raw data.
    private function advertisesBridge(result as Ble.ScanResult) as Boolean {
        var target = Contract.serviceUuid();
        var beacon = Contract.beaconUuid();

        // (0) Name match: the phone advertises its BT name "ARKENSTONE" in the
        // scan response. If CIQ surfaces the name, this is the simplest match.
        try {
            var nm = result.getDeviceName();
            if (nm != null && (nm as String).find("ARKEN") != null) {
                _app.dbgScanDetail(true, true);
                return true;
            }
        } catch (e) {
            // ignore
        }

        // Primary: match the 16-bit beacon (reliably parsed) or the 128-bit
        // service UUID (if this device happens to parse it) from getServiceUuids.
        var hadUuid = false;
        var uuids = result.getServiceUuids();
        if (uuids != null) {
            for (var u = uuids.next(); u != null; u = uuids.next()) {
                hadUuid = true;
                if (u.equals(beacon) || u.equals(target)) {
                    _app.dbgScanDetail(true, true);
                    return true;
                }
            }
        }

        // (2) "STARK" manufacturer-data signature via the dedicated accessor.
        try {
            var mfg = result.getManufacturerSpecificData(0xFFFF);
            if (containsSig(mfg as ByteArray?)) {
                _app.dbgScanDetail(hadUuid, true);
                return true;
            }
        } catch (e) {
            // fall through to the raw scan
        }

        // (3) Raw advert scan for the "STARK" signature (raw reads are reliable
        // on this device), then the old 128-bit needle as a last resort.
        var raw = null;
        try {
            raw = result.getRawData();
        } catch (e) {
            raw = null;
        }
        var hadRaw = (raw != null && (raw as ByteArray).size() > 0);
        _app.dbgScanDetail(hadUuid, hadRaw);

        if (containsSig(raw as ByteArray?)) {
            return true;
        }
        return rawContains(raw as ByteArray?);
    }

    // ASCII "STARK" — the manufacturer-data discovery signature. Must match
    // GarminServer.SIGNATURE on the Android side.
    private const SIG = [0x53, 0x54, 0x41, 0x52, 0x4B]b;

    private function containsSig(raw as ByteArray?) as Boolean {
        if (raw == null) {
            return false;
        }
        var nLen = SIG.size();
        var rLen = raw.size();
        if (rLen < nLen) {
            return false;
        }
        for (var i = 0; i <= rLen - nLen; i++) {
            var match = true;
            for (var j = 0; j < nLen; j++) {
                if (raw[i + j] != SIG[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    // The 128-bit bridge service UUID as it appears in a BLE advertising AD
    // structure: little-endian byte order (reverse of the canonical text form
    // 4761726d-696e-4272-6964-676500000001).
    private const BRIDGE_UUID_LE = [
        0x01, 0x00, 0x00, 0x00, 0x65, 0x67, 0x64, 0x69,
        0x72, 0x42, 0x6e, 0x69, 0x6d, 0x72, 0x61, 0x47
    ]b;

    private function rawContains(raw as ByteArray?) as Boolean {
        if (raw == null) {
            return false;
        }
        var needle = BRIDGE_UUID_LE;
        var nLen = needle.size();
        var rLen = raw.size();
        if (rLen < nLen) {
            return false;
        }
        for (var i = 0; i <= rLen - nLen; i++) {
            var match = true;
            for (var j = 0; j < nLen; j++) {
                if (raw[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    // ---- Probe diagnostic ---------------------------------------------------

    // Capture what CIQ exposes for the strongest-signal device (= our phone when
    // it is touching the Edge), so the on-screen readout shows exactly why the
    // match fails: its name, raw advert bytes, and 0xFFFF manufacturer data.
    private function captureProbe(sr as Ble.ScanResult) as Void {
        var rssi = -997;
        try { rssi = sr.getRssi(); } catch (e) {}
        if (rssi <= _maxRssi) { return; }
        _maxRssi = rssi;

        var name = "-";
        try {
            var n = sr.getDeviceName();
            if (n != null) { name = n as String; }
        } catch (e) {}

        var raw = null;
        try { raw = sr.getRawData(); } catch (e) {}
        var mfg = null;
        try { mfg = sr.getManufacturerSpecificData(0xFFFF); } catch (e) {}

        var rawLen = (raw == null) ? 0 : (raw as ByteArray).size();
        _app.dbgProbe(
            "N:" + name + " R:" + rssi + " L:" + rawLen,
            "raw:" + toHex(raw as ByteArray?, 22),
            "mf:" + toHex(mfg as ByteArray?, 14)
        );
    }

    private function toHex(b as ByteArray?, maxBytes as Number) as String {
        if (b == null) { return "nil"; }
        var digits = "0123456789abcdef";
        var s = "";
        var n = b.size();
        if (n > maxBytes) { n = maxBytes; }
        for (var i = 0; i < n; i++) {
            var v = b[i] & 0xFF;
            s += digits.substring(v >> 4, (v >> 4) + 1);
            s += digits.substring(v & 0x0F, (v & 0x0F) + 1);
        }
        return s;
    }

    // ---- Connection ---------------------------------------------------------

    function onConnectedStateChanged(device as Ble.Device, state as Ble.ConnectionState) as Void {
        if (state == Ble.CONNECTION_STATE_CONNECTED) {
            _app.dbgSetConn("Y");
            _device = device;
            _pairing = false;
            _app.setConnected(true);
            enableNotifications(device);
        } else {
            _app.dbgSetConn(state == Ble.CONNECTION_STATE_REJECTED ? "REJ" : "N");
            // DISCONNECTED or REJECTED: tear down and go back to scanning.
            _pairing = false;
            _app.setConnected(false);
            if (_device != null) {
                try {
                    Ble.unpairDevice(_device);
                } catch (e) {
                    // ignore
                }
            }
            _device = null;
            resumeScanning();
        }
    }

    // Find our service + telemetry characteristic, then write [0x01,0x00] to
    // the CCCD to turn on notifications. Reads alone return nothing per the
    // bridge contract, so notifications are mandatory.
    private function enableNotifications(device as Ble.Device) as Void {
        try {
            var service = device.getService(Contract.serviceUuid());
            if (service == null) {
                System.println("Bridge service not found on peripheral");
                return;
            }
            var chr = service.getCharacteristic(Contract.telemetryCharUuid());
            if (chr == null) {
                System.println("Telemetry characteristic not found");
                return;
            }
            var cccd = chr.getDescriptor(Contract.cccdUuid());
            if (cccd == null) {
                System.println("CCCD not found on telemetry characteristic");
                return;
            }
            // 0x0001 little-endian = enable notifications.
            cccd.requestWrite([0x01, 0x00]b);
        } catch (e) {
            System.println("enableNotifications failed: " + e.getErrorMessage());
        }
    }

    function onDescriptorWrite(descriptor as Ble.Descriptor, status as Ble.Status) as Void {
        _app.dbgSetCccd(status == Ble.STATUS_SUCCESS ? "OK" : ("E" + status));
        if (status != Ble.STATUS_SUCCESS) {
            System.println("CCCD write failed, status=" + status);
        }
        // On success the peripheral begins pushing onCharacteristicChanged.
    }

    // ---- Notifications ------------------------------------------------------

    function onCharacteristicChanged(characteristic as Ble.Characteristic, value as ByteArray) as Void {
        // Only our telemetry characteristic is subscribed, but check anyway.
        if (!characteristic.getUuid().equals(Contract.telemetryCharUuid())) {
            return;
        }
        var parsed = Contract.parsePacket(value);
        if (parsed != null) {
            _app.onTelemetry(parsed);
        }
    }

    // ---- Helpers ------------------------------------------------------------

    private function resumeScanning() as Void {
        try {
            Ble.setScanState(Ble.SCAN_STATE_SCANNING);
        } catch (e) {
            System.println("resume scan failed: " + e.getErrorMessage());
        }
    }
}
