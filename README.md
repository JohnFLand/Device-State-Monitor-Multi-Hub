# Sonoff SNZB-02 Series Temperature and Humidity Sensor — Hubitat Driver

**Driver name:** Sonoff SNZB-02 Series Temperature and Humidity Sensor  
**Driver version:** 1.4.2  
**Devices:** Sonoff SNZB-02P, SNZB-02D, SNZB-02LD, SNZB-02DR2 and SNZB-02M Zigbee sensors  
**Manufacturer strings:** `SONOFF` or `eWeLink`

---

## Overview

One Hubitat driver for five Sonoff battery-powered Zigbee sensors. It recognises the model
from the device and switches on the features that model has:

| Feature | SNZB-02P / 02D / 02LD | SNZB-02DR2 | SNZB-02M |
|---|:-:|:-:|:-:|
| Temperature, humidity, battery | ✓ | ✓ | ✓ |
| Reporting intervals and thresholds set from Hubitat | ✓ | ✓ | ✓ |
| Hubitat-side temperature and humidity offsets | ✓ | ✓ | ✓ |
| Temperature settings in °C or °F; temperature decimal places | ✓ | ✓ | ✓ |
| Dew point and vapour pressure deficit (VPD) | ✓ | ✓ | ✓ |
| Min/max with timestamps, daily auto-reset | ✓ | ✓ | ✓ |
| Rolling 24-hour and 2–365-day averages of temperature and humidity | ✓ | ✓ | ✓ |
| `healthStatus` and `sensorStatus` | ✓ | ✓ | ✓ |
| Comfort thresholds and LCD unit written to the device | | ✓ | |
| Device's own 24-hour min/max (as on its LCD) | | ✓ | |
| Readings sent from Hubitat shown on its LCD (EXT1 / EXT2) | | ✓ | |
| Offsets applied in the sensor, so its LCD matches Hubitat (optional) | | ✓ | |
| Comfort thresholds and calibration read from the device | | | ✓ |
| Barometric pressure (0.1 hPa, selectable unit) | | | ✓ |

**SNZB-02LD.** The SNZB-02LD is Sonoff's probe thermometer with an LCD: it measures temperature
through its 1.5 m probe (−40 to 115 °C, within the driver's sanity limits) and has no humidity
sensor. It uses the same features as the SNZB-02P / 02D; `humidity`, `dewPoint`, `vpd` and the
humidity min/max and averages stay empty on it. You can turn off **Calculate dew point and VPD**
for it.

This driver replaces three separate drivers — **SNZB-02x** (1.5.0, for the SNZB-02P and
SNZB-02D), **SNZB-02DR2** (1.13.0) and **SNZB-02M** (2.4.0). Those remain available but are
frozen apart from serious bug fixes. See **Switching from the separate drivers** below.

---

## Installation

1. In Hubitat, go to **Drivers Code → New Driver**
2. Click **Import**, paste this URL and click **Import**, then **Save** (or paste the full
   driver code instead):  
   `https://raw.githubusercontent.com/JohnFLand/Sonoff_SNZB-02_Series-Zigbee-driver-for-Hubitat/main/Sonoff_SNZB-02_Series.groovy`  
   To update later, open the existing driver in **Drivers Code**, click **Import** (the URL is
   filled in) and **Save**. Always update the existing driver rather than adding a new one,
   so your devices, settings and rules stay attached to it.
3. The driver's fingerprints match automatically when a sensor is paired (except the
   SNZB-02LD, which has no fingerprint yet: select the driver by hand as below). For a sensor that
   is already paired, open the device and choose
   **Sonoff SNZB-02 Series Temperature and Humidity Sensor** from the **Type** dropdown, then
   **Save Device**
4. Press the sensor's button, then click **Configure**. This detects the model, sends the
   reporting configuration and reads the current values. The log shows which model was
   detected, for example `Model SNZB-02DR2: using SNZB-02DR2 features`.
5. Reopen the device page so the preferences for that model appear, set them, wake the
   sensor and click **Save Preferences**.

### Switching from the separate drivers

For each device:

1. Change **Type** to **Sonoff SNZB-02 Series Temperature and Humidity Sensor** and click
   **Save Device**.
2. Press the sensor's button, then click **Save Preferences**. This detects the model, sends
   your settings to the sensor and tidies up attributes the old drivers used (`rtt`, and on
   the SNZB-02DR2, `comfortHumiMin` / `comfortHumiMax`).
3. Check the preferences. The settings have the same names as in the separate drivers, so
   they normally carry over; the temperature units default to °C, which is what the older
   drivers used.

**Attribute changes to check in your rules and dashboards:**

| Old driver | Old attribute | Now |
|---|---|---|
| SNZB-02DR2 | `comfortHumiMin`, `comfortHumiMax` (text, e.g. `40%`) | `comfortHumidityMin`, `comfortHumidityMax` (numbers) |
| SNZB-02DR2 | `comfortTempMin`, `comfortTempMax` (text, e.g. `64.4°F`) | Same names, now numbers with a unit |
| SNZB-02x, SNZB-02DR2 | — | New: `dewPoint` and `vpd` (can be turned off) |
| SNZB-02M | `humidity`, `Humidity Min`/`Max` to 0.1 % | Whole percent |
| all | `rtt` and the Ping command | Removed in the separate drivers' latest versions; not present here |

