# Spike B — bike telemetry calibration checklist

Tools: **nRF Connect for Mobile** on any phone (ideally the Stark phone; the bike serves only **one**
BLE connection, so first **force-stop / disable the stock Stark app** so it isn't holding the link).

Goal: pin down the three things the gist leaves undefined for *your* bike:
1. Battery-% scaling (raw uint16 → real %).
2. Mode index → name mapping.
3. Whether the read-only telemetry path needs the VIN-derived PIN.

---

## UUID reference (base tail = ASCII "Stark Future")

All UUIDs are `XXXXXXXX-5374-6172-4b20-467574757265`.

| Purpose | Service | Characteristic | Notes |
|---|---|---|---|
| **Battery SOC** | `00006000-…` | `00006004-…` | bytes 0–1 LE uint16 = SOC (scaling ↓), 2–3 SOH, 4–5 DC bus |
| **Ride "map" (mode)** | `00002000-…` (LIVE) | `00002004-…` | byte 0 = active map index |
| Status / walk mode | `00001000-…` | `00001002-…` | `miscBits & 15` = walking mode; also charge/gear/fault bits |
| VIN | `00001000-…` | `00001003-…` | 17 ASCII bytes → feeds the PIN |
| Live speed / rpm | `00002000-…` | `00002001-…` | int16 speed ÷10 km/h, int16 rpm |

**Reads return nothing — you must SUBSCRIBE (enable notifications).**

---

## Procedure

### 0. Connect
- [ ] Stop the stock Stark app (Settings ▸ Apps ▸ Stark ▸ Force stop), else it hogs the one connection.
- [ ] nRF Connect ▸ SCANNER. The bike **advertises its name = its VIN** — find that entry, **Connect**.
- [ ] If connection needs pairing, note the prompt (see step 4). If it connects without a PIN, good —
      the read-only path may be "Just Works".
- [ ] Expand services; confirm the three custom services above are present.

### 1. Battery-% scaling (the important one)
- [ ] Note the bike's charge from the **stock display** right now (e.g. 84%).
- [ ] Subscribe to `00006004-…`. Read the first 2 bytes as **little-endian** (byte0 = LSB).
      Compute `raw = byte0 + byte1*256`.
- [ ] Fill the table at two or three different charge levels (charge/ride a bit between rows):

  | Stock display % | byte0 | byte1 | raw (=b0+b1·256) | raw ÷ display % |
  |---|---|---|---|---|
  |   |   |   |   |   |
  |   |   |   |   |   |

- [ ] The last column reveals the scale: **≈10 → SOC% = raw/10** (most likely), ≈100 → raw/100,
      ≈1 → already a percent. Record the divisor — the Android app uses it verbatim.

### 2. Mode index → name
- [ ] Subscribe to `00002004-…` (LIVE map). It's a single byte.
- [ ] Cycle through each ride map on the bike (the maps you configured in the Stark app). For each,
      record the byte value and the name you gave it:

  | Map name (your label) | byte value |
  |---|---|
  |   |   |
  |   |   |
  |   |   |
  |   |   |

- [ ] This table becomes the index→label lookup rendered on the Garmin (e.g. `3 → "SPORT"`).

### 3. (optional) Walking mode
- [ ] Subscribe to `00001002-…`; `miscBits` = bytes 0–1 LE. Watch `miscBits & 15` while toggling
      walk-assist. Record which value = walk on/off, if you want it as a mode too.

### 4. Pairing / PIN reality-check
- [ ] Did any characteristic require pairing before it would notify? Note **which** and **when**.
- [ ] If a passkey was requested, compute the expected PIN from the VIN and compare:

  ```
  key   = VIN + "-19700101"
  h     = SHA256(key)                        # 32 bytes
  digit(i,xor,add) = ((h[i] XOR xor) + add) % 10
  pin   = digit(3,197,228)
        + digit(14,236,209)*10
        + digit(9,158,100)*100
        + digit(15,179,239)*1000             # zero-pad to 6
  ```

- [ ] Record: **PIN required? Y/N.** If N for the telemetry characteristics, the Android app can skip
      the pairing dance entirely (simpler, and matches what the `svag-mini` ESP32 firmware suggests).

---

## Output of this spike

Three facts to hand to the main build:
1. **SOC divisor** = ______
2. **Mode map** = { index: name, … }
3. **PIN required for read-only telemetry?** = Y / N

Reference implementation to check behaviour against: <https://github.com/b1naryth1ef/svag-mini>.
