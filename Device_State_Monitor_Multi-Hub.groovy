/*
Device State Monitor Multi-Hub

PURPOSE: Report device states across up to three Hubitat hubs, with clickable
         State cells to turn switch devices ON or OFF instantly. Also reports
         devices that are OFFLINE, INACTIVE, NOT PRESENT, DISCONNECTED
         (e.g. MQTT Display Publisher), or whose last activity exceeds a
         configurable time threshold, plus RM/BC rules whose Private Boolean
         is currently FALSE.

FEATURES:
    * Hub #1 devices queried locally. Separate ON-monitor, OFF-monitor, and
      Health/Activity-monitor device pickers. The health picker uses Load /
      Select All / Clear All (via Hub #1 Maker API) when credentials are
      configured; falls back to a capability picker otherwise.
    * Hubs #2 & #3 queried via Maker API. Connection credentials are collapsible
      once configured.
    * Clickable State cells in ON/OFF tables replace the old Action column: click
      "ON" to turn a device off, click "OFF" to turn it on. Unknown-state devices
      show → ON / → OFF mini-buttons embedded directly in the State cell.
      The Action column has been removed from all switch tables.
    * Toggle commands for Hub #1 use a separate optional Maker API credential block.
      Toggle commands for Hubs #2 & #3 reuse their existing Maker API credentials.
    * Optional Unknown State table catches devices reporting neither on nor off.
    * Health / Activity Monitor table shows any selected device that is OFFLINE,
      INACTIVE, NOT PRESENT, DISCONNECTED (connectionStatus attribute),
      or has last activity older than X hours (configurable).
    * Each hub has its own Health/Activity device selector. Remote hubs expose ALL
      Maker API devices (not just switch-capable) in the health selector.
    * Optional toggle to exclude virtual devices from all reports, including the
      Health/Activity table (uses same "virtual" definition as other tables).
    * All tables are independently sortable (click headers or Sort Options).
    * Health / Activity Monitor table is horizontally scrollable on narrow screens
      so iPhone portrait views do not squeeze columns until cells overlap.
    * Private Boolean FALSE table scans RM/BC child rules across Hub #1 and
      enabled Hubs #2/#3, lists only FALSE rules, and keeps unreadable rules
      as unknown instead of incorrectly treating them as FALSE. FALSE state
      cells are clickable to set that rule's Private Boolean TRUE: Hub #1
      directly via RMUtils, Hubs #2/#3 relayed to Private Boolean Manager's
      /setPB endpoint on that hub (requires PBM installed there plus its app
      ID and access token in this app's PB table settings). A changed row
      stays listed until the next PB scan and can be clicked back to FALSE.
    * Device names link to their hub's Devices page; PB rule names link to the
      corresponding rule configuration page.
    * HSM status/alert badges for Hub #1 (and optionally Hubs #2/#3). An
      active alert can be cancelled from the page (1.79): a "Cancel HSM
      Alerts" button (with a confirmation step) sends HSM's documented
      hsmSetArm "cancelAlerts" command on Hub #1, or Maker API's
      /hsm/cancelAlerts on Hubs #2/#3. Cancelling stops in-progress alerts
      but does not disarm HSM, so a still-true condition can re-alert.
    * Report tables and Refresh button appear at the TOP of the page;
      all configuration sections are collapsed below.
    * Refresh Table button re-queries all hubs on demand.
    * Graceful error handling if a remote hub is offline or unreachable.
    * Only switch-capable devices appear in ON/OFF/Unknown selection lists.
    * Remote hub action dropdowns leave their section open after action.
    * Each Device Selection section shows one reminder + a direct "Open
      Maker API" link button so newly added hub devices aren't missed: a
      device must be exposed in Maker API on its hub BEFORE it can appear
      in any picker here, even after Load/Reload.
    * Contact Sensor table supports tilt sensors (1.80): a selected sensor
      that reports no "contact" value (e.g. Third Reality tilt sensors,
      whose driver declares ContactSensor but only sends "acceleration")
      shows its acceleration state instead — "active" is flagged in red
      like "open", "inactive" is shown in green like "closed".
*/

import hubitat.helper.RMUtils
import groovy.transform.Field

@Field static final String PB_RM_BASE_URL                    = "http://127.0.0.1:8080"
@Field static final String PB_RM_VERSION                     = "5.0"
@Field static final int    PB_SCAN_TIMEOUT_SECS              = 360
@Field static final int    PB_STATUS_REQUEST_TIMEOUT_SECS    = 30
@Field static final int    PB_STATUS_WATCHDOG_INTERVAL_SECS   = 5    // sequential per-request watchdog check interval
@Field static final int    PB_STATUS_READ_MAX_ATTEMPTS        = 3

// HSM cancel-alerts (1.79)
@Field static final int    HSM_CANCEL_CONFIRM_SECS  = 60    // a "Cancel HSM Alerts" click must be confirmed within this window
@Field static final int    HSM_CANCEL_GRACE_SECS    = 20    // after a cancel, an ALERT still seen on the hub's pages is treated as "pending", not re-latched
@Field static final int    HSM_CANCEL_TRACK_SECS    = 600   // how long a sent cancel is tracked for the result note under the badge

// Transient PB scan state. Like Private Boolean Manager, this avoids writing
// hundreds of intermediate rows to Hubitat's state database during a scan.
@Field static String    pbCurrentScanId      = null
@Field static Long      pbScanStartMs        = 0L
@Field static List<Map> pbScanRuleQueue      = null // all selected rules, read one at a time
@Field static Map       pbScanPartialResults = null
@Field static Integer   pbScanSequentialIdx   = 0
@Field static Map       pbScanSequentialAttempts = [:] // rule key -> attempts already completed
@Field static String    pbScanSequentialActiveKey = null
@Field static String    pbScanSequentialRequestToken = null
@Field static Long      pbScanSequentialStartedMs = 0L

// Finalized scan results, kept in a static so they cannot be clobbered by a
// concurrent execution's whole-map state write-back (Hubitat state is a
// snapshot committed at end of execution — last writer wins for the ENTIRE
// map, so a slow page render can silently erase everything the async scan
// chain wrote to state). mainPage() read-repairs state from this map.
@Field static Map       pbFinalizedResults      = null
@Field static List      pbScanDiscoveryWarnings = null
@Field static String    pbToggleRuleId           = null   // serializes PB click actions against scans / other clicks

definition(
    name:         "Device State Monitor Multi-Hub 1.80",
    namespace:    "John Land",
    author:       "John Land via Claude AI and ChatGPT",
    description:  "Reports device states, health/activity, and FALSE Private Booleans across up to three hubs",
    installOnOpen:  true,
    oauth:          true,
    category:       "Convenience",
    iconUrl:      "",
    iconX2Url:    "",
    importUrl:    "https://raw.githubusercontent.com/JohnFLand/Hubitat-Device-State-Monitor-Multi-Hub/refs/heads/main/Device_State_Monitor_Multi-Hub.groovy"
)

preferences {
    page(name: "mainPage")
}

// Local OAuth endpoint used by the clickable PB State cells. Hub #1 rules are
// set directly via RMUtils; Hub #2/#3 rules are relayed server-side to Private
// Boolean Manager's /setPB endpoint on that hub. Setting TRUE is allowed for
// any rule cached as FALSE; setting FALSE is deliberately restricted to rules
// this table itself set TRUE (cached privateBool == true), so arbitrary rules
// cannot be driven FALSE from here.
mappings {
    path("/setPb") { action: [GET: "handleSetPbEndpoint"] }
}

// ─────────────────────────────────────────────────────────────────────────────
// UI: MAIN PAGE
// ─────────────────────────────────────────────────────────────────────────────

