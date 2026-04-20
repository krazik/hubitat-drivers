# hubitat-drivers

Custom Hubitat Elevation device drivers.

Built on top of [prestonbrown/hubitat-drivers](https://github.com/prestonbrown/hubitat-drivers) — thanks to Preston Brown for the structural patterns and logging conventions used as a starting point.

---

## Drivers

### Aqara H2 US 2-Button Switch ([AqaraH2Switch.groovy](AqaraH2Switch.groovy))

Supports the Aqara H2 US 1-Channel 2-Button switch paired via Zigbee.

**Compatible models:** `lumi.switch.agl004`, `lumi.switch.agl009`

**Features:**
- Relay on/off control
- Button 1 (top) and Button 3 (bottom): push, hold, double-tap events on the parent device
- No child device creation

**Button mapping:**
| Physical button | Hubitat button # | Events supported |
|---|---|---|
| Top | 1 | pushed, held, doubleTapped |
| Bottom | 3 | pushed, held, doubleTapped |

**Installation:**
1. In Hubitat, go to **Drivers Code** → **+ New Driver**
2. Paste the contents of `AqaraH2Switch.groovy` and save
3. Assign the driver to your paired Aqara device
4. Click **Configure** and then **Refresh**
