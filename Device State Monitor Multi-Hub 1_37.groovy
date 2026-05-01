/*
PURPOSE: Report switch device states across up to three Hubitat hubs,
         with in-row toggle buttons to turn devices ON or OFF instantly.
         Also reports devices that are OFFLINE, INACTIVE, NOT PRESENT,
         or whose last activity exceeds a configurable time threshold.

FEATURES:
    * Hub #1 devices queried locally. Separate ON-monitor, OFF-monitor, and
      Health/Activity-monitor device pickers. The health picker uses Load /
      Select All / Clear All (via Hub #1 Maker API) when credentials are
      configured; falls back to a capability picker otherwise.
    * Hubs #2 & #3 queried via Maker API. Connection credentials are collapsible
      once configured.
    * Toggle buttons in each table row: "Turn OFF" in the ON table, "Turn ON" in
      the OFF table, and both "→ ON" / "→ OFF" in the Unknown table. Buttons use
      fetch() with no page refresh; the row updates in-place optimistically.
    * Toggle commands for Hub #1 use a separate optional Maker API credential block.
      Toggle commands for Hubs #2 & #3 reuse their existing Maker API credentials.
    * Optional Unknown State table catches devices reporting neither on nor off.
    * Health / Activity Monitor table shows any selected device that is OFFLINE,
      INACTIVE, NOT PRESENT, or has last activity older than X hours (configurable).
    * Each hub has its own Health/Activity device selector. Remote hubs expose ALL
      Maker API devices (not just switch-capable) in the health selector.
    * Optional toggle to exclude virtual devices from all reports, including the
      Health/Activity table (uses same "virtual" definition as other tables).
    * All tables are independently sortable (click headers or Sort Options).
    * Device names link to their hub's Devices page.
    * Report tables and Refresh button appear at the TOP of the page;
      all configuration sections are collapsed below.
    * Refresh Table button re-queries all hubs on demand.
    * Graceful error handling if a remote hub is offline or unreachable.
    * Only switch-capable devices appear in ON/OFF/Unknown selection lists.
    * Remote hub action dropdowns leave their section open after action.
*/

definition(
    name: "Device State Monitor Multi-Hub 1.37",
    namespace: "John Land",
    author: "John Land via Claude AI and ChatGPT",
    description: "Reports ON/OFF/unknown switch states and health/activity status across up to three hubs",
    installOnOpen: true,
    category: "Convenience",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
}

// ─────────────────────────────────────────────────────────────────────────────
// UI: MAIN PAGE
// ─────────────────────────────────────────────────────────────────────────────