def mainPage() {
    checkOAuth()
    syncAppInstanceLabel()
    migratePbLegacySettings()

    // Read-repair: copy the last finalized PB scan results from the @Field
    // static into this execution's state snapshot BEFORE deciding whether a
    // scan is stuck or needs to be (re)started. If a prior page render's
    // whole-map state commit clobbered the results the async chain wrote,
    // this restores them, and this render's own end-of-execution commit
    // persists them. Without this, rowsJson stayed null forever and every
    // 30-second poll restarted a scan whose results were destroyed in turn.
    syncPbFinalizedResults()

    // @Field scan state is intentionally transient. If the app class was reloaded
    // or the hub restarted mid-scan, do not leave a permanent "scan in progress"
    // message behind after the transient scan context has disappeared.
    if (pbCurrentScanId == null && state.pbFalseScanStatus?.toString()?.contains("scan in progress")) {
        state.pbFalseScanStatus = state.pbFalseRowsJson
            ? "<i>The prior PB scan was interrupted before completion. Previous completed results are shown; click Scan PB FALSE Rules to scan again.</i>"
            : null
    }

    // On the first page open after installing/upgrading, populate the PB table
    // automatically. UNKNOWN is now a valid published result after retries, so it
    // does not trigger another automatic scan on every page render.
    boolean pbNeedsInitialScan = (state.pbFalseRowsJson == null)
    boolean pbIncompleteScanWaitingForManualRetry = state.pbFalseLastError?.toString()?.contains("Fresh results were NOT published")
    if (anyPbFalseHubShown() && pbNeedsInitialScan && !pbIncompleteScanWaitingForManualRetry && pbCurrentScanId == null) {
        startPbFalseScan()
    }

    // PB scans can take a minute or more on a hub with hundreds of rules. A
    // gentle 30-second page poll updates the table when the async chain finishes
    // without repeatedly hammering the remote device APIs every five seconds.
    int pbPollInterval = (pbCurrentScanId != null) ? 30 : 0

    dynamicPage(name: "mainPage", title: "<b>${htmlEscape(getAppDisplayName())}</b>", uninstall: true, install: true, refreshInterval: pbPollInterval) {

        // ── Refresh + Report (TOP) ────────────────────────────────────────────
        section(title: "") {
            input "refresh", "button", title: "Refresh Table", width: 6
            if (anyPbFalseHubShown()) {
                input "btnScanPbFalseTop", "button", title: "Scan PB FALSE Rules", width: 6
            }
            // Generate the report BEFORE deciding which HSM buttons to show:
            // report generation runs the HSM verification poll, which can
            // latch a newly-detected alert or auto-clear a stale one.
            // Evaluating the buttons first left them one refresh behind (1.58).
            def reportHtml = handler()
            renderHsmAlertButtons()
            paragraph reportHtml
        }

        // ── Second Refresh button (bottom of report, above config sections) ──
        section(title: "") {
            input "refresh2", "button", title: "Refresh Table", width: 6
            if (anyPbFalseHubShown()) {
                input "btnScanPbFalseBottom", "button", title: "Scan PB FALSE Rules", width: 6
            }
        }

        // ── Hub #1 – Local ────────────────────────────────────────────────────
        def hub1LabelVal       = settings["hub1Label"] ?: (location.name ?: "Hub 1")
        def h1OnCount          = (devsOn   ?: []).findAll { !it.isDisabled() }.size()
        def h1OffCount         = (devsOff  ?: []).findAll { !it.isDisabled() }.size()
        def h1LockCount        = (devsLock    ?: []).findAll { !it.isDisabled() }.size()
        def h1ContactCount     = (devsContact ?: []).findAll { !it.isDisabled() }.size()
        def hub1Title          = "Device Selection for Hub #1 – ${hub1LabelVal}"
        if (showSectionDetails) hub1Title += buildSelSummary(h1OnCount, h1OffCount, (hub1HealthDevs ?: []).size(), h1LockCount, h1ContactCount)

        def hub1HealthActionVal  = settings["hub1HealthAction"]
        def hub1HealthActionOpen = (hub1HealthActionVal && hub1HealthActionVal != "none")

        section(hideable: true, hidden: !(hub1HealthActionOpen), title: hub1Title) {
            // Process hub1HealthAction inside the section so side-effects happen during render
            if (hub1HealthActionOpen) {
                def h1AllIds = (state["hub1AllDevices"] ?: []).collect { it.id.toString() }
                switch (hub1HealthActionVal) {
                    case "load":
                        loadHub1AllDevices(); break
                    case "selAllHealth":
                        // Operate on the capability picker only — it is the single
                        // source of truth for picker-visible devices. (Picker-invisible
                        // devices are handled via the explicit hub1ExtraHealthIds field.)
                        app.updateSetting("hub1HealthDevs", [value: h1AllIds, type: "capability"])
                        break
                    case "unselAllHealth":
                        app.updateSetting("hub1HealthDevs", [value: [], type: "capability"])
                        break
                }
                app.updateSetting("hub1HealthAction", [value: "none", type: "enum"])
            }

            paragraph("<i>Select local (Hub #1) devices to monitor. Disabled devices are omitted. " +
                      "A device may appear in both switch lists.</i>")
            input "hub1Label", "text",
                title: "Friendly label for Hub #1 (shown in Hub column)",
                defaultValue: (location.name ?: "Hub 1"), required: true, submitOnChange: true

            paragraph("<hr><b>Devices to monitor for ON state</b> <small>(flagged when on)</small>")
            input "devsOn",  "capability.switch", title: "Select ON-monitored devices",
                submitOnChange: true, multiple: true, required: false
            paragraph("<hr><b>Devices to monitor for OFF state</b> <small>(flagged when off)</small>")
            input "devsOff", "capability.switch", title: "Select OFF-monitored devices",
                submitOnChange: true, multiple: true, required: false

            paragraph("<hr><b>Devices to monitor for lock state</b> <small>(all lock states shown)</small>")
            def h1LockSelCount = (devsLock ?: []).size()
            input "devsLock", "capability.lock",
                title: "Select lock-monitored devices (${h1LockSelCount} selected)",
                submitOnChange: true, multiple: true, required: false

            paragraph("<hr><b>Devices to monitor for contact state</b> <small>(sensors currently OPEN — or ACTIVE, for tilt sensors — are flagged)</small>")
            def h1ContactSelCount = (devsContact ?: []).size()
            input "devsContact", "capability.contactSensor",
                title: "Select contact-monitored devices (${h1ContactSelCount} selected)",
                submitOnChange: true, multiple: true, required: false

            // ── Hub #1 Health / Activity selector ────────────────────────────
            paragraph("<hr>")
            // Collapsible Maker API block (toggle commands AND health device loading share same credentials)
            input "hub1ShowToggle", "bool",
                title: "Show / Edit Toggle Command & Health Monitor Settings (Maker API for Hub #1)",
                defaultValue: false, submitOnChange: true
            if (settings["hub1ShowToggle"]) {
                paragraph("<i>Install Maker API on Hub #1, expose all desired devices, then enter the " +
                          "app ID and token below. These credentials are used both for in-row toggle " +
                          "actions/buttons <b>and</b> for loading the Health/Activity device list. " +
                          "If left blank, toggle buttons will not appear and health Select All / Clear All " +
                          "will be unavailable (a capability picker is shown instead).</i>")
                input "hub1AppId", "text", title: "Hub #1 Maker API app ID",       required: false, submitOnChange: true
                input "hub1Token", "text", title: "Hub #1 Maker API access token", required: false, submitOnChange: true
            } else {
                def apiConfigured = (settings["hub1AppId"] && settings["hub1Token"])
                def toggleStatus  = apiConfigured ? "Configured" : "Not configured"
                paragraph("<small><i>Maker API: ${toggleStatus}. Toggle above to edit.</i></small>")
            }

            paragraph("<hr><b>Devices to monitor for health / activity — Hub #1</b> " +
                      "<small>(flagged when OFFLINE, INACTIVE, NOT PRESENT, DISCONNECTED, or activity overdue)</small>")

            def hub1ApiReady = (settings["hub1AppId"] && settings["hub1Token"])
            if (hub1ApiReady) {
                makerApiReminder(hub1LabelVal, location.hubs[0].localIP, settings["hub1AppId"])
                // Load / Select All / Clear All controls (Maker API used only for the device list)
                def h1HealthStatus = state["hub1AllDevicesStatus"]
                if (h1HealthStatus) {
                    def hc = h1HealthStatus.startsWith("OK") ? "green" : "red"
                    paragraph("<small><i>Last load: <span style='color:${hc};font-weight:bold;'>${h1HealthStatus}</span></i></small>")
                }
                input "hub1HealthAction", "enum",
                    title: "Hub #1 Health Device List Actions", defaultValue: "none",
                    options: ["none"          : "Choose action…",
                              "load"          : "⟳ Load / Reload Health Device List from Hub #1",
                              "selAllHealth"  : "✓ Select All health-monitored devices",
                              "unselAllHealth": "✗ Clear health-monitored devices"],
                    required: false, submitOnChange: true
            } else {
                paragraph("<small><i>Configure Hub #1 Maker API credentials above to enable " +
                          "<b>Load / Select All / Clear All</b> for health monitoring.</i></small>")
            }
            // Capability picker always shown — data collection uses this directly (no API call needed).
            // This picker is the SINGLE source of truth for picker-visible devices: checking adds,
            // unchecking removes, with no hidden list overriding it.
            def h1HealthSelCount = (hub1HealthDevs ?: []).size()
            input "hub1HealthDevs", "capability.*",
                title: "Select health/activity-monitored devices (${h1HealthSelCount} selected)",
                submitOnChange: true, multiple: true, required: false

            // Explicit supplement for the niche case the picker cannot cover:
            // devices whose driver exposes no capability the capability.* picker
            // recognizes (e.g. Actuator-only drivers like MQTT Display Publisher).
            // IDs already selected in the picker above are ignored here, so the
            // two never double-count.
            def h1ExtraCount = (settings["hub1ExtraHealthIds"] ?: "").split(",").collect { it.trim() }.findAll { it }.size()
            input "hub1ExtraHealthIds", "text",
                title: "Additional health-monitor device IDs — Hub #1 (${h1ExtraCount} entered)",
                description: "Comma-separated device IDs. Only for devices that do NOT appear in the picker above.",
                required: false, submitOnChange: true
            if (h1ExtraCount > 0 && !(settings["hub1AppId"] && settings["hub1Token"])) {
                paragraph("<small><i><span style='color:red;'>Note:</span> extra device IDs are health-checked " +
                          "through Hub #1's Maker API — configure the Hub #1 Maker API app ID and token above, " +
                          "or these IDs are skipped.</i></small>")
            }
        }

        // ── Hub #2 – Remote ───────────────────────────────────────────────────
        def hub2LabelVal   = settings["hub2Label"] ?: "Hub 2"
        def hub2Enabled    = settings["hub2Enabled"]
        def hub2ActionVal  = settings["hub2Action"]
        def hub2ActionOpen = (hub2ActionVal && hub2ActionVal != "none")

        def h2OnList     = normalizeSelectionList(settings["hub2SelectedOnDevices"])
        def h2OffList    = normalizeSelectionList(settings["hub2SelectedOffDevices"])
        def h2HealthList = normalizeSelectionList(settings["hub2SelectedHealthDevices"])
        def h2LockList    = normalizeSelectionList(settings["hub2SelectedLockDevices"])
        def h2ContactList = normalizeSelectionList(settings["hub2SelectedContactDevices"])
        if (hub2ActionOpen) {
            def stored    = state["hub2Devices"]        ?: []
            def storedAll = state["hub2AllDevices"]     ?: []
            def storedLck = state["hub2LockDevices"]    ?: []
            def storedCt  = state["hub2ContactDevices"] ?: []
            switch (hub2ActionVal) {
                case "selAllOn":      h2OnList     = stored.collect    { it.id.toString() }; break
                case "unselAllOn":    h2OnList     = []; break
                case "selAllOff":     h2OffList    = stored.collect    { it.id.toString() }; break
                case "unselAllOff":   h2OffList    = []; break
                case "selAllHealth":  h2HealthList = storedAll.collect { it.id.toString() }; break
                case "unselAllHealth":h2HealthList = []; break
                case "selAllLock":    h2LockList   = storedLck.collect { it.id.toString() }; break
                case "unselAllLock":  h2LockList   = []; break
                case "selAllContact": h2ContactList = storedCt.collect { it.id.toString() }; break
                case "unselAllContact": h2ContactList = []; break
            }
        }
        def hub2Title = "Device Selection for Hub #2 – ${hub2LabelVal}"
        if (showSectionDetails && hub2Enabled) hub2Title += buildSelSummary(h2OnList.size(), h2OffList.size(), h2HealthList.size(), h2LockList.size(), h2ContactList.size())

        section(hideable: true, hidden: !hub2ActionOpen, title: hub2Title) {
            if (hub2ActionOpen) {
                def hub2Stored    = state["hub2Devices"]        ?: []
                def hub2StoredAll = state["hub2AllDevices"]     ?: []
                def hub2StoredLck = state["hub2LockDevices"]    ?: []
                def hub2StoredCt  = state["hub2ContactDevices"] ?: []
                switch (hub2ActionVal) {
                    case "load":
                        loadRemoteDeviceList(2, settings["hub2Ip"], settings["hub2AppId"], settings["hub2Token"]); break
                    case "selAllOn":
                        app.updateSetting("hub2SelectedOnDevices",     [value: hub2Stored.collect    { it.id.toString() }, type: "enum"]); break
                    case "unselAllOn":
                        app.updateSetting("hub2SelectedOnDevices",     [value: [], type: "enum"]); break
                    case "selAllOff":
                        app.updateSetting("hub2SelectedOffDevices",    [value: hub2Stored.collect    { it.id.toString() }, type: "enum"]); break
                    case "unselAllOff":
                        app.updateSetting("hub2SelectedOffDevices",    [value: [], type: "enum"]); break
                    case "selAllHealth":
                        app.updateSetting("hub2SelectedHealthDevices", [value: hub2StoredAll.collect { it.id.toString() }, type: "enum"]); break
                    case "unselAllHealth":
                        app.updateSetting("hub2SelectedHealthDevices", [value: [], type: "enum"]); break
                    case "selAllLock":
                        app.updateSetting("hub2SelectedLockDevices",   [value: hub2StoredLck.collect { it.id.toString() }, type: "enum"]); break
                    case "unselAllLock":
                        app.updateSetting("hub2SelectedLockDevices",    [value: [], type: "enum"]); break
                    case "selAllContact":
                        app.updateSetting("hub2SelectedContactDevices", [value: hub2StoredCt.collect { it.id.toString() }, type: "enum"]); break
                    case "unselAllContact":
                        app.updateSetting("hub2SelectedContactDevices", [value: [], type: "enum"]); break
                }
                app.updateSetting("hub2Action", [value: "none", type: "enum"])
            }
            input "hub2Enabled", "bool", title: "Enable Hub #2?", defaultValue: false, submitOnChange: true
            if (hub2Enabled) {
                input "hub2Label", "text", title: "Friendly label for Hub #2 (shown in Hub column)",
                    defaultValue: "Hub 2", required: true, submitOnChange: true
                input "hub2ShowConn", "bool",
                    title: "Show / Edit Connection Settings (IP, App ID, Token)",
                    defaultValue: true, submitOnChange: true
                if (settings["hub2ShowConn"]) {
                    paragraph("<i>Install Maker API on Hub #2, expose all desired devices, enter " +
                              "credentials below, then choose <b>Load / Reload Device List</b> from the Action dropdown. " +
                              "These same credentials are used for toggle commands.</i>")
                    input "hub2Ip",    "text", title: "Hub #2 IP address",             required: true, submitOnChange: false
                    input "hub2AppId", "text", title: "Hub #2 Maker API app ID",       required: true, submitOnChange: false
                    input "hub2Token", "text", title: "Hub #2 Maker API access token", required: true, submitOnChange: false
                } else {
                    def sum = settings["hub2Ip"] ? "Connected to ${settings['hub2Ip']}" : "Not yet configured"
                    paragraph("<small><i>Connection: ${sum}. Toggle above to edit.</i></small>")
                }
                def hub2Status = state["hub2LoadStatus"]
                if (hub2Status) {
                    def c = hub2Status.startsWith("OK") ? "green" : "red"
                    paragraph("<small><i>Last load: <span style='color:${c};font-weight:bold;'>${hub2Status}</span></i></small>")
                }
                makerApiReminder(hub2LabelVal, settings["hub2Ip"], settings["hub2AppId"])
                input "hub2Action", "enum", title: "Hub #2 Actions", defaultValue: "none",
                    options: ["none": "Choose action…", "load": "⟳ Load / Reload Device List from Hub #2",
                              "selAllOn": "✓ Select All ON-monitored devices",     "unselAllOn":    "✗ Clear ON-monitored devices",
                              "selAllOff": "✓ Select All OFF-monitored devices",   "unselAllOff":   "✗ Clear OFF-monitored devices",
                              "selAllLock": "✓ Select All lock-monitored devices", "unselAllLock":  "✗ Clear lock-monitored devices",
                              "selAllContact": "✓ Select All contact-monitored devices", "unselAllContact": "✗ Clear contact-monitored devices",
                              "selAllHealth": "✓ Select All health-monitored devices", "unselAllHealth": "✗ Clear health-monitored devices"],
                    required: false, submitOnChange: true
                renderRemoteDeviceSelectors(2, state["hub2Devices"], h2OnList, h2OffList)
                renderRemoteLockDeviceSelector(2, state["hub2LockDevices"], h2LockList)
                renderRemoteContactDeviceSelector(2, state["hub2ContactDevices"], h2ContactList)
                renderRemoteHealthDeviceSelector(2, state["hub2AllDevices"], h2HealthList)
                paragraph("<small><i><b>Disabled devices:</b> Hubitat's Maker API does not expose disabled state " +
                          "reliably, so disabled devices on remote hubs cannot be filtered automatically. " +
                          "If a disabled device appears in the Health/Activity table, deselect it from the " +
                          "health device picker above, or enter its ID below to permanently exclude it. " +
                          "The device ID is the number in its edit URL: <code>/device/edit/169</code>. Comma-separated.</i></small>")
                input "hub2ExcludeHealthIds", "text",
                    title: "Hub #2: Manually excluded health-monitor device IDs (fallback)",
                    required: false, submitOnChange: false
            }
        }

        // ── Hub #3 – Remote ───────────────────────────────────────────────────
        def hub3LabelVal   = settings["hub3Label"] ?: "Hub 3"
        def hub3Enabled    = settings["hub3Enabled"]
        def hub3ActionVal  = settings["hub3Action"]
        def hub3ActionOpen = (hub3ActionVal && hub3ActionVal != "none")

        def h3OnList     = normalizeSelectionList(settings["hub3SelectedOnDevices"])
        def h3OffList    = normalizeSelectionList(settings["hub3SelectedOffDevices"])
        def h3HealthList = normalizeSelectionList(settings["hub3SelectedHealthDevices"])
        def h3LockList    = normalizeSelectionList(settings["hub3SelectedLockDevices"])
        def h3ContactList = normalizeSelectionList(settings["hub3SelectedContactDevices"])
        if (hub3ActionOpen) {
            def stored    = state["hub3Devices"]        ?: []
            def storedAll = state["hub3AllDevices"]     ?: []
            def storedLck = state["hub3LockDevices"]    ?: []
            def storedCt  = state["hub3ContactDevices"] ?: []
            switch (hub3ActionVal) {
                case "selAllOn":      h3OnList     = stored.collect    { it.id.toString() }; break
                case "unselAllOn":    h3OnList     = []; break
                case "selAllOff":     h3OffList    = stored.collect    { it.id.toString() }; break
                case "unselAllOff":   h3OffList    = []; break
                case "selAllHealth":  h3HealthList = storedAll.collect { it.id.toString() }; break
                case "unselAllHealth":h3HealthList = []; break
                case "selAllLock":    h3LockList   = storedLck.collect { it.id.toString() }; break
                case "unselAllLock":  h3LockList   = []; break
                case "selAllContact": h3ContactList = storedCt.collect { it.id.toString() }; break
                case "unselAllContact": h3ContactList = []; break
            }
        }
        def hub3Title = "Device Selection for Hub #3 – ${hub3LabelVal}"
        if (showSectionDetails && hub3Enabled) hub3Title += buildSelSummary(h3OnList.size(), h3OffList.size(), h3HealthList.size(), h3LockList.size(), h3ContactList.size())

        section(hideable: true, hidden: !hub3ActionOpen, title: hub3Title) {
            if (hub3ActionOpen) {
                def hub3Stored    = state["hub3Devices"]        ?: []
                def hub3StoredAll = state["hub3AllDevices"]     ?: []
                def hub3StoredLck = state["hub3LockDevices"]    ?: []
                def hub3StoredCt  = state["hub3ContactDevices"] ?: []
                switch (hub3ActionVal) {
                    case "load":
                        loadRemoteDeviceList(3, settings["hub3Ip"], settings["hub3AppId"], settings["hub3Token"]); break
                    case "selAllOn":
                        app.updateSetting("hub3SelectedOnDevices",     [value: hub3Stored.collect    { it.id.toString() }, type: "enum"]); break
                    case "unselAllOn":
                        app.updateSetting("hub3SelectedOnDevices",     [value: [], type: "enum"]); break
                    case "selAllOff":
                        app.updateSetting("hub3SelectedOffDevices",    [value: hub3Stored.collect    { it.id.toString() }, type: "enum"]); break
                    case "unselAllOff":
                        app.updateSetting("hub3SelectedOffDevices",    [value: [], type: "enum"]); break
                    case "selAllHealth":
                        app.updateSetting("hub3SelectedHealthDevices", [value: hub3StoredAll.collect { it.id.toString() }, type: "enum"]); break
                    case "unselAllHealth":
                        app.updateSetting("hub3SelectedHealthDevices", [value: [], type: "enum"]); break
                    case "selAllLock":
                        app.updateSetting("hub3SelectedLockDevices",   [value: hub3StoredLck.collect { it.id.toString() }, type: "enum"]); break
                    case "unselAllLock":
                        app.updateSetting("hub3SelectedLockDevices",    [value: [], type: "enum"]); break
                    case "selAllContact":
                        app.updateSetting("hub3SelectedContactDevices", [value: hub3StoredCt.collect { it.id.toString() }, type: "enum"]); break
                    case "unselAllContact":
                        app.updateSetting("hub3SelectedContactDevices", [value: [], type: "enum"]); break
                }
                app.updateSetting("hub3Action", [value: "none", type: "enum"])
            }
            input "hub3Enabled", "bool", title: "Enable Hub #3?", defaultValue: false, submitOnChange: true
            if (hub3Enabled) {
                input "hub3Label", "text", title: "Friendly label for Hub #3 (shown in Hub column)",
                    defaultValue: "Hub 3", required: true, submitOnChange: true
                input "hub3ShowConn", "bool",
                    title: "Show / Edit Connection Settings (IP, App ID, Token)",
                    defaultValue: true, submitOnChange: true
                if (settings["hub3ShowConn"]) {
                    paragraph("<i>Install Maker API on Hub #3, expose all desired devices, enter " +
                              "credentials below, then choose <b>Load / Reload Device List</b> from the Action dropdown. " +
                              "These same credentials are used for toggle commands.</i>")
                    input "hub3Ip",    "text", title: "Hub #3 IP address",             required: true, submitOnChange: false
                    input "hub3AppId", "text", title: "Hub #3 Maker API app ID",       required: true, submitOnChange: false
                    input "hub3Token", "text", title: "Hub #3 Maker API access token", required: true, submitOnChange: false
                } else {
                    def sum = settings["hub3Ip"] ? "Connected to ${settings['hub3Ip']}" : "Not yet configured"
                    paragraph("<small><i>Connection: ${sum}. Toggle above to edit.</i></small>")
                }
                def hub3Status = state["hub3LoadStatus"]
                if (hub3Status) {
                    def c = hub3Status.startsWith("OK") ? "green" : "red"
                    paragraph("<small><i>Last load: <span style='color:${c};font-weight:bold;'>${hub3Status}</span></i></small>")
                }
                makerApiReminder(hub3LabelVal, settings["hub3Ip"], settings["hub3AppId"])
                input "hub3Action", "enum", title: "Hub #3 Actions", defaultValue: "none",
                    options: ["none": "Choose action…", "load": "⟳ Load / Reload Device List from Hub #3",
                              "selAllOn": "✓ Select All ON-monitored devices",     "unselAllOn":    "✗ Clear ON-monitored devices",
                              "selAllOff": "✓ Select All OFF-monitored devices",   "unselAllOff":   "✗ Clear OFF-monitored devices",
                              "selAllLock": "✓ Select All lock-monitored devices", "unselAllLock":  "✗ Clear lock-monitored devices",
                              "selAllContact": "✓ Select All contact-monitored devices", "unselAllContact": "✗ Clear contact-monitored devices",
                              "selAllHealth": "✓ Select All health-monitored devices", "unselAllHealth": "✗ Clear health-monitored devices"],
                    required: false, submitOnChange: true
                renderRemoteDeviceSelectors(3, state["hub3Devices"], h3OnList, h3OffList)
                renderRemoteLockDeviceSelector(3, state["hub3LockDevices"], h3LockList)
                renderRemoteContactDeviceSelector(3, state["hub3ContactDevices"], h3ContactList)
                renderRemoteHealthDeviceSelector(3, state["hub3AllDevices"], h3HealthList)
                paragraph("<small><i><b>Disabled devices:</b> Hubitat's Maker API does not expose disabled state " +
                          "reliably, so disabled devices on remote hubs cannot be filtered automatically. " +
                          "If a disabled device appears in the Health/Activity table, deselect it from the " +
                          "health device picker above, or enter its ID below to permanently exclude it. " +
                          "The device ID is the number in its edit URL: <code>/device/edit/169</code>. Comma-separated.</i></small>")
                input "hub3ExcludeHealthIds", "text",
                    title: "Hub #3: Manually excluded health-monitor device IDs (fallback)",
                    required: false, submitOnChange: false
            }
        }

        // ── Sort & Display Options ─────────────────────────────────────────────
        section(hideable: true, hidden: true, title: "Sort & Display Options") {
            paragraph("<hr>")
            input "label", "text",
                title: "<b>App instance name</b>",
                defaultValue: getAppDisplayName(),
                required: false,
                submitOnChange: true,
                width: 9
            input "btnResetAppLabel", "button",
                title: "Reset to App Name",
                width: 3

            paragraph("<hr><i><b>Note:</b> These are the default sort orders. Click any column header to re-sort temporarily.</i>")

            paragraph("<hr><b>ON Devices Table</b>")
            input "sortByOn",    "enum", title: "Sort by", options: ["displayName": "Device Name", "room": "Room", "hub": "Hub"], defaultValue: "displayName", submitOnChange: true
            input "sortOrderOn", "enum", title: "Order",   options: ["asc": "Ascending", "desc": "Descending"],                   defaultValue: "asc",          submitOnChange: true

            paragraph("<hr><b>OFF Devices Table</b>")
            input "sortByOff",    "enum", title: "Sort by", options: ["displayName": "Device Name", "room": "Room", "hub": "Hub"], defaultValue: "displayName", submitOnChange: true
            input "sortOrderOff", "enum", title: "Order",   options: ["asc": "Ascending", "desc": "Descending"],                   defaultValue: "asc",          submitOnChange: true

            paragraph("<hr><b>Unknown State Table</b>")
            input "showUnknownTable", "bool",
                title: "Show Unknown State table? (devices reporting neither on nor off)",
                defaultValue: true, submitOnChange: true
            if (settings["showUnknownTable"] != false) {
                input "sortByUnk",    "enum", title: "Sort by", options: ["displayName": "Device Name", "room": "Room", "hub": "Hub"], defaultValue: "displayName", submitOnChange: true
                input "sortOrderUnk", "enum", title: "Order",   options: ["asc": "Ascending", "desc": "Descending"],                   defaultValue: "asc",          submitOnChange: true
            }

            paragraph("<hr><b>Lock State Table</b>")
            input "showLockTable", "bool",
                title: "Show Lock State table?",
                defaultValue: true, submitOnChange: true
            if (settings["showLockTable"] != false) {
                input "sortByLock",    "enum", title: "Sort by", options: ["displayName": "Device Name", "room": "Room", "hub": "Hub", "lockVal": "Lock State", "battery": "Battery %"], defaultValue: "displayName", submitOnChange: true
                input "sortOrderLock", "enum", title: "Order",   options: ["asc": "Ascending", "desc": "Descending"],                                             defaultValue: "asc",          submitOnChange: true
            }

            paragraph("<hr><b>Contact Sensor Table</b>")
            input "showContactTable", "bool",
                title: "Show Contact Sensor table?",
                defaultValue: true, submitOnChange: true
            if (settings["showContactTable"] != false) {
                input "contactOpenOnly", "bool",
                    title: "Show only sensors currently OPEN (or ACTIVE, for tilt sensors)? (off = show all selected sensors with their state)",
                    defaultValue: true, submitOnChange: true
                input "sortByContact",    "enum", title: "Sort by", options: ["displayName": "Device Name", "room": "Room", "hub": "Hub", "contactVal": "Contact State", "battery": "Battery %"], defaultValue: "displayName", submitOnChange: true
                input "sortOrderContact", "enum", title: "Order",   options: ["asc": "Ascending", "desc": "Descending"],                                                     defaultValue: "asc",          submitOnChange: true
            }

            paragraph("<hr><b>Health / Activity Monitor Table</b>")
            input "showHealthTable", "bool",
                title: "Show Health/Activity Monitor table?",
                defaultValue: true, submitOnChange: true
            if (settings["showHealthTable"] != false) {
                input "activityThresholdHours", "number",
                    title: "Flag devices with last activity more than X hours ago (default: 24)",
                    defaultValue: 24, required: false, submitOnChange: true
                input "sortByHealth", "enum", title: "Sort by",
                    options: ["displayName": "Device Name", "room": "Room", "hub": "Hub",
                              "status": "HE Status", "lastActivity": "Last Activity"],
                    defaultValue: "displayName", submitOnChange: true
                input "sortOrderHealth", "enum", title: "Order",
                    options: ["asc": "Ascending", "desc": "Descending"],
                    defaultValue: "asc", submitOnChange: true
            }

            paragraph("<hr><b>Rules with Private Boolean FALSE Table</b><br>" +
                      "<small><i>Each control selects whether that hub is included in the PB scan and shown in the combined table. Unchecked hubs are not queried during PB scanning.</i></small>")
            [1, 2, 3].each { int hubNum ->
                input "showPbFalseHub${hubNum}", "bool",
                    title: "Show Rules with Private Boolean FALSE table for Hub ${hubNum}",
                    defaultValue: true, submitOnChange: true
            }
            [2, 3].each { int hubNum ->
                if (showPbFalseForHub(hubNum) && settings["hub${hubNum}Enabled"]) {
                    def rLabel = settings["hub${hubNum}Label"] ?: "Hub ${hubNum}"
                    paragraph("<small><b>Hub #${hubNum} (${rLabel}) click-to-set:</b> to make this hub's FALSE cells " +
                              "clickable, enter its <b>Private Boolean Manager</b> app ID and OAuth access token below. " +
                              "Open PBM on that hub: the app ID is the number in its URL " +
                              "(<code>/installedapp/configure/&lt;app ID&gt;</code>), and its printable-report link has the form " +
                              "<code>/apps/api/&lt;app ID&gt;/report?access_token=&lt;token&gt;</code> — copy the token from there. " +
                              "Clicks are relayed from Hub #1 to PBM's /setPB endpoint on that hub.</small>")
                    input "hub${hubNum}PbmAppId", "number",
                        title: "Hub #${hubNum} Private Boolean Manager app ID",
                        required: false, submitOnChange: true
                    input "hub${hubNum}PbmToken", "text",
                        title: "Hub #${hubNum} Private Boolean Manager access token",
                        required: false, submitOnChange: true
                }
            }
            if (anyPbFalseHubShown()) {
                input "sortByPbFalse", "enum", title: "Sort by",
                    options: ["name": "Rule Name", "appType": "App Type", "hub": "Hub", "privateBool": "PB State", "lastRun": "Last Run"],
                    defaultValue: "name", submitOnChange: true
                input "sortOrderPbFalse", "enum", title: "Order",
                    options: ["asc": "Ascending", "desc": "Descending"],
                    defaultValue: "asc", submitOnChange: true
            }

            paragraph("<hr>")
            input "excludeVirtual",    "bool", title: "Exclude virtual devices from all reports (including Health/Activity table)?", defaultValue: false
            input "excludeSystemRoom", "bool", title: "Exclude devices in the \"System\" room from all reports?", defaultValue: false
            paragraph("<hr>")
            input "showHsmStatus",     "bool", title: "Show Hubitat Safety Monitor (HSM) status above the tables?", defaultValue: true, submitOnChange: true
            if (settings["showHsmStatus"] != false) {
                input "hsmVerifyAlert", "bool",
                    title: "Verify the HSM alert state against the hub on every refresh? (recommended — catches missed hsmAlert events and auto-clears stale alerts; requires Hub Login Security OFF on Hub #1)",
                    defaultValue: true, submitOnChange: true
                if (settings["hsmVerifyAlert"] != false) {
                    input "hsmAppId", "number",
                        title: "HSM app ID (optional but recommended — the number in HSM's URL, e.g. /installedapp/configure/<b>2</b>. When set, the live alert text is read straight from the HSM app page.)",
                        required: false, submitOnChange: false
                }
                [2, 3].each { rn ->
                    if (settings["hub${rn}Enabled"]) {
                        def rLabel = settings["hub${rn}Label"] ?: "Hub ${rn}"
                        input "showHsmStatusHub${rn}", "bool",
                            title: "Also show HSM status for Hub #${rn} (${rLabel})? Requires the <b>HSM</b> toggle enabled inside that hub's Maker API app. Adds 1–2 HTTP calls per refresh.",
                            defaultValue: false, submitOnChange: true
                        if (settings["showHsmStatusHub${rn}"]) {
                            input "hub${rn}HsmAppId", "number",
                                title: "Hub #${rn} HSM app ID (optional — the number in HSM's URL on that hub; enables live alert text. Alert checking requires Hub Login Security OFF on Hub #${rn}.)",
                                required: false, submitOnChange: false
                        }
                    }
                }
            }
            paragraph("<hr>")
            input "showSectionDetails","bool", title: "Show extra details in section headers?",    defaultValue: true

            def showHealthForCols = settings["showHealthTable"] != false
            def heStatusBtn  = showHealthForCols ? "<span class='dsm-col-btn' data-dsm-col='dsm-col-hestatus' onclick=\"toggleDsmCol('dsm-col-hestatus',this)\">HE Status</span>"      : ""
            def healthStBtn  = showHealthForCols ? "<span class='dsm-col-btn' data-dsm-col='dsm-col-healthst' onclick=\"toggleDsmCol('dsm-col-healthst',this)\">Health Status</span>"  : ""
            def lastActBtn   = showHealthForCols ? "<span class='dsm-col-btn' data-dsm-col='dsm-col-lastact'  onclick=\"toggleDsmCol('dsm-col-lastact',this)\">Last Activity</span>"   : ""
            def issueBtn     = showHealthForCols ? "<span class='dsm-col-btn' data-dsm-col='dsm-col-issue'    onclick=\"toggleDsmCol('dsm-col-issue',this)\">Issue</span>"             : ""
            def batteryBtn   = showHealthForCols ? "<span class='dsm-col-btn' data-dsm-col='dsm-col-battery'  onclick=\"toggleDsmCol('dsm-col-battery',this)\">Battery %</span>"      : ""
            def lastBattBtn  = showHealthForCols ? "<span class='dsm-col-btn' data-dsm-col='dsm-col-lastbatt' onclick=\"toggleDsmCol('dsm-col-lastbatt',this)\">Last Battery</span>"  : ""
            paragraph("<hr><b>Hide Columns</b><br>" +
                "<div class='dsm-col-toggle-bar'>" +
                "<span class='dsm-col-btn' data-dsm-col='dsm-col-room' onclick=\"toggleDsmCol('dsm-col-room',this)\">Room</span>" +
                "<span class='dsm-col-btn' data-dsm-col='dsm-col-hub'  onclick=\"toggleDsmCol('dsm-col-hub',this)\">Hub</span>" +
                issueBtn + heStatusBtn + healthStBtn + lastActBtn + batteryBtn + lastBattBtn +
                "</div>")

            paragraph("<hr>")
            input "enableLogging", "bool", title: "Enable debug logging?", defaultValue: false
        }

        // ── Notes / User Guide ────────────────────────────────────────────────
        section(hideable: true, hidden: true, title: "Notes / User Guide") {
            paragraph(
                "<b>Device State Monitor Multi-Hub</b> reports switch states and device health " +
                "across up to three Hubitat hubs from a single app page." +

                "<hr><b>Page Layout</b><br>" +
                "The <b>Refresh Table</b> button and report tables appear at the top of the page. " +
                "If HSM is installed, its current intrusion-arm status appears above the tables as a colour-coded badge. " +
                "Active HSM alerts (intrusion, smoke, water, custom rules) are shown separately as a blinking red line — " +
                "the app subscribes to <i>hsmAlert</i> events and stores the active alert in app state, clearing it automatically when HSM cancels the alert " +
                "(both the <i>cancel</i> and <i>cancelRuleAlerts</i> cancellation values are honored — custom-rule alerts use the latter). " +
                "While an alert is active, a <b>Cancel HSM Alerts</b> button appears next to Refresh Table (one per alerting hub). " +
                "After a confirmation click it tells HSM itself to cancel its in-progress alerts (sirens included) — Hub #1 via HSM's <i>cancelAlerts</i> command, " +
                "Hubs #2/#3 via that hub's Maker API (<i>/hsm/cancelAlerts</i>; needs the HSM toggle enabled in that Maker API app). " +
                "Cancelling does not disarm HSM: if the alert condition is still true (a door still unlocked, a sensor still wet), HSM may alert again, and a note under the badge says so. " +
                "The display-only <b>Clear HSM Alert Display</b> button now appears only when the alert state could not be verified against the hub " +
                "(verification off or unavailable) — e.g. if a cancel event was missed — because a verified alert would simply be re-detected. " +
                "In addition, when <b>Verify the HSM alert state</b> is enabled (Sort &amp; Display Options, on by default), every refresh " +
                "cross-checks the badge against the hub itself: with the HSM app ID configured, the live alert text is read straight from the HSM app page " +
                "(e.g. <i>Custom Rule Alert: Door Locks unlocked</i>); without it, the hub's Apps list is scanned for the red ALERT! suffix HSM adds to its label. " +
                "Missed alerts are detected and displayed, and stale alerts are auto-cleared. This requires Hub Login Security to be OFF on Hub #1 — " +
                "if the pages can't be read, a note appears and the badge falls back to events only. " +
                "HSM on <b>Hubs #2 and #3</b> can also be displayed (off by default — enable per hub in Sort &amp; Display Options): " +
                "the arm status is fetched from that hub's Maker API <i>/hsm</i> endpoint (enable the HSM toggle inside that hub's Maker API app), " +
                "and alerts are checked by reading that hub's HSM / Apps pages, like Hub #1. Remote alerts are poll-only — location events don't cross hubs — " +
                "so they appear and clear on each refresh. When any remote HSM badge is on, every HSM line is labeled with its hub's name. " +
                "Note: HSM's <i>hsmStatus</i> only reflects intrusion arming (Away / Home / Night / Disarmed). " +
                "Smoke, water and custom monitoring rules stay armed even when intrusion is disarmed (until All Monitoring is disarmed), " +
                "so the badge shows <b>Intrusion Disarmed</b> with a note that smoke/water/custom monitoring remains armed — matching the hub's own \"Armed Smoke/Water\" wording. " +
                "Configuration sections (Hub #1, Hub #2, Hub #3, Sort &amp; Display Options, and these Notes) " +
                "are collapsed below and stay out of the way after initial setup." +

                "<hr><b>The Report Tables</b>" +
                "<ul>" +
                "<li><b>ON Devices</b> — monitored devices currently reporting switch state <b>on</b>.</li>" +
                "<li><b>OFF Devices</b> — monitored devices currently reporting switch state <b>off</b>.</li>" +
                "<li><b>Unknown State</b> — monitored devices reporting neither on nor off (can be hidden in Sort &amp; Display Options).</li>" +
                "<li><b>Lock State</b> — selected lock devices showing their current lock state and battery level. " +
                "Hub #1 uses a capability picker; Hubs #2 and #3 use the lock device selector populated by Load / Reload. " +
                "Locked is shown in green, unlocked in red. Battery % uses the same colour coding as the Health table " +
                "(green ≥ 40%, orange 20–39%, red &lt; 20%; shown as <i>n/a</i> if the device has no battery attribute).</li>" +
                "<li><b>Contact Sensors</b> — selected contact sensors. By default only sensors currently <b>open</b> are listed " +
                "(shown in red), with the heading reporting \"N open of M monitored\"; a Sort &amp; Display option switches to showing " +
                "every selected sensor with its state (open in red, closed in green). Hub #1 uses a capability picker; " +
                "Hubs #2 and #3 use a contact sensor selector populated by Load / Reload " +
                "(run <b>Load / Reload Device List</b> once after upgrading to 1.56 to populate it). " +
                "Tilt sensors whose driver reports no <i>contact</i> value (e.g. Third Reality tilt sensors, which only send " +
                "<i>acceleration</i>) show their acceleration state instead: <b>active</b> (tilted) is treated like open (red, " +
                "and listed in open-only mode), <b>inactive</b> like closed (green); hover the state for a note.</li>" +
                "<li><b>Health / Activity Monitor</b> — any health-monitored device that is OFFLINE, INACTIVE, NOT PRESENT, " +
                "or whose last activity exceeds the configured threshold. Columns: Device Name, Room, Hub, HE Status, " +
                "Issue, HE Status, Health Status, Last Activity, Battery %, Last Battery.</li>" +
                "<li><b>Rules with Private Boolean FALSE</b> — Rule Machine and Button Controller child rules whose current " +
                "Private Boolean is FALSE, plus any UNKNOWN exceptions that remain unreadable after retries. The scan covers only hubs selected by the three per-hub PB controls; Hubs #2/#3 must also be enabled normally. Unselected hubs are not queried. Columns are Rule, App Type, Hub, PB State, and Last Run. " +
                "Click a FALSE PB State cell to set that rule's Private Boolean TRUE; the cell turns to a blue TRUE and the row remains listed until the next PB scan, so a click can be undone — clicking the TRUE cell sets the rule's Private Boolean back FALSE. Setting FALSE is only possible for rows this table itself set TRUE. " +
                "Hub #1 cells act directly through Rule Machine's RMUtils on this hub. Hub #2/#3 cells become clickable once that hub's Private Boolean Manager app ID and access token are entered in the PB table settings; the click is relayed server-side from Hub #1 to Private Boolean Manager's /setPB endpoint on that hub, and the row is removed only after PBM confirms success. " +
                "A successful rule-status read with no stored Private Boolean is correctly treated as FALSE (the RM default). Any unreadable rule is retried sequentially up to three times; if it still cannot be read, it is published as UNKNOWN with its linked rule name available for manual inspection while all known FALSE results remain visible. Remote PB scanning uses Hubitat internal app/status pages and therefore requires " +
                "those pages to be accessible without a Hub Login Security login challenge.</li>" +
                "</ul>" +
                "Device names are clickable links to the device edit page. " +
                "Devices monitored in both the ON and OFF lists are flagged with a gold star (★) and shown in orange." +

                "<hr><b>Clickable State Cells</b><br>" +
                "When Maker API credentials are configured for a hub, the State cell in the ON and OFF tables is clickable — " +
                "tap it to send the opposite command without leaving the page. " +
                "The cell shows <b>…</b> while the command is in flight, then updates in-place on success. " +
                "The Unknown State table shows <b>→ ON</b> and <b>→ OFF</b> mini-buttons instead, since neither direction can be inferred." +

                "<hr><b>Hub #1 (Local)</b><br>" +
                "Hub #1 is the hub running this app. Devices are read directly — no network call needed. " +
                "Optionally configure Maker API credentials for Hub #1 under <i>Toggle Command &amp; Health Monitor Settings</i> " +
                "to enable clickable State cells and the Load / Select All / Clear All actions in the health device picker." +

                "<hr><b>Hubs #2 and #3 (Remote)</b><br>" +
                "Remote hubs are queried via their Maker API on every Refresh. To set one up:<br>" +
                "1. On the remote hub, install <b>Maker API</b> (Apps → Add Built-In App) and expose all devices you want to monitor.<br>" +
                "2. Note the <b>App ID</b> (shown in the Maker API heading), <b>Access Token</b>, and the hub's <b>local IP address</b>.<br>" +
                "3. In Device State Monitor, enable the hub, open <i>Show / Edit Connection Settings</i>, enter the IP / App ID / Token, " +
                "then choose <b>⟳ Load / Reload Device List</b> from the Actions dropdown.<br>" +
                "4. After loading, use Select All / Clear actions to manage device selections." +

                "<hr><b>Sort &amp; Display Options</b><br>" +
                "<b>App Name</b> — rename this app instance; use <b>Reset to App Name</b> to restore the current app code name/version.<br>" +
                "<b>Per-table sort</b> — set the default sort column and direction for each table. " +
                "Click any column header in the live table to re-sort interactively without changing the saved default.<br>" +
                "<b>Activity threshold</b> — devices with no recorded activity older than this many hours are flagged as Late Activity in the Health table. Default: 24h.<br>" +
                "<b>Private Boolean FALSE table</b> — three independent controls select which hubs are scanned and shown in the combined table. An unchecked hub receives no PB Apps-list discovery request and no per-rule PB status requests, so a Hub 1-only scan spends no time scanning Hubs 2 or 3. The table also has its own saved default sort. Turning a hub off hides its cached rows immediately; run <b>Scan PB FALSE Rules</b> after changing hub selections to refresh the cache from only the selected hubs. The PB scan reads current rule status strictly one rule at a time across all selected hubs. An unreadable rule is retried sequentially up to three times; if it still cannot be read, it is published as UNKNOWN with its linked rule name available for manual inspection while all known FALSE results remain visible. The first scan starts automatically after installing/upgrading when at least one hub's PB control is enabled.<br>" +
                "<b>Exclude virtual devices</b> — omits devices whose driver name contains \"virtual\" or whose name starts with \"VD \" from all tables.<br>" +
                "<b>Exclude System room</b> — omits devices in the Hubitat room named \"System\" from all tables.<br>" +
                "<b>Hide Columns</b> — eight toggle buttons control column visibility. " +
                "<b>Room</b> and <b>Hub</b> apply to all tables. " +
                "The remaining six apply to the Health / Activity table only: " +
                "<b>Issue</b>, <b>HE Status</b>, <b>Health Status</b>, <b>Last Activity</b>, <b>Battery %</b>, <b>Last Battery</b>. " +
                "Buttons appear in the same left-to-right order as the columns they control. " +
                "The Health / Activity table also has a horizontal scroll wrapper " +
                "so columns keep usable minimum widths instead of overlapping. Column visibility is saved in the browser's local storage and " +
                "restored automatically on the next page load." +

                "<hr><b>First-Time Setup</b><br>" +
                "1. Expand <b>Hub #1</b>, set a label, and select devices for ON, OFF, and health monitoring.<br>" +
                "2. For remote hubs: expand their section, enable, enter connection settings, and run Load / Reload.<br>" +
                "3. Expand <b>Sort &amp; Display Options</b> and set your preferences.<br>" +
                "4. Click <b>Refresh Table</b> to run the first report, then <b>Done</b> to save."
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// APP INSTANCE LABEL HELPERS
// ─────────────────────────────────────────────────────────────────────────────

private void syncAppInstanceLabel() {
    String desired = safeString(settings?.label).trim()
    if (!desired) return

    String current = safeString(app.label ?: app.name).trim()
    if (desired == current) return

    try {
        app.updateLabel(desired)
        if (enableLogging) log.debug "App instance label updated to: ${desired}"
    } catch (Throwable t) {
        log.warn "Could not update app instance label to '${desired}': ${t.message}"
    }
}

private void resetAppInstanceLabel() {
    String defaultName = app?.name?.toString() ?: "Device State Monitor Multi-Hub"
    try {
        app.updateLabel(defaultName)
        app.updateSetting("label", [value: defaultName, type: "text"])
        if (enableLogging) log.debug "Device State Monitor Multi-Hub: app label reset to app name '${defaultName}'"
    } catch (Throwable t) {
        log.warn "Device State Monitor Multi-Hub: app label reset failed — ${t.message}"
    }
}


private String getAppDisplayName() {
    return safeString(app.label ?: app.name ?: "Device State Monitor Multi-Hub")
}


// ─────────────────────────────────────────────────────────────────────────────
// PRIVATE BOOLEAN CLICK ENDPOINT / OAUTH
// Mirrors the self-enabling OAuth pattern used by Private Boolean Manager 1.52.
// Only Hub #1 can be changed: RMUtils operates on Rule Machine on this hub.
// ─────────────────────────────────────────────────────────────────────────────

private String getAppTypeId() {
    String typeId = null
    try {
        httpGet([uri: PB_RM_BASE_URL, path: "/hub2/userAppTypes", timeout: 15]) { resp ->
            List apps = resp.data instanceof List ? (List) resp.data : []
            Map match = apps.find { it.name == app.name }
            if (match) typeId = match.id?.toString()
        }
    } catch (Exception e) {
        if (enableLogging) log.debug "PB toggle OAuth: could not fetch user app types — ${e.message}"
    }
    return typeId
}

private boolean autoEnableOAuth() {
    String typeId = getAppTypeId()
    if (!typeId) {
        log.warn "PB toggle OAuth: could not determine app type ID — OAuth must be enabled manually in Apps Code"
        return false
    }

    String internalVer = null
    try {
        httpGet([uri: PB_RM_BASE_URL, path: "/app/ajax/code", query: [id: typeId], timeout: 15]) { resp ->
            internalVer = resp.data?.version?.toString()
        }
    } catch (Exception e) {
        log.warn "PB toggle OAuth: could not fetch app code version — ${e.message}"
        return false
    }
    if (!internalVer) {
        log.warn "PB toggle OAuth: app code version was null"
        return false
    }

    boolean success = false
    try {
        httpPost([
            uri                : PB_RM_BASE_URL,
            path               : "/app/edit/update",
            requestContentType : "application/x-www-form-urlencoded",
            body               : [id: typeId, version: internalVer, oauthEnabled: "true", _action_update: "Update"],
            timeout            : 20
        ]) { resp ->
            success = true
        }
        if (success) log.info "PB toggle OAuth enabled on app code (typeId: ${typeId})"
    } catch (Exception e) {
        log.warn "PB toggle OAuth: auto-enable failed — ${e.message}"
    }
    return success
}

boolean checkOAuth() {
    if (state.accessToken) return true
    try {
        createAccessToken()
        if (state.accessToken) {
            log.info "Device State Monitor: OAuth token created for PB State clicks"
            return true
        }
    } catch (Exception e) {
        if (enableLogging) log.debug "PB toggle OAuth not yet enabled — attempting auto-enable"
        if (autoEnableOAuth()) {
            try {
                createAccessToken()
                if (state.accessToken) {
                    log.info "Device State Monitor: OAuth auto-enabled and token created for PB State clicks"
                    return true
                }
            } catch (Exception e2) {
                log.warn "PB toggle OAuth enabled but token creation failed — ${e2.message}"
            }
        }
    }
    return false
}

private def renderDsmJson(Map m) {
    return render(contentType: "application/json", data: groovy.json.JsonOutput.toJson(m))
}

private List<Map> getCachedPbFalseRows() {
    String rowsJson = pbFinalizedResults?.rowsJson?.toString() ?: state.pbFalseRowsJson?.toString() ?: "[]"
    try {
        Object parsed = new groovy.json.JsonSlurper().parseText(rowsJson)
        if (parsed instanceof List) return (parsed as List).collect { it as Map }
    } catch (Exception e) {
        log.warn "PB FALSE/UNKNOWN table: cached row JSON could not be read — ${e.message}"
    }
    return []
}

// Update a cached row's PB state in place (the row is retained until the next
// scan rebuilds the cache) and recompute the live FALSE count.
private int updateCachedPbFalseRow(int hubNum, String ruleId, boolean newVal) {
    String targetKey = pbRuleKey(hubNum, ruleId)
    List<Map> rows = getCachedPbFalseRows().collect { Map r ->
        if (pbRuleKey(r.hubNum, r.id) == targetKey) {
            Map copy = new LinkedHashMap(r)
            copy.privateBool = newVal
            return copy
        }
        return r
    }
    int remainingFalse = rows.count { Map r -> r.privateBool == false } as int
    String rowsJson = groovy.json.JsonOutput.toJson(rows)

    Map updated = pbFinalizedResults != null
        ? new LinkedHashMap(pbFinalizedResults)
        : [rowsJson         : rowsJson,
           scannedCount     : state.pbFalseScannedCount ?: 0,
           falseCount       : remainingFalse,
           unknownCount     : state.pbFalseUnknownCount ?: 0,
           hubCount         : state.pbFalseHubCount ?: 0,
           lastScan         : state.pbFalseLastScan,
           scanDuration     : state.pbFalseScanDuration ?: "00:00",
           discoveryWarnings: state.pbFalseDiscoveryWarnings ?: [],
           lastError        : state.pbFalseLastError]

    updated.rowsJson   = rowsJson
    updated.falseCount = remainingFalse
    pbFinalizedResults = updated

    state.pbFalseRowsJson = rowsJson
    state.pbFalseCount    = remainingFalse
    return remainingFalse
}

def handleSetPbEndpoint() {
    String ruleId    = params?.id?.toString()
    String hubNumStr = params?.hubNum?.toString() ?: "1"
    String valueStr  = params?.value?.toString() ?: "true"

    if (!ruleId || !(ruleId ==~ /\d+/)) {
        return renderDsmJson([status: "error", message: "Invalid or missing rule ID"])
    }
    if (!(hubNumStr in ["1", "2", "3"])) {
        return renderDsmJson([status: "error", message: "Invalid hub number"])
    }
    if (!(valueStr in ["true", "false"])) {
        return renderDsmJson([status: "error", message: "Invalid value parameter — must be 'true' or 'false'"])
    }
    int hubNum      = hubNumStr as int
    boolean newVal  = (valueStr == "true")
    String hubLabel = (hubNum == 1)
        ? (settings["hub1Label"] ?: (location.name ?: "Hub 1"))
        : (settings["hub${hubNum}Label"] ?: "Hub ${hubNum}")

    if (hubNum != 1 && !remotePbmConfigured(hubNum)) {
        return renderDsmJson([status: "error", message: "${hubLabel}: remote PB changes require that hub to be enabled and its Private Boolean Manager app ID and access token entered in the PB table settings"])
    }
    if (pbCurrentScanId != null) {
        return renderDsmJson([status: "error", message: "PB scan is in progress; wait for the scan to finish before changing PB state"])
    }
    if (pbToggleRuleId != null) {
        return renderDsmJson([status: "error", message: "Another PB state change is already in progress"])
    }

    // Setting TRUE requires a cached FALSE row; setting FALSE requires a
    // cached TRUE row, which can only exist because this table set it TRUE.
    // Undo is therefore limited to the user's own toggles from this table.
    boolean expectedCurrent = !newVal
    boolean isListed = getCachedPbFalseRows().any { Map r ->
        ((r.hubNum ?: 1) as Integer) == hubNum && r.id?.toString() == ruleId && r.privateBool == expectedCurrent
    }
    if (!isListed) {
        return renderDsmJson([status: "error", message: "Rule is not cached as ${expectedCurrent ? 'FALSE' : 'TRUE'} for ${hubLabel}; run a PB scan and try again"])
    }

    pbToggleRuleId = pbRuleKey(hubNum, ruleId)
    try {
        if (hubNum == 1) {
            RMUtils.sendAction([ruleId as Long], newVal ? "setRuleBooleanTrue" : "setRuleBooleanFalse", app.label, PB_RM_VERSION)
        } else {
            setRemotePb(hubNum, hubLabel, ruleId, newVal)   // throws on any failure
        }
        int remaining = updateCachedPbFalseRow(hubNum, ruleId, newVal)
        log.info "PB State click: ${hubLabel} rule ${ruleId} Private Boolean set ${newVal ? 'TRUE' : 'FALSE'}; ${remaining} FALSE rule(s) now cached (row retained until the next PB scan)"
        return renderDsmJson([status: "success", value: newVal, remainingFalse: remaining])
    } catch (Exception e) {
        log.warn "PB State click failed for ${hubLabel} rule ${ruleId} (set ${newVal ? 'TRUE' : 'FALSE'}) — ${e.message}"
        return renderDsmJson([status: "error", message: e.message ?: "Unknown error"])
    } finally {
        pbToggleRuleId = null
    }
}

// True when a remote hub can accept relayed PB changes: the hub is enabled,
// has an IP, and its Private Boolean Manager app ID + access token are set.
private boolean remotePbmConfigured(int hubNum) {
    if (!(hubNum in [2, 3])) return false
    if (!settings["hub${hubNum}Enabled"]) return false
    if (!safeString(settings["hub${hubNum}Ip"]).trim()) return false
    if (settings["hub${hubNum}PbmAppId"] == null) return false
    if (!safeString(settings["hub${hubNum}PbmToken"]).trim()) return false
    return true
}

// Server-side relay from Hub #1 to Private Boolean Manager's /setPB OAuth
// endpoint on a remote hub. PBM validates the parameters, invokes RMUtils
// locally on that hub, and updates its own cached PB states. Throws on any
// transport, HTTP, or PBM-reported failure so the caller returns a real error
// to the browser instead of updating a table row that did not actually change.
private void setRemotePb(int hubNum, String hubLabel, String ruleId, boolean newVal) {
    String ip    = safeString(settings["hub${hubNum}Ip"]).trim()
    String appId = "${settings["hub${hubNum}PbmAppId"] as Long}"
    String token = safeString(settings["hub${hubNum}PbmToken"]).trim()
    String uri   = "http://${ip}/apps/api/${appId}/setPB" +
                   "?id=${ruleId}&value=${newVal}&access_token=${URLEncoder.encode(token, 'UTF-8')}"

    Map result = null
    httpGet([uri: uri, contentType: "application/json", timeout: 15]) { resp ->
        int httpStatus = resp.status as int
        if (httpStatus != 200) {
            throw new Exception("${hubLabel} PBM endpoint returned HTTP ${httpStatus}")
        }
        def d = resp.data
        if (d instanceof Map) {
            result = d as Map
        } else if (d != null) {
            try {
                Object parsed = new groovy.json.JsonSlurper().parseText(d.toString())
                if (parsed instanceof Map) result = parsed as Map
            } catch (Exception ignore) { }
        }
    }

    if (result == null) {
        throw new Exception("${hubLabel} PBM endpoint returned an unreadable response (check the app ID and access token)")
    }
    if (result.status?.toString() != "success") {
        throw new Exception("${hubLabel} PBM reported: ${result.message ?: result.status ?: 'unknown error'}")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// UI HELPERS
// ─────────────────────────────────────────────────────────────────────────────

private void renderRemoteDeviceSelectors(int hubNum, def allDevices, List onSel, List offSel) {
    if (allDevices == null) {
        paragraph("<i>Choose <b>Load / Reload Device List</b> from the Actions dropdown above to fetch devices.</i>")
        return
    }
    if (allDevices.size() == 0) {
        paragraph("<span style='color:red;'>No switch devices returned by Hub #${hubNum} Maker API. " +
                  "Check that devices are selected in the Maker API app on Hub #${hubNum}.</span>")
        return
    }
    def total = allDevices.size()
    paragraph("<hr><b>Switch Device Selection — Hub #${hubNum}</b> &nbsp;" +
              "<small>(${onSel.size()} ON / ${offSel.size()} OFF selected of ${total} switch devices available)</small>")
    input "hub${hubNum}Filter", "text", title: "Filter by name or room", submitOnChange: true, required: false
    def opts      = buildRemoteDeviceOptions(hubNum)
    def filterNote = settings["hub${hubNum}Filter"] ? " — filtered" : ""
    paragraph("<b>Monitor for ON state</b> <small>(flagged when on)</small>")
    if (opts) {
        input "hub${hubNum}SelectedOnDevices", "enum",
            title: "ON-monitored devices (${opts.size()} available${filterNote})",
            options: opts, multiple: true, required: false, submitOnChange: true
    }
    paragraph("<b>Monitor for OFF state</b> <small>(flagged when off)</small>")
    if (opts) {
        input "hub${hubNum}SelectedOffDevices", "enum",
            title: "OFF-monitored devices (${opts.size()} available${filterNote})",
            options: opts, multiple: true, required: false, submitOnChange: true
    }
}

private void renderRemoteHealthDeviceSelector(int hubNum, def allDevices, List healthSel) {
    if (allDevices == null) {
        paragraph("<i><b>Health / Activity monitoring:</b> Device list not yet loaded. " +
                  "Choose <b>Load / Reload Device List</b> above to also populate the health/activity selector " +
                  "(all Maker API devices are included, not just switch-capable ones).</i>")
        return
    }
    def total = allDevices.size()
    paragraph("<hr><b>Devices to monitor for health / activity — Hub #${hubNum}</b> &nbsp;" +
              "<small>(${healthSel.size()} selected of ${total} available — includes all device types)</small>")
    paragraph("<small><i>Flagged when OFFLINE, INACTIVE, NOT PRESENT, DISCONNECTED, or last activity exceeds threshold.</i></small>")
    def opts = buildRemoteHealthDeviceOptions(hubNum)
    def filterNote = settings["hub${hubNum}Filter"] ? " — filtered" : ""
    if (opts != null && opts.size() > 0) {
        input "hub${hubNum}SelectedHealthDevices", "enum",
            title: "Health/activity-monitored devices (${opts.size()} available${filterNote})",
            options: opts, multiple: true, required: false, submitOnChange: true
    } else if (opts != null) {
        paragraph("<span style='color:red;'>No devices available for health monitoring on Hub #${hubNum}.</span>")
    }
}

// PB FALSE scanning/display is controlled independently for each hub.
private boolean showPbFalseForHub(int hubNum) {
    return settings["showPbFalseHub${hubNum}"] != false
}

// One-time upgrade migration from the pre-1.65 single PB table control: any
// per-hub PB toggle that was never saved inherits the legacy value, then the
// legacy setting is removed. Idempotent — a no-op once the legacy key is gone.
private void migratePbLegacySettings() {
    def legacy = settings["showPbFalseTable"]
    if (legacy == null) return
    boolean legacyShown = legacy != false
    [1, 2, 3].each { int hubNum ->
        if (settings["showPbFalseHub${hubNum}"] == null) {
            app.updateSetting("showPbFalseHub${hubNum}", [type: "bool", value: legacyShown])
        }
    }
    app.removeSetting("showPbFalseTable")
    log.info "Migrated legacy showPbFalseTable=${legacyShown} to the per-hub PB controls"
}

// One-time upgrade migration for 1.78: the pre-1.78 hidden Hub #1 health list
// (hub1SelectedHealthDevices) is retired. The capability picker (hub1HealthDevs)
// is now the single source of truth for picker-visible devices, and picker-
// invisible devices are entered explicitly in hub1ExtraHealthIds. Removing the
// stale setting clears any devices that "Select All" had dumped into it and that
// unchecking in the picker could never remove. Only the Hub #1 key is touched;
// the remote hubs' hub2/hub3 SelectedHealthDevices settings are unaffected.
// Idempotent — a no-op once the key is gone.
private void migrateHiddenHealthList() {
    def legacy = settings["hub1SelectedHealthDevices"]
    if (legacy == null) return
    int n = normalizeSelectionList(legacy).size()
    app.removeSetting("hub1SelectedHealthDevices")
    log.info "Retired legacy hub1SelectedHealthDevices (had ${n} ID(s)). The Hub #1 " +
             "capability picker is now authoritative; if any of those were devices that " +
             "do not appear in the picker, re-add them by ID in 'Additional health-monitor " +
             "device IDs — Hub #1'."
}

private boolean anyPbFalseHubShown() {
    return [1, 2, 3].any { int hubNum -> showPbFalseForHub(hubNum) }
}

private Set<Integer> shownPbFalseHubNums() {
    return [1, 2, 3].findAll { int hubNum -> showPbFalseForHub(hubNum) } as Set
}

private List normalizeSelectionList(def raw) {
    if (raw instanceof List)       return raw*.toString()
    if (raw instanceof Collection) return raw.collect { it.toString() }
    return raw ? [raw.toString()] : []
}

// ─────────────────────────────────────────────────────────────────────────────
// MAKER API "DON'T FORGET NEW DEVICES" REMINDER
// A device that exists on a hub but was never checked/exposed in that hub's
// Maker API app will NOT show up here, even after Load/Reload — this app can
// only see what Maker API reports. This helper renders a visible reminder
// plus a clickable button that opens the Maker API config page on the target
// hub directly, so adding a newly-installed device is a one-click trip.
// ─────────────────────────────────────────────────────────────────────────────

private String makerApiOpenUrl(String ip, String appId) {
    if (!ip || !appId) return null
    return "http://${ip}/installedapp/configure/${appId}"
}

private void makerApiReminder(String hubLabel, String ip, String appId) {
    def url = makerApiOpenUrl(ip, appId)
    paragraph("<div style='border:1px solid #e0a800;background:#fff8e1;border-radius:6px;padding:8px 10px;margin:6px 0;'>" +
              "<b>⚠️ Before you Load/Reload:</b> a newly added device on <b>${hubLabel}</b> will not appear " +
              "in any picker below until it has been checked/exposed in the <b>Maker API</b> app on that hub. " +
              "Reloading here only re-reads what Maker API already exposes — it cannot discover devices Maker API doesn't know about." +
              (url
                ? " <a href='${url}' target='_blank' " +
                  "style='display:inline-block;margin-top:6px;padding:4px 10px;" +
                  "background-color:#2a6fdb !important;color:#ffffff !important;border-radius:4px;" +
                  "text-decoration:none !important;font-weight:bold;border:1px solid #1a4f9e;'>" +
                  "🔧 <span style='color:#ffffff !important;'>Open Maker API on ${hubLabel}</span></a>"
                : " <i>(Enter the IP, App ID, and Token below to enable a direct link to Maker API on ${hubLabel}.)</i>") +
              "</div>")
}

private String buildSelSummary(int onCount, int offCount, int healthCount = 0, int lockCount = 0, int contactCount = 0) {
    if (onCount == 0 && offCount == 0 && healthCount == 0 && lockCount == 0 && contactCount == 0) return " — No devices selected"
    def parts = []
    if (onCount      > 0) parts << "${onCount} ON"
    if (offCount     > 0) parts << "${offCount} OFF"
    if (lockCount    > 0) parts << "${lockCount} Lock"
    if (contactCount > 0) parts << "${contactCount} Contact"
    if (healthCount  > 0) parts << "${healthCount} Health"
    return " — " + parts.join(" / ") + " monitored"
}

private Map buildRemoteDeviceOptions(int hubNum) {
    def stored     = state["hub${hubNum}Devices"]
    if (stored == null) return null
    def filterText = settings["hub${hubNum}Filter"]?.toLowerCase()?.trim()
    def filtered   = filterText
        ? stored.findAll { dev -> dev.name?.toLowerCase()?.contains(filterText) || dev.room?.toLowerCase()?.contains(filterText) }
        : stored
    if (!filtered) return [:]
    return filtered.sort { it.name }.collectEntries { dev ->
        def label = dev.name + (dev.room ? " (${dev.room})" : "")
        ["${dev.id}": label]
    }
}

private Map buildRemoteHealthDeviceOptions(int hubNum) {
    def stored     = state["hub${hubNum}AllDevices"]
    if (stored == null) return null
    def filterText = settings["hub${hubNum}Filter"]?.toLowerCase()?.trim()
    def filtered   = filterText
        ? stored.findAll { dev -> dev.name?.toLowerCase()?.contains(filterText) || dev.room?.toLowerCase()?.contains(filterText) }
        : stored
    if (!filtered) return [:]
    return filtered.sort { it.name }.collectEntries { dev ->
        def label = dev.name + (dev.room ? " (${dev.room})" : "")
        ["${dev.id}": label]
    }
}

private Map buildRemoteLockDeviceOptions(int hubNum) {
    def stored     = state["hub${hubNum}LockDevices"]
    if (stored == null) return null
    def filterText = settings["hub${hubNum}Filter"]?.toLowerCase()?.trim()
    def filtered   = filterText
        ? stored.findAll { dev -> dev.name?.toLowerCase()?.contains(filterText) || dev.room?.toLowerCase()?.contains(filterText) }
        : stored
    if (!filtered) return [:]
    return filtered.sort { it.name }.collectEntries { dev ->
        def label = dev.name + (dev.room ? " (${dev.room})" : "")
        ["${dev.id}": label]
    }
}

private Map buildRemoteContactDeviceOptions(int hubNum) {
    def stored     = state["hub${hubNum}ContactDevices"]
    if (stored == null) return null
    def filterText = settings["hub${hubNum}Filter"]?.toLowerCase()?.trim()
    def filtered   = filterText
        ? stored.findAll { dev -> dev.name?.toLowerCase()?.contains(filterText) || dev.room?.toLowerCase()?.contains(filterText) }
        : stored
    if (!filtered) return [:]
    return filtered.sort { it.name }.collectEntries { dev ->
        def label = dev.name + (dev.room ? " (${dev.room})" : "")
        ["${dev.id}": label]
    }
}

private void renderRemoteContactDeviceSelector(int hubNum, def contactDevices, List contactSel) {
    if (contactDevices == null) {
        paragraph("<i><b>Contact sensor monitoring:</b> Device list not yet loaded (or loaded by an app version " +
                  "before 1.56). Choose <b>Load / Reload Device List</b> above to populate the contact sensor selector.</i>")
        return
    }
    def total = contactDevices.size()
    paragraph("<hr><b>Devices to monitor for contact state — Hub #${hubNum}</b> &nbsp;" +
              "<small>(${contactSel.size()} selected of ${total} contact sensor${total == 1 ? '' : 's'} available)</small>")
    def opts = buildRemoteContactDeviceOptions(hubNum)
    def filterNote = settings["hub${hubNum}Filter"] ? " — filtered" : ""
    if (opts != null && opts.size() > 0) {
        input "hub${hubNum}SelectedContactDevices", "enum",
            title: "Contact-monitored devices (${opts.size()} available${filterNote})",
            options: opts, multiple: true, required: false, submitOnChange: true
    } else if (opts != null) {
        paragraph("<span style='color:red;'>No contact sensor devices found on Hub #${hubNum}. " +
                  "Ensure contact sensors are exposed in the Maker API app.</span>")
    }
}

private void renderRemoteLockDeviceSelector(int hubNum, def lockDevices, List lockSel) {
    if (lockDevices == null) {
        paragraph("<i><b>Lock monitoring:</b> Device list not yet loaded. " +
                  "Choose <b>Load / Reload Device List</b> above to populate the lock device selector.</i>")
        return
    }
    def total = lockDevices.size()
    paragraph("<hr><b>Devices to monitor for lock state — Hub #${hubNum}</b> &nbsp;" +
              "<small>(${lockSel.size()} selected of ${total} lock device${total == 1 ? '' : 's'} available)</small>")
    def opts = buildRemoteLockDeviceOptions(hubNum)
    def filterNote = settings["hub${hubNum}Filter"] ? " — filtered" : ""
    if (opts != null && opts.size() > 0) {
        input "hub${hubNum}SelectedLockDevices", "enum",
            title: "Lock-monitored devices (${opts.size()} available${filterNote})",
            options: opts, multiple: true, required: false, submitOnChange: true
    } else if (opts != null) {
        paragraph("<span style='color:red;'>No lock devices found on Hub #${hubNum}. " +
                  "Ensure lock devices are exposed in the Maker API app.</span>")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// LIFECYCLE
// ─────────────────────────────────────────────────────────────────────────────

def installed() {
    syncAppInstanceLabel()
    checkOAuth()
    initialize()
}

def updated() {
    unschedule()
    unsubscribe()
    syncAppInstanceLabel()
    checkOAuth()
    migratePbLegacySettings()
    migrateHiddenHealthList()          // retire pre-1.78 hub1SelectedHealthDevices
    state.remove("pbFalseScanTotal")   // write-only key from pre-1.72 scans
    initialize()
}

void initialize() {
    boolean pbScanWasActive = (pbCurrentScanId != null)

    unschedule()
    pbCurrentScanId              = null
    pbScanStartMs                = 0L
    pbScanRuleQueue              = null
    pbScanPartialResults         = null
    pbScanSequentialIdx          = 0
    pbScanSequentialAttempts     = [:]
    pbScanSequentialActiveKey    = null
    pbScanSequentialRequestToken = null
    pbScanSequentialStartedMs    = 0L
    pbScanDiscoveryWarnings      = null
    pbToggleRuleId               = null
    // pbFinalizedResults is deliberately NOT cleared: the last completed
    // scan's results remain valid across a settings save and keep the table
    // populated until the restarted scan finishes.

    if (pbScanWasActive) {
        state.pbFalseScanStatus = "<i>PB scan was cancelled because app settings/code were saved. Click Scan PB FALSE Rules to scan again.</i>"
    }

    // A half-finished "Cancel HSM Alerts" confirmation must never survive a
    // settings save / code update.
    state.remove("hsmCancelPending")

    subscribe(location, "hsmAlert", hsmAlertHandler)
    log.info "Device State Monitor Multi-Hub initialized"
}

def hsmAlertHandler(evt) {
    def v = evt.value?.toString()
    // HSM uses TWO distinct cancel values: "cancel" for intrusion/smoke/water
    // alerts and "cancelRuleAlerts" for custom-rule alerts. Honoring only
    // "cancel" (pre-1.56 behavior) left CUSTOM RULE alerts latched forever.
    if (v in ["cancel", "cancelRuleAlerts"]) {
        clearHsmActiveAlert()
        if (enableLogging) log.debug "HSM alert cancelled (${v}) — cleared active alert state"
    } else if (v in ["arming", "armingHome", "armingNight"]) {
        // Transient arming / failed-to-arm notices — not ongoing alerts.
        // Latching these would leave a stuck banner with no cancel event to clear it.
        if (enableLogging) log.debug "HSM arming notice (${v}) — not stored as an active alert"
    } else if (v) {
        state.hsmActiveAlert     = v
        state.hsmActiveAlertRule = (v == "rule") ? (evt.descriptionText ?: "").toString() : ""
        state.hsmActiveAlertAt   = new Date().format("yyyy-MM-dd hh:mm a", location.timeZone)
        if (enableLogging) log.debug "HSM alert received: ${v}${evt.descriptionText ? ' — ' + evt.descriptionText : ''}"
    }
}

private void clearHsmActiveAlert() {
    state.remove("hsmActiveAlert")
    state.remove("hsmActiveAlertRule")
    state.remove("hsmActiveAlertAt")
}

def appButtonHandler(btn) {
    if (btn == "btnResetAppLabel") {
        resetAppInstanceLabel()
        return
    }
    if (btn == "btnClearHsmAlert") {
        clearHsmActiveAlert()
        log.info "HSM alert display cleared manually"
        return
    }
    // Cancel HSM Alerts (1.79) — two-step: the first click only arms a
    // confirmation; nothing is sent to HSM until "Yes — Cancel…" is clicked.
    if (btn in ["btnCancelHsmHub1", "btnCancelHsmHub2", "btnCancelHsmHub3"]) {
        int hubNum = Integer.parseInt(btn.substring(btn.length() - 1))
        state.hsmCancelPending = [hub: hubNum, at: now()]
        if (enableLogging) log.debug "HSM cancel requested for ${hsmHubLabel(hubNum)} — awaiting confirmation"
        return
    }
    if (btn == "btnConfirmCancelHsm") {
        Map pending = activeHsmCancelPending()
        state.remove("hsmCancelPending")
        if (pending) {
            cancelHsmAlerts((pending.hub ?: 1) as int)
        } else {
            log.info "HSM cancel confirmation expired (>${HSM_CANCEL_CONFIRM_SECS}s) — nothing was sent"
        }
        return
    }
    if (btn == "btnAbortCancelHsm") {
        state.remove("hsmCancelPending")
        log.info "HSM cancel not confirmed — alerts left as they are"
        return
    }
    if (btn in ["btnScanPbFalseTop", "btnScanPbFalseBottom"]) {
        if (anyPbFalseHubShown()) startPbFalseScan()
        return
    }
    if (btn in ["refresh", "refresh2"]) {
        if (enableLogging) log.debug "Device/report refresh requested"
        return
    }
    if (enableLogging) log.debug "Button pressed: ${btn}"
}

// ─────────────────────────────────────────────────────────────────────────────
// PRIVATE BOOLEAN FALSE RULE SCAN
// ─────────────────────────────────────────────────────────────────────────────
//
// Discovery mirrors Private Boolean Manager 1.52, but this version generalizes
// the same /hub2/appsList + /installedapp/statusJson logic across the hubs selected
// by the three per-hub PB controls. Unselected hubs receive no PB discovery or
// status requests. Current PB state is read strictly one rule at a time across all
// selected hubs. An unreadable rule is retried sequentially up to three attempts;
// if it is still unreadable, it is published as UNKNOWN alongside the FALSE rows
// so known results remain usable.

// Copy the last finalized PB scan results from the pbFinalizedResults static
// into the current execution's state snapshot. Safe to call from any render:
// while a scan is active, the transient "scan in progress" status message is
// left alone; everything else (rows, counts, timestamps, warnings) reflects
// the newest completed scan. Idempotent, so calling it on every render is a
// cheap self-heal against whole-map state overwrites by slow executions.
private void syncPbFinalizedResults() {
    Map fin = pbFinalizedResults
    if (fin == null) return

    state.pbFalseRowsJson     = fin.rowsJson
    state.pbFalseScannedCount = fin.scannedCount
    state.pbFalseCount        = fin.falseCount
    state.pbFalseUnknownCount = fin.unknownCount
    state.pbFalseHubCount     = fin.hubCount
    state.pbFalseLastScan     = fin.lastScan
    state.pbFalseScanDuration = fin.scanDuration

    // While a scan is running, its own status message / discovery warnings /
    // cleared lastError (set by startPbFalseScan) must not be overwritten
    // with the previous scan's values.
    if (pbCurrentScanId == null) {
        state.pbFalseDiscoveryWarnings = fin.discoveryWarnings ?: []
        state.pbFalseLastError         = fin.lastError
        // Only remove a stale "in progress" artifact; leave deliberate
        // messages (e.g. "scan was cancelled because settings were saved")
        // in place until the next scan replaces them.
        if (state.pbFalseScanStatus?.toString()?.contains("scan in progress")) {
            state.pbFalseScanStatus = null
        }
    }
}

void startPbFalseScan() {
    if (pbCurrentScanId != null) {
        if (enableLogging) log.debug "PB FALSE scan already active (${pbCurrentScanId}); duplicate start ignored"
        return
    }
    if (pbToggleRuleId != null) {
        if (enableLogging) log.debug "PB FALSE scan start ignored while PB rule ${pbToggleRuleId} is being changed"
        return
    }

    seedPbFinalizedResultsFromState()

    state.pbFalseLastError = null
    state.pbFalseScanStatus = state.pbFalseRowsJson
        ? "<i>PB scan in progress… showing the previous completed results until this scan finishes.</i>"
        : "<i>PB scan in progress…</i>"

    unschedule("pbFalseScanTimeout")
    unschedule("pbFalseSequentialStep")
    unschedule("pbFalseSequentialWatchdog")
    runIn(PB_SCAN_TIMEOUT_SECS, "pbFalseScanTimeout")

    List<String> discoveryWarnings = []
    List<Map> ruleApps = getPbRuleAppsAcrossHubs(discoveryWarnings)
    state.pbFalseDiscoveryWarnings = discoveryWarnings
    pbScanDiscoveryWarnings = discoveryWarnings

    // Discovery failure is different from an identified rule whose status cannot
    // be read: if a selected hub's Apps list is unavailable, rules may never have
    // been enumerated at all. Preserve the previous completed table in that case.
    if (discoveryWarnings) {
        unschedule("pbFalseScanTimeout")
        String msg = "PB scan incomplete during rule discovery: ${discoveryWarnings.join(' | ')} Fresh results were NOT published; the prior cached table is still shown."
        log.error "PB FALSE scan NOT started — selected-hub discovery was incomplete"
        preservePbCompletedResultsWithError(msg, discoveryWarnings)
        clearPbScanTransient()
        return
    }

    if (ruleApps.isEmpty()) {
        unschedule("pbFalseScanTimeout")
        publishPbFinalizedResults([
            rowsJson         : groovy.json.JsonOutput.toJson([]),
            scannedCount     : 0,
            falseCount       : 0,
            unknownCount     : 0,
            hubCount         : 0,
            lastScan         : new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone),
            scanDuration     : "00:00",
            discoveryWarnings: discoveryWarnings,
            lastError        : null
        ])
        return
    }

    Long nowMs = now() as Long
    String scanId = "${app.id}-${nowMs}"
    List<Map> queue = ruleApps.collect { Map r ->
        [id      : r.id?.toString(),
         name    : r.name?.toString() ?: "Unknown",
         appType : r.appType?.toString() ?: "RM",
         hub     : r.hub?.toString() ?: "Hub",
         hubNum  : (r.hubNum ?: 1) as Integer,
         baseUrl : r.baseUrl?.toString(),
         linkUrl : r.linkUrl?.toString()]
    }

    pbCurrentScanId              = scanId
    pbScanStartMs                = nowMs
    pbScanRuleQueue              = queue
    pbScanPartialResults         = [:]
    pbScanSequentialIdx          = 0
    pbScanSequentialAttempts     = [:]
    pbScanSequentialActiveKey    = null
    pbScanSequentialRequestToken = null
    pbScanSequentialStartedMs    = 0L

    log.info "PB FALSE scan started — ${queue.size()} RM/BC rules across ${queue*.hubNum.unique().size()} hub(s); sequential one-at-a-time status reads, max ${PB_STATUS_READ_MAX_ATTEMPTS} attempt(s) per unreadable rule"

    pbFalseSequentialStep()
}

// Reads exactly one rule-status endpoint at a time across the full selected-hub
// queue. A known TRUE or FALSE result advances immediately to the next rule.
// UNKNOWN/unreadable results retry the SAME rule sequentially up to the configured
// attempt limit, then publish that identified rule as UNKNOWN and continue.
void pbFalseSequentialStep() {
    String scanId = pbCurrentScanId
    if (scanId == null) return
    if (pbScanSequentialActiveKey != null) return

    unschedule("pbFalseSequentialStep")

    int idx   = pbScanSequentialIdx ?: 0
    int total = pbScanRuleQueue?.size() ?: 0

    if (idx >= total) {
        finishPbSequentialScan()
        return
    }

    Map rule = pbScanRuleQueue[idx] as Map
    String key = pbRuleKey(rule.hubNum, rule.id)
    int attempt = ((pbScanSequentialAttempts?.get(key) ?: 0) as int) + 1
    String requestToken = "${scanId}:${key}:${attempt}:${now()}"

    pbScanSequentialActiveKey    = key
    pbScanSequentialRequestToken = requestToken
    pbScanSequentialStartedMs    = now() as Long

    if (enableLogging) {
        log.debug "PB FALSE sequential read ${idx + 1}/${total}: ${rule.hub} rule ${rule.id} (${rule.name}), attempt ${attempt}/${PB_STATUS_READ_MAX_ATTEMPTS}"
    }

    try {
        asynchttpGet("handlePbFalseSequentialResponse",
            [uri: "${rule.baseUrl}/installedapp/statusJson/${rule.id}", timeout: PB_STATUS_REQUEST_TIMEOUT_SECS],
            [scanId      : scanId,
             requestToken: requestToken,
             attempt     : attempt,
             ruleKey     : key,
             ruleId      : rule.id,
             ruleName    : rule.name,
             appType     : rule.appType,
             hub         : rule.hub,
             hubNum      : rule.hubNum,
             linkUrl     : rule.linkUrl]
        )
        unschedule("pbFalseSequentialWatchdog")
        runIn(PB_STATUS_WATCHDOG_INTERVAL_SECS, "pbFalseSequentialWatchdog")
    } catch (Exception e) {
        log.warn "PB FALSE scan: could not start sequential statusJson for ${rule.hub} rule ${rule.id} (${rule.name}), attempt ${attempt}/${PB_STATUS_READ_MAX_ATTEMPTS} — ${e.message}"
        clearPbSequentialActiveRequest()
        handlePbSequentialUnknown(rule, attempt, "request could not be started")
    }
}

void pbFalseSequentialWatchdog() {
    if (pbCurrentScanId == null) return
    if (pbScanSequentialActiveKey == null) return

    Long startedMs = (pbScanSequentialStartedMs ?: 0L) as Long
    Long ageMs = (now() as Long) - startedMs
    if (startedMs && ageMs > (PB_STATUS_REQUEST_TIMEOUT_SECS * 1000L)) {
        int idx = pbScanSequentialIdx ?: 0
        Map rule = (pbScanRuleQueue && idx < pbScanRuleQueue.size())
            ? (pbScanRuleQueue[idx] as Map)
            : [:]
        String key = pbRuleKey(rule.hubNum, rule.id)
        int attempt = ((pbScanSequentialAttempts?.get(key) ?: 0) as int) + 1
        log.warn "PB FALSE scan: sequential statusJson timeout for ${rule.hub ?: 'Hub'} rule ${rule.id ?: ''} (${rule.name ?: ''}), attempt ${attempt}/${PB_STATUS_READ_MAX_ATTEMPTS}"
        clearPbSequentialActiveRequest()
        handlePbSequentialUnknown(rule, attempt, "statusJson timeout")
        return
    }

    runIn(PB_STATUS_WATCHDOG_INTERVAL_SECS, "pbFalseSequentialWatchdog")
}

void handlePbFalseSequentialResponse(resp, data) {
    String scanId = data.scanId?.toString()
    if (pbCurrentScanId != scanId) return

    String requestToken = data.requestToken?.toString()
    if (!requestToken || requestToken != pbScanSequentialRequestToken) {
        if (enableLogging) log.debug "PB FALSE scan: late/stale sequential callback ignored for ${data.hub} rule ${data.ruleId} (${data.ruleName})"
        return
    }

    unschedule("pbFalseSequentialWatchdog")

    String ruleKey = data.ruleKey?.toString() ?: pbRuleKey(data.hubNum, data.ruleId)
    int attempt = (data.attempt ?: 1) as int
    Map rule = [id: data.ruleId, name: data.ruleName, appType: data.appType,
                hub: data.hub, hubNum: data.hubNum, linkUrl: data.linkUrl]
    Map resultRow = buildPbScanResultFromResponse(resp, data)

    clearPbSequentialActiveRequest()

    if (resultRow.privateBool == null) {
        handlePbSequentialUnknown(rule, attempt, "unreadable/malformed statusJson")
        return
    }

    pbScanPartialResults[ruleKey] = resultRow
    pbScanSequentialAttempts.remove(ruleKey)
    pbScanSequentialIdx = (pbScanSequentialIdx ?: 0) + 1
    pbFalseSequentialStep()
}

private void handlePbSequentialUnknown(Map rule, int attempt, String reason) {
    if (pbCurrentScanId == null) return

    String key = pbRuleKey(rule.hubNum, rule.id)
    pbScanSequentialAttempts[key] = attempt

    if (attempt < PB_STATUS_READ_MAX_ATTEMPTS) {
        log.warn "PB FALSE scan: ${rule.hub} rule ${rule.id} (${rule.name}) unreadable after sequential attempt ${attempt}/${PB_STATUS_READ_MAX_ATTEMPTS} (${reason}); retrying the same rule"
        runIn(1, "pbFalseSequentialStep")
        return
    }

    log.warn "PB FALSE scan: ${rule.hub} rule ${rule.id} (${rule.name}) remained unreadable after ${PB_STATUS_READ_MAX_ATTEMPTS} sequential attempt(s) — marking UNKNOWN in the published table"
    pbScanPartialResults[key] = buildPbScanResultRow(rule, null, "")
    pbScanSequentialAttempts.remove(key)
    pbScanSequentialIdx = (pbScanSequentialIdx ?: 0) + 1
    pbFalseSequentialStep()
}

private Map buildPbScanResultFromResponse(resp, data) {
    String ruleId = data.ruleId?.toString()
    Map status = [:]
    boolean statusOk = false

    try {
        int httpStatus = resp.getStatus() as int
        if (httpStatus == 200) {
            Object raw = resp.getData()
            if (raw instanceof Map) {
                status = raw as Map
                statusOk = isPbStatusPayload(status)
            } else if (raw != null) {
                String rawText = raw.toString()
                String trimmed = rawText.trim()
                if (trimmed.startsWith("{")) {
                    Object parsed = new groovy.json.JsonSlurper().parseText(rawText)
                    if (parsed instanceof Map) {
                        status = parsed as Map
                        statusOk = isPbStatusPayload(status)
                    }
                } else if (looksLikeLoginPage(rawText)) {
                    log.warn "PB FALSE scan: ${data.hub} returned a login page for rule ${ruleId} (${data.ruleName})"
                } else {
                    log.warn "PB FALSE scan: non-JSON statusJson payload for ${data.hub} rule ${ruleId} (${data.ruleName})"
                }
            }
            if (!statusOk && status) {
                log.warn "PB FALSE scan: malformed statusJson payload for ${data.hub} rule ${ruleId} (${data.ruleName}) — appState collection missing"
            }
        } else {
            log.warn "PB FALSE scan: HTTP ${httpStatus} for ${data.hub} rule ${ruleId} (${data.ruleName})"
        }
    } catch (Exception e) {
        log.warn "PB FALSE scan: could not parse statusJson for ${data.hub} rule ${ruleId} (${data.ruleName}) — ${e.message}"
    }

    Boolean privateBool = extractPbPrivateBool(status, statusOk)
    Map rule = [id: ruleId, name: data.ruleName, appType: data.appType,
                hub: data.hub, hubNum: data.hubNum, linkUrl: data.linkUrl]
    Map resultRow = buildPbScanResultRow(rule, privateBool, extractPbLastRun(status))

    if (enableLogging) {
        log.debug "PB FALSE scan: ${data.hub} ${data.ruleName} (${ruleId}, ${data.appType}) PrivateBool=${privateBool}"
    }
    return resultRow
}

private void clearPbSequentialActiveRequest() {
    unschedule("pbFalseSequentialWatchdog")
    pbScanSequentialActiveKey    = null
    pbScanSequentialRequestToken = null
    pbScanSequentialStartedMs    = 0L
}

private void finishPbSequentialScan() {
    if (pbCurrentScanId == null) return

    List<Map> queued = pbScanRuleQueue ?: []
    Map partial = pbScanPartialResults ?: [:]
    List<Map> unknownRules = queued.findAll { Map rule ->
        Map row = partial[pbRuleKey(rule.hubNum, rule.id)] as Map
        return row == null || row.privateBool == null
    }

    if (unknownRules) {
        String examples = unknownRules.take(3).collect { Map r -> "${r.hub} rule ${r.id} (${r.name})" }.join("; ")
        String more = unknownRules.size() > 3 ? "; plus ${unknownRules.size() - 3} more" : ""
        log.warn "PB FALSE scan: ${unknownRules.size()} rule(s) remain UNKNOWN after sequential verification and will be published for manual inspection — ${examples}${more}"
    }

    finalizePbFalseScan()
}

private Map buildPbScanResultRow(Map rule, Boolean privateBool, String lastRun) {
    return [
        id         : rule.id?.toString(),
        name       : rule.name?.toString() ?: "Unknown",
        appType    : rule.appType?.toString() ?: "RM",
        hub        : rule.hub?.toString() ?: "Hub",
        hubNum     : (rule.hubNum ?: 1) as Integer,
        linkUrl    : rule.linkUrl?.toString() ?: "",
        lastRun    : lastRun ?: "",
        privateBool: privateBool
    ]
}

void pbFalseScanTimeout() {
    if (pbCurrentScanId != null) {
        int completed = pbScanPartialResults?.size() ?: 0
        int total     = pbScanRuleQueue?.size() ?: 0
        String duration = formatPbScanDuration(((now() as Long) - (pbScanStartMs ?: (now() as Long))) as long)
        String msg = "PB scan timed out after ${duration} with ${completed}/${total} rule-status reads completed. Fresh results were NOT published; the prior cached table is still shown."
        log.error "PB FALSE scan watchdog timeout — ${msg}"
        preservePbCompletedResultsWithError(msg)
        clearPbScanTransient()
    }
}

void finalizePbFalseScan() {
    unschedule("pbFalseScanTimeout")
    unschedule("pbFalseSequentialStep")
    unschedule("pbFalseSequentialWatchdog")

    try {
        List<Map> queued = pbScanRuleQueue ?: []
        Map partial      = pbScanPartialResults ?: [:]

        List<Map> allRows = queued.collect { Map rule ->
            String key = pbRuleKey(rule.hubNum, rule.id)
            Map row = partial[key] as Map
            if (row) return row
            return [id: rule.id?.toString(), name: rule.name?.toString() ?: "Unknown",
                    appType: rule.appType?.toString() ?: "RM", hub: rule.hub?.toString() ?: "Hub",
                    hubNum: (rule.hubNum ?: 1) as Integer, linkUrl: rule.linkUrl?.toString() ?: "",
                    lastRun: "", privateBool: null]
        }

        int unknownCount = allRows.count { it.privateBool == null } as int
        Long endMs        = now() as Long
        Long startedMs    = (pbScanStartMs ?: endMs) as Long

        List<Map> falseRows   = allRows.findAll { it.privateBool == false }
        List<Map> reportRows  = allRows.findAll { it.privateBool == false || it.privateBool == null }
        int hubCount          = queued*.hubNum.unique().size()

        String rowsJson  = "[]"
        String lastError = null
        try {
            rowsJson = groovy.json.JsonOutput.toJson(reportRows)
        } catch (Exception e) {
            log.warn "PB FALSE scan: could not cache FALSE/UNKNOWN rows — ${e.message}"
            lastError = "PB scan completed, but the FALSE/UNKNOWN rule rows could not be cached: ${e.message}"
        }

        Map results = [
            rowsJson         : rowsJson,
            scannedCount     : allRows.size(),
            falseCount       : falseRows.size(),
            unknownCount     : unknownCount,
            hubCount         : hubCount,
            lastScan         : new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone),
            scanDuration     : formatPbScanDuration((endMs - startedMs) as long),
            discoveryWarnings: pbScanDiscoveryWarnings ?: [],
            lastError        : lastError
        ]

        publishPbFinalizedResults(results)

        log.info "PB FALSE scan complete in ${results.scanDuration}: ${falseRows.size()} FALSE of ${allRows.size()} RM/BC rules; ${unknownCount} UNKNOWN after retries"

    } catch (Exception e) {
        log.error "PB FALSE scan finalize failed — ${e.message}"
        preservePbCompletedResultsWithError("PB scan failed while finalizing results: ${e.message}. Fresh results were NOT published; the prior cached table is still shown.")
    } finally {
        clearPbScanTransient()
    }
}

// Single writer for completed-scan results. The static copy is authoritative
// and clobber-proof; the state mirror is best-effort and self-heals through
// syncPbFinalizedResults() on the next page render if a concurrent execution's
// whole-map state commit overwrites it.
private void publishPbFinalizedResults(Map results) {
    pbFinalizedResults = results

    state.pbFalseRowsJson          = results.rowsJson
    state.pbFalseScannedCount      = results.scannedCount
    state.pbFalseCount             = results.falseCount
    state.pbFalseUnknownCount      = results.unknownCount
    state.pbFalseHubCount          = results.hubCount
    state.pbFalseLastScan          = results.lastScan
    state.pbFalseScanDuration      = results.scanDuration
    state.pbFalseDiscoveryWarnings = results.discoveryWarnings
    state.pbFalseLastError         = results.lastError
    state.pbFalseScanStatus        = null
}

private void seedPbFinalizedResultsFromState() {
    if (pbFinalizedResults != null || state.pbFalseRowsJson == null) return
    pbFinalizedResults = [
        rowsJson         : state.pbFalseRowsJson?.toString() ?: "[]",
        scannedCount     : state.pbFalseScannedCount ?: 0,
        falseCount       : state.pbFalseCount ?: 0,
        unknownCount     : state.pbFalseUnknownCount ?: 0,
        hubCount         : state.pbFalseHubCount ?: 0,
        lastScan         : state.pbFalseLastScan,
        scanDuration     : state.pbFalseScanDuration ?: "00:00",
        discoveryWarnings: state.pbFalseDiscoveryWarnings ?: [],
        lastError        : state.pbFalseLastError
    ]
}

private void preservePbCompletedResultsWithError(String msg, List currentWarnings = null) {
    seedPbFinalizedResultsFromState()
    Map kept = pbFinalizedResults != null
        ? new LinkedHashMap(pbFinalizedResults)
        : [rowsJson         : state.pbFalseRowsJson,
           scannedCount     : state.pbFalseScannedCount ?: 0,
           falseCount       : state.pbFalseCount ?: 0,
           unknownCount     : state.pbFalseUnknownCount ?: 0,
           hubCount         : state.pbFalseHubCount ?: 0,
           lastScan         : state.pbFalseLastScan,
           scanDuration     : state.pbFalseScanDuration ?: "00:00",
           discoveryWarnings: state.pbFalseDiscoveryWarnings ?: [],
           lastError        : null]
    kept.lastError = msg
    if (currentWarnings != null) kept.discoveryWarnings = currentWarnings
    pbFinalizedResults = kept
    state.pbFalseLastError = msg
    if (currentWarnings != null) state.pbFalseDiscoveryWarnings = currentWarnings
    state.pbFalseScanStatus = null
}

private void clearPbScanTransient() {
    unschedule("pbFalseScanTimeout")
    unschedule("pbFalseSequentialStep")
    unschedule("pbFalseSequentialWatchdog")
    pbCurrentScanId              = null
    pbScanStartMs                = 0L
    pbScanRuleQueue              = null
    pbScanPartialResults         = null
    pbScanSequentialIdx          = 0
    pbScanSequentialAttempts     = [:]
    pbScanSequentialActiveKey    = null
    pbScanSequentialRequestToken = null
    pbScanSequentialStartedMs    = 0L
    pbScanDiscoveryWarnings      = null
}

private String pbRuleKey(def hubNum, def ruleId) {
    return "${hubNum ?: 1}:${ruleId ?: ''}"
}

private List<Map> getPbRuleAppsAcrossHubs(List<String> warnings) {
    List<Map> hubs = []

    // The per-hub PB controls are scan selectors as well as display selectors.
    // Do not even fetch /hub2/appsList for a hub whose PB control is off.
    if (showPbFalseForHub(1)) {
        hubs << [
            hubNum : 1,
            hub    : settings["hub1Label"] ?: (location.name ?: "Hub 1"),
            baseUrl: PB_RM_BASE_URL
        ]
    }

    [2, 3].each { int hubNum ->
        if (showPbFalseForHub(hubNum) && settings["hub${hubNum}Enabled"]) {
            String ip = safeString(settings["hub${hubNum}Ip"]).trim()
            String label = safeString(settings["hub${hubNum}Label"]).trim() ?: "Hub ${hubNum}"
            if (ip) {
                hubs << [hubNum: hubNum, hub: label, baseUrl: "http://${ip}"]
            } else {
                warnings << "${label}: PB scan skipped because no hub IP address is configured."
            }
        }
    }

    if (enableLogging) {
        String selected = hubs ? hubs.collect { it.hub?.toString() ?: "Hub ${it.hubNum}" }.join(", ") : "none"
        log.debug "PB FALSE scan hubs selected for discovery: ${selected}"
    }

    List<Map> rules = []
    hubs.each { Map hubSpec ->
        rules.addAll(getPbRuleAppsForHub(hubSpec, warnings))
    }

    return rules.sort { Map a, Map b ->
        int hubCmp = (a.hub?.toString()?.toLowerCase() ?: "") <=> (b.hub?.toString()?.toLowerCase() ?: "")
        return hubCmp != 0 ? hubCmp : ((a.name?.toString()?.toLowerCase() ?: "") <=> (b.name?.toString()?.toLowerCase() ?: ""))
    }
}

private List<Map> getPbRuleAppsForHub(Map hubSpec, List<String> warnings) {
    List<Map> rules = []
    Set<String> seenIds = [] as Set
    String hubLabel = hubSpec.hub?.toString() ?: "Hub"
    String baseUrl  = hubSpec.baseUrl?.toString()

    try {
        String body = fetchRawText("${baseUrl}/hub2/appsList", 15)
        if (!body) throw new Exception("empty response")
        String trimmed = body.trim()
        if (!trimmed.startsWith("{") && looksLikeLoginPage(body)) {
            warnings << "${hubLabel}: PB scan unavailable because Hub Login Security/login protection blocked the internal Apps page."
            return rules
        }

        Object parsed = new groovy.json.JsonSlurper().parseText(body)
        Map root = parsed instanceof Map ? (parsed as Map) : [:]
        def apps = root?.apps
        if (!(apps instanceof Collection)) throw new Exception("Apps list JSON did not contain an apps collection")

        apps.each { parentApp ->
            def pd = parentApp?.data
            String parentType  = pd?.type?.toString()  ?: ""
            String parentName  = pd?.name?.toString()  ?: ""
            String parentLabel = pd?.label?.toString() ?: ""
            String appType     = getPbSupportedAutomationAppType(parentType, parentName, parentLabel)

            if (appType) {
                parentApp?.children?.each { child ->
                    collectPbRmLeafRules(child, appType, hubSpec, rules, seenIds, 0)
                }
            }
        }
    } catch (Exception e) {
        warnings << "${hubLabel}: PB rule discovery failed — ${e.message}"
        log.warn "PB FALSE scan discovery failed for ${hubLabel} (${baseUrl}) — ${e.message}"
    }

    if (enableLogging) log.debug "PB FALSE scan: discovered ${rules.size()} RM/BC rules on ${hubLabel}"
    return rules
}

private void collectPbRmLeafRules(Object node, String parentAppType, Map hubSpec,
                                  List<Map> rules, Set<String> seenIds, int depth) {
    if (depth > 6) return
    List children = (node?.children ?: []) as List

    if (children.isEmpty()) {
        def d = node?.data
        if (d?.id && d?.name) {
            String id = d.id.toString()
            if (!seenIds.contains(id)) {
                String childType         = d?.type?.toString()    ?: ""
                String childAppName      = d?.appName?.toString() ?: ""
                String childDetectedType = getPbSupportedAutomationAppType(childType, childAppName)
                String finalAppType      = (parentAppType == "BC" || childDetectedType == "BC") ? "BC" : (childDetectedType ?: parentAppType)
                String baseUrl           = hubSpec.baseUrl?.toString()

                seenIds << id
                rules << [
                    id      : id,
                    name    : d.name.toString(),
                    appType : finalAppType,
                    hub     : hubSpec.hub?.toString() ?: "Hub",
                    hubNum  : (hubSpec.hubNum ?: 1) as Integer,
                    baseUrl : baseUrl,
                    // Browser links for Hub #1 must be relative: 127.0.0.1 would
                    // otherwise point at the user's PC rather than the hub.
                    linkUrl : ((hubSpec.hubNum ?: 1) as Integer) == 1
                        ? "/installedapp/configure/${id}"
                        : "${baseUrl}/installedapp/configure/${id}"
                ]
            }
        }
    } else {
        children.each { child -> collectPbRmLeafRules(child, parentAppType, hubSpec, rules, seenIds, depth + 1) }
    }
}

private String getPbSupportedAutomationAppType(String type, String name, String label = "") {
    String combined = [type, name, label].findAll { it }.join(" ").toLowerCase()
    if (!combined) return null

    // Basic Button Controller is not an RM-compatible child-rule type with the
    // Private Boolean status semantics used here.
    if (combined.contains("basic button controller") || combined.contains("basicbuttoncontroller")) return null
    if (combined.contains("button controller") || combined.contains("buttoncontroller")) return "BC"
    if (combined.contains("rule machine") || combined.contains("rulemachine")) return "RM"
    return null
}

private boolean isPbStatusPayload(Map status) {
    return status?.appState instanceof Collection
}

private Boolean extractPbPrivateBool(Map status, boolean knownRead = false) {
    for (Map item : (status?.appState ?: [])) {
        if (item?.name?.toString() == "private") {
            return asPbBooleanLoose(item?.value)
        }
    }
    // Successful status read + no private state means RM's default FALSE.
    // Failed/unknown read remains null so it can never be misreported as FALSE.
    return knownRead ? false : null
}

private Boolean asPbBooleanLoose(Object value) {
    if (value == null) return false
    if (value instanceof Boolean) return value as Boolean
    return value.toString().equalsIgnoreCase("true")
}

private String extractPbLastRun(Map status) {
    String lastEvtDate = ""
    String lastEvtTime = ""
    String timeFormat  = ""
    String dateFormat  = ""

    status?.appState?.each { item ->
        String n = item?.name?.toString() ?: ""
        if (n == "lastEvtDate") lastEvtDate = item?.value?.toString() ?: ""
        if (n == "lastEvtTime") lastEvtTime = item?.value?.toString() ?: ""
        if (n == "timeFormat")  timeFormat  = item?.value?.toString() ?: ""
        if (n == "dateFormat")  dateFormat  = item?.value?.toString() ?: ""
    }

    if (!lastEvtDate) return ""

    java.text.SimpleDateFormat outDateTimeFmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
    java.text.SimpleDateFormat outDateFmt     = new java.text.SimpleDateFormat("yyyy-MM-dd")
    java.text.SimpleDateFormat outTimeFmt     = new java.text.SimpleDateFormat("HH:mm")

    boolean hasTimeComponent = lastEvtDate.toUpperCase().contains("AM") ||
                               lastEvtDate.toUpperCase().contains("PM") ||
                               lastEvtDate.indexOf(":", 6) >= 0

    if (hasTimeComponent) {
        List<String> fullDateFmts = [
            "dd-MMM-yyyy hh:mm:ss a", "dd-MMM-yyyy HH:mm:ss",
            "dd-MMM-yyyy hh:mm a",    "dd-MMM-yyyy HH:mm",
            "MM/dd/yyyy hh:mm:ss a",  "MM/dd/yyyy HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",    "yyyy-MM-dd hh:mm:ss a"
        ]
        for (String fmt : fullDateFmts) {
            try { return outDateTimeFmt.format(new java.text.SimpleDateFormat(fmt).parse(lastEvtDate)) }
            catch (Exception ignored) {}
        }
        log.warn "extractPbLastRun: unrecognized full datetime '${lastEvtDate}'"
        return "* ${lastEvtDate}"
    }

    if (!lastEvtDate.matches(/\d{4}-\d{2}-\d{2}/)) {
        List<String> dateFmts = (dateFormat ? [dateFormat] : []) + ["dd-MMM-yyyy", "MM/dd/yyyy", "dd/MM/yyyy", "MMM dd, yyyy"]
        String normalizedDate = null
        for (String fmt : dateFmts) {
            try {
                normalizedDate = outDateFmt.format(new java.text.SimpleDateFormat(fmt).parse(lastEvtDate))
                break
            } catch (Exception ignored) {}
        }
        if (normalizedDate) lastEvtDate = normalizedDate
        else {
            log.warn "extractPbLastRun: unrecognized date format '${lastEvtDate}'"
            lastEvtDate = "* ${lastEvtDate}"
        }
    }

    if (!lastEvtTime) return lastEvtDate

    List<String> timeFmts = timeFormat ? [timeFormat] : []
    timeFmts += ["hh:mm:ss a", "h:mm:ss a", "HH:mm:ss", "hh:mm a", "h:mm a", "HH:mm", "h:mm"]
    for (String fmt : timeFmts) {
        try { return "${lastEvtDate} ${outTimeFmt.format(new java.text.SimpleDateFormat(fmt).parse(lastEvtTime))}" }
        catch (Exception ignored) {}
    }

    log.warn "extractPbLastRun: could not parse time '${lastEvtTime}' (timeFormat='${timeFormat}')"
    return "* ${lastEvtDate} ${lastEvtTime}"
}

private String formatPbScanDuration(long elapsedMs) {
    // NOTE: Groovy's '/' on two longs yields a BigDecimal, and
    // Math.max(Long, BigDecimal) is an ambiguous overload that throws a
    // GroovyRuntimeException at runtime (this crashed finalizePbFalseScan()
    // at the end of every scan in 1.60/1.61). intdiv() keeps it all long math.
    long totalSecs = Math.max(0L, elapsedMs).intdiv(1000L)
    long mins      = totalSecs.intdiv(60L)
    long secs      = totalSecs % 60L
    return String.format("%02d:%02d", mins, secs)
}

// ─────────────────────────────────────────────────────────────────────────────
// REMOTE DEVICE LIST LOADER
// ─────────────────────────────────────────────────────────────────────────────

private void loadRemoteDeviceList(int hubNum, String ip, String appId, String token) {
    def hubLabel = settings["hub${hubNum}Label"] ?: "Hub ${hubNum}"
    if (!ip || !appId || !token) {
        state["hub${hubNum}Devices"]    = []
        state["hub${hubNum}AllDevices"] = []
        state["hub${hubNum}LoadStatus"] = "Error: missing IP, app ID, or token"
        return
    }
    def uri        = "http://${ip}/apps/api/${appId}/devices?access_token=${token}"
    def switchList  = []
    def lockList    = []
    def contactList = []
    def allList     = []
    def disabledIds = []
    try {
        def respData = fetchJson(uri, 15)
        if (respData instanceof List && respData.isEmpty()) {
            log.warn "${hubLabel}: Maker API device list returned no usable devices (empty or all-null) — device pickers cannot be (re)loaded until that hub's Maker API is fixed"
        }
        respData?.each { dev ->
                if (enableLogging && switchList.isEmpty() && allList.isEmpty())
                    log.debug "${hubLabel}: first device raw = id:${dev.id} disabled:${dev.disabled} status:${dev.status} caps:${dev.capabilities}"
                def isDisabled = dev.disabled == true || dev.disabled?.toString() == "true" ||
                                 (dev.status ?: "").toString().toUpperCase() == "DISABLED"
                def devEntry = [
                    id  : dev.id?.toString(),
                    name: (dev.label ?: dev.name ?: "Unknown").toString(),
                    room: (dev.room ?: "").toString()
                ]
                if (isDisabled) {
                    disabledIds << dev.id?.toString()
                } else {
                    allList << devEntry
                    if (hasSwitchCapability(dev.capabilities))  switchList  << devEntry
                    if (hasLockCapability(dev.capabilities))    lockList    << devEntry
                    if (hasContactCapability(dev.capabilities)) contactList << devEntry
                }
        }
        state["hub${hubNum}Devices"]        = switchList
        state["hub${hubNum}LockDevices"]    = lockList
        state["hub${hubNum}ContactDevices"] = contactList
        state["hub${hubNum}AllDevices"]     = allList
        state["hub${hubNum}DisabledIds"]    = disabledIds
        state["hub${hubNum}LoadStatus"]  = "OK: ${switchList.size()} switch / ${lockList.size()} lock / ${contactList.size()} contact device${switchList.size() == 1 ? '' : 's'} loaded (${allList.size()} enabled, ${disabledIds.size()} disabled)"
        log.info "${hubLabel}: Loaded ${switchList.size()} switch, ${lockList.size()} lock, ${contactList.size()} contact, ${allList.size()} enabled, ${disabledIds.size()} disabled."
    } catch (Exception e) {
        log.error "${hubLabel}: Error loading device list — ${e.message}"
        state["hub${hubNum}Devices"]        = []
        state["hub${hubNum}LockDevices"]    = []
        state["hub${hubNum}ContactDevices"] = []
        state["hub${hubNum}AllDevices"]     = []
        state["hub${hubNum}DisabledIds"]    = []
        state["hub${hubNum}LoadStatus"]  = "Error: ${e.message}"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// HUB #1 ALL-DEVICE LOADER (for health/activity selector)
// Uses Hub #1 Maker API via localhost — mirrors loadRemoteDeviceList but stores
// only the full device list (not split by switch capability).
// ─────────────────────────────────────────────────────────────────────────────

private void loadHub1AllDevices() {
    def hubLabel = settings["hub1Label"] ?: (location.name ?: "Hub 1")
    def appId    = settings["hub1AppId"] ?: ""
    def token    = settings["hub1Token"] ?: ""
    if (!appId || !token) {
        state["hub1AllDevices"]       = []
        state["hub1AllDevicesStatus"] = "Error: Hub #1 Maker API app ID and/or token not configured"
        return
    }
    def hub1LocalIp = location.hubs[0].localIP
    def uri         = "http://${hub1LocalIp}:8080/apps/api/${appId}/devices?access_token=${token}"
    def allList = []
    try {
        def respData = fetchJson(uri, 15)
        respData?.each { dev ->
            allList << [
                id  : dev.id?.toString(),
                name: (dev.label ?: dev.name ?: "Unknown").toString(),
                room: (dev.room ?: "").toString()
            ]
        }
        state["hub1AllDevices"]       = allList
        if (allList.isEmpty()) {
            state["hub1AllDevicesStatus"] = "Error: Maker API returned no usable devices (empty or all-null device list) — a recent platform/beta update is the usual cause"
            log.warn "${hubLabel}: Maker API device list returned no usable devices — pickers cannot be (re)loaded until the hub's Maker API is fixed"
        } else {
            state["hub1AllDevicesStatus"] = "OK: ${allList.size()} device${allList.size() == 1 ? '' : 's'} loaded"
            log.info "${hubLabel}: Loaded ${allList.size()} device(s) for health monitoring."
        }
    } catch (Exception e) {
        log.error "${hubLabel}: Error loading health device list — ${e.message}"
        state["hub1AllDevices"]       = []
        state["hub1AllDevicesStatus"] = "Error: ${e.message}"
    }
}

String handler() {
    state.lastRun = new Date().format("yyyy-MM-dd hh:mm a", location.timeZone)
    def scanStart = new Date().time
    def report    = generateReportTables()
    def totalMs   = new Date().time - scanStart
    def totalMins = (totalMs / 60000).toInteger()
    def totalSecs = ((totalMs % 60000) / 1000).toInteger()
    def totalStr  = String.format("%d:%02d", totalMins, totalSecs)
    def collectMs  = report.collectMs ?: 0
    def renderMs   = totalMs - collectMs
    def fmtMs = { long ms ->
        def s = (ms / 1000).toInteger()
        def t = ((ms % 1000) / 100).toInteger()
        "${s}.${t}s"
    }
    def timingDetail = " [Data:${fmtMs(collectMs)}, Render:${fmtMs(renderMs)}]"
    // "Last run" is rendered at the TOP of the report, immediately under the
    // first Refresh Table button. It previously followed the last table, where
    // its proximity to the PB scan summary line made it look like a PB-scan
    // timestamp rather than the whole-report refresh time.
    def htmlOut = "<small><i>Last run: ${state.lastRun} (Scan time: ${totalStr}${timingDetail})</i></small>"
    htmlOut    += report.html
    htmlOut    += buildTableJS()
    return htmlOut
}

// ─────────────────────────────────────────────────────────────────────────────
// DATA COLLECTION
// ─────────────────────────────────────────────────────────────────────────────

private Map collectAllDeviceStates() {
    def excludeVirt          = settings["excludeVirtual"]    ?: false
    def excludeSysRoom       = settings["excludeSystemRoom"] ?: false
    def onPool               = []
    def offPool              = []
    def lockPool             = []
    def contactPool          = []
    def healthPool           = []
    def warnings             = []
    def activityThreshHours  = (settings["activityThresholdHours"] ?: 24) as long
    def activityThresholdMs  = activityThreshHours * 3600000L
    def now                  = new Date()

    // ── Hub #1 – Local ────────────────────────────────────────────────────────
    def hub1LabelVal  = settings["hub1Label"] ?: (location.name ?: "Hub 1")
    def hub1AppId     = settings["hub1AppId"] ?: ""
    def hub1Token     = settings["hub1Token"] ?: ""
    def hub1CanToggle = (hub1AppId && hub1Token)

    def roomMap = [:]
    try { location.getRooms()?.each { room -> roomMap[room.id] = room.name } } catch (e) {}

    def filterLocal = { dev ->
        if (dev.isDisabled()) return false
        def roomName = resolveLocalRoom(dev, roomMap)
        if (excludeSysRoom && roomName == "System") return false
        if (excludeVirt && (
            dev.typeName?.toLowerCase()?.contains("virtual") ||
            dev.displayName?.startsWith("VD ")
        )) return false
        return true
    }

    // Lock devices are explicitly hand-picked by the user, so the virtual-device
    // exclusion filter is intentionally not applied — only disabled and System-room
    // devices are skipped.
    def filterLock = { dev ->
        if (dev.isDisabled()) return false
        def roomName = resolveLocalRoom(dev, roomMap)
        if (excludeSysRoom && roomName == "System") return false
        return true
    }

    // Helper: resolve lastActivity for local devices, falling back to parent for
    // child devices. Apps cannot look up arbitrary local devices (there is no
    // getDeviceById() in the Hubitat app sandbox — every call threw
    // MissingMethodException), so the parent is fetched through Hub #1's own
    // Maker API when its credentials are configured.
    def resolveLocalLastActivity = { dev ->
        def lastAct = dev.getLastActivity()
        if (lastAct == null) {
            try {
                def parentId = dev.device?.parentDeviceId
                if (parentId && hub1CanToggle) {
                    def pData = fetchJson("http://127.0.0.1:8080/apps/api/${hub1AppId}/devices/${parentId}?access_token=${hub1Token}", 5)
                    if (pData instanceof Map) {
                        lastAct = parseRemoteLastActivity(pData.lastActivity, hub1LabelVal, "${parentId}-p")
                        if (enableLogging && lastAct) log.debug "Used parent lastActivity (via Hub #1 Maker API) for child ${dev.displayName}"
                    }
                }
            } catch (ex) {
                if (enableLogging) log.debug "Could not resolve parent lastActivity for ${dev.displayName}: ${ex.message}"
            }
        }
        return lastAct
    }

    // ON pool
    (devsOn ?: []).findAll(filterLocal).each { dev ->
        def onUrl  = hub1CanToggle ? "/apps/api/${hub1AppId}/devices/${dev.id}/on?access_token=${hub1Token}"  : null
        def offUrl = hub1CanToggle ? "/apps/api/${hub1AppId}/devices/${dev.id}/off?access_token=${hub1Token}" : null
        onPool << [displayName: dev.displayName, room: resolveLocalRoom(dev, roomMap),
                   hub: hub1LabelVal, linkUrl: "/device/edit/${dev.id}",
                   switchVal: dev.currentValue("switch")?.toString()?.toLowerCase(),
                   toggleOnUrl: onUrl, toggleOffUrl: offUrl]
    }
    // OFF pool
    (devsOff ?: []).findAll(filterLocal).each { dev ->
        def onUrl  = hub1CanToggle ? "/apps/api/${hub1AppId}/devices/${dev.id}/on?access_token=${hub1Token}"  : null
        def offUrl = hub1CanToggle ? "/apps/api/${hub1AppId}/devices/${dev.id}/off?access_token=${hub1Token}" : null
        offPool << [displayName: dev.displayName, room: resolveLocalRoom(dev, roomMap),
                    hub: hub1LabelVal, linkUrl: "/device/edit/${dev.id}",
                    switchVal: dev.currentValue("switch")?.toString()?.toLowerCase(),
                    toggleOnUrl: onUrl, toggleOffUrl: offUrl]
    }
    // Lock pool – Hub #1
    (devsLock ?: []).findAll(filterLock).each { dev ->
        lockPool << [displayName: dev.displayName, room: resolveLocalRoom(dev, roomMap),
                     hub: hub1LabelVal, linkUrl: "/device/edit/${dev.id}",
                     lockVal: dev.currentValue("lock")?.toString()?.toLowerCase() ?: "unknown",
                     battery: dev.currentValue("battery")?.toString() ?: "n/a"]
    }
    // Contact pool – Hub #1 (hand-picked like locks, so the virtual-device
    // exclusion filter is intentionally not applied)
    // Tilt sensors (e.g. Third Reality) may declare ContactSensor but only ever
    // report "acceleration" (active/inactive); fall back to it when no contact
    // value exists (1.80).
    (devsContact ?: []).findAll(filterLock).each { dev ->
        String ctVal = null
        String accVal = null
        try { ctVal  = dev.currentValue("contact")?.toString()?.toLowerCase() } catch (ignored) {}
        if (!ctVal) {
            try { accVal = dev.currentValue("acceleration")?.toString()?.toLowerCase() } catch (ignored) {}
        }
        contactPool << [displayName: dev.displayName, room: resolveLocalRoom(dev, roomMap),
                        hub: hub1LabelVal, linkUrl: "/device/edit/${dev.id}",
                        contactVal: ctVal ?: accVal ?: "unknown",
                        contactSource: (!ctVal && accVal) ? "acceleration" : "contact",
                        battery: dev.currentValue("battery")?.toString() ?: "n/a"]
    }
    // Health pool – Hub #1
    // Primary source: hub1HealthDevs (capability.* picker) — direct device objects.
    // This picker is authoritative for every device it can show: checking adds a
    // device and unchecking removes it, with nothing else able to re-add it.
    // Supplementary: hub1ExtraHealthIds (explicit comma-separated ID list) covers
    // ONLY devices that don't surface in the capability.* picker (e.g. Actuator-
    // only drivers like MQTT Display Publisher). Apps cannot resolve arbitrary
    // local devices as objects (getDeviceById() does not exist in the app
    // sandbox), so these IDs are health-checked through Hub #1's OWN Maker API —
    // the same code path used for Hubs #2/#3 — and the row links are rewritten to
    // relative URLs. Any ID already selected in the picker is dropped here so the
    // picker and the supplement can never double-count.
    def hub1HealthDevObjs = (hub1HealthDevs ?: []) as List
    def hub1HealthDevIds  = hub1HealthDevObjs.collect { it.id.toString() } as Set
    def hub1ExtraIds      = (settings["hub1ExtraHealthIds"] ?: "").split(",").collect { it.trim() }.findAll { it }
    Set hub1SupplementIds = hub1ExtraIds.findAll { !hub1HealthDevIds.contains(it) } as Set
    if (hub1SupplementIds) {
        if (hub1CanToggle) {
            def (supRows, supWarn) = fetchRemoteHealthDeviceStates("127.0.0.1:8080",
                    hub1AppId.toString(), hub1Token.toString(), hub1LabelVal.toString(),
                    excludeVirt, excludeSysRoom, [] as Set, hub1SupplementIds,
                    activityThresholdMs, activityThreshHours)
            if (supWarn && !warnings.contains(supWarn)) warnings << supWarn
            healthPool.addAll(supRows.collect { e ->
                [displayName: e.displayName, room: e.room, hub: hub1LabelVal,
                 linkUrl: "/device/edit/${e.devId}", status: e.status,
                 lastActivity: e.lastActivity, lastActivityStr: e.lastActivityStr,
                 issue: e.issue,
                 healthStatus: e.healthStatus ?: "n/a",
                 battery:     e.battery      ?: "n/a",
                 lastBattery: e.lastBattery  ?: "n/a"]
            })
        } else if (enableLogging) {
            log.info "Hub #1 health: ${hub1SupplementIds.size()} supplementary device ID(s) skipped — Hub #1 Maker API app ID and token are required to health-check devices outside the capability picker"
        }
    }
    hub1HealthDevObjs.findAll(filterLocal).each { dev ->
        def rawStatus      = dev.getStatus()?.toUpperCase() ?: ""
        def rawHealthSt    = (dev.currentHealthStatus ?: "").toString().toLowerCase()
        def connStatus     = (dev.currentValue("connectionStatus") ?: "").toString().toLowerCase()
        def lastAct        = resolveLocalLastActivity(dev)
        def lateActivity   = lastAct ? ((now.time - lastAct.time) > activityThresholdMs) : true
        def statusBad      = rawStatus in ["OFFLINE", "INACTIVE", "NOT PRESENT"]
        def healthBad      = rawHealthSt == "offline"
        def connBad        = connStatus == "disconnected"
        if (!(statusBad || healthBad || connBad || lateActivity)) return
        def lastActStr  = lastAct ? lastAct.format("yyyy-MM-dd hh:mm a", location.timeZone)
                                  : "<span style='color:red;'>Never</span>"
        def lastBattStr = "n/a"
        try {
            def lbRaw = dev.currentValue("lastBattery")
            if (lbRaw) {
                def lbDate = (lbRaw instanceof Date) ? lbRaw
                           : new java.text.SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy").parse(lbRaw.toString())
                lastBattStr = lbDate.format("yyyy-MM-dd hh:mm a", location.timeZone)
            }
        } catch (ignored) {}
        healthPool << [
            displayName    : dev.displayName,
            room           : resolveLocalRoom(dev, roomMap),
            hub            : hub1LabelVal,
            linkUrl        : "/device/edit/${dev.id}",
            status         : rawStatus ?: (connBad ? "DISCONNECTED" : (rawHealthSt == "offline" ? "OFFLINE" : (rawHealthSt == "online" ? "ONLINE" : "—"))),
            lastActivity   : lastAct,
            lastActivityStr: lastActStr,
            issue          : buildHealthIssueLabel(rawStatus, rawHealthSt, connBad, lateActivity, activityThreshHours),
            healthStatus   : rawHealthSt ?: "n/a",
            battery        : dev.currentValue("battery")?.toString() ?: "n/a",
            lastBattery    : lastBattStr
        ]
    }

    // ── Hubs #2 & #3 – Remote ────────────────────────────────────────────────
    [2, 3].each { hubNum ->
        if (!settings["hub${hubNum}Enabled"]) return
        def hubLabel = settings["hub${hubNum}Label"] ?: "Hub ${hubNum}"
        def ip       = settings["hub${hubNum}Ip"]
        def appId    = settings["hub${hubNum}AppId"]
        def token    = settings["hub${hubNum}Token"]

        // — Switch ON/OFF pools —
        def onRaw    = settings["hub${hubNum}SelectedOnDevices"]
        def offRaw   = settings["hub${hubNum}SelectedOffDevices"]
        def onIds    = (onRaw  instanceof List ? onRaw  : (onRaw  ? [onRaw]  : []))*.toString() as Set
        def offIds   = (offRaw instanceof List ? offRaw : (offRaw ? [offRaw] : []))*.toString() as Set
        def allIds   = (onIds + offIds)
        if (allIds) {
            def (devStates, warning) = fetchRemoteDeviceStates(ip, appId, token, hubLabel, excludeVirt, excludeSysRoom, allIds)
            if (warning) warnings << warning
            devStates.each { entry ->
                if (onIds.contains(entry.devId))
                    onPool  << [displayName: entry.displayName, room: entry.room, hub: hubLabel,
                                linkUrl: entry.linkUrl, switchVal: entry.switchVal,
                                toggleOnUrl: entry.toggleOnUrl, toggleOffUrl: entry.toggleOffUrl]
                if (offIds.contains(entry.devId))
                    offPool << [displayName: entry.displayName, room: entry.room, hub: hubLabel,
                                linkUrl: entry.linkUrl, switchVal: entry.switchVal,
                                toggleOnUrl: entry.toggleOnUrl, toggleOffUrl: entry.toggleOffUrl]
            }
        }

        // — Lock pool —
        def lockRaw  = settings["hub${hubNum}SelectedLockDevices"]
        def lockIds  = (lockRaw instanceof List ? lockRaw : (lockRaw ? [lockRaw] : []))*.toString() as Set
        if (lockIds) {
            def (lkEntries, lkWarn) = fetchRemoteLockStates(ip, appId, token, hubLabel, excludeVirt, excludeSysRoom, lockIds)
            if (lkWarn && !warnings.contains(lkWarn)) warnings << lkWarn
            lockPool.addAll(lkEntries.collect { e ->
                [displayName: e.displayName, room: e.room, hub: hubLabel,
                 linkUrl: e.linkUrl, lockVal: e.lockVal, battery: e.battery ?: "n/a"]
            })
        }

        // — Contact pool —
        def ctRaw  = settings["hub${hubNum}SelectedContactDevices"]
        def ctIds  = (ctRaw instanceof List ? ctRaw : (ctRaw ? [ctRaw] : []))*.toString() as Set
        if (ctIds) {
            def (ctEntries, ctWarn) = fetchRemoteContactStates(ip, appId, token, hubLabel, excludeVirt, excludeSysRoom, ctIds)
            if (ctWarn && !warnings.contains(ctWarn)) warnings << ctWarn
            contactPool.addAll(ctEntries.collect { e ->
                [displayName: e.displayName, room: e.room, hub: hubLabel,
                 linkUrl: e.linkUrl, contactVal: e.contactVal,
                 contactSource: e.contactSource ?: "contact", battery: e.battery ?: "n/a"]
            })
        }

        // — Health pool —
        def healthRaw    = settings["hub${hubNum}SelectedHealthDevices"]
        def healthIds    = (healthRaw instanceof List ? healthRaw : (healthRaw ? [healthRaw] : []))*.toString() as Set
        // Combine: IDs flagged disabled during last Load/Reload + any manually entered IDs
        def cachedDisabled = (state["hub${hubNum}DisabledIds"] ?: [])*.toString() as Set
        def manualExclude  = (settings["hub${hubNum}ExcludeHealthIds"] ?: "").split(",").collect { it.trim() }.findAll { it } as Set
        def excludeIds     = cachedDisabled + manualExclude
        if (healthIds) {
            def (hEntries, hWarn) = fetchRemoteHealthDeviceStates(ip, appId, token, hubLabel,
                                        excludeVirt, excludeSysRoom, excludeIds, healthIds, activityThresholdMs, activityThreshHours)
            if (hWarn && !warnings.contains(hWarn)) warnings << hWarn
            healthPool.addAll(hEntries.collect { e ->
                [displayName: e.displayName, room: e.room, hub: hubLabel,
                 linkUrl: e.linkUrl, status: e.status,
                 lastActivity: e.lastActivity, lastActivityStr: e.lastActivityStr,
                 issue: e.issue,
                 healthStatus: e.healthStatus ?: "n/a",
                 battery:     e.battery      ?: "n/a",
                 lastBattery: e.lastBattery  ?: "n/a"]
            })
        }
    }

    return [onPool: onPool, offPool: offPool, lockPool: lockPool, contactPool: contactPool, healthPool: healthPool, warnings: warnings]
}

// ─────────────────────────────────────────────────────────────────────────────
// REMOTE SWITCH STATE FETCHER (ON/OFF/Unknown tables)
// ─────────────────────────────────────────────────────────────────────────────

private List fetchRemoteDeviceStates(String ip, String appId, String token,
                                     String hubLabel, boolean excludeVirt,
                                     boolean excludeSysRoom, Set selectedIds) {
    def results = []
    def warning = null

    if (!ip || !appId || !token) {
        warning = "${hubLabel}: Missing IP, app ID, or access token — skipped."
        return [results, warning]
    }

    def uri = "http://${ip}/apps/api/${appId}/devices?access_token=${token}"

    try {
        if (enableLogging) log.debug "Querying ${hubLabel} — ids: ${selectedIds}"
        def respData = fetchJson(uri, 10)
        if (respData instanceof List && respData.isEmpty() && selectedIds) {
            // An empty (or all-null, see fetchJson) device list cannot be
            // distinguished from "no devices selected in Maker API" — either
            // way, silently returning nothing would render clean tables that
            // hide the outage. Surface it instead.
            warning = "${hubLabel} (${ip}): Maker API returned no usable device data (empty or all-null device list) — this hub's switch states are unavailable until its Maker API is fixed (a recent platform/beta update is the usual cause)."
            return [results, warning]
        }
        respData?.each { dev ->
                def devId = dev.id?.toString()
                if (!selectedIds.contains(devId)) return
                if (!hasSwitchCapability(dev.capabilities)) {
                    if (enableLogging) log.debug "${hubLabel} device ${devId} (${dev.label ?: dev.name}): no switch capability, skipping"
                    return
                }
                if (dev.disabled == true || dev.disabled?.toString() == "true" ||
                    (dev.status ?: "").toString().toUpperCase() == "DISABLED") return
                if (excludeVirt && (
                    (dev.type ?: "").toString().toLowerCase().contains("virtual") ||
                    (dev.label ?: dev.name ?: "").toString().startsWith("VD ")
                )) return
                if (excludeSysRoom && (dev.room ?: "").toString() == "System") return

                def switchVal  = null
                def attrsField = dev.attributes
                if (attrsField instanceof List) {
                    def sw = attrsField.find { a -> a?.name?.toString() == "switch" }
                    switchVal = sw?.currentValue?.toString()?.toLowerCase()
                } else if (attrsField instanceof Map) {
                    switchVal = attrsField["switch"]?.toString()?.toLowerCase()
                }

                if (switchVal == null) {
                    if (enableLogging) log.debug "${hubLabel} device ${devId}: fetching individually for switch state"
                    try {
                        def devData = fetchJson("http://${ip}/apps/api/${appId}/devices/${devId}?access_token=${token}", 5)
                        def da = devData?.attributes
                        if (da instanceof List) {
                            def sw2 = da.find { a -> a?.name?.toString() == "switch" }
                            switchVal = sw2?.currentValue?.toString()?.toLowerCase()
                        } else if (da instanceof Map) {
                            switchVal = da["switch"]?.toString()?.toLowerCase()
                        }
                    } catch (Exception fe) {
                        if (enableLogging) log.warn "${hubLabel} device ${devId}: fallback fetch failed — ${fe.message}"
                    }
                }

                def toggleOnUrl  = "http://${ip}/apps/api/${appId}/devices/${devId}/on?access_token=${token}"
                def toggleOffUrl = "http://${ip}/apps/api/${appId}/devices/${devId}/off?access_token=${token}"

                if (switchVal == null) {
                    if (enableLogging) log.debug "${hubLabel} device ${devId}: no switch attribute found after all attempts, skipping"
                    return
                }

                results << [devId: devId, displayName: (dev.label ?: dev.name ?: "Unknown").toString(),
                            room: (dev.room ?: "—").toString(),
                            linkUrl: "http://${ip}/device/edit/${devId}", switchVal: switchVal,
                            toggleOnUrl: toggleOnUrl, toggleOffUrl: toggleOffUrl]
        }
    } catch (java.net.SocketTimeoutException e) {
        warning = "${hubLabel} (${ip}): Connection timed out — hub may be offline."
        if (enableLogging) log.warn "Timeout querying ${hubLabel}: ${e}"
    } catch (java.net.ConnectException e) {
        warning = "${hubLabel} (${ip}): Could not connect — check IP address."
        if (enableLogging) log.warn "Connection refused for ${hubLabel}: ${e}"
    } catch (Exception e) {
        warning = "${hubLabel} (${ip}): Error — ${e.message}"
        if (enableLogging) log.error "Unexpected error querying ${hubLabel}: ${e}"
    }

    return [results, warning]
}

// ─────────────────────────────────────────────────────────────────────────────
// REMOTE LOCK STATE FETCHER
// ─────────────────────────────────────────────────────────────────────────────

private List fetchRemoteLockStates(String ip, String appId, String token,
                                   String hubLabel, boolean excludeVirt,
                                   boolean excludeSysRoom, Set selectedIds) {
    def results = []
    def warning = null

    if (!ip || !appId || !token) {
        warning = "${hubLabel}: Missing credentials for lock monitor — skipped."
        return [results, warning]
    }

    def uri = "http://${ip}/apps/api/${appId}/devices?access_token=${token}"
    try {
        def respData = fetchJson(uri, 10)
        if (respData instanceof List && respData.isEmpty() && selectedIds) {
            warning = "${hubLabel} (${ip}): Maker API returned no usable device data (empty or all-null device list) — this hub's lock/contact states are unavailable until its Maker API is fixed (a recent platform/beta update is the usual cause)."
            return [results, warning]
        }
        respData?.each { dev ->
                def devId = dev.id?.toString()
                if (!selectedIds.contains(devId)) return
                if (dev.disabled == true || dev.disabled?.toString() == "true" ||
                    (dev.status ?: "").toString().toUpperCase() == "DISABLED") return
                if (excludeVirt && (
                    (dev.type ?: "").toString().toLowerCase().contains("virtual") ||
                    (dev.label ?: dev.name ?: "").toString().startsWith("VD ")
                )) return
                if (excludeSysRoom && (dev.room ?: "").toString() == "System") return

                // Try to read lock attribute from bulk response
                def lockVal = null
                def attrsField = dev.attributes
                if (attrsField instanceof List) {
                    def lk = attrsField.find { a -> a?.name?.toString() == "lock" }
                    lockVal = lk?.currentValue?.toString()?.toLowerCase()
                } else if (attrsField instanceof Map) {
                    lockVal = attrsField["lock"]?.toString()?.toLowerCase()
                }

                // Fall back to per-device fetch if not in bulk response
                if (lockVal == null) {
                    try {
                        def devData = fetchJson("http://${ip}/apps/api/${appId}/devices/${devId}?access_token=${token}", 5)
                        def da = devData?.attributes
                        if (da instanceof List) {
                            def lk2 = da.find { a -> a?.name?.toString() == "lock" }
                            lockVal = lk2?.currentValue?.toString()?.toLowerCase()
                        } else if (da instanceof Map) {
                            lockVal = da["lock"]?.toString()?.toLowerCase()
                        }
                    } catch (Exception fe) {
                        if (enableLogging) log.warn "${hubLabel} device ${devId}: lock fallback fetch failed — ${fe.message}"
                    }
                }

                def lockBatteryVal = "n/a"
                if (attrsField instanceof List) {
                    def ba = attrsField.find { a -> a?.name?.toString() == "battery" }
                    lockBatteryVal = ba?.currentValue?.toString() ?: "n/a"
                } else if (attrsField instanceof Map) {
                    lockBatteryVal = attrsField["battery"]?.toString() ?: "n/a"
                }
                results << [devId      : devId,
                            displayName: (dev.label ?: dev.name ?: "Unknown").toString(),
                            room       : (dev.room ?: "—").toString(),
                            linkUrl    : "http://${ip}/device/edit/${devId}",
                            lockVal    : lockVal ?: "unknown",
                            battery    : lockBatteryVal]
        }
    } catch (java.net.SocketTimeoutException e) {
        warning = "${hubLabel} (${ip}): Connection timed out (lock fetch) — hub may be offline."
        if (enableLogging) log.warn "Timeout querying ${hubLabel} locks: ${e}"
    } catch (java.net.ConnectException e) {
        warning = "${hubLabel} (${ip}): Could not connect (lock fetch) — check IP address."
        if (enableLogging) log.warn "Connection refused for ${hubLabel} locks: ${e}"
    } catch (Exception e) {
        warning = "${hubLabel} (${ip}): Error (lock fetch) — ${e.message}"
        if (enableLogging) log.error "Unexpected error querying ${hubLabel} locks: ${e}"
    }

    return [results, warning]
}

// ─────────────────────────────────────────────────────────────────────────────
// REMOTE CONTACT SENSOR STATE FETCHER
// ─────────────────────────────────────────────────────────────────────────────

private List fetchRemoteContactStates(String ip, String appId, String token,
                                      String hubLabel, boolean excludeVirt,
                                      boolean excludeSysRoom, Set selectedIds) {
    def results = []
    def warning = null

    if (!ip || !appId || !token) {
        warning = "${hubLabel}: Missing credentials for contact sensor monitor — skipped."
        return [results, warning]
    }

    def uri = "http://${ip}/apps/api/${appId}/devices?access_token=${token}"
    try {
        def respData = fetchJson(uri, 10)
        if (respData instanceof List && respData.isEmpty() && selectedIds) {
            warning = "${hubLabel} (${ip}): Maker API returned no usable device data (empty or all-null device list) — this hub's lock/contact states are unavailable until its Maker API is fixed (a recent platform/beta update is the usual cause)."
            return [results, warning]
        }
        respData?.each { dev ->
                def devId = dev.id?.toString()
                if (!selectedIds.contains(devId)) return
                if (dev.disabled == true || dev.disabled?.toString() == "true" ||
                    (dev.status ?: "").toString().toUpperCase() == "DISABLED") return
                if (excludeVirt && (
                    (dev.type ?: "").toString().toLowerCase().contains("virtual") ||
                    (dev.label ?: dev.name ?: "").toString().startsWith("VD ")
                )) return
                if (excludeSysRoom && (dev.room ?: "").toString() == "System") return

                // Try to read contact (or, for tilt sensors that only report
                // acceleration, the acceleration attribute) from bulk response
                def attrsField = dev.attributes
                def contactVal = remoteAttrValue(attrsField, "contact")
                def accelVal   = remoteAttrValue(attrsField, "acceleration")

                // Fall back to per-device fetch if neither is in bulk response
                if (contactVal == null && accelVal == null) {
                    try {
                        def devData = fetchJson("http://${ip}/apps/api/${appId}/devices/${devId}?access_token=${token}", 5)
                        def da = devData?.attributes
                        contactVal = remoteAttrValue(da, "contact")
                        accelVal   = remoteAttrValue(da, "acceleration")
                    } catch (Exception fe) {
                        if (enableLogging) log.warn "${hubLabel} device ${devId}: contact fallback fetch failed — ${fe.message}"
                    }
                }
                def contactSource = (contactVal == null && accelVal != null) ? "acceleration" : "contact"
                if (contactVal == null) contactVal = accelVal

                def ctBatteryVal = "n/a"
                if (attrsField instanceof List) {
                    def ba = attrsField.find { a -> a?.name?.toString() == "battery" }
                    ctBatteryVal = ba?.currentValue?.toString() ?: "n/a"
                } else if (attrsField instanceof Map) {
                    ctBatteryVal = attrsField["battery"]?.toString() ?: "n/a"
                }
                results << [devId      : devId,
                            displayName: (dev.label ?: dev.name ?: "Unknown").toString(),
                            room       : (dev.room ?: "—").toString(),
                            linkUrl    : "http://${ip}/device/edit/${devId}",
                            contactVal : contactVal ?: "unknown",
                            contactSource: contactSource,
                            battery    : ctBatteryVal]
        }
    } catch (java.net.SocketTimeoutException e) {
        warning = "${hubLabel} (${ip}): Connection timed out (contact fetch) — hub may be offline."
        if (enableLogging) log.warn "Timeout querying ${hubLabel} contacts: ${e}"
    } catch (java.net.ConnectException e) {
        warning = "${hubLabel} (${ip}): Could not connect (contact fetch) — check IP address."
        if (enableLogging) log.warn "Connection refused for ${hubLabel} contacts: ${e}"
    } catch (Exception e) {
        warning = "${hubLabel} (${ip}): Error (contact fetch) — ${e.message}"
        if (enableLogging) log.error "Unexpected error querying ${hubLabel} contacts: ${e}"
    }

    return [results, warning]
}

// ─────────────────────────────────────────────────────────────────────────────
// REMOTE HEALTH / ACTIVITY STATE FETCHER
// ─────────────────────────────────────────────────────────────────────────────

private List fetchRemoteHealthDeviceStates(String ip, String appId, String token,
                                           String hubLabel, boolean excludeVirt,
                                           boolean excludeSysRoom, Set excludeIds,
                                           Set selectedIds, long activityThresholdMs,
                                           long activityThreshHours) {
    def results        = []
    def warning        = null
    def errors         = []
    def now            = new Date()
    def hubUnreachable = false
    // Circuit breaker: when the hub's Maker API is broken, every per-device
    // request fails the same way — stop after a few identical failures instead
    // of logging one warn per selected device.
    int consecutiveFailures = 0
    // Cache parent lastActivity so siblings share one parent-events fetch.
    // Map<parentId(String), Date|null>
    def parentCache    = [:]

    if (!ip || !appId || !token) {
        warning = "${hubLabel}: Missing credentials for health/activity monitor — skipped."
        return [results, warning]
    }

    // Fetch the live device list once and build a Set of IDs the Maker API currently exposes.
    // Disabled devices may simply be absent from this list even when dev.disabled is never set,
    // which makes this the most reliable runtime disabled-device filter available via the API.
    def liveIds = null as Set
    try {
        def listData = fetchJson("http://${ip}/apps/api/${appId}/devices?access_token=${token}", 10)
        if (listData instanceof List) {
            liveIds = listData.collect { it.id?.toString() } as Set
            if (enableLogging) log.debug "${hubLabel}: live device list has ${liveIds.size()} IDs"
            if (!liveIds && selectedIds) {
                // A list with zero usable entries (empty, or all-null as seen
                // with a broken platform/beta build) cannot distinguish
                // disabled devices. Treating it as authoritative would skip
                // every selected device and render a clean health table — a
                // false all-clear. Report the outage and stop instead.
                warning = "${hubLabel} (${ip}): Maker API returned no usable device data (empty or all-null device list) — health/activity results for this hub are unavailable until its Maker API is fixed (a recent platform/beta update is the usual cause)."
                return [results, warning]
            }
        }
    } catch (Exception listEx) {
        if (enableLogging) log.debug "${hubLabel}: live device list fetch failed (disabled check unavailable) — ${listEx.message}"
    }

    selectedIds.each { devId ->
        if (hubUnreachable) return
        if (excludeIds.contains(devId.toString())) {
            if (enableLogging) log.debug "${hubLabel} device ${devId}: skipped (manual exclusion list)"
            return
        }
        if (liveIds != null && !liveIds.contains(devId.toString())) {
            if (enableLogging) log.debug "${hubLabel} device ${devId}: not in live device list — skipping (disabled or removed)"
            return
        }

        try {
            def uri = "http://${ip}/apps/api/${appId}/devices/${devId}?access_token=${token}"
            if (enableLogging) log.debug "Health check ${hubLabel} device ${devId}"
            def dev = fetchJson(uri, 10)
            consecutiveFailures = 0
            if (!dev) return
                if (dev.disabled == true || dev.disabled?.toString() == "true" ||
                    (dev.status ?: "").toString().toUpperCase() == "DISABLED") return

                if (excludeVirt && (
                    (dev.type ?: "").toString().toLowerCase().contains("virtual") ||
                    (dev.label ?: dev.name ?: "").toString().startsWith("VD ")
                )) return
                if (excludeSysRoom && (dev.room ?: "").toString() == "System") return

                // HE status
                def rawStatus = (dev.status ?: "").toString().toUpperCase()

                // healthStatus attribute
                def rawHealthSt = ""
                def attrsField  = dev.attributes
                if (attrsField instanceof List) {
                    def hs = attrsField.find { a -> a?.name?.toString() == "healthStatus" }
                    rawHealthSt = hs?.currentValue?.toString()?.toLowerCase() ?: ""
                } else if (attrsField instanceof Map) {
                    rawHealthSt = attrsField["healthStatus"]?.toString()?.toLowerCase() ?: ""
                }

                // battery, lastBattery attributes
                def batteryVal   = "n/a"
                def lastBattVal  = "n/a"
                if (attrsField instanceof List) {
                    def ba  = attrsField.find { a -> a?.name?.toString() == "battery" }
                    batteryVal  = ba?.currentValue?.toString() ?: "n/a"
                    def lb  = attrsField.find { a -> a?.name?.toString() == "lastBattery" }
                    def lbRaw = lb?.currentValue?.toString()
                    if (lbRaw) {
                        try {
                            def lbDate = (lbRaw.isLong())
                                ? new Date(lbRaw.toLong())
                                : Date.parse("EEE MMM dd HH:mm:ss zzz yyyy", lbRaw)
                            lastBattVal = lbDate.format("yyyy-MM-dd hh:mm a", location.timeZone)
                        } catch (ignored) { lastBattVal = lbRaw }
                    }
                } else if (attrsField instanceof Map) {
                    batteryVal = attrsField["battery"]?.toString() ?: "n/a"
                    def lbRaw  = attrsField["lastBattery"]?.toString()
                    if (lbRaw) {
                        try {
                            def lbDate = (lbRaw.isLong())
                                ? new Date(lbRaw.toLong())
                                : Date.parse("EEE MMM dd HH:mm:ss zzz yyyy", lbRaw)
                            lastBattVal = lbDate.format("yyyy-MM-dd hh:mm a", location.timeZone)
                        } catch (ignored) { lastBattVal = lbRaw }
                    }
                }

                // ── lastActivity resolution (3-step) ─────────────────────────
                // Step 1: lastActivity field on the device endpoint (present in some HE versions)
                Date lastActDate = parseRemoteLastActivity(dev.lastActivity, hubLabel, devId)
                if (enableLogging) log.debug "${hubLabel} ${devId}: device-endpoint lastActivity='${dev.lastActivity}' → ${lastActDate}"

                // Step 2: this device's own events endpoint
                if (lastActDate == null) {
                    lastActDate = fetchLastActivityFromEvents(ip, appId, token, devId.toString(), hubLabel)
                }

                // Step 3: parent's events (child devices record activity on the parent).
                // Results are cached so multiple children of the same parent cost one call.
                if (lastActDate == null && dev.parentDeviceId) {
                    def parentId = dev.parentDeviceId.toString()
                    if (!parentCache.containsKey(parentId)) {
                        // Try parent device endpoint first, then parent events
                        Date pd = null
                        try {
                            def pData = fetchJson("http://${ip}/apps/api/${appId}/devices/${parentId}?access_token=${token}", 10)
                            if (pData) pd = parseRemoteLastActivity(pData.lastActivity, hubLabel, "${parentId}-p")
                        } catch (ignored) {}
                        if (pd == null) pd = fetchLastActivityFromEvents(ip, appId, token, parentId, hubLabel)
                        parentCache[parentId] = pd
                        if (enableLogging) log.debug "${hubLabel} parent ${parentId} resolved lastActivity: ${pd}"
                    }
                    lastActDate = parentCache[parentId]
                    if (enableLogging && lastActDate) log.debug "${hubLabel} ${devId}: using parent ${dev.parentDeviceId} lastActivity ${lastActDate}"
                }
                // ─────────────────────────────────────────────────────────────

                // connectionStatus attribute (e.g. MQTT Display Publisher driver)
                def connStatus = ""
                if (attrsField instanceof List) {
                    def cs = attrsField.find { a -> a?.name?.toString() == "connectionStatus" }
                    connStatus = cs?.currentValue?.toString()?.toLowerCase() ?: ""
                } else if (attrsField instanceof Map) {
                    connStatus = attrsField["connectionStatus"]?.toString()?.toLowerCase() ?: ""
                }

                def statusBad    = rawStatus in ["OFFLINE", "INACTIVE", "NOT PRESENT"]
                def healthBad    = rawHealthSt == "offline"
                def connBad      = connStatus == "disconnected"
                def lateActivity = lastActDate ? ((now.time - lastActDate.time) > activityThresholdMs) : true

                if (!(statusBad || healthBad || connBad || lateActivity)) return

                def lastActStr = lastActDate
                    ? lastActDate.format("yyyy-MM-dd hh:mm a", location.timeZone)
                    : "<span style='color:red;'>Never</span>"

                results << [
                    devId          : devId,
                    displayName    : (dev.label ?: dev.name ?: "Unknown").toString(),
                    room           : (dev.room ?: "—").toString(),
                    linkUrl        : "http://${ip}/device/edit/${devId}",
                    status         : rawStatus ?: (connBad ? "DISCONNECTED" : (rawHealthSt == "offline" ? "OFFLINE" : (rawHealthSt == "online" ? "ONLINE" : "—"))),
                    lastActivity   : lastActDate,
                    lastActivityStr: lastActStr,
                    issue          : buildHealthIssueLabel(rawStatus, rawHealthSt, connBad, lateActivity, activityThreshHours),
                    healthStatus   : rawHealthSt ?: "n/a",
                    battery        : batteryVal,
                    lastBattery    : lastBattVal
                ]
        } catch (java.net.SocketTimeoutException e) {
            errors << "device ${devId}: timed out"
            if (enableLogging) log.warn "${hubLabel} device ${devId}: timeout — ${e}"
            consecutiveFailures++
            if (consecutiveFailures >= 3 && results.isEmpty()) {
                hubUnreachable = true
                warning = "${hubLabel} (${ip}): Maker API is not responding (${consecutiveFailures} consecutive timeouts) — remaining ${selectedIds.size() - errors.size()} health check(s) skipped."
            }
        } catch (java.net.ConnectException e) {
            hubUnreachable = true
            warning = "${hubLabel} (${ip}): Could not connect (health check) — check IP address."
        } catch (Exception e) {
            errors << "device ${devId}: ${e.message}"
            if (enableLogging) log.warn "${hubLabel} device ${devId}: error — ${e.message}"
            consecutiveFailures++
            if (consecutiveFailures >= 3 && results.isEmpty()) {
                hubUnreachable = true
                warning = "${hubLabel} (${ip}): Maker API is not returning device data (${e.message}) — remaining ${selectedIds.size() - errors.size()} health check(s) skipped."
            }
        }
    }

    if (errors && !warning) warning = "${hubLabel}: ${errors.size()} device(s) had errors during health check (first: ${errors[0]})"
    return [results, warning]
}

// Fetch the most-recent event timestamp from the Maker API events endpoint.
// HE returns events most-recent-first; we parse the first entry's date field.
// Returns null if the device has no events, the endpoint is unavailable, or parsing fails.
private Date fetchLastActivityFromEvents(String ip, String appId, String token,
                                        String devId, String hubLabel) {
    Date result = null
    try {
        def uri = "http://${ip}/apps/api/${appId}/devices/${devId}/events?access_token=${token}"
        def evData = fetchJson(uri, 10)
        if (evData instanceof List && evData.size() > 0) {
            def event   = evData[0]
            def dateVal = event.date ?: event.time ?: event.isoDate
            result = parseRemoteLastActivity(dateVal, hubLabel, "${devId}-evt")
            if (enableLogging) log.debug "${hubLabel} ${devId}: events → raw='${dateVal}' parsed=${result}"
        } else if (enableLogging) {
            log.debug "${hubLabel} ${devId}: events → count=${evData instanceof List ? evData.size() : 'n/a'}"
        }
    } catch (Exception e) {
        if (enableLogging) log.debug "${hubLabel} ${devId}: events fetch failed — ${e.message}"
    }
    return result
}

// Parse a lastActivity value from the Maker API. Handles:
//   • Long / Number  — epoch milliseconds
//   • Numeric string — epoch milliseconds as string
//   • Date string    — "yyyy-MM-dd HH:mm:ss±HHmm", ISO-8601 with T, positive or NEGATIVE offset
// Both + and – timezone offsets are stripped before parsing so US hubs (-05:00 etc.) work correctly.
private Date parseRemoteLastActivity(def laVal, String hubLabel, def devId) {
    if (laVal == null) return null
    try {
        if (laVal instanceof Number) return new Date(laVal.toLong())
        def laStr = laVal.toString().trim()
        if (!laStr || laStr == "null") return null
        if (laStr.isLong()) return new Date(laStr.toLong())
        // Replace T separator, then strip trailing ±HH:mm or ±HHmm (handles both signs)
        def raw = laStr.replace('T', ' ').replaceAll(/[+\-]\d{2}:?\d{2}$/, '').trim()
        if (raw) return Date.parse("yyyy-MM-dd HH:mm:ss", raw)
    } catch (pe) {
        if (enableLogging) log.warn "${hubLabel} device ${devId}: could not parse lastActivity '${laVal}' — ${pe.message}"
    }
    return null
}

// ─────────────────────────────────────────────────────────────────────────────
// HEALTH ISSUE LABEL BUILDER
// ─────────────────────────────────────────────────────────────────────────────

private String buildHealthIssueLabel(String rawStatus, String rawHealthSt,
                                     boolean connBad, boolean lateActivity, long threshHours) {
    def reasons = []
    if (rawStatus in ["OFFLINE", "INACTIVE", "NOT PRESENT"]) reasons << rawStatus
    // Only add HEALTH OFFLINE if it's not already covered by rawStatus == "OFFLINE"
    if (rawHealthSt == "offline" && !reasons.contains("OFFLINE")) reasons << "HEALTH OFFLINE"
    if (connBad) reasons << "DISCONNECTED"
    if (lateActivity) reasons << "Late Activity (>${threshHours}h)"
    return reasons ? reasons.join(", ") : "—"
}

// ─────────────────────────────────────────────────────────────────────────────
// HSM ALERT VERIFICATION (added in 1.57)
// HSM has NO query API for active alerts — it only publishes hsmAlert location
// events, so a single missed event leaves the badge wrong forever. These
// helpers read the hub's own pages at refresh time to reconcile the badge:
//   1. If the HSM app ID is configured, GET the HSM app page
//      (/installedapp/configure/<id>) — it shows "ALERT!" in its heading and
//      lists the live alerts, e.g. "Custom Rule Alert: Door Locks unlocked".
//   2. Otherwise GET the Apps list (/installedapp/list) and look for the red
//      "ALERT!" suffix HSM appends to its own app label while alerting.
// Both are local (127.0.0.1) reads on Hub #1. If Hub Login Security is
// enabled these pages return a login page and verification reports
// "unavailable" — the badge then falls back to event-only behavior.
// ─────────────────────────────────────────────────────────────────────────────

// Plain-text page fetcher — same defensive body handling as fetchJson(), but
// with no JSON requirement (these are HTML pages).
private String fetchRawText(String uri, int timeoutSec) {
    String body = null
    httpGet([uri: uri, contentType: "text/plain", headers: ["Accept": "*/*"], timeout: timeoutSec]) { resp ->
        if (resp.status != 200) throw new Exception("HTTP ${resp.status}")
        def d = resp.data
        if (d == null) {
            body = ""
        } else if (d instanceof String) {
            body = d
        } else if (d instanceof Map || d instanceof List) {
            // Newer platform builds can deliver JSON responses already parsed
            // (see fetchJson). Callers of this helper parse text themselves,
            // so re-serialize to keep both old and new behavior working.
            body = groovy.json.JsonOutput.toJson(d)
        } else {
            try { body = d.text } catch (ignore) { body = d.toString() }
        }
    }
    return body ?: ""
}

private boolean looksLikeLoginPage(String body) {
    def lower = body.toLowerCase()
    return (lower.contains("login") || lower.contains("password")) && !lower.contains("safety monitor")
}

// Returns [status: "alerting" | "clear" | null, detail: String or null]
// status null means the state could not be determined (leave badge as-is).
// Hub #1 wrapper — reads the local hub over loopback.
private Map pollHsmAlertFromHub() {
    return pollHsmAlertFromHost("http://127.0.0.1:8080", settings["hsmAppId"], "Hub #1")
}

// Generic version (added in 1.58) — works against any hub base URL, so the
// same verification code path serves Hub #1 and remote Hubs #2/#3.
private Map pollHsmAlertFromHost(String base, def hsmId, String hubTag) {

    // ── Preferred source: the HSM app page itself ────────────────────────────
    if (hsmId) {
        try {
            String body = fetchRawText("${base}/installedapp/configure/${hsmId}", 8)
            if (body && !looksLikeLoginPage(body) && body.contains("Safety Monitor")) {
                // Live alert lines, e.g. "Custom Rule Alert: Door Locks unlocked",
                // "Intrusion Alert: Front Door open", "Water Alert: ..."
                def details = []
                def dm = (body =~ /((?:Intrusion|Smoke|Water|Gas|Carbon Monoxide|Custom Rule)[^:<>\r\n]{0,30}?Alert)\s*:\s*([^<\r\n]{1,120})/)
                while (dm.find()) {
                    details << "${dm.group(1).trim()}: ${dm.group(2).trim()}".toString()
                }
                // "ALERT!" in the page heading (case-sensitive on purpose so
                // lowercase JavaScript alert( calls can never false-positive)
                boolean headingAlert = (body =~ /(?s)Safety\s+Monitor.{0,160}?ALERT/).find()
                if (details || headingAlert) {
                    return [status: "alerting", detail: details ? details.unique().join("; ") : null]
                }
                return [status: "clear", detail: null]
            }
            if (enableLogging) log.debug "HSM verify (${hubTag}): app page for ID ${hsmId} unusable (login page or no HSM content) — trying Apps list"
        } catch (Exception e) {
            if (enableLogging) log.debug "HSM verify (${hubTag}): app page fetch failed — ${e.message}"
        }
    }

    // ── Fallback source: the Apps list (HSM label carries "ALERT!") ─────────
    // Note: "/hub2/appsList" is the hub UI's internal JSON endpoint name on
    // EVERY hub — it has nothing to do with this app's "Hub #2".
    def listUris = ["${base}/installedapp/list",
                    "${base}/hub2/appsList"]
    for (uri in listUris) {
        try {
            String body = fetchRawText(uri, 6)
            if (!body || looksLikeLoginPage(body)) continue
            if (!body.contains("Safety Monitor")) continue   // page didn't include app labels
            boolean alerting = (body =~ /(?s)Safety\s+Monitor.{0,160}?ALERT/).find()
            return [status: alerting ? "alerting" : "clear", detail: null]
        } catch (Exception e) {
            if (enableLogging) log.debug "HSM verify (${hubTag}): ${uri} failed — ${e.message}"
        }
    }
    return [status: null, detail: null]
}

// ─────────────────────────────────────────────────────────────────────────────
// REMOTE HSM STATUS (added in 1.58)
// Maker API exposes the HSM arm state at /apps/api/<id>/hsm when the HSM
// toggle is enabled inside that hub's Maker API app. Alerts are NOT exposed
// by Maker API, so remote alerts use pollHsmAlertFromHost() page scraping.
// ─────────────────────────────────────────────────────────────────────────────

// Returns the hsmStatus value string (e.g. "armedAway", "disarmed") or null.
private String fetchRemoteHsmStatus(String ip, String appId, String token) {
    try {
        def data = fetchJson("http://${ip}/apps/api/${appId}/hsm?access_token=${token}", 8)
        if (data instanceof Map) {
            def v = data.hsm ?: data.status ?: data.value
            return v ? v.toString() : null
        }
        if (data instanceof List && data && data[0] instanceof Map) {
            def v = data[0].hsm ?: data[0].status
            return v ? v.toString() : null
        }
        return data != null ? data.toString() : null
    } catch (Exception e) {
        if (enableLogging) log.warn "Remote HSM status fetch failed for ${ip}: ${e.message}"
        return null
    }
}

// Reconcile the latched alert state with what the hub actually shows.
// Runs at the start of every report refresh when verification is enabled.
private void reconcileHsmAlertWithHub() {
    def poll = pollHsmAlertFromHub()
    state.hsmAlertPollResult = poll.status ?: "unavailable"

    if (poll.status == "clear") {
        if (state.hsmActiveAlert) {
            log.info "HSM verify: hub shows no active alert — clearing stale '${state.hsmActiveAlert}' alert display"
            clearHsmActiveAlert()
        }
    } else if (poll.status == "alerting") {
        if (hsmCancelInGrace(1)) {
            // A cancelAlerts was sent moments ago and HSM may not have updated
            // its pages yet. Do not re-latch during the grace window; the note
            // under the badge asks the user to refresh to confirm (1.79).
            if (enableLogging) log.debug "HSM verify: alert still shown within ${HSM_CANCEL_GRACE_SECS}s of a cancel — treating as pending"
            return
        }
        if (!state.hsmActiveAlert) {
            // Missed hsmAlert event (or HSM still alerting after a cancel) — latch a detected alert now.
            if (hsmCancelSentMs(1) != null) {
                log.warn "HSM verify: hub still shows an active alert after Cancel HSM Alerts was sent — the alert condition is probably still true; displaying it again"
            } else {
                log.warn "HSM verify: hub shows an active alert but no hsmAlert event was captured — displaying it now"
            }
            state.hsmActiveAlert     = "detected"
            state.hsmActiveAlertRule = poll.detail ?: ""
            state.hsmActiveAlertAt   = new Date().format("yyyy-MM-dd hh:mm a", location.timeZone)
        } else if (poll.detail && !state.hsmActiveAlertRule) {
            // Event-latched alert without detail — enrich it from the HSM page.
            state.hsmActiveAlertRule = poll.detail
        }
    }
    // status null → could not verify; leave event-latched state untouched.
}

// ─────────────────────────────────────────────────────────────────────────────
// HSM CANCEL ALERTS (added in 1.79)
// The "Clear HSM Alert Display" button only resets this app's copy of the
// alert, and with verification on a still-active alert is re-detected on the
// same render. These helpers let the page ask HSM itself to cancel:
//   • Hub #1 — documented HSM interface: sendLocationEvent hsmSetArm
//     "cancelAlerts" ("stop all in progress alerts"; lower-case 'c').
//   • Hubs #2/#3 — Maker API GET /apps/api/<id>/hsm/cancelAlerts (requires
//     the HSM toggle enabled in that hub's Maker API app).
// Cancelling does NOT disarm HSM; if the alert condition is still true HSM
// can re-alert immediately, and the result note under the badge says so.
// Every cancel needs a confirmation click (expires after
// HSM_CANCEL_CONFIRM_SECS) because it also silences sirens.
// ─────────────────────────────────────────────────────────────────────────────

private String hsmHubLabel(int hubNum) {
    return (hubNum == 1)
        ? (settings["hub1Label"] ?: (location.name ?: "Hub 1")).toString()
        : (settings["hub${hubNum}Label"] ?: "Hub ${hubNum}").toString()
}

// True when a remote hub has everything needed to send /hsm/cancelAlerts.
private boolean remoteHsmCancelConfigured(int hubNum) {
    if (!(hubNum in [2, 3])) return false
    if (!settings["hub${hubNum}Enabled"]) return false
    if (!safeString(settings["hub${hubNum}Ip"]).trim())    return false
    if (!safeString(settings["hub${hubNum}AppId"]).trim()) return false
    if (!safeString(settings["hub${hubNum}Token"]).trim()) return false
    return true
}

// True when this refresh's Hub #1 alert line was confirmed against the hub's
// own pages (so a display-only "Clear" would just be re-detected).
private boolean hub1HsmAlertVerified() {
    return settings["showHsmStatus"] != false &&
           settings["hsmVerifyAlert"] != false &&
           (state.hsmAlertPollResult?.toString() in ["alerting", "clear"])
}

// The pending (not yet confirmed) cancel request, or null. Expired requests
// are discarded so a stale confirmation can never fire later.
private Map activeHsmCancelPending() {
    def p = state.hsmCancelPending
    if (!(p instanceof Map)) return null
    Long at = 0L
    try { at = (p.at ?: 0L) as Long } catch (ignored) { at = 0L }
    if (!at || (now() - at) > HSM_CANCEL_CONFIRM_SECS * 1000L) {
        state.remove("hsmCancelPending")
        return null
    }
    return p as Map
}

private Long hsmCancelSentMs(int hubNum) {
    def v = state["hsmCancelSentAt${hubNum}".toString()]
    if (v == null) return null
    try { return v as Long } catch (ignored) { return null }
}

private boolean hsmCancelInGrace(int hubNum) {
    Long sent = hsmCancelSentMs(hubNum)
    return sent != null && (now() - sent) < HSM_CANCEL_GRACE_SECS * 1000L
}

private void clearHsmCancelTracking(int hubNum) {
    state.remove("hsmCancelSentAt${hubNum}".toString())
    state.remove("hsmCancelSentStr${hubNum}".toString())
}

private void cancelHsmAlerts(int hubNum) {
    String label = hsmHubLabel(hubNum)
    state.remove("hsmCancelError${hubNum}".toString())
    try {
        if (hubNum == 1) {
            sendLocationEvent(name: "hsmSetArm", value: "cancelAlerts")
            // HSM answers with its own hsmAlert "cancel" event, which
            // hsmAlertHandler also handles. Clearing here as well keeps the
            // render that immediately follows this click consistent.
            clearHsmActiveAlert()
        } else {
            if (!remoteHsmCancelConfigured(hubNum)) {
                throw new Exception("hub is not enabled or its Maker API IP / app ID / token is missing")
            }
            sendRemoteHsmCancel(hubNum)   // throws on any failure
        }
        state["hsmCancelSentAt${hubNum}".toString()]  = now()
        state["hsmCancelSentStr${hubNum}".toString()] = new Date().format("hh:mm:ss a", location.timeZone)
        log.info "HSM: cancelAlerts sent to ${label} (requested from the ${getAppDisplayName()} page)"
    } catch (Exception e) {
        clearHsmCancelTracking(hubNum)
        state["hsmCancelError${hubNum}".toString()] = (e.message ?: "unknown error").toString()
        log.warn "HSM: cancelAlerts request to ${label} failed — ${e.message}"
    }
}

// Maker API relay for Hubs #2/#3. Parameters go in the query: map rather than
// an embedded ?access_token= (see fetchJson for the platform 2.5.1 reason).
private void sendRemoteHsmCancel(int hubNum) {
    String ip    = safeString(settings["hub${hubNum}Ip"]).trim()
    String appId = safeString(settings["hub${hubNum}AppId"]).trim()
    String token = safeString(settings["hub${hubNum}Token"]).trim()
    String uri   = "http://${ip}/apps/api/${appId}/hsm/cancelAlerts".toString()

    httpGet([uri: uri, query: [access_token: token], contentType: "text/plain",
             headers: ["Accept": "*/*"], timeout: 10]) { resp ->
        if (resp.status != 200) throw new Exception("Maker API returned HTTP ${resp.status}")
        def d = resp.data
        String body
        if (d == null) {
            body = ""
        } else if (d instanceof String) {
            body = d
        } else if (d instanceof Map || d instanceof List) {
            body = groovy.json.JsonOutput.toJson(d)
        } else {
            try { body = d.text } catch (ignore) { body = d.toString() }
        }
        if (body && looksLikeLoginPage(body)) {
            throw new Exception("hub returned a login page instead of a Maker API response — check the Maker API app ID and token")
        }
        if (enableLogging) log.debug "HSM cancel (${hsmHubLabel(hubNum)}): Maker API replied ${jsonErrSnippet(body ?: '(empty)')}"
    }
}

// Result note shown under a hub's HSM badge after a cancel was sent (or
// failed). pollStatus is that refresh's verification result for the hub:
// "alerting", "clear", or null/"unavailable" when it could not be verified.
private String hsmCancelNote(int hubNum, String pollStatus) {
    String html = ""
    String err = state["hsmCancelError${hubNum}".toString()]?.toString()
    if (err) {
        String hint = (hubNum == 1) ? "" :
            " Check that ${htmlEscape(hsmHubLabel(hubNum))} is reachable and that the <b>HSM</b> toggle is enabled in its Maker API app."
        html += "<p style='margin:0 0 8px 0;'><small style='color:#cc0000;'>" +
                "<b>⚠ Cancel HSM Alerts failed: ${htmlEscape(err)}.</b>${hint}</small></p>"
        state.remove("hsmCancelError${hubNum}".toString())
    }

    Long sent = hsmCancelSentMs(hubNum)
    if (sent == null) return html
    long ageMs = (now() - sent) as long
    if (ageMs > HSM_CANCEL_TRACK_SECS * 1000L) {
        clearHsmCancelTracking(hubNum)
        return html
    }

    String sentStr = htmlEscape(state["hsmCancelSentStr${hubNum}".toString()] ?: "")
    String color
    String msg
    if (pollStatus == "clear") {
        color = "#1a7a1a"
        msg   = "✓ Cancel sent at ${sentStr} — HSM now reports no active alert."
        clearHsmCancelTracking(hubNum)
    } else if (pollStatus == "alerting") {
        if (ageMs < HSM_CANCEL_GRACE_SECS * 1000L) {
            color = "#b36b00"
            msg   = "Cancel sent at ${sentStr}. HSM had not finished processing it when this page was built — " +
                    "click <b>Refresh Table</b> in a few seconds to confirm."
        } else {
            color = "#cc0000"
            msg   = "Cancel sent at ${sentStr}, but HSM is reporting an active alert again. The alert condition is " +
                    "probably still true (for example a door still unlocked or a sensor still wet), so HSM re-alerted. " +
                    "Fix the condition, or disarm the affected monitoring in HSM. If the condition is definitely " +
                    "cleared, cancel the alert directly in the HSM app on that hub."
        }
    } else {
        color = "#888"
        msg   = "Cancel sent to HSM at ${sentStr}. The result could not be confirmed here (alert verification is off " +
                "or unavailable) — check HSM directly."
        clearHsmCancelTracking(hubNum)
    }
    html += "<p style='margin:0 0 8px 0;'><small style='color:${color};'>${msg}</small></p>"
    return html
}

// Buttons rendered next to Refresh Table, AFTER the report has been built so
// they reflect this refresh's verification result.
private void renderHsmAlertButtons() {
    Map pending = activeHsmCancelPending()
    if (pending) {
        int hn     = (pending.hub ?: 1) as int
        String lbl = hsmHubLabel(hn)
        paragraph("<div style='border:2px solid #cc0000;background:#fff0f0;border-radius:6px;padding:8px 10px;margin:6px 0;'>" +
                  "<b>⚠ Cancel the active HSM alert(s) on ${htmlEscape(lbl)}?</b><br>" +
                  "<small>This tells HSM to stop all in-progress alerts on that hub, including sirens and light alerts. " +
                  "It does <b>not</b> disarm HSM — if the alert condition is still true, HSM may alert again. " +
                  "This confirmation expires after ${HSM_CANCEL_CONFIRM_SECS} seconds.</small></div>")
        input "btnConfirmCancelHsm", "button", title: "Yes — Cancel HSM Alerts on ${lbl}", width: 6
        input "btnAbortCancelHsm",   "button", title: "No — Leave Alerts Active", width: 6
        return
    }

    if (settings["showHsmStatus"] != false) {
        if (state.hsmActiveAlert) {
            input "btnCancelHsmHub1", "button", title: "Cancel HSM Alerts on ${hsmHubLabel(1)}"
        }
        [2, 3].each { int rn ->
            if (state["hsmRemoteAlertingHub${rn}".toString()] && remoteHsmCancelConfigured(rn)) {
                input "btnCancelHsmHub${rn}", "button", title: "Cancel HSM Alerts on ${hsmHubLabel(rn)}"
            }
        }
    }

    // Display-only reset. Useful only when the alert state could NOT be
    // verified against the hub (verification off/unavailable, e.g. a missed
    // cancel event): a verified alert is re-detected on the very same render,
    // which made this button look broken before 1.79.
    if (state.hsmActiveAlert && !hub1HsmAlertVerified()) {
        input "btnClearHsmAlert", "button", title: "Clear HSM Alert Display"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// HUBITAT SAFETY MONITOR (HSM) STATUS BADGE
// ─────────────────────────────────────────────────────────────────────────────

// hsmStatus values per Hubitat official docs (intrusion arm state only).
// Note: smoke/water rules arm via hsmRules (armAll), NOT hsmStatus — so
// hsmStatus can read "disarmed" even while smoke/water monitoring is active.
private Map hsmStatusDisplayMap() {
    return [
        "armedAway"   : [label: "Armed Away",         color: "#cc0000", icon: "&#x1F534;"],
        "armingAway"  : [label: "Arming Away\u2026",  color: "#cc6600", icon: "&#x23F3;"],
        "armedHome"   : [label: "Armed Home",          color: "#cc6600", icon: "&#x1F7E0;"],
        "armingHome"  : [label: "Arming Home\u2026",   color: "#cc6600", icon: "&#x23F3;"],
        "armedNight"  : [label: "Armed Night",         color: "#8800cc", icon: "&#x1F7E3;"],
        "armingNight" : [label: "Arming Night\u2026",  color: "#8800cc", icon: "&#x23F3;"],
        "disarmed"    : [label: "Intrusion Disarmed",     color: "#1a7a1a", icon: "&#x1F7E2;"],
        "allDisarmed" : [label: "All Monitoring Disarmed", color: "#1a7a1a", icon: "&#x1F7E2;"],
    ]
}

// hsmStatus "disarmed" means only INTRUSION is disarmed — smoke, water and
// custom monitoring rules stay armed until All Monitoring is disarmed.
private String hsmDisarmedNote(String hsmState) {
    return (hsmState == "disarmed")
        ? "&nbsp;<small style='color:#888;font-weight:normal;'>(smoke / water / custom monitoring remains armed unless All Monitoring is disarmed)</small>"
        : ""
}

// Suffix appended to "HSM Status" / "HSM Alert" labels. Hub names are shown
// on every line as soon as more than one hub's HSM is being displayed.
private String hsmHubTag(String hubLabel) {
    def anyRemote = settings["showHsmStatusHub2"] || settings["showHsmStatusHub3"]
    return anyRemote ? " — ${htmlEscape(hubLabel)}" : ""
}

private String buildHsmStatusBadge() {
    def hsmState = location.hsmStatus?.toString()
    if (!hsmState || hsmState == "null") {
        return "<p style='margin:4px 0;'><b>HSM:</b> <span style='color:#888;'>Not available</span></p>"
    }
    def hub1LabelVal = settings["hub1Label"] ?: (location.name ?: "Hub 1")
    def hubTag = hsmHubTag(hub1LabelVal)
    def info  = hsmStatusDisplayMap()[hsmState]
    def label = info?.label ?: hsmState
    def color = info?.color ?: "#555555"
    def icon  = info?.icon  ?: "&#x1F512;"
    def statusNote = hsmDisarmedNote(hsmState)
    def html = "<p style='margin:6px 0 4px 0;font-size:1.05em;'>" +
               "<b>HSM Status${hubTag}:</b>&nbsp;" +
               "<span style='color:${color};font-weight:bold;'>${icon} ${htmlEscape(label)}</span>" +
               statusNote +
               "</p>"

    // Active alert — set by hsmAlertHandler when hsmAlert fires, cleared on
    // cancel / cancelRuleAlerts or via the "Clear HSM Alert Display" button.
    def activeAlert = state.hsmActiveAlert?.toString()
    // Defensive: purge values a pre-1.56 handler may have latched by mistake.
    if (activeAlert in ["cancel", "cancelRuleAlerts", "arming", "armingHome", "armingNight"]) {
        clearHsmActiveAlert()
        activeAlert = null
    }
    if (activeAlert) {
        def alertDisplayMap = [
            "intrusion"      : [label: "INTRUSION (Away)",  color: "#cc0000"],
            "intrusion-home" : [label: "INTRUSION (Home)",  color: "#cc0000"],
            "intrusion-night": [label: "INTRUSION (Night)", color: "#cc0000"],
            "smoke"          : [label: "SMOKE",             color: "#cc0000"],
            "water"          : [label: "WATER LEAK",        color: "#0055cc"],
            "rule"           : [label: "CUSTOM RULE",       color: "#cc6600"],
            "detected"       : [label: "ACTIVE ALERT",      color: "#cc0000"],
        ]
        def aInfo  = alertDisplayMap[activeAlert]
        def aLabel = aInfo?.label ?: activeAlert
        def aColor = aInfo?.color ?: "#cc0000"
        def ruleName = state.hsmActiveAlertRule?.toString() ?: ""
        if (ruleName) aLabel += " — ${ruleName}"
        def sinceStr = state.hsmActiveAlertAt
            ? ((activeAlert == "detected" ? " detected " : " since ") + state.hsmActiveAlertAt)
            : ""
        html += "<p style='margin:2px 0 10px 0;font-size:1.05em;'>" +
                "<b>HSM Alert${hubTag}:</b>&nbsp;" +
                "<span style='color:${aColor};font-weight:bold;animation:dsm-hsm-blink 0.8s step-start infinite;'>" +
                "&#x1F6A8; ${htmlEscape(aLabel)}" +
                "</span>" +
                "&nbsp;<small style='color:#888;font-weight:normal;'>(${htmlEscape(sinceStr ? sinceStr.trim() + ' — ' : '')}clears when HSM cancels the alert; use the Cancel HSM Alerts button above to cancel it from here)</small>" +
                "</p>" +
                "<style>@keyframes dsm-hsm-blink{50%{opacity:0}}</style>"
    } else if (settings["hsmVerifyAlert"] != false && hsmCancelInGrace(1) &&
               state.hsmAlertPollResult?.toString() == "alerting") {
        // Cancel just sent; the hub's pages still showed ALERT a moment ago.
        html += "<p style='margin:2px 0 10px 0;font-size:1.05em;'>" +
                "<b>HSM Alert${hubTag}:</b>&nbsp;" +
                "<span style='color:#b36b00;font-weight:bold;'>&#x23F3; Cancel sent — waiting for HSM to confirm</span>" +
                "</p>"
    } else {
        html += "<p style='margin:2px 0 10px 0;font-size:1.05em;'>" +
                "<b>HSM Alert${hubTag}:</b>&nbsp;" +
                "<span style='color:#1a7a1a;font-weight:bold;'>No current alert</span>" +
                "</p>"
    }

    // Result of a "Cancel HSM Alerts" click, if one is being tracked.
    html += hsmCancelNote(1, settings["hsmVerifyAlert"] != false ? state.hsmAlertPollResult?.toString() : null)

    // Verification footnote — says whether the alert line above was checked
    // against the hub on this refresh, or is running on events alone.
    if (settings["hsmVerifyAlert"] != false) {
        def pr = state.hsmAlertPollResult?.toString()
        if (pr == "unavailable") {
            html += "<p style='margin:0 0 8px 0;'><small style='color:#b36b00;'>" +
                    "⚠ Alert verification unavailable — could not read the hub's Apps / HSM pages " +
                    "(Hub Login Security may be enabled on Hub #1). Falling back to hsmAlert events only." +
                    "</small></p>"
        } else if (pr in ["alerting", "clear"]) {
            def srcNote = settings["hsmAppId"] ? "HSM app page" : "hub Apps list"
            html += "<p style='margin:0 0 8px 0;'><small style='color:#888;'>" +
                    "Alert state verified against the ${srcNote} at refresh." +
                    "</small></p>"
        }
    }
    return html
}

// ─────────────────────────────────────────────────────────────────────────────
// REMOTE HSM STATUS BADGE (Hubs #2 / #3, added in 1.58)
// Status via the hub's Maker API /hsm endpoint; alert via the same page
// verification used for Hub #1, pointed at the remote IP. Location events do
// not cross hubs, so remote alert state is poll-only (refresh-time).
// ─────────────────────────────────────────────────────────────────────────────

private String buildRemoteHsmStatusBadge(int hubNum) {
    if (!settings["hub${hubNum}Enabled"]) return ""
    def hubLabel = settings["hub${hubNum}Label"] ?: "Hub ${hubNum}"
    def ip       = settings["hub${hubNum}Ip"]
    def appId    = settings["hub${hubNum}AppId"]
    def token    = settings["hub${hubNum}Token"]
    def hubTag   = " — ${htmlEscape(hubLabel)}"
    def html     = ""

    // ── Status (Maker API /hsm endpoint) ─────────────────────────────────────
    def hsmState = (ip && appId && token) ? fetchRemoteHsmStatus(ip, appId, token) : null
    if (hsmState) {
        def info  = hsmStatusDisplayMap()[hsmState]
        def label = info?.label ?: hsmState
        def color = info?.color ?: "#555555"
        def icon  = info?.icon  ?: "&#x1F512;"
        html += "<p style='margin:6px 0 4px 0;font-size:1.05em;'>" +
                "<b>HSM Status${hubTag}:</b>&nbsp;" +
                "<span style='color:${color};font-weight:bold;'>${icon} ${htmlEscape(label)}</span>" +
                hsmDisarmedNote(hsmState) +
                "</p>"
    } else {
        html += "<p style='margin:6px 0 4px 0;font-size:1.05em;'>" +
                "<b>HSM Status${hubTag}:</b>&nbsp;" +
                "<span style='color:#888;'>Unavailable</span>" +
                "&nbsp;<small style='color:#b36b00;'>(is the <b>HSM</b> toggle enabled in ${htmlEscape(hubLabel)}'s Maker API app? Is the hub reachable?)</small>" +
                "</p>"
    }

    // ── Alert (page verification against the remote hub) ────────────────────
    def poll = pollHsmAlertFromHost("http://${ip}", settings["hub${hubNum}HsmAppId"], hubLabel)
    boolean cancelPending = (poll.status == "alerting") && hsmCancelInGrace(hubNum)
    // Read by renderHsmAlertButtons() to decide whether to offer Cancel.
    state["hsmRemoteAlertingHub${hubNum}".toString()] = (poll.status == "alerting") && !cancelPending
    if (cancelPending) {
        html += "<p style='margin:2px 0 10px 0;font-size:1.05em;'>" +
                "<b>HSM Alert${hubTag}:</b>&nbsp;" +
                "<span style='color:#b36b00;font-weight:bold;'>&#x23F3; Cancel sent — waiting for HSM to confirm</span>" +
                "</p>"
    } else if (poll.status == "alerting") {
        def aLabel = "ACTIVE ALERT" + (poll.detail ? " — ${poll.detail}" : "")
        def cancelHint = remoteHsmCancelConfigured(hubNum) ? "; use the Cancel HSM Alerts button above to cancel it" : ""
        html += "<p style='margin:2px 0 10px 0;font-size:1.05em;'>" +
                "<b>HSM Alert${hubTag}:</b>&nbsp;" +
                "<span style='color:#cc0000;font-weight:bold;animation:dsm-hsm-blink 0.8s step-start infinite;'>" +
                "&#x1F6A8; ${htmlEscape(aLabel)}" +
                "</span>" +
                "&nbsp;<small style='color:#888;font-weight:normal;'>(poll-only — updates on each refresh${cancelHint})</small>" +
                "</p>" +
                "<style>@keyframes dsm-hsm-blink{50%{opacity:0}}</style>"
    } else if (poll.status == "clear") {
        html += "<p style='margin:2px 0 10px 0;font-size:1.05em;'>" +
                "<b>HSM Alert${hubTag}:</b>&nbsp;" +
                "<span style='color:#1a7a1a;font-weight:bold;'>No current alert</span>" +
                "</p>"
    } else {
        html += "<p style='margin:2px 0 10px 0;font-size:1.05em;'>" +
                "<b>HSM Alert${hubTag}:</b>&nbsp;" +
                "<span style='color:#888;'>Not verified</span>" +
                "&nbsp;<small style='color:#b36b00;'>(could not read ${htmlEscape(hubLabel)}'s Apps / HSM pages — Hub Login Security may be enabled there)</small>" +
                "</p>"
    }

    // Result of a "Cancel HSM Alerts" click for this hub, if one is tracked.
    html += hsmCancelNote(hubNum, poll.status?.toString())
    return html
}

// ─────────────────────────────────────────────────────────────────────────────
// REPORT TABLE GENERATION
// ─────────────────────────────────────────────────────────────────────────────

private Map generateReportTables() {
    def t0          = new Date().time
    def data        = collectAllDeviceStates()
    def collectMs   = new Date().time - t0
    def onPool      = data.onPool
    def offPool     = data.offPool
    def lockPool    = data.lockPool
    def contactPool = data.contactPool
    def healthPool  = data.healthPool
    def warnings    = data.warnings
    def showUnknown = settings["showUnknownTable"] != false
    def showLock    = settings["showLockTable"]    != false
    def showContact = settings["showContactTable"] != false
    def showHealth  = settings["showHealthTable"]  != false

    def html = ""
    if (warnings) warnings.each { w -> html += "<p style='color:red;font-weight:bold;'>⚠ ${w}</p>" }

    // ── Hubitat Safety Monitor (HSM) status ──────────────────────────────────
    // Remote "alerting" flags are rebuilt on every render by
    // buildRemoteHsmStatusBadge(); reset them first so a hub whose badge is no
    // longer shown can never keep a stale Cancel button.
    state.hsmRemoteAlertingHub2 = false
    state.hsmRemoteAlertingHub3 = false
    if (settings["showHsmStatus"] != false) {
        // Reconcile the latched alert with the hub's own pages first, so the
        // badge below reflects reality even if an hsmAlert event was missed.
        if (settings["hsmVerifyAlert"] != false) reconcileHsmAlertWithHub()
        html += buildHsmStatusBadge()
        [2, 3].each { rn ->
            if (settings["showHsmStatusHub${rn}"]) html += buildRemoteHsmStatusBadge(rn)
        }
    }

    // Devices selected for BOTH the ON-monitor list and the OFF-monitor list
    def onPoolLinks  = onPool.collect  { it.linkUrl } as Set
    def offPoolLinks = offPool.collect { it.linkUrl } as Set
    def bothLinks    = onPoolLinks.intersect(offPoolLinks)

    // ON table
    html += buildTable(onPool.findAll { it.switchVal == "on" },
        "<br><br><b>Devices that are ON</b>", "table_on", "#cc0000", "ON", "color:red;font-weight:bold;",
        settings["sortByOn"] ?: "displayName", settings["sortOrderOn"] ?: "asc", "off",
        bothLinks, "Also monitored for OFF state")

    // OFF table
    html += "<br>"
    html += buildTable(offPool.findAll { it.switchVal == "off" },
        "<b>Devices that are OFF</b>", "table_off", "#1a7a1a", "OFF", "color:#444;font-weight:bold;",
        settings["sortByOff"] ?: "displayName", settings["sortOrderOff"] ?: "asc", "on",
        bothLinks, "Also monitored for ON state")

    // Unknown State table
    if (showUnknown) {
        def seen   = [] as Set
        def unkAll = []
        (onPool + offPool).findAll { it.switchVal != "on" && it.switchVal != "off" }.each { d ->
            if (seen.add(d.linkUrl)) unkAll << d
        }
        html += "<br>"
        html += buildTable(unkAll,
            "<b>Devices with Unknown State</b>", "table_unk", "#888888", "Unknown", "color:#888;font-weight:bold;",
            settings["sortByUnk"] ?: "displayName", settings["sortOrderUnk"] ?: "asc", "both")
    }

    // Lock State table
    if (showLock) {
        html += "<br>"
        html += buildLockTable(lockPool,
            "<b>Lock State</b>", "table_lock", "#2e5fa3",
            settings["sortByLock"]    ?: "displayName",
            settings["sortOrderLock"] ?: "asc")
    }

    // Contact Sensor table
    if (showContact) {
        html += "<br>"
        html += buildContactTable(contactPool,
            "table_contact", "#7a4fa3",
            settings["sortByContact"]    ?: "displayName",
            settings["sortOrderContact"] ?: "asc",
            settings["contactOpenOnly"] != false)
    }

    // Health / Activity Monitor table
    if (showHealth) {
        html += "<br>"
        html += buildHealthTable(healthPool,
            "<b>Health / Activity Monitor</b>", "table_health", "#CC6600",
            settings["sortByHealth"]    ?: "displayName",
            settings["sortOrderHealth"] ?: "asc",
            (settings["activityThresholdHours"] ?: 24) as long)
    }

    // Rules with Private Boolean FALSE table — intentionally after Health / Activity.
    if (anyPbFalseHubShown()) {
        html += "<br>"
        html += buildPbFalseTable(
            settings["sortByPbFalse"]    ?: "name",
            settings["sortOrderPbFalse"] ?: "asc")
    }

    return [html: html, collectMs: collectMs]
}

// ─────────────────────────────────────────────────────────────────────────────
// SWITCH STATE TABLE BUILDER
// ─────────────────────────────────────────────────────────────────────────────

private String buildTable(List devices, String title, String tableId,
                           String headerColor, String stateLabel, String stateStyle,
                           String sortBy, String sortOrder, String toggleCmd,
                           Set bothLinks = [], String bothTooltip = "") {
    def count      = devices.size()
    def sortColIdx = (sortBy == "room") ? 1 : (sortBy == "hub") ? 2 : 0
    def sortClass  = (sortOrder == "desc") ? "sort-desc" : "sort-asc"

    devices = devices.sort { it ->
        switch (sortBy) {
            case "room": return it.room?.toLowerCase() ?: ""
            case "hub":  return it.hub?.toLowerCase()  ?: ""
            default:     return it.displayName?.toLowerCase() ?: ""
        }
    }
    if (sortOrder == "desc") devices = devices.reverse()

    def countStr = (count > 0) ? "${count} device${count == 1 ? '' : 's'}" : "No devices"
    def html  = "<h4 style='margin-bottom:4px;'>${title}: ${countStr}.</h4><br>"

    if (count > 0) {
        def isUnknown  = (toggleCmd == "both")
        def stateW     = isUnknown ? "125px" : "95px"
        html += "<div class='dsm-scroll-wrap'>"
        html += "<table id='${tableId}' class='on-table' cellpadding='0' cellspacing='0' style='--hdr-bg:${headerColor};table-layout:fixed;width:100%;min-width:300px;'>"
        html += "<colgroup><col><col class='dsm-col-room col-room'><col class='col-hub'><col style='width:${stateW}'></colgroup>"
        html += "<thead><tr>"
        html += "<th onclick='sortOnTable(\"${tableId}\",0)' class='${sortColIdx == 0 ? sortClass : ""}'>Device Name</th>"
        html += "<th onclick='sortOnTable(\"${tableId}\",1)' class='dsm-col-room ${sortColIdx == 1 ? sortClass : ""}'>Room</th>"
        html += "<th onclick='sortOnTable(\"${tableId}\",2)' class='dsm-col-hub ${sortColIdx == 2 ? sortClass : ""}'>Hub</th>"
        html += "<th style='cursor:default;'>State</th>"
        html += "</tr></thead><tbody>"
        devices.each { it ->
            html += "<tr>"
            def inBoth = bothLinks?.contains(it.linkUrl)
            def nameInner = inBoth
                ? ("<span style='color:#cc6600;' title='${bothTooltip}'>${it.displayName}</span>"
                   + "&nbsp;<small style='color:#cc6600;font-size:0.8em;'>&#x2605;</small>")
                : it.displayName
            html += "<td><a href='${it.linkUrl}' target='_blank'>${nameInner}</a></td>"
            html += "<td class='dsm-col-room'>${it.room}</td>"
            html += "<td class='dsm-col-hub hub-col'>${it.hub}</td>"
            html += buildStateCell(it, toggleCmd, stateLabel, stateStyle)
            html += "</tr>"
        }
        html += "</tbody></table></div>"
    }
    return html
}

// ─────────────────────────────────────────────────────────────────────────────
// LOCK STATE TABLE BUILDER
// ─────────────────────────────────────────────────────────────────────────────

private String buildLockTable(List devices, String title, String tableId, String headerColor,
                              String sortBy, String sortOrder) {
    def count = devices.size()

    def sortColIdx = 0
    switch (sortBy) {
        case "room":    sortColIdx = 1; break
        case "hub":     sortColIdx = 2; break
        case "lockVal": sortColIdx = 3; break
        case "battery": sortColIdx = 4; break
        default:        sortColIdx = 0; break
    }
    def sortClass = (sortOrder == "desc") ? "sort-desc" : "sort-asc"

    devices = devices.sort { it ->
        switch (sortBy) {
            case "room":    return it.room?.toLowerCase()        ?: ""
            case "hub":     return it.hub?.toLowerCase()         ?: ""
            case "lockVal": return it.lockVal?.toLowerCase()     ?: ""
            case "battery":
                try { return (it.battery?.toString() == "n/a" ? -1 : it.battery?.toString()?.toInteger() ?: -1) } catch (ignored) { return -1 }
            default:        return it.displayName?.toLowerCase() ?: ""
        }
    }
    if (sortOrder == "desc") devices = devices.reverse()

    def countStr = (count > 0) ? "${count} device${count == 1 ? '' : 's'}" : "No devices"
    def html = "<h4 style='margin-bottom:4px;'>${title}: ${countStr}.</h4><br>"

    if (count > 0) {
        html += "<div class='dsm-scroll-wrap'>"
        html += "<table id='${tableId}' class='on-table' cellpadding='0' cellspacing='0' " +
                "style='--hdr-bg:${headerColor};table-layout:fixed;width:100%;min-width:300px;'>"
        html += "<colgroup><col><col class='dsm-col-room col-room'><col class='col-hub'><col style='width:110px'><col style='width:90px'></colgroup>"
        html += "<thead><tr>"
        html += "<th onclick='sortOnTable(\"${tableId}\",0)' class='${sortColIdx == 0 ? sortClass : ""}'>Lock Name</th>"
        html += "<th onclick='sortOnTable(\"${tableId}\",1)' class='dsm-col-room ${sortColIdx == 1 ? sortClass : ""}'>Room</th>"
        html += "<th onclick='sortOnTable(\"${tableId}\",2)' class='dsm-col-hub ${sortColIdx == 2 ? sortClass : ""}'>Hub</th>"
        html += "<th onclick='sortOnTable(\"${tableId}\",3)' class='${sortColIdx == 3 ? sortClass : ""}'>State</th>"
        html += "<th onclick='sortOnTable(\"${tableId}\",4)' class='${sortColIdx == 4 ? sortClass : ""}'>Battery %</th>"
        html += "</tr></thead><tbody>"

        devices.each { it ->
            def lv = it.lockVal?.toLowerCase() ?: "unknown"
            def lockColor = (lv == "locked")   ? "color:green;font-weight:bold;" :
                            (lv == "unlocked") ? "color:red;font-weight:bold;"   :
                                                 "color:#888;"
            def battStr = it.battery?.toString() ?: "n/a"
            def battColor = ""
            if (battStr != "n/a") {
                try {
                    def battInt = battStr.toInteger()
                    battColor = battInt < 20 ? "color:red;" : battInt < 40 ? "color:darkorange;" : "color:green;"
                } catch (ignored) {}
            }
            html += "<tr>"
            html += "<td><a href='${it.linkUrl}' target='_blank'>${it.displayName}</a></td>"
            html += "<td class='dsm-col-room'>${it.room}</td>"
            html += "<td class='dsm-col-hub hub-col'>${it.hub}</td>"
            html += "<td class='state-col' style='${lockColor}'>${lv}</td>"
            html += "<td style='text-align:center;${battColor}'>${battStr}</td>"
            html += "</tr>"
        }
        html += "</tbody></table></div>"
    }
    return html
}

// ─────────────────────────────────────────────────────────────────────────────
// CONTACT SENSOR TABLE BUILDER
// In open-only mode (default) the table lists just the sensors currently
// reporting "open"; the heading still shows how many are monitored in total.
// ─────────────────────────────────────────────────────────────────────────────

private String buildContactTable(List devices, String tableId, String headerColor,
                                 String sortBy, String sortOrder, boolean openOnly) {
    // "active" (tilt sensors reporting acceleration only) is flagged like "open".
    def flaggedVals = ["open", "active"] as Set
    def monitored = devices.size()
    def openCount = devices.count { flaggedVals.contains(it.contactVal ?: "") }
    def shown     = openOnly ? devices.findAll { flaggedVals.contains(it.contactVal ?: "") } : devices
    def count     = shown.size()
    def openWord  = devices.any { it.contactSource == "acceleration" } ? "open/active" : "open"

    def sortColIdx = 0
    switch (sortBy) {
        case "room":       sortColIdx = 1; break
        case "hub":        sortColIdx = 2; break
        case "contactVal": sortColIdx = 3; break
        case "battery":    sortColIdx = 4; break
        default:           sortColIdx = 0; break
    }
    def sortClass = (sortOrder == "desc") ? "sort-desc" : "sort-asc"

    shown = shown.sort { it ->
        switch (sortBy) {
            case "room":       return it.room?.toLowerCase()       ?: ""
            case "hub":        return it.hub?.toLowerCase()        ?: ""
            case "contactVal": return it.contactVal?.toLowerCase() ?: ""
            case "battery":
                try { return (it.battery?.toString() == "n/a" ? -1 : it.battery?.toString()?.toInteger() ?: -1) } catch (ignored) { return -1 }
            default:           return it.displayName?.toLowerCase() ?: ""
        }
    }
    if (sortOrder == "desc") shown = shown.reverse()

    def titleStr
    if (openOnly) {
        titleStr = (openCount > 0)
            ? "<b>Open Contact Sensors</b>: <span style='color:#cc0000;'>${openCount} ${openWord}</span> of ${monitored} monitored."
            : "<b>Open Contact Sensors</b>: <span style='color:#1a7a1a;'>None ${openWord}</span> (${monitored} monitored)."
    } else {
        def openNote = openCount > 0 ? " <span style='color:#cc0000;'>(${openCount} ${openWord})</span>" : ""
        titleStr = "<b>Contact Sensor State</b>: ${monitored} device${monitored == 1 ? '' : 's'}${openNote}."
    }
    def html = "<h4 style='margin-bottom:4px;'>${titleStr}</h4><br>"

    if (count > 0) {
        html += "<div class='dsm-scroll-wrap'>"
        html += "<table id='${tableId}' class='on-table' cellpadding='0' cellspacing='0' " +
                "style='--hdr-bg:${headerColor};table-layout:fixed;width:100%;min-width:300px;'>"
        html += "<colgroup><col><col class='dsm-col-room col-room'><col class='col-hub'><col style='width:110px'><col style='width:90px'></colgroup>"
        html += "<thead><tr>"
        html += "<th onclick='sortOnTable(\"${tableId}\",0)' class='${sortColIdx == 0 ? sortClass : ""}'>Sensor Name</th>"
        html += "<th onclick='sortOnTable(\"${tableId}\",1)' class='dsm-col-room ${sortColIdx == 1 ? sortClass : ""}'>Room</th>"
        html += "<th onclick='sortOnTable(\"${tableId}\",2)' class='dsm-col-hub ${sortColIdx == 2 ? sortClass : ""}'>Hub</th>"
        html += "<th onclick='sortOnTable(\"${tableId}\",3)' class='${sortColIdx == 3 ? sortClass : ""}'>State</th>"
        html += "<th onclick='sortOnTable(\"${tableId}\",4)' class='${sortColIdx == 4 ? sortClass : ""}'>Battery %</th>"
        html += "</tr></thead><tbody>"

        shown.each { it ->
            def cv = it.contactVal?.toLowerCase() ?: "unknown"
            def contactColor = (cv in ["open", "active"])     ? "color:red;font-weight:bold;"   :
                               (cv in ["closed", "inactive"]) ? "color:green;font-weight:bold;" :
                                                                "color:#888;"
            def stateTip = (it.contactSource == "acceleration")
                ? " title='Tilt sensor: no contact attribute reported, showing acceleration (active = tilted)'"
                : ""
            def battStr = it.battery?.toString() ?: "n/a"
            def battColor = ""
            if (battStr != "n/a") {
                try {
                    def battInt = battStr.toInteger()
                    battColor = battInt < 20 ? "color:red;" : battInt < 40 ? "color:darkorange;" : "color:green;"
                } catch (ignored) {}
            }
            html += "<tr>"
            html += "<td><a href='${it.linkUrl}' target='_blank'>${it.displayName}</a></td>"
            html += "<td class='dsm-col-room'>${it.room}</td>"
            html += "<td class='dsm-col-hub hub-col'>${it.hub}</td>"
            html += "<td class='state-col' style='${contactColor}'${stateTip}>${cv}</td>"
            html += "<td style='text-align:center;${battColor}'>${battStr}</td>"
            html += "</tr>"
        }
        html += "</tbody></table></div>"
    }
    return html
}

// ─────────────────────────────────────────────────────────────────────────────
// HEALTH / ACTIVITY TABLE BUILDER
// ─────────────────────────────────────────────────────────────────────────────

private String buildHealthTable(List devices, String title, String tableId, String headerColor,
                                String sortBy, String sortOrder, long threshHours) {
    def count = devices.size()

    def sortColIdx = 0
    switch (sortBy) {
        case "room":         sortColIdx = 1; break
        case "hub":          sortColIdx = 2; break
        case "status":       sortColIdx = 4; break
        case "lastActivity": sortColIdx = 6; break
        default:             sortColIdx = 0; break
    }
    def sortClass = (sortOrder == "desc") ? "sort-desc" : "sort-asc"

    devices = devices.sort { it ->
        switch (sortBy) {
            case "room":         return it.room?.toLowerCase()         ?: ""
            case "hub":          return it.hub?.toLowerCase()          ?: ""
            case "status":       return it.status?.toLowerCase()       ?: ""
            case "lastActivity": return it.lastActivity                ?: new Date(0)
            default:             return it.displayName?.toLowerCase()  ?: ""
        }
    }
    if (sortOrder == "desc") devices = devices.reverse()

    def countStr = (count > 0) ? "${count} device${count == 1 ? '' : 's'}" : "No devices"
    def html = "<h4 style='margin-bottom:4px;'>${title}: ${countStr}.</h4>" +
               "<small><i>Flagged when OFFLINE, INACTIVE, NOT PRESENT, DISCONNECTED, or last activity &gt; ${threshHours}h ago.</i></small><br>"

    if (count > 0) {
        // Columns 0-8 are always in the DOM so sort indices stay stable regardless of hide state:
        // 0=Device Name, 1=Room, 2=Hub, 3=Issue, 4=HE Status, 5=Health Status, 6=Last Activity,
        // 7=Battery %, 8=Last Battery
        html += "<div class='dsm-scroll-wrap dsm-health-scroll'>"
        html += "<table id='${tableId}' class='on-table dsm-health-table' cellpadding='0' cellspacing='0' " +
                "style='--hdr-bg:${headerColor};table-layout:fixed;width:100%;'>"
        html += "<colgroup>" +
                "<col style='width:230px'>" +
                "<col class='dsm-col-room col-health-room'>" +
                "<col class='dsm-col-hub  col-health-hub'>" +
                "<col class='dsm-col-issue'    style='width:190px'>" +
                "<col class='dsm-col-hestatus' style='width:110px'>" +
                "<col class='dsm-col-healthst' style='width:105px'>" +
                "<col class='dsm-col-lastact'  style='width:150px'>" +
                "<col class='dsm-col-battery'  style='width:75px'>" +
                "<col class='dsm-col-lastbatt' style='width:145px'>" +
                "</colgroup>"
        html += "<thead><tr>"
        html += "<th onclick='sortOnTable(\"${tableId}\",0)' class='${sortColIdx == 0 ? sortClass : ""}'>Device Name</th>"
        html += "<th onclick='sortOnTable(\"${tableId}\",1)' class='dsm-col-room ${sortColIdx == 1 ? sortClass : ""}'>Room</th>"
        html += "<th onclick='sortOnTable(\"${tableId}\",2)' class='dsm-col-hub ${sortColIdx == 2 ? sortClass : ""}'>Hub</th>"
        html += "<th onclick='sortOnTable(\"${tableId}\",3)' class='dsm-col-issue'>Issue</th>"
        html += "<th onclick='sortOnTable(\"${tableId}\",4)' class='dsm-col-hestatus ${sortColIdx == 4 ? sortClass : ""}'>HE Status</th>"
        html += "<th onclick='sortOnTable(\"${tableId}\",5)' class='dsm-col-healthst'>Health Status</th>"
        html += "<th onclick='sortOnTable(\"${tableId}\",6)' class='dsm-col-lastact ${sortColIdx == 6 ? sortClass : ""}'>Last Activity</th>"
        html += "<th onclick='sortOnTable(\"${tableId}\",7)' class='dsm-col-battery'>Battery %</th>"
        html += "<th onclick='sortOnTable(\"${tableId}\",8)' class='dsm-col-lastbatt'>Last Battery</th>"
        html += "</tr></thead><tbody>"

        devices.each { it ->
            def statusStyle = (it.status in ["OFFLINE", "INACTIVE", "NOT PRESENT"])
                ? "color:red;font-weight:bold;text-align:center;white-space:nowrap;"
                : "text-align:center;white-space:nowrap;"
            def hsSt    = it.healthStatus?.toString()?.toLowerCase() ?: "n/a"
            def hsColor = (hsSt == "online") ? "color:green;" : (hsSt == "offline") ? "color:red;" : ""
            def battStr = it.battery?.toString() ?: "n/a"
            def battColor = ""
            if (battStr != "n/a") {
                try {
                    def battInt = battStr.toInteger()
                    battColor = battInt < 20 ? "color:red;" : battInt < 40 ? "color:darkorange;" : "color:green;"
                } catch (ignored) {}
            }
            html += "<tr>"
            html += "<td><a href='${it.linkUrl}' target='_blank'>${it.displayName}</a></td>"
            html += "<td class='dsm-col-room'>${it.room}</td>"
            html += "<td class='dsm-col-hub hub-col'>${it.hub}</td>"
            html += "<td class='dsm-col-issue'    style='color:#CC4400;font-size:0.9em;white-space:normal;'>${it.issue}</td>"
            html += "<td class='dsm-col-hestatus' style='${statusStyle}'>${it.status}</td>"
            html += "<td class='dsm-col-healthst' style='text-align:center;${hsColor}'>${hsSt}</td>"
            html += "<td class='dsm-col-lastact'  style='white-space:nowrap;font-size:0.9em;'>${it.lastActivityStr}</td>"
            html += "<td class='dsm-col-battery'  style='text-align:center;${battColor}'>${battStr}</td>"
            html += "<td class='dsm-col-lastbatt' style='white-space:nowrap;font-size:0.9em;'>${it.lastBattery ?: 'n/a'}</td>"
            html += "</tr>"
        }
        html += "</tbody></table></div>"
    }
    return html
}

// ─────────────────────────────────────────────────────────────────────────────
// PRIVATE BOOLEAN FALSE / UNKNOWN RULE TABLE BUILDER
// ─────────────────────────────────────────────────────────────────────────────

private String buildPbFalseTable(String sortBy, String sortOrder) {
    // getCachedPbFalseRows() prefers the clobber-proof static copy of the
    // finalized results over the state mirror.
    List<Map> rows = getCachedPbFalseRows()

    // Keep one combined PB table and display only rows from hubs whose
    // per-hub PB control is enabled. The same controls also select which hubs
    // are scanned; filtering here immediately hides stale cached rows from a
    // hub that was disabled before the next PB scan replaces the cache.
    Set<Integer> visibleHubNums = shownPbFalseHubNums()
    rows = rows.findAll { Map r ->
        int rowHubNum = (r.hubNum ?: 1) as Integer
        return visibleHubNums.contains(rowHubNum)
    }

    String pbSetEndpoint = ""
    if (state.accessToken) {
        pbSetEndpoint = "/apps/api/${app.id}/setPb?access_token=${state.accessToken}"
    }

    int sortColIdx = 0
    switch (sortBy) {
        case "appType": sortColIdx = 1; break
        case "hub":         sortColIdx = 2; break
        case "privateBool": sortColIdx = 3; break
        case "lastRun":     sortColIdx = 4; break
        default:         sortColIdx = 0; break
    }
    String sortClass = (sortOrder == "desc") ? "sort-desc" : "sort-asc"

    rows = rows.sort { Map r ->
        switch (sortBy) {
            case "appType": return r.appType?.toString()?.toLowerCase() ?: ""
            case "hub":         return r.hub?.toString()?.toLowerCase() ?: ""
            case "privateBool": return r.privateBool == null ? "unknown" : (r.privateBool == false ? "false" : "true")
            case "lastRun":     return r.lastRun?.toString() ?: ""
            default:         return r.name?.toString()?.toLowerCase() ?: ""
        }
    }
    if (sortOrder == "desc") rows = rows.reverse()

    int falseCount   = rows.count { Map r -> r.privateBool == false } as int
    int visibleUnknownCount = rows.count { Map r -> r.privateBool == null } as int
    int scannedCount = state.pbFalseScannedCount != null ? (state.pbFalseScannedCount as int) : 0
    int unknownCount = state.pbFalseUnknownCount != null ? (state.pbFalseUnknownCount as int) : 0
    int hubCount     = state.pbFalseHubCount != null ? (state.pbFalseHubCount as int) : 0
    String falseCountStr = (falseCount > 0) ? "${falseCount} rule${falseCount == 1 ? '' : 's'}" : "No rules"
    String unknownCountStr = "${visibleUnknownCount} rule${visibleUnknownCount == 1 ? '' : 's'}"

    String html = "<h4 style='margin-bottom:4px;'><b>Rules with Private Boolean FALSE</b>: " +
                  "<span id='pbfalse-count' data-count='${falseCount}'>${falseCountStr}</span>" +
                  (visibleUnknownCount > 0 ? "; <b>UNKNOWN after retries</b>: <span id='pbunknown-count' data-count='${visibleUnknownCount}'>${unknownCountStr}</span>" : "") +
                  ".</h4>"

    if (state.pbFalseScanStatus) {
        html += "${state.pbFalseScanStatus}<br>"
    }

    if (state.pbFalseLastScan) {
        html += "<small><i>PB scan: ${scannedCount} RM/BC rule${scannedCount == 1 ? '' : 's'} across ${hubCount} hub${hubCount == 1 ? '' : 's'} with RM/BC rules; " +
                "${unknownCount} UNKNOWN after sequential retries. Last completed scan: ${htmlEscape(state.pbFalseLastScan)} " +
                "(${htmlEscape(state.pbFalseScanDuration ?: '00:00')}).</i></small><br>"
    } else if (!state.pbFalseScanStatus) {
        html += "<small><i>No PB scan has completed yet. Click Scan PB FALSE Rules.</i></small><br>"
    }

    List warnings = (state.pbFalseDiscoveryWarnings ?: []) as List
    warnings.each { w ->
        html += "<small style='color:#CC6600;font-weight:bold;'>⚠ ${htmlEscape(w)}</small><br>"
    }
    if (state.pbFalseLastError) {
        html += "<small style='color:red;font-weight:bold;'>⚠ ${htmlEscape(state.pbFalseLastError)}</small><br>"
    }

    boolean hasClickTargets = rows.any { Map r ->
        int hn = (r.hubNum ?: 1) as Integer
        return r.privateBool != null && (hn == 1 || remotePbmConfigured(hn))
    }
    if (!pbSetEndpoint && hasClickTargets) {
        html += "<small style='color:#CC6600;'><i>PB State clicks are unavailable because the app OAuth endpoint is not active. Re-open or re-save the app; if needed, enable OAuth manually in Apps Code.</i></small><br>"
    }

    if (!rows.isEmpty()) {
        html += "<div class='dsm-scroll-wrap'>"
        html += "<table id='table_pbfalse' class='on-table' cellpadding='0' cellspacing='0' " +
                "style='--hdr-bg:#8B0000;table-layout:fixed;width:100%;min-width:690px;'>"
        html += "<colgroup><col><col style='width:100px'><col class='dsm-col-hub col-hub'><col style='width:95px'><col style='width:165px'></colgroup>"
        html += "<thead><tr>"
        html += "<th onclick='sortOnTable(\"table_pbfalse\",0)' class='${sortColIdx == 0 ? sortClass : ''}'>Rule</th>"
        html += "<th onclick='sortOnTable(\"table_pbfalse\",1)' class='${sortColIdx == 1 ? sortClass : ''}'>App Type</th>"
        html += "<th onclick='sortOnTable(\"table_pbfalse\",2)' class='dsm-col-hub ${sortColIdx == 2 ? sortClass : ''}'>Hub</th>"
        html += "<th onclick='sortOnTable(\"table_pbfalse\",3)' class='${sortColIdx == 3 ? sortClass : ''}'>PB State</th>"
        html += "<th onclick='sortOnTable(\"table_pbfalse\",4)' class='${sortColIdx == 4 ? sortClass : ''}'>Last Run</th>"
        html += "</tr></thead><tbody>"

        rows.each { Map r ->
            String ruleName = renderPbRuleNameHtml(r.name ?: "Unknown")
            String linkUrl  = htmlEscape(r.linkUrl ?: "")
            String nameCell = linkUrl ? "<a href='${linkUrl}' target='_blank'>${ruleName}</a>" : ruleName
            String lastRun  = htmlEscape(r.lastRun ?: "Never / unavailable")
            int rowHubNum    = (r.hubNum ?: 1) as Integer
            String stateCell

            if (r.privateBool == null) {
                String tip = "PB state remained unreadable after ${PB_STATUS_READ_MAX_ATTEMPTS} read attempt(s); click the linked rule name to inspect manually"
                stateCell = "<td class='state-col' title='${htmlEscape(tip)}' " +
                            "style='color:darkorange;font-weight:bold;text-align:center;white-space:nowrap;'>UNKNOWN</td>"
            } else {
                boolean isFalse   = (r.privateBool == false)
                String stateTxt   = isFalse ? "FALSE" : "TRUE"
                String stateColor = isFalse ? "red" : "blue"
                boolean clickable = (rowHubNum == 1 || remotePbmConfigured(rowHubNum)) &&
                                    pbSetEndpoint && pbCurrentScanId == null && pbToggleRuleId == null
                if (clickable) {
                    String baseUrl  = htmlEscape("${pbSetEndpoint}&id=${r.id}&hubNum=${rowHubNum}")
                    String relayTip = rowHubNum == 1 ? "" : " (relayed to Private Boolean Manager on ${r.hub ?: 'the remote hub'})"
                    String clickTip = isFalse
                        ? "Click to set this rule Private Boolean TRUE${relayTip}"
                        : "Click to set this rule Private Boolean back FALSE${relayTip}; the row remains listed until the next PB scan"
                    stateCell = "<td class='state-col state-clickable pb-state-clickable' " +
                                "data-pb-url='${baseUrl}' data-pb-current='${isFalse ? 'false' : 'true'}' " +
                                "onclick='togglePbState(this)' " +
                                "title='${htmlEscape(clickTip)}' " +
                                "style='color:${stateColor};font-weight:bold;text-align:center;white-space:nowrap;cursor:pointer;'>" +
                                "<span class='pb-state-label'>${stateTxt}</span></td>"
                } else {
                    String tip = pbCurrentScanId != null ? "PB scan in progress — state change temporarily disabled" :
                                 pbToggleRuleId != null  ? "Another PB state change is in progress" :
                                 !pbSetEndpoint          ? "PB toggle endpoint unavailable" :
                                 "To make this hub's PB State cells clickable, enter its Private Boolean Manager app ID and access token in the PB table settings"
                    stateCell = "<td class='state-col' title='${htmlEscape(tip)}' " +
                                "style='color:${stateColor};font-weight:bold;text-align:center;white-space:nowrap;'>${stateTxt}</td>"
                }
            }

            html += "<tr>"
            html += "<td>${nameCell}</td>"
            html += "<td style='text-align:center;white-space:nowrap;'>${htmlEscape(r.appType ?: 'RM')}</td>"
            html += "<td class='dsm-col-hub hub-col'>${htmlEscape(r.hub ?: 'Hub')}</td>"
            html += stateCell
            html += "<td style='white-space:nowrap;font-size:0.9em;'>${lastRun}</td>"
            html += "</tr>"
        }
        html += "</tbody></table></div>"
        if (rows.any { Map r -> r.privateBool == true }) {
            html += "<small><i>Rows changed to TRUE from this table remain listed (and can be clicked back to FALSE) until the next PB scan.</i></small><br>"
        }
    }

    return html
}

// ─────────────────────────────────────────────────────────────────────────────
// STATE CELL BUILDER
// ─────────────────────────────────────────────────────────────────────────────

private String buildStateCell(Map device, String toggleCmd, String stateLabel, String stateStyle) {
    def onUrl   = device.toggleOnUrl  ?: ""
    def offUrl  = device.toggleOffUrl ?: ""
    def safeOn  = onUrl.replace("'",  "\\'")
    def safeOff = offUrl.replace("'", "\\'")

    if (toggleCmd == "both") {
        // Unknown-state table: embed both mini-buttons inside the state cell.
        def b1 = onUrl  ? "<button class='toggle-btn toggle-btn-sm' " +
                           "data-on-url='${safeOn}' data-off-url='${safeOff}' data-current='unknown' " +
                           "onclick='toggleDevice(this,\"on\")'>→ ON</button>" : ""
        def b2 = offUrl ? "<button class='toggle-btn toggle-btn-sm' " +
                           "data-on-url='${safeOn}' data-off-url='${safeOff}' data-current='unknown' " +
                           "onclick='toggleDevice(this,\"off\")'>→ OFF</button>" : ""
        def btns = b1 + (b1 && b2 ? "&nbsp;" : "") + b2
        if (btns) {
            return "<td class='state-col'>${btns}</td>"
        } else {
            return "<td class='state-col' style='${stateStyle}'>${stateLabel}</td>"
        }
    } else {
        // ON / OFF tables: the whole state cell is clickable.
        def targetUrl = (toggleCmd == "off") ? offUrl : onUrl
        if (targetUrl) {
            def dataCurrent = (toggleCmd == "off") ? "on" : "off"
            return "<td class='state-col state-clickable' " +
                   "data-on-url='${safeOn}' data-off-url='${safeOff}' data-current='${dataCurrent}' " +
                   "onclick='toggleStateCell(this)' " +
                   "style='${stateStyle}cursor:pointer;'>" +
                   "<span class='state-label'>${stateLabel}</span></td>"
        } else {
            return "<td class='state-col' style='${stateStyle}'>${stateLabel}</td>"
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// JS + CSS
// ─────────────────────────────────────────────────────────────────────────────

private String buildTableJS() {
    return """
<script>
// ── Table sort ────────────────────────────────────────────────────────────────
function sortOnTable(tableId, columnIndex) {
    const table = document.getElementById(tableId);
    if (!table) return;
    const tbody   = table.querySelector('tbody');
    if (!tbody) return;
    const rows    = Array.from(tbody.querySelectorAll('tr'));
    const headers = table.querySelectorAll('th');
    if (!window._tblSorts) window._tblSorts = {};
    if (!window._tblSorts[tableId]) window._tblSorts[tableId] = {};
    const cur    = window._tblSorts[tableId][columnIndex] || 'asc';
    const newDir = cur === 'asc' ? 'desc' : 'asc';
    window._tblSorts[tableId][columnIndex] = newDir;
    headers.forEach(h => h.classList.remove('sort-asc','sort-desc'));
    headers[columnIndex].classList.add('sort-' + newDir);
    rows.sort((a, b) => {
        const aT = (a.querySelectorAll('td')[columnIndex]?.textContent?.trim() || '').toLowerCase();
        const bT = (b.querySelectorAll('td')[columnIndex]?.textContent?.trim() || '').toLowerCase();
        return newDir === 'asc' ? aT.localeCompare(bT) : bT.localeCompare(aT);
    });
    rows.forEach(row => tbody.appendChild(row));
}

// ── Device toggle (Unknown-table buttons) ────────────────────────────────────
async function toggleDevice(btn, forceCmd) {
    const current   = btn.dataset.current;
    const targetCmd = forceCmd || (current === 'on' ? 'off' : 'on');
    const url       = btn.dataset[targetCmd + 'Url'];
    if (!url) return;

    const originalText = btn.textContent;
    btn.disabled = true;
    btn.textContent = '…';

    // Hub #2 / #3 URLs are absolute http:// — different origin from hub #1 page.
    // Use no-cors so the command goes through; treat the opaque response as success.
    const isCrossOrigin = url.startsWith('http://') || url.startsWith('https://');
    const fetchOpts = isCrossOrigin ? { mode: 'no-cors' } : {};

    try {
        const resp = await fetch(url, fetchOpts);
        if (resp.ok || resp.type === 'opaque') {
            btn.textContent = '✓ Sent';
            setTimeout(() => { btn.textContent = originalText; btn.disabled = false; }, 2000);
        } else {
            btn.textContent = '⚠ ' + resp.status;
            setTimeout(() => { btn.textContent = originalText; btn.disabled = false; }, 3000);
        }
    } catch (err) {
        btn.textContent = '⚠ Error';
        setTimeout(() => { btn.textContent = originalText; btn.disabled = false; }, 3000);
    }
}

// ── Clickable state cell (ON / OFF tables) ────────────────────────────────────
async function toggleStateCell(cell) {
    if (cell.dataset.busy === 'true') return;
    const current   = cell.dataset.current;
    const targetCmd = current === 'on' ? 'off' : 'on';
    const url       = cell.dataset[targetCmd + 'Url'];
    if (!url) return;

    cell.dataset.busy = 'true';
    const lbl      = cell.querySelector('.state-label') || cell;
    const savedTxt = lbl.textContent;
    lbl.textContent    = '…';
    cell.style.opacity = '0.5';
    cell.style.cursor  = 'wait';

    // Hub #2 / #3 URLs are absolute http:// — different origin from hub #1 page.
    // Use no-cors so the command goes through; treat the opaque response as success.
    const isCrossOrigin = url.startsWith('http://') || url.startsWith('https://');
    const fetchOpts = isCrossOrigin ? { mode: 'no-cors' } : {};

    try {
        const resp = await fetch(url, fetchOpts);
        if (resp.ok || resp.type === 'opaque') {
            if (targetCmd === 'off') {
                lbl.textContent = 'OFF';
                cell.style.cssText = 'color:#444;font-weight:bold;text-align:center;white-space:nowrap;cursor:pointer;';
                cell.dataset.current = 'off';
            } else {
                lbl.textContent = 'ON';
                cell.style.cssText = 'color:red;font-weight:bold;text-align:center;white-space:nowrap;cursor:pointer;';
                cell.dataset.current = 'on';
            }
            cell.dataset.busy = 'false';
        } else {
            lbl.textContent    = '⚠ ' + resp.status;
            cell.style.opacity = '1';
            cell.style.cursor  = 'pointer';
            setTimeout(() => { lbl.textContent = savedTxt; cell.dataset.busy = 'false'; }, 3000);
        }
    } catch (err) {
        lbl.textContent    = '⚠ Err';
        cell.style.opacity = '1';
        cell.style.cursor  = 'pointer';
        setTimeout(() => { lbl.textContent = savedTxt; cell.dataset.busy = 'false'; }, 3000);
    }
}

// ── Private Boolean FALSE → TRUE (Hub #1 only) ─────────────────────────────
async function togglePbState(cell) {
    const baseUrl = cell.dataset.pbUrl;
    if (!baseUrl || window._pbTrueClickBusy || cell.dataset.busy === 'true') return;
    const cur  = cell.dataset.pbCurrent === 'true';
    const next = !cur;
    window._pbTrueClickBusy = true;

    cell.dataset.busy = 'true';
    const lbl = cell.querySelector('.pb-state-label') || cell;
    const savedTxt = lbl.textContent;
    lbl.textContent = '…';
    cell.style.opacity = '0.5';
    cell.style.cursor = 'wait';

    try {
        const resp = await fetch(baseUrl + '&value=' + next);
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        const text = await resp.text();
        if (!text || !text.trim()) throw new Error('Empty response from PB endpoint');
        let result;
        try { result = JSON.parse(text); }
        catch (e) { throw new Error('Non-JSON response: ' + text.substring(0, 100)); }
        if (result.status !== 'success') throw new Error(result.message || JSON.stringify(result));

        const nowTrue = result.value === true || result.value === 'true';
        cell.dataset.pbCurrent = nowTrue ? 'true' : 'false';
        lbl.textContent = nowTrue ? 'TRUE' : 'FALSE';
        cell.style.cssText = 'color:' + (nowTrue ? 'blue' : 'red') +
            ';font-weight:bold;text-align:center;white-space:nowrap;cursor:pointer;';
        cell.style.opacity = '1';
        cell.title = nowTrue
            ? 'Click to set this rule Private Boolean back FALSE; the row remains listed until the next PB scan'
            : 'Click to set this rule Private Boolean TRUE';
        cell.dataset.busy = 'false';

        const countEl = document.getElementById('pbfalse-count');
        if (countEl) {
            const remaining = Number.isFinite(Number(result.remainingFalse))
                ? Number(result.remainingFalse)
                : Math.max(0, parseInt(countEl.dataset.count || '0', 10) + (nowTrue ? -1 : 1));
            countEl.dataset.count = String(remaining);
            countEl.textContent = remaining > 0
                ? remaining + ' rule' + (remaining === 1 ? '' : 's')
                : 'No rules';
        }
    } catch (err) {
        lbl.textContent = '⚠ Err';
        cell.style.opacity = '1';
        cell.style.cursor = 'pointer';
        setTimeout(function() {
            lbl.textContent = savedTxt;
            cell.dataset.busy = 'false';
        }, 2500);
        alert('Set Private Boolean ' + (next ? 'TRUE' : 'FALSE') + ' failed: ' + err.message);
    } finally {
        window._pbTrueClickBusy = false;
    }
}

// ── Column hide toggles ────────────────────────────────────────────────────
function toggleDsmCol(cls, btn) {
    var hiding = btn.className.indexOf('dsm-col-hidden') === -1;
    document.querySelectorAll('.' + cls).forEach(function(el) { el.style.display = hiding ? 'none' : ''; });
    btn.className = hiding ? 'dsm-col-btn dsm-col-hidden' : 'dsm-col-btn';
    try { localStorage.setItem('dsm_col_' + cls, hiding ? 'true' : 'false'); } catch(e) {}
}

// Apply saved column-hide state from localStorage on page load
(function() {
    var cols = ['dsm-col-room', 'dsm-col-hub', 'dsm-col-hestatus',
                'dsm-col-healthst', 'dsm-col-lastact', 'dsm-col-issue',
                'dsm-col-battery', 'dsm-col-lastbatt'];
    cols.forEach(function(cls) {
        var hidden = false;
        try { hidden = localStorage.getItem('dsm_col_' + cls) === 'true'; } catch(e) {}
        if (hidden) {
            document.querySelectorAll('.' + cls).forEach(function(el) { el.style.display = 'none'; });
            var btn = document.querySelector('[data-dsm-col="' + cls + '"]');
            if (btn) btn.className = 'dsm-col-btn dsm-col-hidden';
        }
    });
})();
// ── Responsive column widths via window.innerWidth (reliable on all mobile browsers) ──
function dsmApplyColWidths() {
    var w = window.innerWidth;
    var small = w <= 767;
    var cfg = {
        'col-room':        small ? '140px' : '180px',
        'col-hub':         small ? '70px'  : '200px',
        'col-health-room': small ? '105px' : '150px',
        'col-health-hub':  small ? '90px'  : '200px'
    };
    for (var cls in cfg) {
        document.querySelectorAll('col.' + cls).forEach(function(el) {
            el.style.width = cfg[cls];
        });
    }
    document.querySelectorAll('.dsm-health-table').forEach(function(el) {
        el.style.minWidth = small ? '1200px' : '1355px';
    });
    // Room column: on any phone-sized screen (≤767px) orientation drives visibility
    // unconditionally — localStorage is ignored so manual-toggle testing doesn't
    // permanently override the auto behaviour. On desktop (>767px) the user's
    // manually saved preference from the toggle button is respected.
    var roomEls = document.querySelectorAll('.dsm-col-room');
    var roomBtn = document.querySelector('[data-dsm-col="dsm-col-room"]');
    if (w <= 767) {
        var hide = w <= 480;
        roomEls.forEach(function(el) { el.style.display = hide ? 'none' : ''; });
        if (roomBtn) roomBtn.className = 'dsm-col-btn' + (hide ? ' dsm-col-hidden' : '');
    } else {
        var userHid = false;
        try { userHid = localStorage.getItem('dsm_col_dsm-col-room') === 'true'; } catch(e) {}
        roomEls.forEach(function(el) { el.style.display = userHid ? 'none' : ''; });
        if (roomBtn) roomBtn.className = 'dsm-col-btn' + (userHid ? ' dsm-col-hidden' : '');
    }
}
dsmApplyColWidths();
window.addEventListener('resize', dsmApplyColWidths);
</script>
<style>
.dsm-scroll-wrap {
    max-width:100%; overflow-x:auto; -webkit-overflow-scrolling:touch;
    padding-bottom:4px;
}
.on-table { border-collapse:collapse; width:100%; }
/* col-room/col-hub/col-health-* widths are set by dsmApplyColWidths() in JS below,
   which uses window.innerWidth to avoid CSS viewport-meta ambiguity on mobile. */
.dsm-health-table { min-width:1200px; } /* JS overrides for large screens */
.on-table th {
    cursor:pointer; user-select:none;
    background-color: var(--hdr-bg, #FFD700);
    color:#fff; font-weight:bold;
    border:1px solid #555; white-space:nowrap; padding:4px 6px;
}
.on-table td { border:1px solid #aaa; padding:4px 6px; word-break:break-word; }
.dsm-health-table th, .dsm-health-table td { overflow-wrap:normal; }
.on-table th:not(:last-child):hover { opacity:0.85; }
.on-table th.sort-asc::after  { content:' ▲'; font-size:0.8em; }
.on-table th.sort-desc::after { content:' ▼'; font-size:0.8em; }
.on-table td.state-col  { text-align:center; white-space:nowrap; }
.on-table td.hub-col    { word-break:break-word; }
.on-table td.state-clickable:hover:not([data-busy='true']) { background-color:rgba(0,0,0,0.06); }
.toggle-btn {
    font-size:0.8em; padding:2px 8px; cursor:pointer;
    border-radius:3px; border:1px solid #888;
    background:#f5f5f5; white-space:nowrap;
}
.toggle-btn:hover:not(:disabled) { background:#e0e0e0; }
.toggle-btn:disabled { opacity:0.5; cursor:wait; }
.toggle-btn-sm { padding:2px 5px; }
.dsm-col-toggle-bar { margin-bottom:8px; font-size:0.9em; }
.dsm-col-btn {
    display:inline-block; cursor:pointer; padding:2px 8px; margin-right:6px;
    border:1px solid #999; border-radius:3px; background:#e8e8e8; font-size:0.9em;
}
.dsm-col-btn:hover { background:#d4d4d4; }
.dsm-col-btn.dsm-col-hidden { text-decoration:line-through; opacity:0.45; background:#ccc; }
</style>"""
}

// ─────────────────────────────────────────────────────────────────────────────
// HELPERS
// ─────────────────────────────────────────────────────────────────────────────

private String safeString(Object v) {
    return v == null ? "" : v.toString()
}

// ─────────────────────────────────────────────────────────────────────────────
// HARDENED JSON FETCHER (added in 1.54)
// Fetches the URI as raw text (no platform auto-parsing), then parses JSON
// manually. If the hub returns anything that is not JSON (e.g. an HTML login
// page or error page — the cause of "Lexing failed ... reading '<'" errors on
// platform 2.5.1 beta), it throws a descriptive exception that includes the
// HTTP status and the start of the actual response body, so the warning banner
// tells you exactly WHAT the hub sent back.
// ─────────────────────────────────────────────────────────────────────────────

private Object fetchJson(String uri, int timeoutSec) {
    // ── Workaround for platform 2.5.1.x (Apache HttpClient 5.6.1) ───────────
    // The new HTTP client silently DROPS a query string embedded in the uri
    // (see beta forum: "[2.5.1.112-116] (breaking change) httpPost/asynchttpPost
    // drops the URI query string"). A Maker API request that loses its
    // ?access_token= arrives unauthenticated and the remote hub answers with
    // an HTML error page. Passing parameters via the query: map is carried on
    // the wire correctly on both old and new clients, so split the uri here.
    // Note: values are URL-decoded before being placed in the map because the
    // client re-encodes them (avoids double-encoding). Maker API tokens are
    // hex + dashes, so this is lossless for our URLs.
    String base = uri
    Map qmap = null
    int qIdx = uri.indexOf('?')
    if (qIdx >= 0) {
        base = uri.substring(0, qIdx)
        qmap = [:]
        uri.substring(qIdx + 1).split('&').each { pair ->
            if (!pair) return
            int eq = pair.indexOf('=')
            if (eq >= 0) {
                qmap[URLDecoder.decode(pair.substring(0, eq), "UTF-8")] =
                    URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            } else {
                qmap[URLDecoder.decode(pair, "UTF-8")] = ""
            }
        }
    }
    Map reqParams = [uri: base, contentType: "text/plain",
                     headers: ["Accept": "application/json"], timeout: timeoutSec]
    if (qmap) reqParams.query = qmap

    Object parsed = null
    httpGet(reqParams) { resp ->
        def d = resp.data

        // Newer platform builds (first seen on the 2026-07-07 beta) can hand
        // resp.data back ALREADY PARSED as a Map/List when the response is
        // application/json, regardless of the requested contentType. Coercing
        // those through the text fallback below silently mangles them:
        // map.text is a missing key → null → "empty response", and list.text
        // spread-collects a nonexistent 'text' property from every element →
        // a list of nulls whose toString is "[null, null, …]" — valid JSON
        // that parses to an all-null device list. Accept parsed data directly;
        // this is compatible with both old and new platform behavior.
        if (resp.status == 200 && (d instanceof Map || d instanceof List)) {
            parsed = d
            return
        }

        String body
        if (d == null) {
            body = ""
        } else if (d instanceof String) {
            body = d
        } else {
            // Reader / InputStream / etc. — Groovy adds .text to all of them.
            // (Deliberately no instanceof checks: the Hubitat sandbox blocks
            // referencing java.io.Reader / InputStream as class expressions.)
            try { body = d.text } catch (ignore) { body = d.toString() }
        }
        body = body?.trim() ?: ""

        if (resp.status != 200) {
            throw new Exception("HTTP ${resp.status}${body ? ' — body starts: ' + jsonErrSnippet(body) : ''}")
        }
        if (!body) {
            throw new Exception("hub returned an empty response (HTTP 200) for ${base} — if this URL (plus the access token) is also blank in a browser, the Maker API app on that hub is malfunctioning: open that Maker API app and press Done, or reboot that hub")
        }
        if (!(body.startsWith("{") || body.startsWith("["))) {
            String hint = ""
            String lower = body.toLowerCase()
            if (lower.contains("login") || lower.contains("password")) {
                hint = " — looks like the hub LOGIN page. Hub Login Security on that hub appears to be intercepting Maker API calls; verify the access token / app ID, or check Hub Security settings on that hub"
            } else if (lower.startsWith("<!doctype") || lower.startsWith("<html")) {
                hint = " — hub returned an HTML page instead of JSON. Verify Maker API is still installed/enabled on that hub and the app ID + token are correct (open the URL in a browser to see the page)"
            }
            throw new Exception("non-JSON response (HTTP 200)${hint}. Body starts: ${jsonErrSnippet(body)}")
        }
        parsed = new groovy.json.JsonSlurper().parseText(body)
    }
    // A Maker API device list can contain literal null entries (typically a
    // deleted device still referenced by that Maker API app's selection).
    // A single null used to abort the whole hub's query with
    // "Cannot get property 'id' on null object"; drop them here — after the
    // closure, so the filter applies to pre-parsed and text-parsed responses
    // alike — and every caller gets a clean list.
    if (parsed instanceof List && (parsed as List).contains(null)) {
        int nullCount = (parsed as List).count { it == null } as int
        log.warn "fetchJson: ${base} returned ${nullCount} null entr${nullCount == 1 ? 'y' : 'ies'} in its device list — usually a deleted device still selected in that hub's Maker API app; open that Maker API app, review its device selection, and press Done"
        parsed = (parsed as List).findAll { it != null }
    }
    return parsed
}

// First ~160 chars of a response body, whitespace-collapsed and HTML-escaped
// so it displays safely inside the red warning banner.
private String jsonErrSnippet(String body) {
    String s = body.replaceAll(/\s+/, " ")
    if (s.length() > 160) s = s.substring(0, 160) + "…"
    return htmlEscape(s)
}

// Render Hubitat rule-name status suffixes such as
// <span style='color:red'>(Required Expression false)</span> as colored text
// instead of literal HTML source. Escape the complete name first, then restore
// only a narrowly restricted color span. This mirrors Private Boolean Manager's
// safe rule-name renderer and prevents arbitrary HTML in a rule name from being
// interpreted by the report page.
private String renderPbRuleNameHtml(Object val) {
    if (val == null) return ""
    String encoded = htmlEscape(val)
    return encoded.replaceAll(
        /&lt;span style=(?:&#39;|&quot;)color:([a-zA-Z#0-9]+)(?:&#39;|&quot;)&gt;(.*?)&lt;\/span&gt;/,
        "<span style='color:\$1'>\$2</span>"
    )
}

private String htmlEscape(Object val) {
    String s = val == null ? "" : val.toString()
    return s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

private boolean hasSwitchCapability(def caps) {
    if (!caps) return true
    def capsList   = (caps instanceof List ? caps : [caps])
    def switchCaps = ["switch", "light", "outlet"] as Set
    return capsList.any { c ->
        def name = (c instanceof Map) ? (c.title ?: c.name ?: "").toString().toLowerCase()
                                      : c?.toString()?.toLowerCase() ?: ""
        name in switchCaps
    }
}

// Current value of one attribute from a Maker API device "attributes" field,
// which can arrive as a List of [name:, currentValue:] maps or as a flat Map.
// Returns a lower-cased string, or null when the attribute is absent/empty.
private String remoteAttrValue(def attrsField, String attrName) {
    def v = null
    if (attrsField instanceof List) {
        v = attrsField.find { a -> a?.name?.toString() == attrName }?.currentValue
    } else if (attrsField instanceof Map) {
        v = attrsField[attrName]
    }
    String s = v?.toString()?.trim()?.toLowerCase()
    return (s && s != "null") ? s : null
}

private boolean hasContactCapability(def caps) {
    if (!caps) return false
    def capsList = (caps instanceof List ? caps : [caps])
    return capsList.any { c ->
        def name = (c instanceof Map) ? (c.title ?: c.name ?: "").toString().toLowerCase()
                                      : c?.toString()?.toLowerCase() ?: ""
        name == "contactsensor" || name == "contact sensor"
    }
}

private boolean hasLockCapability(def caps) {
    if (!caps) return false
    def capsList = (caps instanceof List ? caps : [caps])
    return capsList.any { c ->
        def name = (c instanceof Map) ? (c.title ?: c.name ?: "").toString().toLowerCase()
                                      : c?.toString()?.toLowerCase() ?: ""
        name == "lock"
    }
}

private String resolveLocalRoom(def dev, Map roomMap) {
    def roomName = ""
    try { roomName = dev.roomName ?: "" } catch (ignore) {}
    if (!roomName) {
        try {
            def roomId = dev.device?.roomId ?: dev.roomId
            if (roomId) roomName = roomMap[roomId] ?: ""
        } catch (ignore) {}
    }
    return roomName ?: "—"
}
