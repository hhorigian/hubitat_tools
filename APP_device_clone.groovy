/*
 *  Device Clone
 * 
 *
 *  Licensed Virtual the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WIyTHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *    Date            Who                    Description
 *    -------------   -------------------    ---------------------------------------------------------
 *    Description: Clone an existing device into N devices using the SAME driver as the original. 
 *
 *   HHorigian: 
 *	- Handles built-in "Virtual *" drivers by trying namespace "hubitat" automatically.
 *  — Namespace-Probing
 *  - Clone an existing device into N child devices using the SAME driver as the original.
 *  - Copies data values, current attribute values, and (where possible) preferences/settings.
 *  - Learns/auto-remembers successful (typeName → namespace) in state.nsMap
 *  - Bulk aliases text box: "Driver A=nsA; Driver B=nsB"
 *  - Tries case variants for candidate namespaces automatically
 */

definition(
    name:        "Device Clone(Any Driver)",
    namespace:   "thebearmay",
    author:      "Jean P. May, Jr.",
    description: "Clone any device into one or more child devices using the same driver, copying attributes and settings.",
    category:    "Utility",
    iconUrl:     "",
    iconX2Url:   "",
    iconX3Url:   ""
)

preferences { page(name: "mainPage") }

def version() { "1.3.4" }

/* ========================= Lifecycle ========================= */

def installed() { logInfo "Installed v${version()}"; initialize() }
def updated()   { logInfo "Updated v${version()}"; unsubscribe(); unschedule(); initialize() }
def initialize(){}

/* ========================= UI ========================= */

def mainPage() {
    dynamicPage(name: "mainPage", title: "Device Cloner (v${version()})", install: true, uninstall: true) {
        section("Select the device to clone") {
            input name: "selectedDev", type: "capability.*", title: "Device to clone",
                  multiple: false, required: true, submitOnChange: true
        }
        section("How many clones?") {
            input name: "cloneAmt", type: "number", title: "Number of clones to create",
                  defaultValue: 1, range: "1..100", required: true
        }
        section("Naming (optional)") {
            input name: "baseName", type: "text",
                  title: "Base label for new devices (default: original label/name + index)", required: false
        }
        section("Advanced options") {
            input name: "allowAllDrivers", type: "bool",
                  title: "Clone any driver (advanced) — skip virtual-only guard",
                  defaultValue: false, required: true
            input name: "preferredNamespace", type: "text",
                  title: "Preferred namespace to try first (optional)",
                  description: "e.g., 'hubitat', 'TRATO', 'community.ns'", required: false
            input name: "extraNamespaces", type: "text",
                  title: "Extra namespaces (comma-separated, optional)",
                  description: "e.g., 'TRATO, community.ns, another.ns'", required: false
            input name: "nsAliasesBulk", type: "text",
                  title: "Bulk aliases (type=namespace; type2=namespace2)",
                  description: "e.g., MolSmart - GW3 - RF=TRATO; My Driver=community.ns",
                  required: false
            input name: "applyNsAliases", type: "button", title: "Save aliases"
        }
        if (state?.nsMap) {
            section("Known driver namespaces") {
                paragraph state.nsMap.collect { k,v -> "${k} → ${v}" }.join("<br>")
            }
        }
        section("Logging") {
            input name: "debugEnabled", type: "bool", title: "Enable debug logging",
                  defaultValue: false, required: true
        }
        section("") {
            input name: "cloneNow", type: "button", title: "Clone Now"
        }
        if (state?.lastMsg) {
            section("Result") { paragraph state.lastMsg }
        }
    }
}

/* ========================= Button Handler ========================= */

def appButtonHandler(String btn) {
    if (btn == "applyNsAliases") {
        saveBulkAliases()
        state.lastMsg = "Aliases saved."
        logInfo "Namespace aliases updated."
        return
    }
    if (btn == "cloneNow") {
        def dev = getSelectedDevObj()
        if (!dev) {
            state.lastMsg = "Please select a valid device first."
            logWarn state.lastMsg; return
        }

        def ctrlType = (dev.hasProperty('controllerType')) ? dev.controllerType : null
        if (ctrlType && !settings?.allowAllDrivers) {
            state.lastMsg = "Device appears non-virtual (controllerType=${ctrlType}). Enable 'Clone any driver' to proceed."
            logWarn state.lastMsg; return
        }

        try {
            String msg = cloneDevice(dev)
            state.lastMsg = msg
            logInfo msg?.replaceAll(/<[^>]+>/,'')
        } catch (Throwable t) {
            state.lastMsg = "Error while cloning: ${t?.class?.simpleName}: ${t?.message}"
            logError state.lastMsg
        }
    }
}