def mainPage() {
    dynamicPage(name: "mainPage", title: "", uninstall: true, install: true) {

        // ── Refresh + Report (TOP) ────────────────────────────────────────────
        section(title: "") {
            input "refresh", "button", title: "Refresh Table"
            paragraph handler()
        }

        // ── Second Refresh button (bottom of report, above config sections) ──
        section(title: "") {
            input "refresh2", "button", title: "Refresh Table"
        }

        // ── Hub #1 – Local ────────────────────────────────────────────────────
        def hub1LabelVal       = settings["hub1Label"] ?: (location.name ?: "Hub 1")
        def h1OnCount          = (devsOn  ?: []).findAll { !it.isDisabled() }.size()
        def h1OffCount         = (devsOff ?: []).findAll { !it.isDisabled() }.size()
        def hub1Title          = "Hub #1 – ${hub1LabelVal}"
        if (showSectionDetails) hub1Title += buildSelSummary(h1OnCount, h1OffCount, (hub1HealthDevs ?: []).size())

        def hub1HealthActionVal  = settings["hub1HealthAction"]
        def hub1HealthActionOpen = (hub1HealthActionVal && hub1HealthActionVal != "none")
        def h1HealthList         = normalizeSelectionList(settings["hub1SelectedHealthDevices"])
        if (hub1HealthActionOpen) {
            def stored = state["hub1AllDevices"] ?: []
            switch (hub1HealthActionVal) {
                case "selAllHealth":   h1HealthList = stored.collect { it.id.toString() }; break
                case "unselAllHealth": h1HealthList = []; break
            }
        }

        section(hideable: true, hidden: !(hub1HealthActionOpen), title: hub1Title) {
            // Process hub1HealthAction inside the section so side-effects happen during render
            if (hub1HealthActionOpen) {
                def h1AllIds = (state["hub1AllDevices"] ?: []).collect { it.id.toString() }
                switch (hub1HealthActionVal) {
                    case "load":
                        loadHub1AllDevices(); break
                    case "selAllHealth":
                        // Sync both the enum (for display) and the capability.* input (for data access)
                        app.updateSetting("hub1SelectedHealthDevices", [value: h1AllIds, type: "enum"])
                        app.updateSetting("hub1HealthDevs",            [value: h1AllIds, type: "capability"])
                        break
                    case "unselAllHealth":
                        app.updateSetting("hub1SelectedHealthDevices", [value: [], type: "enum"])
                        app.updateSetting("hub1HealthDevs",            [value: [], type: "capability"])
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

            // ── Hub #1 Health / Activity selector ────────────────────────────
            paragraph("<hr>")
            // Collapsible Maker API block (toggle commands AND health device loading share same credentials)
            input "hub1ShowToggle", "bool",
                title: "Show / Edit Toggle Command & Health Monitor Settings (Maker API for Hub #1)",
                defaultValue: false, submitOnChange: true
            if (settings["hub1ShowToggle"]) {
                paragraph("<i>Install Maker API on Hub #1, expose all desired devices, then enter the " +
                          "app ID and token below. These credentials are used both for in-row toggle " +
                          "buttons <b>and</b> for loading the Health/Activity device list. " +
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
                      "<small>(flagged when OFFLINE, INACTIVE, NOT PRESENT, or activity overdue)</small>")

            def hub1ApiReady = (settings["hub1AppId"] && settings["hub1Token"])
            if (hub1ApiReady) {
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
            // Capability picker always shown — data collection uses this directly (no API call needed)
            def h1HealthSelCount = (hub1HealthDevs ?: []).size()
            input "hub1HealthDevs", "capability.*",
                title: "Select health/activity-monitored devices (${h1HealthSelCount} selected)",
                submitOnChange: true, multiple: true, required: false
        }

        // ── Hub #2 – Remote ───────────────────────────────────────────────────
        def hub2LabelVal   = settings["hub2Label"] ?: "Hub 2"
        def hub2Enabled    = settings["hub2Enabled"]
        def hub2ActionVal  = settings["hub2Action"]
        def hub2ActionOpen = (hub2ActionVal && hub2ActionVal != "none")

        def h2OnList     = normalizeSelectionList(settings["hub2SelectedOnDevices"])
        def h2OffList    = normalizeSelectionList(settings["hub2SelectedOffDevices"])
        def h2HealthList = normalizeSelectionList(settings["hub2SelectedHealthDevices"])
        if (hub2ActionOpen) {
            def stored    = state["hub2Devices"]    ?: []
            def storedAll = state["hub2AllDevices"] ?: []
            switch (hub2ActionVal) {
                case "selAllOn":      h2OnList     = stored.collect    { it.id.toString() }; break
                case "unselAllOn":    h2OnList     = []; break
                case "selAllOff":     h2OffList    = stored.collect    { it.id.toString() }; break
                case "unselAllOff":   h2OffList    = []; break
                case "selAllHealth":  h2HealthList = storedAll.collect { it.id.toString() }; break
                case "unselAllHealth":h2HealthList = []; break
            }
        }
        def hub2Title = "Hub #2 – ${hub2LabelVal}"
        if (showSectionDetails && hub2Enabled) hub2Title += buildSelSummary(h2OnList.size(), h2OffList.size(), h2HealthList.size())

        section(hideable: true, hidden: !hub2ActionOpen, title: hub2Title) {
            if (hub2ActionOpen) {
                def hub2Stored    = state["hub2Devices"]    ?: []
                def hub2StoredAll = state["hub2AllDevices"] ?: []
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
                input "hub2Action", "enum", title: "Hub #2 Actions", defaultValue: "none",
                    options: ["none": "Choose action…", "load": "⟳ Load / Reload Device List from Hub #2",
                              "selAllOn": "✓ Select All ON-monitored devices",     "unselAllOn":    "✗ Clear ON-monitored devices",
                              "selAllOff": "✓ Select All OFF-monitored devices",   "unselAllOff":   "✗ Clear OFF-monitored devices",
                              "selAllHealth": "✓ Select All health-monitored devices", "unselAllHealth": "✗ Clear health-monitored devices"],
                    required: false, submitOnChange: true
                renderRemoteDeviceSelectors(2, state["hub2Devices"], h2OnList, h2OffList)
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
        if (hub3ActionOpen) {
            def stored    = state["hub3Devices"]    ?: []
            def storedAll = state["hub3AllDevices"] ?: []
            switch (hub3ActionVal) {
                case "selAllOn":      h3OnList     = stored.collect    { it.id.toString() }; break
                case "unselAllOn":    h3OnList     = []; break
                case "selAllOff":     h3OffList    = stored.collect    { it.id.toString() }; break
                case "unselAllOff":   h3OffList    = []; break
                case "selAllHealth":  h3HealthList = storedAll.collect { it.id.toString() }; break
                case "unselAllHealth":h3HealthList = []; break
            }
        }
        def hub3Title = "Hub #3 – ${hub3LabelVal}"
        if (showSectionDetails && hub3Enabled) hub3Title += buildSelSummary(h3OnList.size(), h3OffList.size(), h3HealthList.size())

        section(hideable: true, hidden: !hub3ActionOpen, title: hub3Title) {
            if (hub3ActionOpen) {
                def hub3Stored    = state["hub3Devices"]    ?: []
                def hub3StoredAll = state["hub3AllDevices"] ?: []
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
                input "hub3Action", "enum", title: "Hub #3 Actions", defaultValue: "none",
                    options: ["none": "Choose action…", "load": "⟳ Load / Reload Device List from Hub #3",
                              "selAllOn": "✓ Select All ON-monitored devices",     "unselAllOn":    "✗ Clear ON-monitored devices",
                              "selAllOff": "✓ Select All OFF-monitored devices",   "unselAllOff":   "✗ Clear OFF-monitored devices",
                              "selAllHealth": "✓ Select All health-monitored devices", "unselAllHealth": "✗ Clear health-monitored devices"],
                    required: false, submitOnChange: true
                renderRemoteDeviceSelectors(3, state["hub3Devices"], h3OnList, h3OffList)
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
            paragraph("<i><b>Note:</b> These are the default sort orders. Click any column header to re-sort temporarily.</i>")

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

            paragraph("<hr>")
            input "excludeVirtual",    "bool", title: "Exclude virtual devices from all reports (including Health/Activity table)?", defaultValue: false
            input "excludeSystemRoom", "bool", title: "Exclude devices in the \"System\" room from all reports?", defaultValue: false
            paragraph("<hr>")
            input "showSectionDetails","bool", title: "Show extra details in section headers?",    defaultValue: true
            input "enableLogging",     "bool", title: "Enable debug logging?",                     defaultValue: false
        }
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
    paragraph("<small><i>Flagged when OFFLINE, INACTIVE, NOT PRESENT, or last activity exceeds threshold.</i></small>")
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

private List normalizeSelectionList(def raw) {
    if (raw instanceof List)       return raw*.toString()
    if (raw instanceof Collection) return raw.collect { it.toString() }
    return raw ? [raw.toString()] : []
}

private String buildSelSummary(int onCount, int offCount, int healthCount = 0) {
    if (onCount == 0 && offCount == 0 && healthCount == 0) return " — No devices selected"
    def parts = []
    if (onCount    > 0) parts << "${onCount} ON"
    if (offCount   > 0) parts << "${offCount} OFF"
    if (healthCount > 0) parts << "${healthCount} Health"
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

// ─────────────────────────────────────────────────────────────────────────────
// LIFECYCLE
// ─────────────────────────────────────────────────────────────────────────────

def installed()  { initialize() }
def updated()    { unschedule(); unsubscribe(); initialize() }

void initialize() {
    unschedule()
    log.info "Device State Monitor Multi-Hub initialized"
}

def appButtonHandler(btn) {
    if (enableLogging) log.debug "Button pressed: ${btn}"
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
    def allList     = []
    def disabledIds = []
    try {
        httpGet([uri: uri, contentType: "application/json", timeout: 15]) { resp ->
            if (resp.status != 200) {
                state["hub${hubNum}Devices"]     = []
                state["hub${hubNum}AllDevices"]  = []
                state["hub${hubNum}DisabledIds"] = []
                state["hub${hubNum}LoadStatus"]  = "Error: HTTP ${resp.status}"
                return
            }
            resp.data?.each { dev ->
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
                    if (hasSwitchCapability(dev.capabilities)) switchList << devEntry
                }
            }
        }
        state["hub${hubNum}Devices"]     = switchList
        state["hub${hubNum}AllDevices"]  = allList
        state["hub${hubNum}DisabledIds"] = disabledIds
        state["hub${hubNum}LoadStatus"]  = "OK: ${switchList.size()} switch device${switchList.size() == 1 ? '' : 's'} loaded (${allList.size()} enabled, ${disabledIds.size()} disabled)"
        log.info "${hubLabel}: Loaded ${switchList.size()} switch device(s), ${allList.size()} enabled, ${disabledIds.size()} disabled."
    } catch (Exception e) {
        log.error "${hubLabel}: Error loading device list — ${e.message}"
        state["hub${hubNum}Devices"]     = []
        state["hub${hubNum}AllDevices"]  = []
        state["hub${hubNum}DisabledIds"] = []
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
        httpGet([uri: uri, contentType: "application/json", timeout: 15]) { resp ->
            if (resp.status != 200) {
                state["hub1AllDevices"]       = []
                state["hub1AllDevicesStatus"] = "Error: HTTP ${resp.status}"
                return
            }
            resp.data?.each { dev ->
                allList << [
                    id  : dev.id?.toString(),
                    name: (dev.label ?: dev.name ?: "Unknown").toString(),
                    room: (dev.room ?: "").toString()
                ]
            }
        }
        state["hub1AllDevices"]       = allList
        state["hub1AllDevicesStatus"] = "OK: ${allList.size()} device${allList.size() == 1 ? '' : 's'} loaded"
        log.info "${hubLabel}: Loaded ${allList.size()} device(s) for health monitoring."
    } catch (Exception e) {
        log.error "${hubLabel}: Error loading health device list — ${e.message}"
        state["hub1AllDevices"]       = []
        state["hub1AllDevicesStatus"] = "Error: ${e.message}"
    }
}



String handler() {
    state.lastRun = new Date().format("yyyy-MM-dd hh:mm a", location.timeZone)
    def report  = generateReportTables()
    def htmlOut = report.html
    htmlOut    += "<br><small><i>Last run: ${state.lastRun}</i></small>"
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

    // Helper: resolve lastActivity for local devices, falling back to parent for child devices
    def resolveLocalLastActivity = { dev ->
        def lastAct = dev.getLastActivity()
        if (lastAct == null) {
            try {
                def parentId = dev.device?.parentDeviceId
                if (parentId) {
                    def parentDev = getDeviceById(parentId)
                    lastAct = parentDev?.getLastActivity()
                    if (enableLogging && lastAct) log.debug "Used parent lastActivity for child ${dev.displayName}"
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
    // Health pool – Hub #1
    // Uses hub1HealthDevs (capability.* input) for direct device object access —
    // no HTTP call needed and gives accurate .getStatus() / .getLastActivity() data.
    // Select All / Clear All syncs hub1HealthDevs via app.updateSetting in the UI.
    (hub1HealthDevs ?: []).findAll(filterLocal).each { dev ->
        def rawStatus   = dev.getStatus()?.toUpperCase() ?: ""
        def rawHealthSt = (dev.currentHealthStatus ?: "").toString().toLowerCase()
        def lastAct     = resolveLocalLastActivity(dev)
        def lateActivity = lastAct ? ((now.time - lastAct.time) > activityThresholdMs) : true
        def statusBad   = rawStatus in ["OFFLINE", "INACTIVE", "NOT PRESENT"]
        def healthBad   = rawHealthSt == "offline"
        if (!(statusBad || healthBad || lateActivity)) return
        def lastActStr  = lastAct ? lastAct.format("yyyy-MM-dd hh:mm a", location.timeZone)
                                  : "<span style='color:red;'>Never</span>"
        healthPool << [
            displayName  : dev.displayName,
            room         : resolveLocalRoom(dev, roomMap),
            hub          : hub1LabelVal,
            linkUrl      : "/device/edit/${dev.id}",
            status       : rawStatus ?: (rawHealthSt == "offline" ? "OFFLINE" : (rawHealthSt == "online" ? "ONLINE" : "—")),
            lastActivity : lastAct,
            lastActivityStr: lastActStr,
            issue        : buildHealthIssueLabel(rawStatus, rawHealthSt, lateActivity, activityThreshHours)
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
                 issue: e.issue]
            })
        }
    }

    return [onPool: onPool, offPool: offPool, healthPool: healthPool, warnings: warnings]
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
        httpGet([uri: uri, contentType: "application/json", timeout: 10]) { resp ->
            if (resp.status != 200) {
                warning = "${hubLabel}: Unexpected HTTP status ${resp.status}."
                return
            }
            resp.data?.each { dev ->
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
                        httpGet([uri: "http://${ip}/apps/api/${appId}/devices/${devId}?access_token=${token}",
                                 contentType: "application/json", timeout: 5]) { devResp ->
                            if (devResp.status == 200) {
                                def da = devResp.data?.attributes
                                if (da instanceof List) {
                                    def sw2 = da.find { a -> a?.name?.toString() == "switch" }
                                    switchVal = sw2?.currentValue?.toString()?.toLowerCase()
                                } else if (da instanceof Map) {
                                    switchVal = da["switch"]?.toString()?.toLowerCase()
                                }
                            }
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
        httpGet([uri: "http://${ip}/apps/api/${appId}/devices?access_token=${token}",
                 contentType: "application/json", timeout: 10]) { listResp ->
            if (listResp.status == 200 && listResp.data instanceof List) {
                liveIds = listResp.data.collect { it.id?.toString() } as Set
                if (enableLogging) log.debug "${hubLabel}: live device list has ${liveIds.size()} IDs"
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
            httpGet([uri: uri, contentType: "application/json", timeout: 10]) { resp ->
                if (resp.status != 200) { errors << "device ${devId}: HTTP ${resp.status}"; return }
                def dev = resp.data
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
                            httpGet([uri: "http://${ip}/apps/api/${appId}/devices/${parentId}?access_token=${token}",
                                     contentType: "application/json", timeout: 10]) { pResp ->
                                if (pResp.status == 200 && pResp.data)
                                    pd = parseRemoteLastActivity(pResp.data.lastActivity, hubLabel, "${parentId}-p")
                            }
                        } catch (ignored) {}
                        if (pd == null) pd = fetchLastActivityFromEvents(ip, appId, token, parentId, hubLabel)
                        parentCache[parentId] = pd
                        if (enableLogging) log.debug "${hubLabel} parent ${parentId} resolved lastActivity: ${pd}"
                    }
                    lastActDate = parentCache[parentId]
                    if (enableLogging && lastActDate) log.debug "${hubLabel} ${devId}: using parent ${dev.parentDeviceId} lastActivity ${lastActDate}"
                }
                // ─────────────────────────────────────────────────────────────

                def statusBad    = rawStatus in ["OFFLINE", "INACTIVE", "NOT PRESENT"]
                def healthBad    = rawHealthSt == "offline"
                def lateActivity = lastActDate ? ((now.time - lastActDate.time) > activityThresholdMs) : true

                if (!(statusBad || healthBad || lateActivity)) return

                def lastActStr = lastActDate
                    ? lastActDate.format("yyyy-MM-dd hh:mm a", location.timeZone)
                    : "<span style='color:red;'>Never</span>"

                results << [
                    devId          : devId,
                    displayName    : (dev.label ?: dev.name ?: "Unknown").toString(),
                    room           : (dev.room ?: "—").toString(),
                    linkUrl        : "http://${ip}/device/edit/${devId}",
                    status         : rawStatus ?: (rawHealthSt == "offline" ? "OFFLINE" : (rawHealthSt == "online" ? "ONLINE" : "—")),
                    lastActivity   : lastActDate,
                    lastActivityStr: lastActStr,
                    issue          : buildHealthIssueLabel(rawStatus, rawHealthSt, lateActivity, activityThreshHours)
                ]
            }
        } catch (java.net.SocketTimeoutException e) {
            errors << "device ${devId}: timed out"
            if (enableLogging) log.warn "${hubLabel} device ${devId}: timeout — ${e}"
        } catch (java.net.ConnectException e) {
            hubUnreachable = true
            warning = "${hubLabel} (${ip}): Could not connect (health check) — check IP address."
        } catch (Exception e) {
            errors << "device ${devId}: ${e.message}"
            if (enableLogging) log.warn "${hubLabel} device ${devId}: error — ${e.message}"
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
        httpGet([uri: uri, contentType: "application/json", timeout: 10]) { resp ->
            if (resp.status == 200 && resp.data instanceof List && resp.data.size() > 0) {
                def event   = resp.data[0]
                def dateVal = event.date ?: event.time ?: event.isoDate
                result = parseRemoteLastActivity(dateVal, hubLabel, "${devId}-evt")
                if (enableLogging) log.debug "${hubLabel} ${devId}: events → raw='${dateVal}' parsed=${result}"
            } else if (enableLogging) {
                log.debug "${hubLabel} ${devId}: events → status=${resp.status} count=${resp.data instanceof List ? resp.data.size() : 'n/a'}"
            }
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
                                     boolean lateActivity, long threshHours) {
    def reasons = []
    if (rawStatus in ["OFFLINE", "INACTIVE", "NOT PRESENT"]) reasons << rawStatus
    // Only add HEALTH OFFLINE if it's not already covered by rawStatus == "OFFLINE"
    if (rawHealthSt == "offline" && !reasons.contains("OFFLINE")) reasons << "HEALTH OFFLINE"
    if (lateActivity) reasons << "Late Activity (>${threshHours}h)"
    return reasons ? reasons.join(", ") : "—"
}

// ─────────────────────────────────────────────────────────────────────────────
// REPORT TABLE GENERATION
// ─────────────────────────────────────────────────────────────────────────────

private Map generateReportTables() {
    def data        = collectAllDeviceStates()
    def onPool      = data.onPool
    def offPool     = data.offPool
    def healthPool  = data.healthPool
    def warnings    = data.warnings
    def showUnknown = settings["showUnknownTable"] != false
    def showHealth  = settings["showHealthTable"]  != false

    def html = ""
    if (warnings) warnings.each { w -> html += "<p style='color:red;font-weight:bold;'>⚠ ${w}</p>" }

    // Devices selected for BOTH the ON-monitor list and the OFF-monitor list
    def onPoolLinks  = onPool.collect  { it.linkUrl } as Set
    def offPoolLinks = offPool.collect { it.linkUrl } as Set
    def bothLinks    = onPoolLinks.intersect(offPoolLinks)

    // ON table
    html += buildTable(onPool.findAll { it.switchVal == "on" },
        "Devices that are ON", "table_on", "#cc0000", "ON", "color:red;font-weight:bold;",
        settings["sortByOn"] ?: "displayName", settings["sortOrderOn"] ?: "asc", "off",
        bothLinks, "Also monitored for OFF state")

    // OFF table
    html += "<br>"
    html += buildTable(offPool.findAll { it.switchVal == "off" },
        "Devices that are OFF", "table_off", "#1a7a1a", "OFF", "color:#444;font-weight:bold;",
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
            "Devices with Unknown State", "table_unk", "#888888", "Unknown", "color:#888;font-weight:bold;",
            settings["sortByUnk"] ?: "displayName", settings["sortOrderUnk"] ?: "asc", "both")
    }

    // Health / Activity Monitor table
    if (showHealth) {
        html += "<br>"
        html += buildHealthTable(healthPool,
            "Health / Activity Monitor", "table_health", "#CC6600",
            settings["sortByHealth"]    ?: "displayName",
            settings["sortOrderHealth"] ?: "asc",
            (settings["activityThresholdHours"] ?: 24) as long)
    }

    return [html: html]
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
        html += "<table id='${tableId}' class='on-table' cellpadding='0' cellspacing='0' style='--hdr-bg:${headerColor};table-layout:fixed;width:100%;'>"
        html += "<colgroup><col style='width:40%'><col style='width:20%'><col style='width:18%'><col style='width:7%'><col style='width:15%'></colgroup>"
        html += "<thead><tr>"
        html += "<th onclick='sortOnTable(\"${tableId}\",0)' class='${sortColIdx == 0 ? sortClass : ""}'>Device Name</th>"
        html += "<th onclick='sortOnTable(\"${tableId}\",1)' class='${sortColIdx == 1 ? sortClass : ""}'>Room</th>"
        html += "<th onclick='sortOnTable(\"${tableId}\",2)' class='${sortColIdx == 2 ? sortClass : ""}'>Hub</th>"
        html += "<th onclick='sortOnTable(\"${tableId}\",3)'>State</th>"
        html += "<th style='cursor:default;'>Action</th>"
        html += "</tr></thead><tbody>"
        devices.each { it ->
            html += "<tr>"
            def inBoth = bothLinks?.contains(it.linkUrl)
            def nameInner = inBoth
                ? ("<span style='color:#cc6600;' title='${bothTooltip}'>${it.displayName}</span>"
                   + "&nbsp;<small style='color:#cc6600;font-size:0.8em;'>&#x2605;</small>")
                : it.displayName
            html += "<td><a href='${it.linkUrl}' target='_blank'>${nameInner}</a></td>"
            html += "<td>${it.room}</td>"
            html += "<td class='hub-col'>${it.hub}</td>"
            html += "<td class='state-col' style='${stateStyle}'>${stateLabel}</td>"
            html += "<td class='action-col'>${buildToggleButton(it, toggleCmd)}</td>"
            html += "</tr>"
        }
        html += "</tbody></table>"
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
        case "status":       sortColIdx = 3; break
        case "lastActivity": sortColIdx = 4; break
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
               "<small><i>Flagged when OFFLINE, INACTIVE, NOT PRESENT, or last activity &gt; ${threshHours}h ago.</i></small><br>"

    if (count > 0) {
        html += "<table id='${tableId}' class='on-table' cellpadding='0' cellspacing='0' " +
                "style='--hdr-bg:${headerColor};table-layout:fixed;width:100%;'>"
        html += "<colgroup>" +
                "<col style='width:28%'><col style='width:13%'><col style='width:11%'>" +
                "<col style='width:11%'><col style='width:19%'><col style='width:18%'>" +
                "</colgroup>"
        html += "<thead><tr>"
        html += "<th onclick='sortOnTable(\"${tableId}\",0)' class='${sortColIdx == 0 ? sortClass : ""}'>Device Name</th>"
        html += "<th onclick='sortOnTable(\"${tableId}\",1)' class='${sortColIdx == 1 ? sortClass : ""}'>Room</th>"
        html += "<th onclick='sortOnTable(\"${tableId}\",2)' class='${sortColIdx == 2 ? sortClass : ""}'>Hub</th>"
        html += "<th onclick='sortOnTable(\"${tableId}\",3)' class='${sortColIdx == 3 ? sortClass : ""}'>HE Status</th>"
        html += "<th onclick='sortOnTable(\"${tableId}\",4)' class='${sortColIdx == 4 ? sortClass : ""}'>Last Activity</th>"
        html += "<th onclick='sortOnTable(\"${tableId}\",5)'>Issue</th>"
        html += "</tr></thead><tbody>"

        devices.each { it ->
            def statusStyle = (it.status in ["OFFLINE", "INACTIVE", "NOT PRESENT"])
                ? "color:red;font-weight:bold;text-align:center;white-space:nowrap;"
                : "text-align:center;white-space:nowrap;"
            html += "<tr>"
            html += "<td><a href='${it.linkUrl}' target='_blank'>${it.displayName}</a></td>"
            html += "<td>${it.room}</td>"
            html += "<td class='hub-col'>${it.hub}</td>"
            html += "<td style='${statusStyle}'>${it.status}</td>"
            html += "<td style='white-space:nowrap;font-size:0.9em;'>${it.lastActivityStr}</td>"
            html += "<td style='color:#CC4400;font-size:0.9em;white-space:normal;'>${it.issue}</td>"
            html += "</tr>"
        }
        html += "</tbody></table>"
    }
    return html
}

// ─────────────────────────────────────────────────────────────────────────────
// TOGGLE BUTTON BUILDER
// ─────────────────────────────────────────────────────────────────────────────

private String buildToggleButton(Map device, String toggleCmd) {
    def onUrl  = device.toggleOnUrl  ?: ""
    def offUrl = device.toggleOffUrl ?: ""

    if (!onUrl && !offUrl) return "<span style='color:#ccc;'>—</span>"

    def safeOn  = onUrl.replace("'", "\\'")
    def safeOff = offUrl.replace("'", "\\'")

    switch (toggleCmd) {
        case "off":
            return "<button class='toggle-btn' " +
                   "data-on-url='${safeOn}' data-off-url='${safeOff}' data-current='on' " +
                   "onclick='toggleDevice(this)'>Turn OFF</button>"

        case "on":
            return "<button class='toggle-btn' " +
                   "data-on-url='${safeOn}' data-off-url='${safeOff}' data-current='off' " +
                   "onclick='toggleDevice(this)'>Turn ON</button>"

        case "both":
        default:
            def b1 = onUrl  ? "<button class='toggle-btn toggle-btn-sm' " +
                               "data-on-url='${safeOn}' data-off-url='${safeOff}' data-current='unknown' " +
                               "onclick='toggleDevice(this,\"on\")'  >→ ON</button>" : ""
            def b2 = offUrl ? "<button class='toggle-btn toggle-btn-sm' " +
                               "data-on-url='${safeOn}' data-off-url='${safeOff}' data-current='unknown' " +
                               "onclick='toggleDevice(this,\"off\")' >→ OFF</button>" : ""
            return b1 + (b1 && b2 ? "&nbsp;" : "") + b2
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

// ── Device toggle ─────────────────────────────────────────────────────────────
async function toggleDevice(btn, forceCmd) {
    const current   = btn.dataset.current;
    const targetCmd = forceCmd || (current === 'on' ? 'off' : 'on');
    const url       = btn.dataset[targetCmd + 'Url'];
    if (!url) return;

    const originalText = btn.textContent;
    btn.disabled = true;
    btn.textContent = '…';

    try {
        const resp = await fetch(url);
        if (resp.ok) {
            const row     = btn.closest('tr');
            const stateTd = row.querySelector('.state-col');
            if (current !== 'unknown') {
                if (targetCmd === 'off') {
                    stateTd.textContent = 'OFF';
                    stateTd.style.cssText = 'color:#444;font-weight:bold;text-align:center;white-space:nowrap;';
                    btn.dataset.current = 'off';
                    btn.textContent = 'Turn ON';
                } else {
                    stateTd.textContent = 'ON';
                    stateTd.style.cssText = 'color:red;font-weight:bold;text-align:center;white-space:nowrap;';
                    btn.dataset.current = 'on';
                    btn.textContent = 'Turn OFF';
                }
            } else {
                btn.textContent = '✓ Sent';
                setTimeout(() => { btn.textContent = originalText; }, 2000);
            }
            btn.disabled = false;
        } else {
            btn.textContent = '⚠ ' + resp.status;
            setTimeout(() => { btn.textContent = originalText; btn.disabled = false; }, 3000);
        }
    } catch (err) {
        btn.textContent = '⚠ Error';
        setTimeout(() => { btn.textContent = originalText; btn.disabled = false; }, 3000);
    }
}
</script>
<style>
.on-table { border-collapse:collapse; width:100%; table-layout:fixed; }
.on-table th {
    cursor:pointer; user-select:none;
    background-color: var(--hdr-bg, #FFD700);
    color:#fff; font-weight:bold;
    border:1px solid #555; white-space:nowrap; padding:4px 6px;
}
.on-table td { border:1px solid #aaa; padding:4px 6px; word-break:break-word; }
.on-table th:not(:last-child):hover { opacity:0.85; }
.on-table th.sort-asc::after  { content:' ▲'; font-size:0.8em; }
.on-table th.sort-desc::after { content:' ▼'; font-size:0.8em; }
.on-table td.state-col  { text-align:center; white-space:nowrap; width:1%; }
.on-table td.hub-col    { white-space:nowrap; width:1%; }
.on-table td.action-col { text-align:center; white-space:nowrap; }
.toggle-btn {
    font-size:0.8em; padding:2px 8px; cursor:pointer;
    border-radius:3px; border:1px solid #888;
    background:#f5f5f5; white-space:nowrap;
}
.toggle-btn:hover:not(:disabled) { background:#e0e0e0; }
.toggle-btn:disabled { opacity:0.5; cursor:wait; }
.toggle-btn-sm { padding:2px 5px; }
</style>"""
}

// ─────────────────────────────────────────────────────────────────────────────
// HELPERS
// ─────────────────────────────────────────────────────────────────────────────

private boolean hasSwitchCapability(def caps) {
    if (!caps) return true
    def capsList   = (caps instanceof List ? caps : [caps])
    if (!capsList)  return true
    def switchCaps = ["switch", "light", "outlet"] as Set
    return capsList.any { c ->
        def name = (c instanceof Map) ? (c.title ?: c.name ?: "").toString().toLowerCase()
                                      : c?.toString()?.toLowerCase() ?: ""
        name in switchCaps
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
