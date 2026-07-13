# Stark Varg Bridge — Garmin Connect IQ data field

A Connect IQ **data field** for Garmin Edge cycling computers that shows the
Stark Varg's **battery %** and **ride mode** live on a data screen, e.g.:

```
        STARK VARG
     84%        SPORT
```

The Edge acts as a BLE **central**. It scans for a phone-hosted *bridge*
service, connects, subscribes to a single telemetry characteristic (NOTIFY),
parses an 8-byte packet, and renders it. The Garmin never talks to the bike
directly — the companion Android app decodes the Stark GATT and re-publishes
the compact packet over the bridge service.

> The bike only serves **one** BLE connection at a time, which is why the phone
> owns the bike link and the Garmin subscribes to the phone.

---

## BLE contract (must match the bridge byte-for-byte)

| Item | UUID |
|------|------|
| Bridge service | `4761726d-696e-4272-6964-676500000001` |
| Telemetry characteristic (READ + NOTIFY) | `4761726d-696e-4272-6964-676500000002` |
| CCCD descriptor | `00002902-0000-1000-8000-00805f9b34fb` (via `BluetoothLowEnergy.cccdUuid()`) |

Telemetry packet — **8 bytes, little-endian**:

| Offset | Field | Type | Notes |
|--------|-------|------|-------|
| 0 | version | u8 | `0x01` |
| 1 | battery_pct | u8 | 0–100; `0xFF` = unknown |
| 2 | mode_index | u8 | active ride map index; `0xFF` = unknown |
| 3 | status_flags | u8 | bit0 on, bit1 charging, bit2 charger-connected, bit3 in-gear, bit4 fault-active |
| 4–5 | speed_kmh_x10 | u16 LE | km/h×10; `0xFFFF` = unknown |
| 6 | soh_pct | u8 | `0xFF` = unknown |
| 7 | seq | u8 | increments each publish (liveness) |

Our packet is 8 bytes, well under the 20-byte default BLE MTU, so no
fragmentation handling is needed.

---

## Project layout

```
garmin-datafield/
├── manifest.xml                         app id, type=datafield, BLE permission, products, minApiLevel 3.1.0
├── monkey.jungle                        build config
├── resources/
│   ├── drawables/launcher_icon.xml      vector placeholder icon
│   ├── drawables/drawables.xml          maps @Drawables.LauncherIcon
│   ├── strings/strings.xml              app name + settings labels
│   ├── settings/settings.xml            user settings (mode labels, stale timeout)
│   └── properties.xml                   backing property defaults
└── source/
    ├── Contract.mc                      UUIDs + parsePacket() + mode labels
    ├── StarkBridgeApp.mc                AppBase: owns BLE + latest telemetry
    ├── StarkBleDelegate.mc              BleDelegate: scan→pair→notify→parse→reconnect
    └── StarkBridgeView.mc               DataField: onUpdate(dc) draws battery% + mode
```

---

## Building

You need the **Connect IQ SDK** (SDK Manager) and the **Monkey C** VS Code
extension.

1. Install the [Connect IQ SDK Manager](https://developer.garmin.com/connect-iq/sdk/)
   and download an SDK **≥ 3.1.0** (BLE central needs 3.1.0+). Newer SDKs are
   fine.
2. Install the **Monkey C** extension in VS Code and point it at the SDK.
3. Generate a signing (developer) key if you don't have one:
   ```
   openssl genrsa -out developer_key.der 4096   # then convert to PKCS#8 DER
   # or use: VS Code → "Monkey C: Generate a Developer Key"
   ```
4. Open this folder in VS Code. Run **"Monkey C: Build for Device"** (or use the
   command line):
   ```
   monkeyc \
     -o bin/StarkVargBridge.prg \
     -f monkey.jungle \
     -y /path/to/developer_key.der \
     -d edge530
   ```
   Swap `-d` for any product listed in `manifest.xml` (edge530, edge830,
   edge540, edge840, edge1030, edge1030plus, edge1030bontrager, edge1040,
   edge1050).

### Simulator

Run **"Monkey C: Run App"** to launch the Connect IQ simulator. BLE can be
exercised in the simulator via its **BLE** menu, but the most realistic test is
against real advertising hardware (see Testing).

---

## Sideloading to the Edge

1. Build a `.prg` as above.
2. Connect the Edge over USB (it mounts as `GARMIN`).
3. Copy the `.prg` into `GARMIN/APPS/` on the device.
4. Eject and restart the Edge.

(Alternatively, once published, install through the Connect IQ store /
Garmin Express, which handles this for you.)

---

## Adding the field to a data screen

On the Edge:

1. **Menu → Activity Profiles → (your profile) → Data Screens**.
2. Edit a screen, choose a field slot, and pick **Connect IQ → Stark Varg
   Bridge**.
3. Start a ride (or the activity timer). The field begins scanning immediately;
   it shows `SCAN` until it finds the bridge, then live values.

---

## Configuring mode labels

The bike reports a numeric **mode index** only; human labels are configured in
the data field's **Connect IQ app settings** (Garmin Connect mobile app →
the device → Connect IQ apps → **Stark Varg Bridge → Settings**, or via
Garmin Express):

