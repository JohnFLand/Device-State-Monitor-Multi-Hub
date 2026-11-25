# Device State Monitor Multi-Hub — User Guide

**App version:** 1.80  
**Applies to:** Hubitat Elevation, same-LAN multi-hub deployments

---

## Overview

Device State Monitor Multi-Hub reports device states across up to three Hubitat hubs from a single app page. It provides seven live tables:

- **ON Devices** — switch-capable devices currently reporting ON
- **OFF Devices** — switch-capable devices currently reporting OFF
- **Unknown State** — switch-capable devices reporting neither ON nor OFF
- **Lock State** — explicitly selected lock devices showing their current lock state (locked/unlocked) and battery level
- **Contact Sensors** — explicitly selected contact sensors; by default only sensors currently **open** are listed (*"N open of M monitored"*), with an option to show every selected sensor and its state. Tilt sensors that report only `acceleration` (e.g. Third Reality) are supported as of 1.80 — see [Tilt sensors](#tilt-sensors-new-in-180)
- **Health / Activity Monitor** — any device (switch or otherwise) that is OFFLINE, INACTIVE, NOT PRESENT, DISCONNECTED, or whose last activity exceeds a configurable time threshold
- **Rules with Private Boolean FALSE** — Rule Machine and Button Controller rules on the scanned hubs whose Private Boolean is currently FALSE, with clickable PB State cells to set a rule's Private Boolean TRUE (and back) without leaving the page — see [Rules with Private Boolean FALSE Table](#rules-with-private-boolean-false-table)

Above the tables, a **Hubitat Safety Monitor (HSM)** status badge shows the current intrusion arm state and any active alert. The alert display is verified against the hub itself on every Refresh (not just driven by events), an active alert can be **cancelled in HSM directly from the page** (new in 1.79), and HSM on Hubs #2 and #3 can optionally be shown as well — see [Hubitat Safety Monitor (HSM) Status](#hubitat-safety-monitor-hsm-status).

Device names are clickable links to their hub's device edit page; rule names in the Private Boolean table link to the rule's configuration page on its hub. When Maker API credentials are configured, the State cell in the ON and OFF tables is clickable to toggle the device without leaving the page.

---

## Setting Up Maker API on a Remote Hub

Hubs #2 and #3 are queried via Hubitat's built-in **Maker API** app. Perform these steps on each remote hub before configuring it in Device State Monitor.

### Step 1 — Install Maker API on the remote hub

1. On the remote hub, go to **Apps** → **+ Add Built-In App**.
2. Find **Maker API** and install it.
3. Open the Maker API app. Under **Allow Access to Devices**, select every device you want to be reachable from Device State Monitor — this includes devices you want to monitor for ON/OFF state, contact sensors, locks, and any you want in the Health/Activity table. Only devices selected here will be visible to the app.
   - If you plan to display this hub's **HSM status** in the report (optional, off by default), also enable the **HSM** toggle ("Allow control of HSM") in the same Maker API app — the arm state is read through Maker API's `/hsm` endpoint, and the **Cancel HSM Alerts** button for this hub uses Maker API's `/hsm/cancelAlerts`.
4. Note the **App ID** shown in the Maker API page heading (e.g. *Maker API — App # 42*). You will need this number.
5. Copy the **Access Token** shown on the same page.
6. Note the hub's **local IP address** (visible in Hubitat's **Settings → Hub Details**, or in your router's DHCP table).

### Step 2 — Test connectivity (optional but recommended)

From a browser on the same LAN, visit:

```
http://<hub-ip>/apps/api/<app-id>/devices?access_token=<token>
```

You should see a JSON list of your selected devices. If the page loads, the credentials are correct.

### Step 3 — Enter credentials in Device State Monitor

Open the Hub #2 (or #3) section in Device State Monitor, enable the hub, expand **Show / Edit Connection Settings**, and enter the IP address, App ID, and Access Token you noted above. Then choose **⟳ Load / Reload Device List** from the Actions dropdown to fetch the device list.

---

## Page Layout

The app page is divided into two zones:

**Top — live report area**
- **Refresh Table** button — re-queries all hubs immediately and redraws all tables
- **Scan PB FALSE Rules** button — appears next to Refresh when the Private Boolean FALSE table is enabled for at least one hub; starts a new PB scan on demand
- **Cancel HSM Alerts on _hub_** button — appears next to Refresh while an HSM alert is active (one button per alerting hub); after a confirmation click it tells HSM to cancel its in-progress alerts (see the HSM section)
- **Clear HSM Alert Display** button — display-only reset; appears only while an alert is latched *and* the alert state could not be verified against the hub (see the HSM section)
- ***Last run* timestamp** — directly under the buttons, showing the report time and total scan time with a data-collection / render breakdown. (It sits at the top so it is not mistaken for a PB-scan timestamp; the PB table has its own *Last completed scan* line.)
- **HSM Status** and **HSM Alert** badges (if HSM is installed), optionally repeated for Hubs #2 and #3
- The seven report tables (ON, OFF, Unknown, Lock State, Contact Sensors, Health/Activity, Rules with Private Boolean FALSE)
- A second **Refresh Table** button (and **Scan PB FALSE Rules** button, when enabled) below the tables, so a refresh is one click away after scrolling through a long report

**Bottom — collapsed configuration sections**
- Hub #1, Hub #2, Hub #3 settings (each collapsible)
- Sort & Display Options (sort defaults, Private Boolean table settings, Hide Columns, filtering, display preferences)
- Notes / User Guide (condensed in-app reference)

Configuration sections are hidden by default once set up, so the report tables are the first thing you see on every visit.

---

## Mobile Support

All seven tables are designed to display correctly on both desktop and mobile browsers. Column widths and Room column visibility adjust automatically based on the actual viewport width — no manual setting is required.

### Responsive Column Widths

The Hub and Room columns switch width automatically at a 767 px breakpoint:

| Viewport | Room | Hub | Health Room | Health Hub |
|---|---|---|---|---|
| **≤ 767 px** (phone, small tablet) | 140 px | 70 px | 105 px | 90 px |
| **> 767 px** (tablet landscape, desktop) | 180 px | 200 px | 150 px | 200 px |

The wider Hub column on large screens accommodates long hub names (e.g. "Felida Hubitat C8P Office") on one line. On narrow screens the Hub column word-wraps gracefully. Width switching is driven by `window.innerWidth` in JavaScript rather than CSS media queries, which is necessary because Hubitat's app page does not always set a mobile-appropriate viewport meta tag.

### Room Column Auto-Hide

On any screen **≤ 480 px** wide — all iPhones in portrait orientation — the Room column is hidden automatically. It reappears when the phone is rotated to landscape or the page is opened on a wider screen.

The Room **Hide Columns** toggle button still works in portrait, but any change it makes is overridden on the next orientation change; orientation controls Room on phones. On desktop (> 767 px) the toggle works normally and saves its state to local storage.

### Horizontal Scrolling

Every table is wrapped in a horizontal scroll container. On narrow screens where a table's content is wider than the viewport — particularly the Health / Activity table with its many columns — the table scrolls horizontally without affecting the rest of the page.

---

## Hub #1 — Local Hub

Hub #1 is the hub running Device State Monitor. Its devices are accessed directly — no network calls are required.

| Control | Purpose |
|---|---|
| **Friendly label** | Name shown in the Hub column of all tables. Defaults to the hub's location name. |
| **Select ON-monitored devices** | Devices that appear in the ON table when their switch state is on. Accepts any switch-capable device. |
| **Select OFF-monitored devices** | Devices that appear in the OFF table when their switch state is off. A device may be selected in both lists. |
| **Select lock-monitored devices** | Devices that appear in the Lock State table. Uses a `capability.lock` picker. Virtual lock devices are always included regardless of the Exclude Virtual Devices setting, since these are explicit selections. |
| **Select contact-monitored devices** | Devices that appear in the Contact Sensor table. Uses a `capability.contactSensor` picker. Like locks, these are explicit selections, so virtual devices are always included. Tilt sensors whose driver declares ContactSensor (e.g. Third Reality) appear here too; if they never report a `contact` value, their `acceleration` state is shown instead (1.80). |
| **Toggle Command & Health Monitor Settings** | Toggle to reveal Maker API credentials for Hub #1. These are used for two purposes: (1) clickable State cells in the ON/OFF tables and embedded toggle buttons in the Unknown table; (2) the Load / Select All / Clear All actions in the health device picker. If left blank, State cells are non-interactive and health selection reverts to a manual capability picker. |
| **Maker API reminder** | Appears with the health actions once Maker API credentials are entered. It reminds you that a new device must be exposed in Hub #1's Maker API app before **Load / Reload** can see it, and has an **Open Maker API** button that opens that Maker API app. |
| **Hub #1 Health Device List Actions** | Appears once Maker API credentials are entered. Choose **⟳ Load / Reload** to fetch the device list, then **✓ Select All** or **✗ Clear** to bulk-manage health selections. *(As of 1.78 these actions operate on the capability picker below directly — see note.)* |
| **Select health/activity-monitored devices** | The capability picker — always visible, and **as of 1.78 the single source of truth** for which picker-visible devices are health-monitored on Hub #1. Devices selected here appear in the Health/Activity table when flagged; unchecking a device removes it. Disabled devices are automatically excluded. |
| **Additional health-monitor device IDs — Hub #1** | *(New in 1.78.)* Optional. A list of numeric device **IDs** for devices that do **not** appear in the capability picker — for example Actuator-only drivers such as MQTT Display Publisher. This is the only supplementary path in 1.78. IDs already selected in the picker above are ignored here, so the two can never double-count. |

---

## Hubs #2 and #3 — Remote Hubs

Remote hubs are queried via their Maker API on every Refresh.

### Enabling a remote hub

Toggle **Enable Hub #2?** (or #3) to on. The connection settings expand automatically.

### Connection Settings

| Control | Purpose |
|---|---|
| **Show / Edit Connection Settings** | Toggle to reveal or hide the IP, App ID, and Token fields. Collapse it once configured to keep the section tidy. |
| **Hub IP address** | Local LAN IP of the remote hub (e.g. 192.168.1.100). |
| **Maker API app ID** | The number shown in the Maker API app heading on the remote hub. |
| **Maker API access token** | The token from the Maker API app on the remote hub. |
| **Last load status** | Shown in green (OK) or red (error) after a Load/Reload action. Displays counts of switch, lock, and contact sensor devices loaded, enabled devices, and disabled devices detected. |

### Actions Dropdown

All bulk device management for remote hubs is done through the **Actions** dropdown. Each selection takes effect immediately on the next page render.

| Action | Effect |
|---|---|
| **⟳ Load / Reload Device List** | Fetches the current device list from the remote hub's Maker API. Must be run before device pickers appear. Re-run whenever devices are added to or removed from the Maker API app on the remote hub. |
| **✓ Select All ON-monitored devices** | Selects all switch-capable devices for ON monitoring. |
| **✗ Clear ON-monitored devices** | Removes all ON monitoring selections. |
| **✓ Select All OFF-monitored devices** | Selects all switch-capable devices for OFF monitoring. |
| **✗ Clear OFF-monitored devices** | Removes all OFF monitoring selections. |
| **✓ Select All lock-monitored devices** | Selects all lock-capable devices for lock state monitoring. |
| **✗ Clear lock-monitored devices** | Removes all lock monitoring selections. |
| **✓ Select All contact-monitored devices** | Selects all contact-sensor devices for contact state monitoring. |
| **✗ Clear contact-monitored devices** | Removes all contact monitoring selections. |
| **✓ Select All health-monitored devices** | Selects all devices (switch and non-switch) for health/activity monitoring. |
| **✗ Clear health-monitored devices** | Removes all health monitoring selections. |

### Device Pickers

Above the Actions dropdown, a yellow **Before you Load/Reload** box reminds you that **Load / Reload** only re-reads what the remote hub's Maker API already exposes. A newly added device won't appear in any picker until you tick it in that hub's Maker API app. Once the IP, App ID and Token are entered, the box has an **Open Maker API on _hub_** button that opens that Maker API app directly.

After loading, five pickers appear:

**Switch Device Selection** — shows only switch-capable devices. Use the **Filter by name or room** field to narrow a long list. The ON-monitored and OFF-monitored pickers each show the count of devices available in the loaded list.

**Lock Device selector** — shows only lock-capable devices exposed to the Maker API. Select the devices whose lock state you want to appear in the Lock State table.

**Contact sensor selector** — shows only contact-sensor devices exposed to the Maker API. Select the sensors you want in the Contact Sensor table. Tilt sensors whose driver declares ContactSensor are listed here as well (see [Tilt sensors](#tilt-sensors-new-in-180)). *(Upgrading from a version before 1.56? Run **⟳ Load / Reload Device List** once to populate this selector.)*

**Health / Activity device selector** — shows all devices exposed to the Maker API, not just switch-capable ones, so you can monitor sensors, remotes, and other non-switch devices for activity.

### Disabled Devices on Remote Hubs

The Health/Activity table makes a best-effort attempt to exclude disabled devices automatically: on every Refresh it cross-checks selected device IDs against the hub's live device list and skips any ID that no longer appears there (disabled devices typically drop off that list). IDs flagged as disabled during the last Load/Reload are also cached and excluded at refresh time.

This filtering covers most cases, but is not guaranteed — the Maker API does not expose disabled state reliably in all Hubitat versions. If a disabled device still appears in the Health/Activity table, the fallback remedies in order of preference are:

1. **Deselect it** from the health device picker — the cleanest ongoing solution.
2. **Enter its device ID** in the **Manually excluded health-monitor device IDs** field — the device is then permanently excluded from the health table regardless of its state. The device ID is the number in its edit URL: `/device/edit/169`.

Note that the exclusion field applies only to the Health/Activity table, not the ON/OFF/Unknown tables.

---

## Report Tables

### ON Devices Table

Lists every monitored device currently reporting switch state **on**. Devices also selected in the OFF-monitored list are highlighted with a gold star (★) and orange-colored name, indicating they are being watched in both directions.

When Maker API credentials are configured, the State cell (showing **ON**) is clickable. Click it to send an **off** command; the cell updates in-place without a full page refresh.

### OFF Devices Table

Lists every monitored device currently reporting switch state **off**. Same dual-monitoring highlight applies.

Click the State cell (showing **OFF**) to send an **on** command.

### Unknown State Table

Lists monitored devices reporting a switch state other than on or off (e.g. null, initializing, or an unrecognized value). Can be hidden in Sort & Display Options. When Maker API credentials are configured, the State cell contains **→ ON** and **→ OFF** mini-buttons to send either command.

### Lock State Table

Lists all explicitly selected lock devices and their current lock state. Devices are selected per hub:

- **Hub #1** — use the **Select lock-monitored devices** capability picker in the Hub #1 section.
- **Hubs #2 and #3** — use the **Lock Device selector** that appears after running Load / Reload.

| Lock State | Display |
|---|---|
| **locked** | Green, bold |
| **unlocked** | Red, bold |
| **unknown** or other | Gray |

The table also includes a **Battery %** column showing the current battery level for each lock. Color coding matches the Health / Activity table: green ≥ 40 %, orange 20–39 %, red < 20 %. Locks with no battery attribute report *n/a*.

The table can be hidden in Sort & Display Options. It is sortable by Device Name, Room, Hub, Lock State, or Battery %. Virtual lock devices (e.g. Virtual Lock driver) are always shown regardless of the **Exclude virtual devices** setting, because they are individually hand-picked rather than bulk-selected.

### Contact Sensor Table

Lists explicitly selected contact sensors. Selection works exactly like locks:

- **Hub #1** — use the **Select contact-monitored devices** capability picker in the Hub #1 section.
- **Hubs #2 and #3** — use the **Contact sensor selector** that appears after running Load / Reload.

By default the table lists **only sensors currently open** (or **active**, for tilt sensors), with a heading of the form *Open Contact Sensors: 2 open of 14 monitored* — or *None open* in green when everything is closed. Turn off **Show only sensors currently OPEN (or ACTIVE, for tilt sensors)?** in Sort & Display Options to list every selected sensor with its state instead. That mode's heading reads *Contact Sensor State: 14 devices (2 open)*.

| Contact State | Display |
|---|---|
| **open** | Red, bold |
| **active** (tilt sensor, tilted) | Red, bold — treated like open |
| **closed** | Green, bold |
| **inactive** (tilt sensor, level) | Green, bold — treated like closed |
| **unknown** or other | Gray |

#### Tilt sensors *(new in 1.80)*

Some tilt sensors — for example **Third Reality** tilt sensors — use a driver that declares the ContactSensor capability but only ever sends an `acceleration` event (`active` / `inactive`), never a `contact` value. Before 1.80 these sensors always showed *unknown*. Now, when a selected sensor reports no `contact` value, the table shows its `acceleration` state instead:

- **active** (tilted) is flagged in red like **open**. It is counted in the open total and listed in open-only mode.
- **inactive** is shown in green like **closed**.
- Hover over the State cell of a tilt sensor to see a tooltip explaining that the value comes from `acceleration`.
- When at least one selected sensor is reporting through `acceleration`, the heading says **open/active** instead of **open** (e.g. *2 open/active of 14 monitored*).

A sensor that reports a `contact` value always shows it. The fallback is used only when `contact` is absent. On Hubs #2/#3 the app first looks for either attribute in the Maker API device list. If neither is there, it fetches that one device on its own before giving up. No extra setup is needed: tilt sensors are selected the same way as any other contact sensor.

A **Battery %** column uses the same color coding as the Lock State table: green ≥ 40 %, orange 20–39 %, red < 20 %; sensors with no battery attribute report *n/a*.

The table can be hidden in Sort & Display Options and is sortable by Device Name, Room, Hub, Contact State, or Battery %. As with locks, virtual devices are always shown because the sensors are individually hand-picked.

### Health / Activity Monitor Table

Lists any health-monitored device that meets one or more of:

| HE Status / Attribute | Meaning |
|---|---|
| **OFFLINE** | Hub reports device is offline |
| **INACTIVE** | Hub reports device is inactive |
| **NOT PRESENT** | Hub reports device is not present (typically for presence sensors) |
| **DISCONNECTED** | Device's `connectionStatus` attribute reports disconnected (e.g. MQTT Display Publisher and similar devices) |
| **HEALTH OFFLINE** | Device's `healthStatus` attribute reports offline (shown when HE status itself is absent) |
| **Late Activity (>Xh)** | Last recorded device activity is older than the configured threshold |

The **Issue** column may contain multiple reasons separated by commas if more than one condition applies.

On **Hub #1**, the devices monitored here come from two independent sources: everything you check in the **capability picker** (the single source of truth as of 1.78 — unchecking a device reliably removes it), plus any IDs you list in **Additional health-monitor device IDs — Hub #1** for devices the picker cannot show (e.g. Actuator-only drivers). IDs already in the picker are ignored in the additional field, so nothing is counted twice.

For child devices (e.g. individual endpoints of a USB switch hub), last activity is resolved from the parent device if the child has no activity record of its own. On Hub #1 this parent lookup goes through Hub #1's own Maker API, using the same credentials configured for toggle links — without them, the fallback is skipped (with a single info log entry) and the child is evaluated on its own record.

If a hub's per-device health checks start failing (e.g. its Maker API stops responding mid-refresh), a circuit breaker marks that hub as not responding after three consecutive failures and skips the remaining checks, replacing a long cascade of per-device warnings with one actionable log entry.

The table contains the following columns, all independently hideable via the Hide Columns toggle bar:

| Column | Description |
|---|---|
| **Device Name** | Clickable link to the device edit page |
| **Room** | Room the device is assigned to |
| **Hub** | Friendly hub label |
| **Issue** | Summary of the condition(s) that caused the device to appear in this table |
| **HE Status** | Hubitat's reported device status (OFFLINE, INACTIVE, NOT PRESENT, DISCONNECTED, ACTIVE, etc.) — shown in red when problematic |
| **Health Status** | Value of the device's `healthStatus` attribute — green for online, red for offline |
| **Last Activity** | Timestamp of the most recent device event, color-coded by age |
| **Battery %** | Current battery level — green ≥ 40 %, orange 20–39 %, red < 20 % |
| **Last Battery** | Timestamp of the last battery report |

### Rules with Private Boolean FALSE Table

*(New in 1.60, extended through 1.74.)* Lists Rule Machine and Button Controller child rules whose current **Private Boolean is FALSE**, plus any rules that remained **UNKNOWN** after read retries. It appears immediately after the Health / Activity table.

| Column | Description |
|---|---|
| **Rule** | Clickable link to the rule's configuration page on its hub. Hubitat status suffixes (e.g. *(Required Expression false)*) are shown with their original coloring. |
| **App Type** | RM (Rule Machine) or BC (Button Controller) |
| **Hub** | Friendly hub label |
| **PB State** | FALSE in red, TRUE in blue, UNKNOWN in orange — clickable where supported (see below) |
| **Last Run** | The rule's last run time, or *Never / unavailable* |

The table heading reports the live FALSE count (and an UNKNOWN count when applicable), and a summary line below it shows how many RM/BC rules were scanned across how many hubs, plus the time and duration of the last completed scan.

#### Which hubs are scanned

Three per-hub controls in Sort & Display Options — **Show Rules with Private Boolean FALSE table for Hub 1 / 2 / 3** — select which hubs are **scanned and displayed**. An unchecked hub receives no discovery request and no per-rule status reads, so a Hub 1-only scan spends no time on Hubs 2 or 3. Hubs #2/#3 must also be enabled normally. Turning a hub's control off hides its cached rows immediately; run **Scan PB FALSE Rules** after changing hub selections to rebuild the cache from only the selected hubs.

PB scanning reads Hubitat's internal app/status pages (remote hubs via their configured IP), so it requires **Hub Login Security to be OFF** on each scanned hub.

#### How scanning works

- Scans run **asynchronously** with a six-minute watchdog, so hundreds of rules do not hold the app page open. While a scan is running, the previous completed results remain visible and the page auto-polls every 30 seconds until the scan finishes.
- Start a scan with either **Scan PB FALSE Rules** button; a scan also starts automatically after clicking **Done** and on the first page open after installing/upgrading (when at least one hub's PB control is enabled).
- Rule status is read strictly **one rule at a time** across all selected hubs. An unreadable rule is retried up to three times; if it still cannot be read, it is published as **UNKNOWN** with its linked rule name available for manual inspection — known FALSE results from the same scan remain visible. Read failures are never mis-reported as FALSE.
- A successful read with no stored Private Boolean is correctly treated as FALSE (Rule Machine's default).

#### Clickable PB State cells

Click a **FALSE** cell to set that rule's Private Boolean **TRUE**. The cell turns into a blue clickable **TRUE** and the row *remains listed until the next PB scan*, so the click can be undone — clicking the TRUE cell sets the rule's Private Boolean back FALSE. Setting FALSE is deliberately possible only for rows this table itself set TRUE, so arbitrary rules can never be driven FALSE from here. A footnote below the table notes when changed rows are being retained.

- **Hub #1 rules** act directly through Rule Machine's `RMUtils` (RM 5.0) on the local hub. This uses a small self-enabling OAuth endpoint in the app; if the endpoint is inactive, an orange note appears — re-open or re-save the app (or enable OAuth manually in Apps Code).
- **Hub #2/#3 rules** become clickable once that hub's **Private Boolean Manager** (1.52+) app ID and OAuth access token are entered in the PB table settings (Sort & Display Options — the inline instructions explain where to find both on that hub). The click is relayed **server-side from Hub #1** to PBM's `/setPB` endpoint on the remote hub, and the change is verified: the table updates only after PBM confirms success, and transport/HTTP/credential/PBM errors surface in a browser alert and the log. Remote hubs without PBM credentials show read-only cells with a tooltip explaining how to enable click-to-set. *(Note: a successful relayed click is silent in the remote PBM's log unless PBM's debug logging is enabled.)*
- Clicks are disabled while a PB scan is running or while another PB change is in flight, to avoid a completing scan racing a user change.

---

## Hubitat Safety Monitor (HSM) Status

When HSM is installed on Hub #1, two status lines appear above the report tables on every Refresh. HSM on Hubs #2 and #3 can optionally be shown as well (see [Remote HSM](#remote-hsm--hubs-2-and-3) below). When any remote HSM display is enabled, every HSM line is labeled with its hub's friendly name.

### HSM Status

Shows the current intrusion arm state as a color-coded badge:

| Status | Color | Meaning |
|---|---|---|
| **Armed Away** | Red | Intrusion alerts armed for Away mode |
| **Arming Away…** | Orange | Exit delay in progress before Armed Away activates |
| **Armed Home** | Orange | Intrusion alerts armed for Home mode |
| **Arming Home…** | Orange | Exit delay in progress before Armed Home activates |
| **Armed Night** | Purple | Intrusion alerts armed for Night mode |
| **Arming Night…** | Purple | Exit delay in progress before Armed Night activates |
| **Intrusion Disarmed** | Green | Intrusion alerts disarmed — smoke/water/custom monitoring remains armed |
| **All Monitoring Disarmed** | Green | Intrusion, smoke, water, and custom rule alerts all disarmed |

> **Note:** `hsmStatus` only reflects the intrusion arm state. Smoke, water, and custom monitoring rules stay armed even while intrusion is disarmed, until *All Monitoring* is disarmed. The badge therefore reads **Intrusion Disarmed** with an inline reminder that smoke/water/custom monitoring remains armed — matching the hub UI's own "Intrusion Disarmed, Water/Smoke/Gas/CO Armed" wording. There is no queryable arm state for smoke/water specifically; this is a Hubitat platform limitation.

### HSM Alert

Shows the most recent active alert, or **No current alert** in green when none is present. When an alert is active, the label blinks red.

| Alert | Label |
|---|---|
| `intrusion` | INTRUSION (Away) |
| `intrusion-home` | INTRUSION (Home) |
| `intrusion-night` | INTRUSION (Night) |
| `smoke` | SMOKE |
| `water` | WATER LEAK |
| `rule` | CUSTOM RULE |
| *(poll-detected)* | ACTIVE ALERT |

For custom rule alerts, the rule's name is appended to the label (e.g. *CUSTOM RULE — Door Locks unlocked*), along with the time the alert was first seen.

**How alerts are tracked.** Two mechanisms work together:

1. **Events (instant).** The app subscribes to Hubitat's `hsmAlert` location event. Both of HSM's cancellation values are honored — plain `cancel` (intrusion/smoke/water alerts) and `cancelRuleAlerts` (custom rule alerts). Transient `arming` / `armingHome` / `armingNight` notices are ignored rather than latched as alerts.
2. **Verification (every Refresh — recommended, on by default).** HSM offers no query API for alerts, so a single missed event would otherwise leave the badge wrong until the next event. When **Verify the HSM alert state against the hub on every refresh?** is enabled, each Refresh also reads the hub's own pages and reconciles the badge with reality:
   - With the **HSM app ID** configured (the number in HSM's URL, e.g. `/installedapp/configure/2`), the live alert text is read straight from the HSM app page — a missed alert appears as *ACTIVE ALERT — Custom Rule Alert: Door Locks unlocked*.
   - Without it, the hub's Apps list is scanned for the red **ALERT!** suffix HSM appends to its own app label while alerting (alert presence only, no detail).
   - Missed alerts are detected and displayed; stale alerts are auto-cleared. A footnote under the badge reports whether verification succeeded on that refresh.
   - Verification requires **Hub Login Security to be OFF** on Hub #1. If the pages can't be read, an orange note appears and the badge falls back to event-only behavior.

**Cancel HSM Alerts button** *(new in 1.79).* While an alert is active, a **Cancel HSM Alerts on _hub_** button appears next to **Refresh Table**. This actually cancels the alert in HSM — it is not just a display reset:

1. Click **Cancel HSM Alerts on _hub_**. Nothing is sent yet; a red confirmation box appears instead, because cancelling also silences sirens and light alerts.
2. Click **Yes — Cancel HSM Alerts on _hub_** within 60 seconds (or **No — Leave Alerts Active**). An unconfirmed request expires on its own.
3. The app sends HSM's documented `hsmSetArm` → `cancelAlerts` command on Hub #1, or calls `/hsm/cancelAlerts` through that hub's Maker API on Hubs #2/#3.
4. A note under the badge reports the result on the following refreshes:
   - **✓ Cancel sent … HSM now reports no active alert** — done.
   - **Cancel sent — waiting for HSM to confirm** — HSM had not finished processing it when the page was built (within ~20 seconds of the click); click **Refresh Table** again.
   - **Cancel sent …, but HSM is reporting an active alert again** — see below.
   - **Cancel HSM Alerts failed: …** — the command could not be sent (for a remote hub, check it is reachable and that the **HSM** toggle is enabled in its Maker API app).

> **Cancelling is not disarming.** HSM stops the in-progress alert but stays armed. If the condition that caused the alert is still true — a door still unlocked for a custom rule like *Door Locks unlocked*, a water sensor still wet — HSM can alert again immediately, and the badge will show the alert again with the note above. Fix the condition first, or disarm the affected monitoring in HSM. If the condition is definitely cleared and the alert still returns, cancel it directly in the HSM app on that hub.

Remote cancel buttons appear only for hubs whose HSM display is enabled, whose alert was detected on this refresh, and whose Maker API IP, app ID and token are configured. Every cancel is logged (`HSM: cancelAlerts sent to …`).

**Clear HSM Alert Display button.** This only resets the app's own copy of the alert; it does not touch HSM. As of 1.79 it appears only while an alert is latched **and** the alert state could not be verified on that refresh (verification turned off, or the hub's pages unreadable) — for example when a cancel event was missed and nothing can re-check the hub. When verification succeeds, a still-active alert would simply be re-detected on the same refresh, so the button is not offered; use **Cancel HSM Alerts** instead. The buttons and the alert badge always agree within the same refresh.

Because Hubitat app pages never update spontaneously while open, a new alert (or its clearing) becomes visible on the next **Refresh Table** click or page load — never while the page simply sits idle.

> **Important:** After installing or updating the app code, open the app and click **Done** once so subscriptions and settings re-initialize.

### Remote HSM — Hubs #2 and #3

HSM status and alerts for the remote hubs can be displayed under Hub #1's badge. This is **off by default** — enable it per hub in Sort & Display Options (**Also show HSM status for Hub #2 / #3?**).

- **Status** is fetched from the remote hub's Maker API `/hsm` endpoint using the credentials already configured for that hub. This requires the **HSM** toggle ("Allow control of HSM") to be enabled inside that hub's Maker API app; until it is, the badge shows *Unavailable* with a hint.
- **Alerts** use the same page verification as Hub #1, pointed at the remote hub's IP. Configure the optional **Hub #2/#3 HSM app ID** (the number in HSM's URL *on that hub*) to get live alert text; otherwise the remote Apps list is scanned for the **ALERT!** label. Requires Hub Login Security OFF on the remote hub — otherwise the alert line shows *Not verified* with a note.
- **Remote alerts are poll-only.** Hubitat location events do not cross hubs, so there is no event path from remote HSMs — remote alert lines are marked *(poll-only — updates on each refresh)* and appear/clear on each Refresh, which is when the report updates anyway.
- **Cancelling remote alerts** *(1.79)*. When a remote hub's alert is detected, a **Cancel HSM Alerts on _hub_** button appears (with the same confirmation step as Hub #1). The command is sent through that hub's Maker API `/hsm/cancelAlerts` endpoint, so it needs the same **HSM** toggle in that Maker API app as the status line. The result is confirmed on the next Refresh by the same page check that drives the alert line.

The HSM badges can be hidden entirely via the **Show HSM status above the tables?** toggle in Sort & Display Options.

---

## Clickable State Cells

State cells are interactive in the ON, OFF, and Unknown tables when Maker API credentials are configured for the relevant hub. They work as follows:

- **ON / OFF tables:** the entire State cell is a click target. Hover over it to see a subtle highlight; the cursor changes to a pointer. Click to send the opposite command.
- **Unknown table:** the State cell contains **→ ON** and **→ OFF** mini-buttons since neither direction can be inferred.
- The command is sent via the Maker API using `fetch()` — the page does not reload.
- The cell (or button) shows **…** while the command is in flight.
- On success, the State cell's label and color update immediately (optimistic update).
- On failure (network error or non-200 HTTP status), a brief error indicator appears and the original state is restored.

State cells are not interactive in the Health/Activity table — that table is for monitoring only. The Private Boolean FALSE table has its own clickable PB State cells with different mechanics (OAuth callback on Hub #1, Private Boolean Manager relay for remote hubs) — see [Rules with Private Boolean FALSE Table](#rules-with-private-boolean-false-table).

---

## Sort & Display Options

Expand the **Sort & Display Options** section at the bottom of the page to configure default sort behaviour, filtering, and display preferences.

### App Name

An **App instance name** field appears at the top of the section. Enter a custom label to distinguish this instance from others (the label is shown in the Hubitat Apps list). The **Reset to App Name** button next to it restores the app code's own name and version (e.g. *Device State Monitor Multi-Hub 1.80*).

### Per-Table Sort Settings

Each table has independent **Sort by** and **Order** controls. The sort applied here is the default when the page first loads. Click any column header in a table to re-sort interactively; this does not change the saved default.

**ON / OFF tables:** sortable by Device Name, Room, or Hub.  
**Unknown State table:** same columns.  
**Lock State table:** sortable by Device Name, Room, Hub, Lock State, or Battery %.  
**Contact Sensor table:** sortable by Device Name, Room, Hub, Contact State, or Battery %.  
**Health / Activity table:** sortable by Device Name, Room, Hub, HE Status, or Last Activity.  
**Private Boolean FALSE table:** sortable by Rule Name, App Type, Hub, PB State, or Last Run.

### Show/Hide Tables

| Control | Effect |
|---|---|
| **Show Unknown State table?** | Hides or shows the Unknown table. Default: on. |
| **Show Lock State table?** | Hides or shows the Lock State table. Default: on. |
| **Show Contact Sensor table?** | Hides or shows the Contact Sensor table. Default: on. |
| **Show only sensors currently OPEN (or ACTIVE, for tilt sensors)?** | When on (default), the Contact Sensor table lists only open sensors (and tilt sensors reporting `active`); when off, all selected sensors appear with their state. |
| **Show Health/Activity Monitor table?** | Hides or shows the Health table. Default: on. |
| **Show Rules with Private Boolean FALSE table for Hub 1 / 2 / 3** | Three independent per-hub toggles. Each controls both whether that hub is **scanned** during PB scanning and whether its cached rows are **shown** in the combined table. Default: on. When all three are off, the PB table and the Scan PB FALSE Rules buttons are hidden. |
| **Hub #2 / #3 Private Boolean Manager app ID & access token** | Optional per-hub credentials (shown when the hub is enabled and its PB control is on) that make that remote hub's PB State cells clickable — clicks are relayed to Private Boolean Manager's `/setPB` endpoint on that hub. See [Rules with Private Boolean FALSE Table](#rules-with-private-boolean-false-table). |

### Activity Threshold

**Flag devices with last activity more than X hours ago** — default 24 hours. Any health-monitored device whose most recent event is older than this value is flagged as Late Activity in the Health table. Set to a larger value (e.g. 72 hours) to reduce noise for devices that naturally report infrequently.

### Filtering Options

| Control | Effect |
|---|---|
| **Exclude virtual devices** | Omits virtual devices from the ON, OFF, Unknown, and Health/Activity tables. "Virtual" is identified by driver type name containing "virtual", or device name starting with "VD " (a naming convention for virtual devices). Lock and contact sensor devices are exempt — they are always shown regardless of this setting because they are individually selected rather than bulk-included. |
| **Exclude devices in the "System" room** | Omits devices assigned to the Hubitat room named "System" from all tables. |

### Display Options

| Control | Effect |
|---|---|
| **Show HSM status above the tables?** | Shows or hides the HSM Status and HSM Alert badges at the top of the report area. Default: on. Has no effect if HSM is not installed. |
| **Verify the HSM alert state against the hub on every refresh?** | Cross-checks the alert badge against the hub's own pages on each Refresh — detects missed alerts and auto-clears stale ones. Default: on. Requires Hub Login Security OFF on Hub #1. See the HSM section. |
| **HSM app ID** | Optional but recommended — the number in HSM's URL (e.g. `/installedapp/configure/2`). When set, live alert text is read straight from the HSM app page. |
| **Also show HSM status for Hub #2 / #3?** | Per-hub toggles (shown only for enabled hubs) that add that hub's HSM status and alert lines under Hub #1's. Default: off. Each reveals an optional per-hub HSM app ID field. See [Remote HSM](#remote-hsm--hubs-2-and-3). |
| **Show extra details in section headers?** | Appends monitored device counts to each hub section header (e.g. "Device Selection for Hub #3 – Office — 15 ON / 15 OFF / 4 Lock / 6 Contact / 47 Health monitored"). |
| **Enable debug logging?** | Writes detailed log entries to the Hubitat log for each device checked during a refresh. Useful for diagnosing missing or incorrect data. Disable when not troubleshooting — it generates a large number of log entries for hubs with many selected devices. |

### Hide Columns

Eight toggle buttons at the bottom of Sort & Display Options control column visibility. They appear in the same left-to-right order as the columns they control.

| Button | Applies to | Columns hidden |
|---|---|---|
| **Room** | All tables with a Room column (the PB table has none) | Room column |
| **Hub** | All seven tables | Hub column |
| **Issue** | Health / Activity table | Issue column |
| **HE Status** | Health / Activity table | HE Status column |
| **Health Status** | Health / Activity table | Health Status column |
| **Last Activity** | Health / Activity table | Last Activity column |
| **Battery %** | Health / Activity table | Battery % column |
| **Last Battery** | Health / Activity table | Last Battery column |

The six Health-table-specific buttons appear only when the Health / Activity table is enabled.

Click a button to hide the column; click again to show it. The button text is struck through while the column is hidden. On desktop (viewport > 767 px), visibility choices are saved in the browser's local storage and restored automatically on every subsequent page load — no Refresh or Save required.

**Room column on mobile:** The Room column is automatically hidden on portrait phones and shown in landscape — the Hide Columns toggle has no persistent effect on phone-sized screens. See [Mobile Support](#mobile-support) for details. The Health / Activity table has a horizontal scroll wrapper so all of its columns remain accessible at their minimum widths on any screen size.

### Notes / User Guide

A collapsible **Notes / User Guide** section appears below Sort & Display Options and contains a condensed version of this guide for quick in-app reference.

---

## Typical First-Time Setup Sequence

1. Install the app on Hub #1 via **Apps → + Add User App**.
2. Open the app. The seven report tables appear (empty) and configuration sections are below.
3. Expand **Hub #1**. Set a friendly label. Select devices for ON, OFF, lock, contact, and health monitoring.
4. To enable clickable State cells and health Select All/Clear All on Hub #1: toggle **Show / Edit Toggle Command & Health Monitor Settings**, enter Maker API credentials for Hub #1, then use the **Hub #1 Health Device List Actions** dropdown to Load and Select All.
5. For each remote hub: expand its section, toggle **Enable**, expand **Connection Settings**, enter IP/App ID/Token, collapse Connection Settings, then choose **⟳ Load / Reload Device List** from the Actions dropdown. After loading, use Select All actions and adjust individual selections as needed (including lock and contact sensor devices).
6. Expand **Sort & Display Options**. Set activity threshold, sort preferences, and filtering options. Use **Hide Columns** to control which columns appear on desktop — Room and Hub apply to all tables; the six Health-specific buttons apply to the Health table only. Room visibility on phones is managed automatically by orientation (see Mobile Support).
7. *(Optional)* In Sort & Display Options, set the **HSM app ID** for live alert text on Hub #1, and enable **Also show HSM status for Hub #2 / #3** if those hubs run HSM (remember to enable the HSM toggle in their Maker API apps — it is needed both for the status line and for the remote **Cancel HSM Alerts** button).
8. *(Optional)* Review the three **Show Rules with Private Boolean FALSE table for Hub 1 / 2 / 3** controls (on by default) — only checked hubs are PB-scanned. To make remote hubs' PB State cells clickable, enter each hub's Private Boolean Manager app ID and access token. PB scanning requires Hub Login Security OFF on each scanned hub. The first PB scan starts automatically once at least one hub's PB control is enabled.
9. Click **Refresh Table** at the top of the page to run the first full report.
10. Click **Done** to save.

---

## Maintenance

**Upgrading the app code:** Paste the new version over the existing app code (Apps Code → open the app → replace → Save), then open the app instance and click **Done** once so subscriptions and settings re-initialize. When upgrading across version 1.56, run **⟳ Load / Reload Device List** on each remote hub once to populate the new contact sensor selector. When upgrading across version 1.60, the Private Boolean FALSE table appears and its first scan starts automatically on the next page open (its per-hub controls default to on; a pre-1.72 single PB visibility setting is migrated to the per-hub controls automatically). When upgrading across version 1.78, the old hidden Hub #1 health list (previously populated by **Select All**) is retired and removed automatically on the first **Done** — the capability picker now governs everything it can show. If you had relied on **Select All** to pull in a device the picker cannot display (e.g. an Actuator-only driver such as MQTT Display Publisher), re-add that one device by ID in the new **Additional health-monitor device IDs — Hub #1** field. When upgrading to 1.79, nothing needs migrating: the **Cancel HSM Alerts** buttons appear automatically the next time an alert is active, and **Clear HSM Alert Display** is now shown only when the alert state could not be verified. When upgrading to 1.80, nothing needs migrating either. Tilt sensors already selected in a contact picker will show their `active` / `inactive` state on the next Refresh instead of *unknown*. To add a tilt sensor on Hub #2/#3, expose it in that hub's Maker API app, then run **⟳ Load / Reload Device List** so it appears in the contact sensor selector.

**Adding devices to a remote hub's report:** Add the device in the remote hub's Maker API app, then run **⟳ Load / Reload** from the Actions dropdown. The new device will appear in the pickers.

**Removing a device from monitoring:** Deselect it in the appropriate picker.

**Hub goes offline:** The report shows a red warning banner for that hub and omits its devices from the tables. The remaining hubs still report normally.

**"No usable device data" warnings:** If a hub's Maker API returns a device list with no usable entries, the app treats it as a data outage rather than an empty selection — the affected fetchers stop and a visible per-hub warning names the likely cause (typically a recent platform/beta update) instead of rendering clean-but-empty tables. A genuinely empty selection still reports normally.

**Deleted devices in a Maker API list:** A device that was deleted while still selected in a hub's Maker API app can appear as a literal null entry in that hub's device list. These entries are skipped automatically (with a log entry noting how many); re-save that hub's Maker API app to clean them up.

**Platform compatibility:** As of 1.77 the app works on both standard firmware and the 2026 beta platform builds that hand back Maker API JSON responses pre-parsed — no rollback is required.

**Refresh cadence:** The tables are only updated when you click **Refresh Table** or revisit the app page. The app does not poll on a schedule, with one exception: while a PB scan is running, the page auto-refreshes every 30 seconds until the scan completes.