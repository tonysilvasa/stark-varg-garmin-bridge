//
// StarkBridgeApp.mc
//
// AppBase for the Stark Varg Bridge data field. It owns the BLE lifecycle and
// the latest telemetry, so both survive independently of the (transient) view.
//
// Responsibilities:
//   * Register the BLE delegate and our GATT profile once, at startup.
//   * Start scanning for the bridge service.
//   * Hold the most recent parsed telemetry + timestamps for staleness.
//   * Expose accessors the view reads in onUpdate().
//

import Toybox.Lang;
using Toybox.Application;
using Toybox.WatchUi;
using Toybox.System;
using Toybox.Time;
using Toybox.BluetoothLowEnergy as Ble;

class StarkBridgeApp extends Application.AppBase {

    // The BLE delegate (also our connection state machine). Kept on the app so
    // it is never garbage collected while the app runs.
    private var _bleDelegate as StarkBleDelegate?;

    // Latest telemetry, as returned by Contract.parsePacket, or null if none yet.
    private var _telemetry as Dictionary?;

    // System.getTimer() millisecond stamp of the last accepted packet, or null.
    private var _lastPacketMs as Number?;

    // Last sequence byte we saw, to detect a "stalled but connected" bridge.
    private var _lastSeq as Number?;
    private var _lastSeqChangeMs as Number?;

    // True once we have an active BLE connection to the bridge peripheral.
    private var _connected as Boolean = false;

    // ---- On-screen diagnostics (shown until telemetry flows) ---------------
    private var _dbgProfile as String = "?"; // profile register result
    private var _dbgScanSeen as Number = 0;  // total advert results iterated
    private var _dbgMatched as Number = 0;   // times our bridge service matched
    private var _dbgConn as String = "-";    // last connection state
    private var _dbgCccd as String = "-";    // CCCD (notify enable) result
    private var _dbgPkt as Number = 0;       // telemetry packets received
    private var _dbgSvc as Number = 0;       // scan results with >=1 getServiceUuids
    private var _dbgRaw as Number = 0;       // scan results with non-null getRawData
    private var _probe1 as String = "-";     // strongest device: name / rssi / rawlen
    private var _probe2 as String = "-";     // strongest device: raw advert hex
    private var _probe3 as String = "-";     // strongest device: mfg(FFFF) hex

    function initialize() {
        AppBase.initialize();
    }

    // onStart is the right place to bring up BLE: the permission is granted and
    // the runtime is ready. We guard the whole thing so a BLE failure (e.g.
    // unsupported hardware) degrades to a "no data" field rather than a crash.
    function onStart(state as Dictionary?) as Void {
        try {
            _bleDelegate = new StarkBleDelegate(self);
            Ble.setDelegate(_bleDelegate);

            // Register our profile so the stack knows to discover the telemetry
            // characteristic and its CCCD after connecting. Must be done before
            // (or immediately alongside) scanning. Limit is 3 profiles total.
            Ble.registerProfile({
                :uuid => Contract.serviceUuid(),
                :characteristics => [
                    {
                        :uuid => Contract.telemetryCharUuid(),
                        :descriptors => [ Contract.cccdUuid() ]
                    }
                ]
            });

            // Start scanning. onScanResults will fire in the delegate.
            Ble.setScanState(Ble.SCAN_STATE_SCANNING);
        } catch (e) {
            System.println("BLE init failed: " + e.getErrorMessage());
        }
    }

    function onStop(state as Dictionary?) as Void {
        try {
            Ble.setScanState(Ble.SCAN_STATE_OFF);
        } catch (e) {
            // ignore
        }
    }

    // The data field entry point. Connect IQ instantiates the view; the view
    // reads state back off this app via getApp().
    function getInitialView() as [WatchUi.Views] or [WatchUi.Views, WatchUi.InputDelegates] {
        return [ new StarkBridgeView() ];
    }

    // Settings changed in the phone/Express app: request a redraw so new mode
    // labels / stale timeout take effect immediately.
    function onSettingsChanged() as Void {
        WatchUi.requestUpdate();
    }

    // ---- Called by the BLE delegate ----------------------------------------

