/**
 *  Aqara H2 US 2-Button 1-Channel Switch - Hubitat Driver
 *
 *  Supports: lumi.switch.agl004, lumi.switch.agl009
 *  Tested with: H2 US 2-Button 1-Channel (lumi.switch.agl004)
 *
 *  Button mapping (verified on hardware):
 *    Top button    → Button 1 (endpoint 01)
 *    Bottom button → Button 3 (endpoint 03)
 *
 *  Based on prestonbrown/hubitat-drivers (AqaraZigbeeDimmer.groovy)
 *  https://github.com/prestonbrown/hubitat-drivers
 *
 *  Licensed under the Apache License, Version 2.0
 */

import hubitat.helper.HexUtils
import groovy.transform.Field

@Field static final String VERSION     = "1.0.0"
@Field static final String DRIVER_NAME = "Aqara H2 US 2-Button Switch"

@Field static final Integer CLUSTER_ON_OFF    = 0x0006
@Field static final Integer CLUSTER_MULTISTATE = 0x0012

metadata {
    definition(
        name: DRIVER_NAME,
        namespace: "krazik",
        author: "Rylan Hazelton",
        singleThreaded: true
    ) {
        capability "Switch"
        capability "PushableButton"
        capability "HoldableButton"
        capability "DoubleTapableButton"
        capability "Configuration"
        capability "Refresh"
        capability "HealthCheck"

        // Top = button 1, bottom = button 3 (as reported by hardware)
        attribute "numberOfButtons", "number"
        attribute "driverVersion",   "string"
        attribute "healthStatus",    "enum", ["online", "offline"]
        attribute "lastCheckin",     "string"

        fingerprint profileId: "0104", endpointId: "01",
                    inClusters: "0000,0003,0004,0005,0006,000A,0012,0B05,FCC0",
                    outClusters: "000A,0019",
                    model: "lumi.switch.agl004", manufacturer: "LUMI",
                    deviceJoinName: "Aqara H2 US 2-Button Switch"

        fingerprint profileId: "0104", endpointId: "01",
                    inClusters: "0000,0003,0004,0005,0006,000A,0012,0B05,FCC0",
                    outClusters: "000A,0019",
                    model: "lumi.switch.agl009", manufacturer: "LUMI",
                    deviceJoinName: "Aqara H2 US 2-Button Switch"
    }

    preferences {
        input name: "logEnable",  type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "txtEnable",  type: "bool", title: "Enable info logging",  defaultValue: true
        input name: "healthCheckInterval", type: "enum", title: "Health check interval",
              options: ["10": "10 minutes", "30": "30 minutes", "60": "1 hour"], defaultValue: "60"
    }
}

// ==================== LIFECYCLE ====================

void installed() {
    logTxt "Installed"
    sendEvent(name: "numberOfButtons", value: 3)
    sendEvent(name: "driverVersion",   value: VERSION)
    sendEvent(name: "healthStatus",    value: "online")
    configure()
}

void updated() {
    logTxt "Preferences updated"
    sendEvent(name: "driverVersion", value: VERSION)
    unschedule()
    scheduleHealthCheck()
    if (logEnable) {
        logTxt "Debug logging will auto-disable in 30 minutes"
        runIn(1800, "logsOff")
    }
}

List<String> configure() {
    logTxt "Configuring"
    List<String> cmds = []
    cmds += zigbee.configureReporting(CLUSTER_ON_OFF, 0x0000, 0x10, 0, 3600, null)
    cmds += zigbee.readAttribute(CLUSTER_ON_OFF, 0x0000)
    return cmds
}

List<String> refresh() {
    logTxt "Refreshing"
    return zigbee.readAttribute(CLUSTER_ON_OFF, 0x0000)
}

// ==================== COMMANDS ====================

List<String> on() {
    logTxt "Turning on"
    return ["he cmd 0x${device.deviceNetworkId} 0x01 0x0006 0x01 {}"]
}

