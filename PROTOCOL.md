# Bridge protocol — the contract between the two apps

The Android app (on the Stark phone) and the Garmin Connect IQ data field share exactly one thing:
a custom BLE service the phone advertises and the Garmin subscribes to. Everything else is private to
each side. Keep this file authoritative — if you change a UUID or a byte offset, change it in both apps.

## BLE service (phone = peripheral, Garmin = central)

| Item | UUID |
|---|---|
| Bridge service | `4761726d-696e-4272-6964-676500000001` |
| Telemetry characteristic (READ + NOTIFY) | `4761726d-696e-4272-6964-676500000002` |
| CCCD descriptor (standard) | `00002902-0000-1000-8000-00805f9b34fb` |

- The phone advertises **connectable**, with the 128-bit bridge service UUID in the advertising data.
- The Garmin registers a CIQ profile for that service + characteristic, scans, pairs, and enables
  notifications on the CCCD. The phone pushes a notification whenever the bike data changes (and at least
  every ~2 s as a keepalive).

## Telemetry packet — value of the characteristic (8 bytes, little-endian)

| Offset | Field | Type | Meaning |
|---|---|---|---|
| 0 | `version` | u8 | protocol version, currently `0x01` |
| 1 | `battery_pct` | u8 | 0–100; `0xFF` = unknown |
| 2 | `mode_index` | u8 | active ride "map" index; `0xFF` = unknown |
| 3 | `status_flags` | u8 | bit0 bike-on, bit1 charging, bit2 charger-connected, bit3 in-gear, bit4 fault-active |
| 4–5 | `speed_kmh_x10` | u16 LE | speed in km/h × 10; `0xFFFF` = unknown |
| 6 | `soh_pct` | u8 | battery state-of-health %; `0xFF` = unknown |
| 7 | `seq` | u8 | increments every publish; lets the Garmin detect a stale/dead feed |

Under the 20-byte CIQ MTU ceiling with room to spare.

## Stark bike GATT (decoded on the Android side only — the Garmin never sees this)

Base UUID tail `5374-6172-4b20-467574757265` = ASCII "Stark Future". The bike **advertises its name = its
VIN**; scan and match on that. **Subscribe to notifications — plain reads return nothing.**

| Data | Service | Characteristic | Decode (bytes are LE) |
|---|---|---|---|
| Battery SOC/SOH | `00006000-…` | `00006004-…` | `socRaw = b0\|b1<<8` → `battery_pct = round(socRaw / SOC_DIVISOR)` clamp 0–100. `sohRaw = b2\|b3<<8`. |
| Ride mode ("map") | `00002000-…` | `00002004-…` | `mode_index = b0` |
| Speed | `00002000-…` | `00002001-…` | `speed_kmh_x10 = int16(b0\|b1<<8)` (already ×10) |
| Status bits | `00001000-…` | `00001002-…` | `infoBits = b8\|b9<<8`: on=`(infoBits>>4)&1`, charging=`infoBits&1`, chargerConn=`(infoBits>>1)&1`, inGear=`(infoBits>>3)&1`. `alertBits = b4\|b5<<8`, faultActive = `alertBits != 0`. |
| VIN | `00001000-…` | `00001003-…` | 17 ASCII bytes |

## Two constants to calibrate in Spike B

Both default to a best guess and are overridable without touching logic:

- **`SOC_DIVISOR`** — default **10** (raw uint16 assumed 0.1% resolution). Verify on the bike.
- **`MODE_LABELS`** — map of `mode_index` → display name (e.g. `3 → "SPORT"`). Unknown until you record
  each of your configured maps on the bike. Default is `"Mode N"`.

## VIN → pairing PIN (Android side, only if the bike demands a passkey)

```
key   = VIN + "-19700101"
h     = SHA256(key)                         # 32 bytes
d(i,xor,add) = ((h[i] XOR xor) + add) % 10
pin   = d(3,197,228) + d(14,236,209)*10 + d(9,158,100)*100 + d(15,179,239)*1000   # pad to 6
```