    function setConnected(connected as Boolean) as Void {
        _connected = connected;
        if (!connected) {
            // Keep the last telemetry on screen but let staleness logic show it
            // as old; clear seq tracking so a reconnect re-arms liveness.
            _lastSeq = null;
            _lastSeqChangeMs = null;
        }
        WatchUi.requestUpdate();
    }

    // Store a freshly parsed packet and update liveness bookkeeping.
    function onTelemetry(parsed as Dictionary) as Void {
        var now = System.getTimer();
        _dbgPkt += 1;
        _telemetry = parsed;
        _lastPacketMs = now;

        var seq = parsed[:seq] as Number?;
        if (_lastSeq == null || seq != _lastSeq) {
            _lastSeq = seq;
            _lastSeqChangeMs = now;
        }

        WatchUi.requestUpdate();
    }

    // ---- Diagnostics set by the BLE delegate --------------------------------

    function dbgSetProfile(s as String) as Void { _dbgProfile = s; WatchUi.requestUpdate(); }
    function dbgAddScan(n as Number, matched as Boolean) as Void {
        _dbgScanSeen += n;
        if (matched) { _dbgMatched += 1; }
        WatchUi.requestUpdate();
    }
    function dbgSetConn(s as String) as Void { _dbgConn = s; WatchUi.requestUpdate(); }
    function dbgSetCccd(s as String) as Void { _dbgCccd = s; WatchUi.requestUpdate(); }

    function dbgScanDetail(hadUuid as Boolean, hadRaw as Boolean) as Void {
        if (hadUuid) { _dbgSvc += 1; }
        if (hadRaw) { _dbgRaw += 1; }
    }

    // Details of the strongest-RSSI device seen (= our phone when it's touching
    // the Edge). Shows exactly what CIQ hands us for that device.
    function dbgProbe(a as String, b as String, c as String) as Void {
        _probe1 = a; _probe2 = b; _probe3 = c;
        WatchUi.requestUpdate();
    }
    function probeLines() as Array<String> {
        return [_probe1, _probe2, _probe3];
    }

    // Compact status. "v6" tag confirms THIS build is loaded.
    //   S=adverts seen  U=results with parseable service-UUIDs
    //   X=results with raw advert data  M=matched  C=connected  R=packets
    function debugLine() as String {
        return "v7 S:" + _dbgScanSeen + " U:" + _dbgSvc + " X:" + _dbgRaw
             + " M:" + _dbgMatched + " C:" + _dbgConn + " R:" + _dbgPkt;
    }

    // ---- Read by the view ---------------------------------------------------

    function getTelemetry() as Dictionary? {
        return _telemetry;
    }

    function isConnected() as Boolean {
        return _connected;
    }

    // Stale if disconnected, if no packet ever arrived, if the last packet is
    // older than the configured timeout, or if seq has stopped advancing while
    // still nominally connected (bridge alive but bike link dead).
    function isStale() as Boolean {
        if (!_connected || _lastPacketMs == null) {
            return true;
        }
        var timeoutMs = staleTimeoutMs();
        var now = System.getTimer();

        if (elapsed(now, _lastPacketMs) > timeoutMs) {
            return true;
        }
        if (_lastSeqChangeMs != null && elapsed(now, _lastSeqChangeMs) > timeoutMs) {
            return true;
        }
        return false;
    }

    // ---- Helpers ------------------------------------------------------------

    private function staleTimeoutMs() as Number {
        var secs = 6;
        try {
            var v = Application.Properties.getValue("StaleTimeout");
            if (v != null && v instanceof Number && v > 0) {
                secs = v as Number;
            }
        } catch (e) {
            secs = 6;
        }
        return secs * 1000;
    }

    // System.getTimer() wraps around ~24.8 days; compute a wrap-safe delta.
    private function elapsed(now as Number, then as Number) as Number {
        var d = now - then;
        if (d < 0) {
            d += 0x40000000; // best-effort unwrap; timeouts are small anyway
        }
        return d;
    }
}

// Convenience accessor used by the view. Named to avoid clashing with the
// SDK's Application.getApp(); call as $.getStarkApp() from anywhere.
function getStarkApp() as StarkBridgeApp {
    return Application.getApp() as StarkBridgeApp;
}