Everything else keeps its name and format. The Hubitat-tracked min/max carries over.

---

## Model detection

The model comes from the device's model string, recorded when it was paired:

| Model string | Treated as |
|---|---|
| `SNZB-02DR2` | SNZB-02DR2 |
| `SNZB-02M` | SNZB-02M |
| `SNZB-02P`, `SNZB-02D`, `SNZB-02LD` | SNZB-02P / SNZB-02D / SNZB-02LD |
| anything else | SNZB-02P / SNZB-02D / SNZB-02LD, with a warning in the log |

If the model is not recognised, or you want to try a model's features on another sensor, set
**Device model** in the preferences. Save, then reopen the page to see that model's
settings.

**The Pressure Measurement capability is declared for every model**, because Hubitat
capabilities cannot vary by device. Only the SNZB-02M ever reports a pressure value, but
SNZB-02P, 02D and 02DR2 sensors will appear in apps' pressure-sensor pickers. If that is a
problem, comment out the `capability 'PressureMeasurement'` line and use the separate
SNZB-02M driver for any SNZB-02M sensors.

---

## The sensors are sleepy devices

These sensors spend almost all their time asleep, waking briefly to send readings. While
asleep they do not hear the hub, so anything the hub sends — reporting configuration,
SNZB-02DR2 settings, Configure, Refresh, Initialize — is lost unless the sensor is awake.

**Press the sensor's button immediately before clicking Save Preferences, Configure,
Refresh or Initialize.**

With description text logging on, the log confirms each request:

- `Temperature reporting configuration accepted by device` (likewise Humidity, Pressure and Battery)
- `FC11 attribute write accepted by device` (SNZB-02DR2 settings)
- A warning such as `FC11 write of attribute 0x0007 rejected (status 0x8D)` if the sensor refuses a change

If none of these lines appear after a Save, the sensor was asleep — wake it and save again.

Sleep affects only what the sensor *hears*. It does not limit how often it *sends*
readings — see the FAQ.

The SNZB-02DR2 remote-display commands are the exception: no button press is needed. The
driver re-sends their values automatically when the sensor next wakes — see
**Remote display (SNZB-02DR2)**.

---

## Supported Capabilities

| Capability | Notes |
|---|---|
| `TemperatureMeasurement` | Standard ZCL cluster 0x0402 |
| `RelativeHumidityMeasurement` | Standard ZCL cluster 0x0405 |
| `PressureMeasurement` | Standard ZCL cluster 0x0403 — SNZB-02M only (see **Model detection**) |
| `Battery` | Percentage from cluster 0x0001 attr 0x0021; SNZB-02P / 02D / 02DR2 also read the voltage (attr 0x0020) on Refresh |
| `Refresh` | Reads current values and the model's device settings (wake the sensor first) |
| `Configuration` | **Configure** detects the model, sends the reporting configuration and reads current values |
| `Sensor` | Base capability |

---

## Current States