- **Ride mode labels** — a comma-separated list, index 0 first, e.g.
  `Eco,Wet,Sport,Race,Custom`. If blank, the field falls back to `Mode N`.
- **Stale timeout (seconds)** — if no fresh packet (or the `seq` counter stops
  advancing) within this many seconds, the field shows dashes / `STALE`.

These are calibrated during "Spike B" once the real Stark mode map and
`SOC_DIVISOR` are confirmed.

---

## Display states

| Shown | Meaning |
|-------|---------|
| `-- SCAN` | Not connected yet; scanning for the bridge. |
| `-- LINK?` | Connected to the bridge but no telemetry received yet. |
| `-- STALE` | Was live, but no fresh packet within the stale timeout. |
| `??% ` / `?? mode` | Field present but value is the `0xFF`/`0xFFFF` unknown sentinel. |
| `84% SPORT` | Live. Battery color: green >25%, orange ≤25%, red ≤10% or fault active. |

A charging flag shows a small ⚡ glyph in the corner.

---

## Testing

You **must** test against something advertising the bridge service:

- The companion **Android bridge app**, or
- **nRF Connect** (mobile) configured as a GATT **server** advertising service
  `4761726d-696e-4272-6964-676500000001` with a NOTIFY characteristic
  `4761726d-696e-4272-6964-676500000002`, pushing 8-byte packets that match the
  table above (start with `01 54 02 01 ...` = v1, 84%, mode 2, on).

Confirm: the field transitions `SCAN → 84% SPORT`, updates as you change the
notified value, and falls back to `STALE` when notifications stop.

---

## Confirmed Connect IQ API facts

- Permission id: **`BluetoothLowEnergy`** (`<iq:uses-permission id="BluetoothLowEnergy"/>`).
- Application type: **`datafield`**; `minApiLevel="3.1.0"` (BLE central was added in 3.1.0).
- `registerProfile({ :uuid, :characteristics => [{ :uuid, :descriptors => [BluetoothLowEnergy.cccdUuid()] }] })`.
  The stack allows at most **3** registered profiles.
- Enable notifications by writing **`[0x01, 0x00]b`** to the CCCD descriptor
  (`[0x02, 0x00]b` would be *indications*).
- Delegate callbacks used: `onScanResults(iter)`, `onConnectedStateChanged(device, state)`,
  `onCharacteristicChanged(char, value)`, `onDescriptorWrite(desc, status)`,
  `onProfileRegister(uuid, status)`, `onScanStateChange(state, status)`.
- `ScanResult.getServiceUuids()` returns a `BluetoothLowEnergy.Iterator`; iterate with `next()`.
- Connection states: `CONNECTION_STATE_CONNECTED`, `CONNECTION_STATE_DISCONNECTED`, `CONNECTION_STATE_REJECTED`.
- `WatchUi.DataField` extends `WatchUi.View`; `compute(info)` may return `null`
  for a display-only field, custom rendering in `onUpdate(dc)`.

## TODOs

- Calibrate `MODE_LABELS` (default `Mode N`) and confirm the mode index → label
  mapping in Spike B.
- Replace the vector `launcher_icon.xml` with a proper per-resolution PNG before
  store submission.
- ~~Some SDK versions report BLE advertising-parse quirks (`getServiceUuids()`
  occasionally empty).~~ Implemented: `advertisesBridge()` now falls back to
  scanning `ScanResult.getRawData()` for the little-endian 128-bit UUID bytes
  when `getServiceUuids()` misses (a documented CIQ parsing bug for 128-bit
  service UUIDs).
- Optional: expose speed / SOH on larger (1030/1040/1050) fields where there's
  room for a third value.
