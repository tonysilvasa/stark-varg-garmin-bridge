# Stark → Garmin Bridge (Android)

Runs on the **Stark phone**. It is simultaneously:

1. A **BLE central** to the Stark Varg bike — scans for the device whose
   advertised name equals the **VIN**, connects, subscribes to notifications on
   the 4 Stark characteristics (SOC, map/mode, speed, status), and decodes them
   into a `BikeState`.
2. A **BLE peripheral** — hosts a GATT server with the bridge service and a
   single telemetry characteristic (READ + NOTIFY), advertises it as
   CONNECTABLE with the 128-bit service UUID, and pushes an 8-byte
   little-endian packet to a subscribed **Garmin**.

The bike serves only one BLE connection at a time — this phone holds it and
re-broadcasts a distilled telemetry packet the Garmin can read.

## Build & run

1. Open `android-bridge/` in **Android Studio** (AGP 8.5.2, Kotlin 1.9.24,
   JDK 17, compileSdk 34, minSdk 26).
2. On the phone: enable **Developer options → USB debugging**, plug in.
3. Press **Run** (installs the `app` module).
4. On first Start you will be prompted for **Bluetooth (Scan/Connect/Advertise)**
   and, on Android 13+, **Notifications**. Grant them.

## Using it

1. Enter the bike **VIN** (17 chars, the name the bike advertises). It is saved
   in SharedPreferences and the **derived PIN** appears immediately.
2. Turn the bike on / wake its BLE.
3. Press **Start bridge**. A persistent notification shows live
   battery% / mode / bike & garmin connection state. The bridge auto-reconnects
   to the bike if it drops, and keeps advertising for the Garmin.
4. On the **Garmin**, pair/connect to the bridge and read the telemetry
   characteristic. If Android shows a pairing dialog:
   - `PAIRING_VARIANT_PIN` is auto-filled from the derived PIN.
   - Other variants use the OS dialog — type the PIN shown in the UI.
   - Many links are **Just Works** (no PIN) — nothing to do.

## Bridge BLE contract (must match the Garmin app byte-for-byte)

| Item | UUID |
| --- | --- |
| Bridge service | `4761726d-696e-4272-6964-676500000001` |
| Telemetry char (READ+NOTIFY) | `4761726d-696e-4272-6964-676500000002` |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` |

Telemetry packet — 8 bytes, little-endian:

| Byte | Field | Notes |
| --- | --- | --- |
| 0 | `version` | `0x01` |
| 1 | `battery_pct` | 0–100; `0xFF` unknown |
| 2 | `mode_index` | active map index; `0xFF` unknown |
| 3 | `status_flags` | bit0 on, bit1 charging, bit2 charger-connected, bit3 in-gear, bit4 fault-active |
| 4–5 | `speed_kmh_x10` u16 LE | km/h×10; `0xFFFF` unknown |
| 6 | `soh_pct` | `0xFF` unknown |
| 7 | `seq` | increments each publish (liveness) |

Encoding lives in `BridgeContract.encodePacket`.

## Calibration constants (Spike B)

Set from Spike B in `StarkProtocol.kt`:

- **`SOC_DIVISOR`** (default `10.0`) — `battery_pct = round(socRaw / SOC_DIVISOR)`
  then clamped 0..100. Adjust the `var SOC_DIVISOR` at the top of
  `StarkProtocol`.
- **`MODE_LABELS`** (default empty → labels fall back to `"Mode N"`) — populate
  `StarkProtocol.MODE_LABELS[index] = "…"` with the real ride-mode names once
  Spike B maps them.

## PIN derivation

Only used if the bike demands a passkey:

```
key = VIN + "-19700101"
h   = SHA256(key)                       # 32 bytes
d(i, xor, add) = ((h[i] XOR xor) + add) % 10
pin = d(3,197,228)
    + d(14,236,209) * 10
    + d(9,158,100)  * 100
    + d(15,179,239) * 1000              # padded to 6 digits
```

Implemented in `StarkProtocol.getVehiclePin(vin)` and always shown read-only in
the UI.

## File map

- `BridgeContract.kt` — bridge UUIDs + `encodePacket`.
- `StarkProtocol.kt` — Stark UUIDs, `BikeState`, decoders, `getVehiclePin`,
  `SOC_DIVISOR`, `MODE_LABELS`.
- `BikeClient.kt` — BLE central (scan → connect → notify → decode → StateFlow).
- `GarminServer.kt` — BLE peripheral (GATT server + advertiser + notify).
- `BridgeService.kt` — foreground service (`connectedDevice`) wiring bike →
  peripheral, notification, pairing receiver.
- `MainActivity.kt` — UI, permissions, persistence, live status, log.
```