Temperatures (including dew point, comfort temperatures and the SNZB-02DR2's own min/max) are
shown in the hub's temperature scale (**Settings → Location**), rounded half-up to the number
of places set in **Temperature decimal places** (default 1). `temperatureCalibration` also
follows the hub's scale: to 0.01 in °C and to 0.1 in °F, since the sensor stores it in 0.01 °C
steps (0.018 °F). As a temperature difference it is converted without the +32 (1.11 °C = 2.0 °F).

### All models
| Attribute | Description |
|---|---|
| `temperature` | Current temperature, with the Hubitat temperature offset applied |
| `humidity` | Current relative humidity in whole percent, with the Hubitat humidity offset applied (clamped to 0–100 %) |
| `battery` | Battery level %. A Refresh on the SNZB-02P / 02D / 02DR2 also reads the battery voltage and estimates a percentage from it (2.1–3.0 V) |
| `dewPoint` | Dew point, calculated from temperature and humidity |
| `vpd` | Vapour pressure deficit in kPa, calculated from temperature and humidity |
| `temperatureAvg24h` / `humidityAvg24h` | Time-weighted average over the last 24 hours (humidity to 0.1 %). See **Averages** |
| `temperatureAvgLongTerm` / `humidityAvgLongTerm` | Time-weighted average over the last N days, N set by **Averaging period (days)**. The value is the average, not the period's length; the length is shown in `averagesInfo` |
| `averagesInfo` | How much data each average covers, e.g. `24 h average: 24.0 of 24 hours; 7-day average: 2.5 of 7 days (collecting since 2026-11-19 11:30)` |
| `Temperature Min` / `Temperature Max` | Lowest / highest temperature since the last reset, with the time it was recorded, e.g. `72.4°F @ 11-07 14:22` |
| `Humidity Min` / `Humidity Max` | Lowest / highest humidity since the last reset, with the time it was recorded |
| `Min/Max Reset` | Time of the last reset (manual or automatic) |
| `Readings Since Reset` | Number of temperature readings since the last reset |
| `Last Reading` | Time of the most recent temperature report |
| `sensorStatus` | `Waiting for first report – press the sensor's button, then click Refresh` until the first reading, then `Reporting` |
| `healthStatus` | `online` / `offline` / `unknown` — see below |

**Health status** is passive. It becomes `online` whenever any message arrives from the
sensor. A check runs every 3 hours and counts the checks with no message in between; at the
fifth such check the sensor is marked `offline`, so a silent sensor is flagged 12–15 hours
after its last message. This avoids false alarms from a sleepy device, but a dead battery is
not flagged quickly.

### SNZB-02DR2
| Attribute | Description |
|---|---|
| `Device Temp Max` / `Device Temp Min` | The sensor's own 24-hour temperature extremes, as shown on its LCD. Pushed by the sensor about hourly; also read on Refresh |
| `Device Humidity Max` / `Device Humidity Min` | The sensor's own 24-hour humidity extremes |
| `Device Temp Avg` / `Device Humidity Avg` | The sensor's own average temperature and humidity, apparently over the same 24 hours. Pushed with the min/max about hourly; also read on Refresh |
| `comfortTempMin` / `comfortTempMax` | Comfort temperatures stored in the sensor (❄️ below the minimum, 🔥 above the maximum), read back after each Save |
| `comfortHumidityMin` / `comfortHumidityMax` | Comfort humidity limits stored in the sensor, % |
| `lcdTempUnit` | `Celsius` or `Fahrenheit` — the unit the LCD is actually using, read back after each Save |
| `remoteDisplay` | State of the LCD's EXT line: `off`, `pending` (values sent, not yet confirmed by the sensor), `on`, `offline` or `unsupported` |
| `ext1Temperature` / `ext1Humidity` | Values on the EXT1 line, published once the sensor confirms it has stored them |
| `ext2Temperature` / `ext2Humidity` | Values on the EXT2 line (firmware 1.0.5 and later) |
| `temperatureCalibration` / `humidityCalibration` | Calibration stored in the sensor (temperature in the hub's scale, humidity in %), read back after each Save and on Refresh. Non-zero when **Apply offsets in the sensor** is on, or if set in the eWeLink app |

The Hubitat-tracked min/max accumulates from install or the last reset. The device's own
24-hour values are kept by the sensor's firmware and can only be reset with a ~10-second hold
of its button. Writing new values into them over Zigbee does not work: on firmware 1.0.2 the
sensor answers "write accepted" but keeps its min/max unchanged (tested in 1.3.0).

The averages (FC11 `0x200A` / `0x200D`) were previously logged as unexplained "reference"
values. Zigbee2MQTT names the same attributes "average" on Sonoff's SS-Z2N sensor, and on the
SNZB-02DR2 they always fall between the sensor's own min and max and change slowly, as a
24-hour average would. Sonoff has not documented them.

### SNZB-02M
| Attribute | Description |
|---|---|
| `pressure` | Barometric pressure in the unit chosen in **Pressure unit**, with the Hubitat pressure offset applied |
| `comfortTempMin` / `comfortTempMax` | Comfort temperatures stored in the sensor (read-only here) |
| `comfortHumidityMin` / `comfortHumidityMax` | Comfort humidity limits stored in the sensor, % (read-only here) |
| `temperatureCalibration` | Device-side temperature calibration, in the hub's temperature scale (read-only here) |
| `humidityCalibration` | Device-side humidity calibration, % (read-only here) |
| `pressureCalibration` | Device-side pressure calibration, hPa (read-only here) |

---

## Preferences

Model-specific preferences appear only for that model.

| Preference | Models | Default | Valid range | Notes |
|---|---|---|---|---|
| Debug logging | all | enabled | | Turns itself off after 24 hours |
| Description text logging | all | enabled | | Logs readings and confirmations |
| Device model | all | Automatic | Automatic / SNZB-02P / 02D / 02LD / SNZB-02DR2 / SNZB-02M | See **Model detection** |
| Units for temperature settings | all | °C | °C / °F | Unit for the temperature threshold, temperature offset and (SNZB-02DR2) comfort temperatures. See **Temperature units** |
| Temperature decimal places | all | 1 | 0 / 1 / 2 | Rounded half-up. Use 0 for dashboard tiles that do not handle decimals |
| Min time between temperature reports | all | 10 s | 1–3600 | Sent to the sensor on Save |
| Max time between temperature reports | all | 3600 s | 10–43200 | Heartbeat: a report is sent at least this often even with no change. Sent on Save |
| Temperature reporting threshold | all | 0.2 °C | 0.1–5.0 °C (0.2–9.0 °F) | Minimum change that triggers a report. Sent on Save |
| Min time between humidity reports | all | 10 s | 1–3600 | Sent on Save |
| Max time between humidity reports | all | 3600 s | 10–43200 | Sent on Save |
| Humidity reporting threshold | all | 1 % | 1–10 | Sent on Save |
| Pressure unit | 02M | hPa | hPa / kPa / inHg / mmHg | Unit of the `pressure` attribute |
| Min time between pressure reports | 02M | 60 s | 1–3600 | Sent on Save |
| Max time between pressure reports | 02M | 3600 s | 10–43200 | Sent on Save |
| Pressure reporting threshold | 02M | 1 hPa | 1–50 | Sent on Save |
| Temperature offset | all | 0.0 °C | −20–20 °C (−36–36 °F) | Added to every reading in Hubitat, or on the SNZB-02DR2 optionally in the sensor (see **Apply offsets in the sensor**) |
| Humidity offset | all | 0.0 % | −20–20 % | Added to every reading in Hubitat, or on the SNZB-02DR2 optionally in the sensor |
| Pressure offset | 02M | 0.0 hPa | −50–50 hPa | Added to every reading in Hubitat; always entered in hPa |
| Calculate dew point and VPD | all | on | | Turning it off removes `dewPoint` and `vpd` |
| Calculate averages | all | on | | Rolling temperature and humidity averages. Turning it off removes the average attributes and the stored totals |
| Averaging period (days) | all | 7 | 2–365 | Length of the longer average |
| LCD temperature unit | 02DR2 | Leave unchanged | Leave unchanged / °C / °F | Unit on the sensor's screen |
| Comfort temperature minimum | 02DR2 | 18.0 °C | −10–60 °C (14–140 °F) | ❄️ shown below this value. Written to the sensor |
| Comfort temperature maximum | 02DR2 | 26.0 °C | −10–60 °C (14–140 °F) | 🔥 shown above this value. Written to the sensor |
| Comfort humidity minimum | 02DR2 | 40 % | 5–95 | ☀️ shown below this value. Written to the sensor |
| Comfort humidity maximum | 02DR2 | 60 % | 5–95 | 💧 shown above this value. Written to the sensor |
| Apply offsets in the sensor | 02DR2 | off | | Writes the temperature and humidity offsets to the sensor's calibration, so its LCD shows the same values as Hubitat. See **Offsets in the sensor (SNZB-02DR2)**. Wake the sensor before saving |
| Remote display method | 02DR2 | Automatic | Automatic / Older method / Remote sources | How the remote-display commands reach the LCD. Automatic chooses by firmware. See **Remote display (SNZB-02DR2)** |
| Auto-reset min/max daily | all | off | | Resets the Hubitat-tracked min/max every day at the time below |
| Auto-reset time | all | `00:00` | | Time of day for the daily reset |

Decimal preferences are range-checked by the driver rather than by the preferences page. A
value outside the valid range is replaced with the nearest valid value on Save, and a warning
is written to the log.

**Temperature units.** The threshold, offset and (SNZB-02DR2) comfort temperatures are entered
in the unit chosen in **Units for temperature settings**. When you change the unit, the driver
converts the values already entered so they keep the same meaning (for example a 0.2 °C
threshold becomes 0.36 °F, and an 18 °C comfort minimum becomes 64.4 °F) and logs each
conversion. A value you change in the same Save as the unit is taken as already being in the
new unit. To be safe, change the unit and Save first, then adjust the values. Readings in
Hubitat always follow the hub's temperature scale, whatever this preference is set to.

**Changes take effect immediately.** When you click **Save Preferences**, the driver
re-publishes the current readings (temperature, humidity, pressure, dew point and VPD) from the
last values it received, with the new offsets, decimal places and pressure unit applied. This
part works whether or not the sensor is awake. Re-published events are marked `digital` and
logged with "(re-published)". The min/max, `Last Reading` and `Readings Since Reset` are not
changed; use **Reset Min/Max** if you want the min/max recalculated on the new offset or shown
with the new number of decimal places.

**Sanity checks.** Raw readings outside −40 to 125 °C, 0 to 100 % or 300 to 1100 hPa are
treated as corrupt frames: they are ignored and logged as a warning. The check is made on the
sensor's own value, before any Hubitat offset is applied (with **Apply offsets in the sensor**
on, the sensor's value already includes its calibration).

**Offsets in the sensor (SNZB-02DR2).** Normally the offsets are applied only in Hubitat, so the
sensor's screen shows the uncorrected values. With **Apply offsets in the sensor** on, Save
writes the offsets to the sensor's calibration attributes (FC11 `0x2003` and `0x2004`) and reads
them back. The sensor then applies them to its screen and to the readings it sends (confirmed
on firmware 1.0.2).

- Hubitat adds only the part of the offset the sensor has not confirmed applying, so a reading
  never gets the offset twice, even if the sensor was asleep during the Save. Until the read-back
  arrives, Hubitat keeps adding the whole offset.
- Turning the setting off writes 0 back to the sensor; once the sensor confirms 0, the offsets
  are applied in Hubitat again. Wake the sensor before saving either way.
- Turning it on replaces any calibration set earlier in the eWeLink app.
- The `temperatureCalibration` and `humidityCalibration` attributes show what the sensor holds;
  for example, a +3 °F offset shows as `temperatureCalibration` 3.0 °F on a °F hub (the sensor
  stores it as 1.67 °C). Differences of less than half the sensor's 0.01 step are treated as
  that rounding, so Hubitat adds nothing and matches the sensor's screen.

---

## Commands

| Command | Description |
|---|---|
| **Configure** | Detects the model, sends the reporting configuration (and SNZB-02DR2 settings), then reads current values and the model's device settings. Wake the sensor first. |
| **Refresh** | Reads current values and the model's device settings; on the SNZB-02DR2 also its own 24-hour min/max. Wake the sensor first. |
| **Reset Min/Max** | Clears the Hubitat-tracked min/max and the readings counter. Also run automatically by the daily auto-reset. Does not affect the SNZB-02DR2's own 24-hour values. |
| **Reset Averages** | Clears the rolling averages and their stored totals, and starts collecting again from the current readings. Separate from **Reset Min/Max**, and not affected by the daily auto-reset. |
| **Initialize** | Clears all driver state, including the min/max, sets `sensorStatus` to "Waiting for first report…", then runs Configure. Wake the sensor first. Not needed just to apply preference changes — Save Preferences does that. |
| **Set Remote Temperature** | SNZB-02DR2 only. Shows a temperature on the LCD's EXT line. Parameters: the temperature, in the hub's scale, and the slot (`EXT1`, the default, or `EXT2`). No need to wake the sensor. |
| **Set Remote Humidity** | SNZB-02DR2 only. Shows a humidity (%) on the EXT line. Same parameters. Needs firmware 1.0.4 or later. |
| **Remote Display Off** | SNZB-02DR2 only. Clears the EXT line, so the LCD shows only the sensor's own readings again. |

---

## Remote display (SNZB-02DR2)

The SNZB-02DR2 can show a reading from another sensor on its LCD, on the line labelled
**EXT1** (and, on newer firmware, **EXT2**). Sonoff offers this through its own gateways; this
driver does it from Hubitat. Thanks to **temes33** on the Hubitat Community forum for
discovering that it works from Hubitat.

**Typical use:** a Rule Machine rule triggered when another sensor's temperature changes, with
the action **Run custom action → this sensor → `setRemoteTemperature`**, parameters: a number
(the other sensor's temperature, e.g. from a variable) and a string, `EXT1`.

**What the sensor accepts depends on its firmware** (shown in the device data as
**Firmware MT**, e.g. `1286-0810-00001002` = 1.0.2, or **Software Build**):

| Firmware | Method | Slots | Humidity |
|---|---|---|---|
| Before 1.0.5 | Older method: FC11 attributes `0x600E`, `0x600D`, `0x6018` | EXT1 only | Needs 1.0.4 or later |
| 1.0.5 and later | Remote sources: FC11 attributes `0x600E` and `0x601E` (as in Zigbee2MQTT) | EXT1 and EXT2 | Yes |

With **Remote display method** on Automatic, the driver chooses from the firmware version. If a
sensor rejects the 1.0.5 method, the driver switches to the older method by itself. On firmware
before 1.0.4, the first remote humidity is rejected; the driver logs one warning and sends
temperature only from then on. Saving preferences makes the driver check both again (for
example after a firmware update).

**Sleeping sensor.** A value is sent at once, which works if the sensor is awake. If it is not
confirmed, the driver sends it again the moment the sensor next sends anything — a reading,
or the message it sends when its button is pressed — at most once every 8 seconds and up to 10
times. Only the latest value for each slot is kept, so a rule can call the command as often
as it likes. `remoteDisplay` shows `pending` until the sensor confirms, then `on`; the log
shows, for example, `Remote display: EXT1 temperature 70.0°F confirmed by the sensor`.

**On the LCD:**

- Temperatures are entered in the hub's scale and sent in °C; the sensor converts them to its
  own LCD unit. Its conversion to °F cuts off digits instead of rounding (70 °F sent as
  21.11 °C showed 69.9), so on a °F screen the driver sends the value that displays exactly
  the tenth entered.
- On firmware 1.0.2 the EXT1 line takes the place of the temperature min/max on the screen.
  The humidity MAX/MIN stay visible. **Remote Display Off** brings the temperature min/max back.
- In tests on firmware 1.0.2 the screen showed a new value within seconds of the sensor
  receiving it. The sensor also redraws the EXT line when its button is pressed briefly, and
  updates remote values about every 30 minutes.
- Only numbers can be shown. Accepted ranges: −50 to 125 °C and 0 to 100 % with the older
  method; −20 to 60 °C and 0 to 99.9 % with the 1.0.5 method.
- The EXT line cannot show 99.0 or more in the LCD's unit: it shows `HHH` instead (seen with
  99, 99.0 and 105 °F; 98.9 °F shows correctly). The driver therefore sends higher
  temperatures as 98.9 and logs a note.

**Tested:** firmware 1.0.2, older method, temperature on EXT1 (20, 70, 73, 75 and 77 °F),
with the button and with automatic re-send, and **Remote Display Off**. The 1.0.5 method and
EXT2 follow the Zigbee2MQTT implementation but have not yet been tested on a sensor; reports
from anyone with firmware 1.0.5 are welcome.

---

## Pressure (SNZB-02M)

The SNZB-02M sends pressure in two attributes of the Pressure Measurement cluster (`0x0403`):
`0x0000` (INT16, whole hPa) and `0x0004` (INT32, 0.01 hPa), sometimes in the same frame. The
driver publishes from `0x0004` whenever it is present, giving 0.1 hPa precision, and uses
`0x0000` only when no high-resolution value has arrived for more than twice the maximum
pressure reporting interval plus two minutes. This stops the value bouncing between, say,
1006 and 1006.6.

The value is shown in the **Pressure unit**: hPa (= mbar) and mmHg to 0.1, kPa and inHg to
0.01. It is station pressure as measured at the sensor, not adjusted to sea level; use the
**Pressure offset** to match a local sea-level (QNH) reading if you prefer.

## Dew point and VPD

Neither is sent by the sensors; the driver calculates both each time a temperature or humidity
reading arrives, using the offset-corrected values.

- **Dew point** uses the Magnus formula (a = 17.62, b = 243.12 °C).
- **VPD** is the saturation vapour pressure (Tetens: 0.6108 × e^(17.27·T / (T + 237.3)) kPa)
  multiplied by (1 − RH/100), shown in kPa to 0.01.

## Averages

The driver keeps two rolling averages of temperature and humidity: over the last 24 hours, and
over the last N days (**Averaging period (days)**, 2 to 365, default 7). They work on every model
and are calculated in Hubitat from the readings the sensor sends, using the offset-corrected
values; the SNZB-02DR2's own `Device Temp Avg` / `Device Humidity Avg` are separate.

- **Time-weighted.** The sensors report when the value changes by the reporting threshold, plus a
  heartbeat at the max reporting interval. Each reading counts for as long as it stayed current,
  so a period with many reports (a changing temperature) does not outweigh a steady one.
- **Gaps are left out.** A reading counts for at most twice the max reporting interval (at least
  30 minutes). If the sensor goes silent, its last value is not stretched over the gap.
- **Exact windows.** Totals are stored per hour for the last day and per day for the longer
  period. Each average covers exactly 24 hours or N days back from now; the oldest hour or day in
  the window is counted in proportion to the part of it that still falls inside.
- **Coverage.** Until enough data has been collected, `averagesInfo` shows how much each average
  covers (for example `2.5 of 7 days`). Averages start when this version is installed; the hub
  keeps too little event history to work them out from past readings.
- **Updates.** The averages are updated with every reading and once an hour, so the 24-hour window
  moves on even when the readings are steady. After updating the driver code, click **Save
  Preferences** once to start the hourly update.
- **Offsets.** A reading is stored with the offset in force when it arrived; changing an offset
  affects later readings only. Use **Reset Averages** to start again with the new offset.
- **Storage.** About 20 KB of driver state per device for a 365-day period (totals for at least 31
  days are always kept, so lengthening the period later uses data already collected).

---

## FAQ

### Can I get temperature updates more often?
Usually, yes. Reports are triggered by change: the sensor sends a temperature report whenever
the reading moves by at least the **Temperature reporting threshold**, no more often than the
**Min time** interval, and sends a heartbeat at the **Max time** interval even if nothing has
changed. For use as a remote sensor for a thermostat, a threshold of 0.1–0.2 °C and a max
interval of 300–600 s are reasonable starting points. Wake the sensor before saving and check
the log for "reporting configuration accepted". More frequent reporting shortens battery life.

### Can the temperature units be changed?
There are up to three separate settings:

- **Readings in Hubitat** always follow the hub's temperature scale (**Settings → Location**).
- **Temperature settings** (threshold, offset, comfort limits) can be entered in °C or °F with
  **Units for temperature settings**.
- **The SNZB-02DR2's own LCD** is set with **LCD temperature unit**.

### My dashboard tile can't show decimals
Set **Temperature decimal places** to 0. Values are rounded half-up (72.5 → 73).

### Why are there no readings after I switched to this driver?
The sensor is asleep most of the time and does not hear the hub, so changing drivers does not
wake it; it keeps reporting on its own schedule (up to the last configured heartbeat, one hour
by default). Press its button, then click **Configure** or **Refresh**, and readings arrive
within seconds. Until then `sensorStatus` says "Waiting for first report…"; the readings stay
empty rather than holding a placeholder that could mislead rules (0 °F would trigger a freeze
rule, for example).

### The log says my model is not recognized
Set **Device model** to the matching model, Save, and reopen the page for its settings.
Please report the model string shown in the log so it can be added.

### Is there a Ping command?
No. Ping sends a request and times the reply, but a sleeping sensor does not answer, so it
nearly always timed out. Use `healthStatus` instead.

### Can the sensors receive firmware updates through Hubitat?
Not as far as is known. Hubitat only performs Zigbee OTA updates for firmware images it hosts
itself, and Sonoff images are not among them. To update, temporarily pair the sensor to a
platform that carries Sonoff firmware (a Sonoff/eWeLink hub, Zigbee2MQTT or ZHA), update it
there, then reset and re-pair it to Hubitat. On the SNZB-02DR2, firmware 1.0.5 adds the EXT2
slot and remote humidity on both slots; firmware 1.0.4 adds remote humidity on EXT1.

### Can the EXT line show text?
No. The sensor only receives a number for each slot and only draws numbers. Numeric codes
(for example 1.0 = garage door open) work, within the accepted ranges.

---

## Known Limitations

- **Pressure capability on every model** — see **Model detection**.
- **SNZB-02D device settings.** The SNZB-02D is treated like the SNZB-02P: its display and
  comfort settings, if any, are not read or changed. Setting **Device model** to SNZB-02DR2
  would try the SNZB-02DR2's settings on it, untested.
- **SNZB-02LD.** Temperature only, with no fingerprint yet, so a newly paired SNZB-02LD needs the
  driver chosen from the **Type** dropdown. Its LCD unit and temperature calibration, which other
  platforms can change, are not read or changed here; set them on the sensor or in the eWeLink app.
- **SNZB-02M settings are read-only.** Its comfort thresholds and calibration are read without
  Sonoff's manufacturer code; this driver does not change them. If those attributes stay empty
  after a Refresh with the sensor awake, please report it.
- **SNZB-02DR2 open questions.** Attribute `0x200E` is still unexplained, it is not yet known when
  the sensor's own 24-hour min/max window resets. See the SNZB-02DR2 driver's README for the
  research notes.
- **SNZB-02DR2 remote display.** The firmware 1.0.5 method (and so EXT2) is untested, and so
  is remote humidity on firmware 1.0.4. Whether the sensor keeps showing a remote value
  indefinitely, or blanks it when no new value arrives, is not yet known.

---

## Zigbee Cluster Summary

| Cluster | ID | Models | Usage |
|---|---|---|---|
| Basic | `0x0000` | all | Device information (model string used for detection) |
| Power Configuration | `0x0001` | all | Battery % (attr 0x0021, reported); voltage (attr 0x0020, read on Refresh except SNZB-02M) |
| Identify | `0x0003` | all | Standard. The SNZB-02DR2 sends Identify when its button is pressed; the driver treats it as a wake-up |
| Poll Control | `0x0020` | all | Sleepy-device check-in (not changed by this driver) |
| Temperature Measurement | `0x0402` | all | Temperature, 0.01 °C resolution |
| Pressure Measurement | `0x0403` | 02M | Attr 0x0000 (whole hPa) and 0x0004 (0.01 hPa) |
| Relative Humidity | `0x0405` | all | Humidity, 0.01 % resolution |
| Sonoff Private (FC11) | `0xFC11` | 02DR2, 02M | Comfort thresholds, LCD unit, calibration, device min/max, remote display — see below |
| Sonoff Private (FC57) | `0xFC57` | all | Device management; not used by this driver |
| Time | `0x000A` | 02P, 02D, 02DR2 | Out-cluster |
| OTA Upgrade | `0x0019` | all | Out-cluster |

Battery reporting is configured for 60 s minimum, 4 hours maximum, on any change.

### FC11 attributes used

On the SNZB-02DR2 these are read and written with Sonoff's manufacturer code `0x1286`, except
the remote-display attributes (`0x600D`, `0x600E`, `0x6018`, `0x601E`), which are written
without it, as Zigbee2MQTT does. On the SNZB-02M they are read without it.

| Attribute | Type | Models | Use |
|---|---|---|---|
| `0x0003` | INT16, °C × 100 | 02DR2 (R/W), 02M (R) | Comfort temperature maximum |
| `0x0004` | INT16, °C × 100 | 02DR2 (R/W), 02M (R) | Comfort temperature minimum |
| `0x0005` | UINT16, % × 100 | 02DR2 (R/W), 02M (R) | Comfort humidity minimum |
| `0x0006` | UINT16, % × 100 | 02DR2 (R/W), 02M (R) | Comfort humidity maximum |
| `0x0007` | UINT16 | 02DR2 (R/W) | LCD temperature unit (0 = °C, 1 = °F). Rejects UINT8 writes (`0x8D`); the driver retries other types if needed |
| `0x2003` | INT16, °C × 100 | 02DR2 (R/W), 02M (R) | Temperature calibration (02DR2: written by **Apply offsets in the sensor**) |
| `0x2004` | INT16, % × 100 | 02DR2 (R/W), 02M (R) | Humidity calibration (02DR2: written by **Apply offsets in the sensor**) |
| `0x2007` | INT16, hPa × 100 | 02M (R) | Pressure calibration |
| `0x2008` / `0x2009` | °C × 100 (the sensor declares `0x2008` as UINT16 and `0x2009` as INT16; both read as signed) | 02DR2 (R; writes are acknowledged but ignored) | Device 24-hour temperature max / min |
| `0x200B` / `0x200C` | UINT16, % × 100 | 02DR2 (R; writes are acknowledged but ignored) | Device 24-hour humidity max / min |
| `0x200A` | INT16, °C × 100 | 02DR2 (R) | Device 24-hour average temperature (`Device Temp Avg`) |
| `0x200D` | UINT16, % × 100 | 02DR2 (R) | Device 24-hour average humidity (`Device Humidity Avg`) |
| `0x600E` | UINT8 | 02DR2 (R/W) | Remote display. Before 1.0.5: 0 = built-in sensor, 1 = external value. 1.0.5 and later: 0 = unbound, 1 = using, 2 = offline |
| `0x600D` | INT16, °C × 100 | 02DR2 (R/W) | Remote temperature on EXT1 (before 1.0.5) |
| `0x6018` | UINT16, % × 100 | 02DR2 (R/W) | Remote humidity on EXT1 (before 1.0.5; needs 1.0.4) |
| `0x601E` | ARRAY of UINT8 | 02DR2 (W) | Remote sources, 1.0.5 and later. Header `01 01 00 03 <length> <count>`, then per item: type (0 temperature, 1 humidity), slot (0 EXT1, 1 EXT2), state (1 online), value length (2), value × 100 little-endian. Written with a hand-built frame, since Hubitat's `writeAttribute` cannot encode arrays |

---

## Changelog

| Version | Date | Notes |
|---|---|---|
| 1.4.2 | 2026-11-25 | SNZB-02LD (probe thermometer with LCD) recognized as a supported model and given the SNZB-02P / 02D features, without the "not recognized" warning. Spelling of that warning corrected to "recognized". |
| 1.4.1 | 2026-11-19 | Renamed `temperatureAvgPeriod` / `humidityAvgPeriod` to `temperatureAvgLongTerm` / `humidityAvgLongTerm`, so the name does not read as the period's length. The old attributes are removed when you click **Save Preferences**. |
| 1.4.0 | 2026-11-19 | Rolling time-weighted averages of temperature and humidity on every model: `temperatureAvg24h`, `humidityAvg24h`, `temperatureAvgPeriod`, `humidityAvgPeriod` and `averagesInfo`. New preferences **Calculate averages** and **Averaging period (days)** (2–365, default 7); new command **Reset Averages**. |
| 1.3.1 | 2026-11-17 | Removed the experimental **Try Reset Device Min Max** command: on firmware 1.0.2 the sensor answers "write accepted" but keeps its own min/max unchanged, so they can only be reset with the button hold. |
| 1.3.0 | 2026-11-17 | SNZB-02DR2: new attributes `Device Temp Avg` and `Device Humidity Avg` (FC11 `0x200A` / `0x200D`, previously logged as unexplained reference values). New experimental command **Try Reset Device Min Max**, to find out whether the sensor's own min/max can be reset over Zigbee. |
| 1.2.1 | 2026-11-17 | Remote display: the highest temperature sent is now 98.9 (was 99.9); the screen shows `HHH` from 99.0 upward. Driver namespace changed from `v. 1.0.0` to `JohnFLand` (it now stays fixed across versions): update by importing or pasting into the existing driver, not by adding a new one. If you did add a new one, switch each device's **Type** to it, then delete the old driver. |
| 1.2.0 | 2026-11-17 | SNZB-02DR2: new preference **Apply offsets in the sensor** writes the temperature and humidity offsets to the sensor's calibration (FC11 `0x2003` / `0x2004`), reads them back on every Save and Refresh, and adds in Hubitat only the part not yet confirmed by the sensor; turning it off writes 0 back. Confirmed on firmware 1.0.2: the sensor's screen and its Zigbee readings both include the calibration. `temperatureCalibration` (SNZB-02DR2 and SNZB-02M) now follows the hub's temperature scale (0.1 in °F, 0.01 in °C); it was always °C, so check any rule on an SNZB-02M that uses it. Remote display: temperatures of 100 or more in the LCD's unit, which the screen shows as `HHH`, are sent as 99.9 (lowered to 98.9 in 1.2.1). |
| 1.1.0 | 2026-11-16 | SNZB-02DR2 remote display: new commands **Set Remote Temperature**, **Set Remote Humidity** and **Remote Display Off**; new attributes `remoteDisplay`, `ext1Temperature`, `ext1Humidity`, `ext2Temperature`, `ext2Humidity`; new preference **Remote display method**. Method chosen by firmware (older method before 1.0.5, remote sources from 1.0.5), automatic re-send when the sensor wakes, and exact tenths on a °F LCD. The Identify message sent by the SNZB-02DR2's button is now recognised instead of being logged as an unhandled cluster. Thanks to temes33 for the discovery. |
| 1.0.0 | 2026-11-07 | First release. Merges the separate drivers SNZB-02x 1.5.0, SNZB-02DR2 1.13.0 and SNZB-02M 2.4.0, with the model detected from the device. Differences from those drivers: comfort attributes are numbers on every model (`comfortTempMin/Max`, `comfortHumidityMin/Max`; the SNZB-02DR2 driver used text values and `comfortHumiMin/Max`); dew point and VPD are calculated for every model and can be turned off; the Configure command, sanity checks on raw readings and logging of write responses apply to every model; `temperatureCalibration` and `humidityCalibration` are published whenever the sensor sends them; humidity is shown in whole percent on every model (the SNZB-02M driver showed 0.1 %); default values on the preferences page follow the selected temperature unit. |

---

## License

Licensed under the Apache License, Version 2.0.