List<String> off() {
    logTxt "Turning off"
    return ["he cmd 0x${device.deviceNetworkId} 0x01 0x0006 0x00 {}"]
}

// ==================== PARSE ====================

void parse(String description) {
    checkHealth()

    Map descMap
    try {
        descMap = zigbee.parseDescriptionAsMap(description)
    } catch (e) {
        log.warn "${device.displayName}: Failed to parse: ${description}"
        return
    }

    if (!descMap) return

    Integer clusterInt = descMap.clusterInt ?: (descMap.cluster ? Integer.parseInt(descMap.cluster, 16) : null)
    if (clusterInt == null) return

    logDebug "cluster: 0x${String.format('%04X', clusterInt)}, endpoint: ${descMap.sourceEndpoint ?: descMap.endpoint}, cmd: ${descMap.command}, attrId: ${descMap.attrId}, value: ${descMap.value}"

    switch (clusterInt) {
        case CLUSTER_ON_OFF:
            parseOnOffCluster(descMap)
            break
        case CLUSTER_MULTISTATE:
            parseMultistateCluster(descMap)
            break
        default:
            logDebug "Unhandled cluster: 0x${String.format('%04X', clusterInt)}"
    }
}

void parseOnOffCluster(Map descMap) {
    if (descMap.attrId == "0000" && descMap.value != null) {
        String value = (descMap.value == "01") ? "on" : "off"
        logTxt "Switch is ${value}"
        sendEvent(name: "switch", value: value, type: "physical")
    }
}

void parseMultistateCluster(Map descMap) {
    // Cluster 0x0012 attr 0x0055 carries button action value on some Aqara firmwares
    if (descMap.attrId != "0055" || descMap.value == null) return

    String ep = descMap.sourceEndpoint ?: descMap.endpoint ?: "01"
    Integer endpointInt = Integer.parseInt(ep, 16)
    Integer buttonNumber = (endpointInt == 0x03) ? 3 : 1

    Integer actionValue = Integer.parseInt(descMap.value, 16)
    String action
    switch (actionValue) {
        case 1:   action = "pushed";       break
        case 2:   action = "doubleTapped"; break
        case 255: action = "held";         break
        default:
            logDebug "Unhandled multistate value: ${actionValue} on endpoint ${ep}"
            return
    }

    logTxt "Button ${buttonNumber} ${action} (multistate)"
    sendEvent(name: action, value: buttonNumber, isStateChange: true, type: "physical")
}

// ==================== HEALTH CHECK ====================

void scheduleHealthCheck() {
    Integer interval = (healthCheckInterval ?: "60") as Integer
    switch (interval) {
        case 10: runEvery10Minutes("healthCheck"); break
        case 30: runEvery30Minutes("healthCheck"); break
        default: runEvery1Hour("healthCheck")
    }
}

void healthCheck() {
    String lastCheckin = device.currentValue("lastCheckin")
    if (!lastCheckin) return

    Date lastDate = Date.parse("yyyy-MM-dd HH:mm:ss", lastCheckin)
    Integer elapsed = ((now() - lastDate.time) / 1000).intValue()
    Integer timeout = ((healthCheckInterval ?: "60") as Integer) * 2 * 60

    if (elapsed > timeout && device.currentValue("healthStatus") != "offline") {
        sendEvent(name: "healthStatus", value: "offline")
        log.warn "${device.displayName}: Device offline (no response for ${elapsed}s)"
    }
}

void checkHealth() {
    sendEvent(name: "lastCheckin", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
    if (device.currentValue("healthStatus") != "online") {
        sendEvent(name: "healthStatus", value: "online")
        logTxt "Device back online"
    }
}

void ping() { refresh() }

// ==================== UTILITIES ====================

void logDebug(String msg) {
    if (logEnable) log.debug "${device.displayName}: ${msg}"
}

void logTxt(String msg) {
    if (txtEnable) log.info "${device.displayName}: ${msg}"
}

void logsOff() {
    log.info "${device.displayName}: Debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}