/* ========================= Resolve Selection ========================= */

private def getSelectedDevObj() {
    def sel = settings?.selectedDev
    if (!sel) return null
    def candidate = (sel instanceof List) ? sel.find { it != null } : sel
    if (!candidate) return null
    if (candidate?.hasProperty('id') && candidate?.hasProperty('deviceNetworkId')) return candidate

    Long id = null
    if (candidate instanceof Number) id = (candidate as Number).longValue()
    else if (candidate instanceof CharSequence) {
        String token = candidate.toString().split(',')[0].trim()
        if (token ==~ /^\d+$/) id = token as Long
    }
    return id ? getDeviceById(id) : null
}

/* ========================= Core: Cloning ========================= */

private String cloneDevice(def selectedDev) {
    int created = 0
    int clones = safeInt(settings?.cloneAmt, 1); if (clones < 1) clones = 1

    String typeName  = selectedDev?.typeName ?: ""
    String nsFromDev = selectedDev?.typeNamespace ?: ""

    if (!typeName) return "<p>Could not determine the driver's name (typeName). Aborting.</p>"

    // Pull fullJson to replay preferences/settings if possible
    Map fullJson = [:]
    try {
        httpGet([uri:"http://127.0.0.1:8080", path:"/device/fullJson/${selectedDev.id}",
                 headers:["Connection-Timeout":600,"Accept":"application/json"]]) { resp ->
            fullJson = (resp?.data ?: [:]) as Map
        }
        logDebug "fullJson loaded with ${(fullJson?.settings ?: []).size()} settings"
    } catch (Throwable t) {
        logWarn "Unable to pull fullJson for ${selectedDev}: ${t?.message} (continuing)"
        fullJson = [:]
    }

    // Build candidate namespaces (ordered)
    List<String> nsCandidates = []
    String nsPreferred = (settings?.preferredNamespace ?: "").trim()
    if (nsPreferred) nsCandidates << nsPreferred
    if (nsFromDev)  nsCandidates << nsFromDev

    // Learned alias comes first if present
    String learned = lookupNamespaceAlias(typeName)
    if (learned) nsCandidates.add(0, learned)

    // Extract potential namespaces from fullJson (best effort; often empty)
    def maybeNS = []
    maybeNS << (fullJson?.driver?.namespace)
    maybeNS << (fullJson?.device?.typeNamespace)
    maybeNS << (fullJson?.metadata?.namespace)
    maybeNS << (fullJson?.typeNamespace)
    nsCandidates.addAll(maybeNS.findAll { it instanceof CharSequence && it.toString().trim() })

    // User-provided extra namespaces (comma-separated)
    String extras = (settings?.extraNamespaces ?: "")
    if (extras) nsCandidates.addAll(extras.split(',')*.trim().findAll{ it })

    // Heuristic for MolSmart naming (kept from earlier guidance)
    if (typeName?.toLowerCase()?.contains("molsmart")) nsCandidates << "TRATO"

    // Built-in fallback for virtuals
    if (typeName.startsWith("Virtual ")) nsCandidates << "hubitat"

    // Expand case variants and dedupe (order preserved)
    List<String> withVariants = []
    nsCandidates*.toString().collect{ it.trim() }.findAll{ it }.unique().each { ns ->
        withVariants << ns
        withVariants << ns.toUpperCase()
        withVariants << ns.toLowerCase()
    }
    nsCandidates = withVariants.collect{ it.trim() }.findAll{ it }.unique()

    logDebug "Driver: typeName='${typeName}', candidate namespaces=${nsCandidates}"

    String baseLabel = (settings?.baseName ?: selectedDev?.label ?: selectedDev?.name ?: "Clone")

    (0..<clones).each { idx ->
        String suffix   = (clones > 1) ? "-${idx+1}" : ""
        String newLabel = "${baseLabel}${suffix}"
        String newName  = "${(selectedDev?.name ?: selectedDev?.label ?: 'Device')}${suffix}"
        String newDNI   = buildUniqueDNI("${selectedDev?.deviceNetworkId ?: selectedDev?.id}-${now()}-${idx}")

        def nDev = createChildTryingNamespaces(nsCandidates, typeName, newDNI, [name:newName, label:newLabel])
        if (!nDev) {
            logError "Failed to create device '${newLabel}' with driver '${typeName}'"
            return // skip this iteration
        }

        // Copy device data values
        try {
            selectedDev?.data?.each { k, v -> if (k != null) nDev.updateDataValue("$k", "$v") }
        } catch (Throwable t) { logWarn "Could not copy data values: ${t?.message}" }

        // Copy current values for each supported attribute
        try {
            selectedDev?.supportedAttributes?.each { attr ->
                def aName = attr?.name
                if (aName) {
                    def aVal = selectedDev.currentValue(aName)
                    if (aVal != null) nDev.sendEvent(name: aName, value: aVal)
                }
            }
        } catch (Throwable t) { logWarn "Could not copy attribute values: ${t?.message}" }

        // Copy preferences/settings (best-effort) from fullJson
        try {
            fullJson?.settings?.each { s ->
                if (s?.name && s?.type != null) {
                    nDev.updateSetting("${s.name}", [value: "${s.value}", type: "${s.type}"])
                }
            }
        } catch (Throwable t) { logWarn "Could not copy settings: ${t?.message}" }

        created++
        logInfo "Created clone #${created}: ${nDev?.displayName} (DNI=${nDev?.deviceNetworkId})"
    }

    return "<p><b>${created}</b> device(s) created.</p>"
}

/* ========================= Creation helper ========================= */

private def createChildTryingNamespaces(List nsList, String typeName, String dni, Map props=[:]) {
    def nDev = null
    // Try (namespace, typeName, dni, props) for each namespace candidate
    for (String ns in nsList) {
        try {
            logDebug "Trying addChildDevice(ns='${ns}', type='${typeName}')"
            nDev = addChildDevice(ns, typeName, dni, props)
            if (nDev) {
                logDebug "Success with ns='${ns}'"
                rememberNamespace(typeName, ns)
                return nDev
            }
        } catch (Throwable t) {
            logWarn "addChildDevice failed with namespace '${ns}': ${t?.message}"
        }
    }
    // Very last resort: 2-arg overload (typeName, dni, props)
    try {
        logDebug "Trying addChildDevice(type='${typeName}', dni='${dni}') [2-arg overload]"
        nDev = addChildDevice(typeName, dni, props)
        if (nDev) {
            logDebug "Success with 2-arg overload"
            rememberNamespace(typeName, "__2ARG__") // sentinel
            return nDev
        }
    } catch (Throwable t) {
        logError "Final addChildDevice attempt failed for '${typeName}': ${t?.message}"
    }
    return null
}

/* ========================= Namespace memory & bulk aliases ========================= */

private void rememberNamespace(String typeName, String ns) {
    if (!typeName || !ns) return
    state.nsMap = (state.nsMap ?: [:]) as Map
    state.nsMap[typeName] = ns
    logDebug "Remembered namespace: '${typeName}' → '${ns}'"
}

private String lookupNamespaceAlias(String typeName) {
    return (state.nsMap ?: [:])[typeName] as String
}

private void saveBulkAliases() {
    String bulk = (settings?.nsAliasesBulk ?: "").trim()
    if (!bulk) return
    state.nsMap = (state.nsMap ?: [:]) as Map
    bulk.split(/\s*;\s*/).each { pair ->
        def kv = pair.split(/\s*=\s*/, 2)
        if (kv.size() == 2) {
            String t = kv[0].trim()
            String n = kv[1].trim()
            if (t && n) {
                state.nsMap[t] = n
                logDebug "Alias set: '${t}' → '${n}'"
            }
        }
    }
}

/* ========================= Helpers ========================= */

private String buildUniqueDNI(String candidate) {
    String base = candidate?.replaceAll(/\s+/, "_") ?: "clone-${now()}"
    String dni  = base
    int salt = 0
    while (getChildDevice(dni)) { salt++; dni = "${base}-${salt}" }
    return dni
}

private Integer safeInt(val, Integer d=0) {
    try { return (val == null) ? d : (val as Integer) } catch (Throwable t) { return d }
}

/* ========================= Logging ========================= */

private void logDebug(msg) { if (settings?.debugEnabled) log.debug "${app.label}: ${msg}" }
private void logInfo(msg)  { log.info  "${app.label}: ${msg}" }
private void logWarn(msg)  { log.warn  "${app.label}: ${msg}" }
private void logError(msg) { log.error "${app.label}: ${msg}" }
