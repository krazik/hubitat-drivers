/**
 *  Aqara H2 US 2-Button 1-Channel Switch - Hubitat Driver
 *
 *  Supports: lumi.switch.agl004, lumi.switch.agl009
 *  Tested with: H2 US 2-Button 1-Channel (lumi.switch.agl004)
 *
 *  Based on prestonbrown/hubitat-drivers (AqaraZigbeeDimmer.groovy)
 *  https://github.com/prestonbrown/hubitat-drivers
 *
 *  ── How this driver works ───────────────────────────────────────────────────
 *
 *  The device exposes two separate Zigbee endpoints:
 *    Endpoint 01 → top button    (also controls the physical relay)
 *    Endpoint 03 → bottom button (no relay — buttons only)
 *
 *  Button press events arrive on Zigbee cluster 0x0012 (Multistate Input),
 *  attribute 0x0055. The endpoint tells us which button; the value tells us
 *  the action:  1 = pushed,  2 = doubleTapped,  255 = held
 *
 *  The relay (switch on/off) is reported separately on cluster 0x0006.
 *  Note: the bottom button does NOT have a relay — only the top button does.
 *
 *  ── Button numbers & how to automate them ───────────────────────────────────
 *
 *  Button 1 = top button     → pushed / held / doubleTapped
 *  Button 3 = bottom button  → pushed / held / doubleTapped
 *  (Button 2 is unused — the hardware skips it)
 *
 *  In Rule Machine or Button Controller, trigger on:
 *    "Button 1 pushed"   → top button single press
 *    "Button 1 held"     → top button press-and-hold
 *    "Button 1 doubleTapped" → top button double press
 *    "Button 3 pushed"   → bottom button single press
 *    "Button 3 held"     → bottom button press-and-hold
 *    "Button 3 doubleTapped" → bottom button double press
 *
 *  ── Child devices ───────────────────────────────────────────────────────────
 *
 *  This driver intentionally creates NO child devices. All button events and
 *  the switch state are reported on the parent device. If Hubitat created child
 *  devices before you assigned this driver, they can be safely deleted.
 *
 *  ────────────────────────────────────────────────────────────────────────────
 *
 *  Licensed under the Apache License, Version 2.0
 */

import hubitat.helper.HexUtils
import groovy.transform.Field

@Field static final String VERSION     = "1.0.7"
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

        command "setBottomLED", [[name:"mode", type:"ENUM", constraints:["on","off","relay"]]]
        attribute "bottomLED", "string"

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
        input name: "logEnable",  type: "bool", title: "Enable debug logging", defaultValue: true
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

List<String> setBottomLED(String mode) {
    // Controls bottom button LED via Aqara FCC0 cluster, endpoint 03
    // mode "on"    = LED always on   (value 0x02)
    // mode "off"   = LED always off  (value 0x01)
    // mode "relay" = follows relay   (value 0x00) - may not work without relay
    Integer value = mode == "on" ? 0x02 : (mode == "off" ? 0x01 : 0x00)
    logTxt "Setting bottom LED to ${mode}"
    sendEvent(name: "bottomLED", value: mode)
    return ["he wattr 0x${device.deviceNetworkId} 0x03 0xFCC0 0x0203 0x20 {${intToHex(value,1)}} {115F}"]
}

List<String> on() {
    logTxt "Turning on"
    return ["he cmd 0x${device.deviceNetworkId} 0x01 0x0006 0x01 {}"]
}

List<String> off() {
    logTxt "Turning off"
    return ["he cmd 0x${device.deviceNetworkId} 0x01 0x0006 0x00 {}"]
}

void push(buttonNumber) {
    logTxt "Button ${buttonNumber} pushed (digital)"
    sendEvent(name: "pushed", value: buttonNumber as Integer, isStateChange: true, type: "digital")
}

void hold(buttonNumber) {
    logTxt "Button ${buttonNumber} held (digital)"
    sendEvent(name: "held", value: buttonNumber as Integer, isStateChange: true, type: "digital")
}

void doubleTap(buttonNumber) {
    logTxt "Button ${buttonNumber} doubleTapped (digital)"
    sendEvent(name: "doubleTapped", value: buttonNumber as Integer, isStateChange: true, type: "digital")
}

// ==================== PARSE ====================

void parse(String description) {
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
    if (descMap.attrId != "0055" || descMap.value == null) return

    String endpoint = descMap.sourceEndpoint ?: descMap.endpoint
    Integer buttonNum = (endpoint == "01") ? 1 : (endpoint == "02" ? 2 : 3)

    Integer actionValue = Integer.parseInt(descMap.value, 16)
    String action
    switch (actionValue) {
        case 0:   action = "released";     break
        case 1:   action = "pushed";       break
        case 2:   action = "doubleTapped"; break
        case 255: action = "held";         break
        default:  action = "pushed"
    }

    logTxt "Button ${buttonNum} ${action}"
    sendEvent(name: action, value: buttonNum, isStateChange: true, type: "physical")
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
    // Uses Hubitat's built-in lastActivity (updated automatically on any Zigbee message)
    def lastActivity = device.lastActivity
    if (!lastActivity) return

    Integer elapsed = ((now() - lastActivity.time) / 1000).intValue()
    Integer timeout = ((healthCheckInterval ?: "60") as Integer) * 2 * 60

    if (elapsed > timeout) {
        if (device.currentValue("healthStatus") != "offline") {
            sendEvent(name: "healthStatus", value: "offline")
            log.warn "${device.displayName}: Device offline (no response for ${elapsed}s)"
        }
    } else {
        if (device.currentValue("healthStatus") != "online") {
            sendEvent(name: "healthStatus", value: "online")
            logTxt "Device back online"
        }
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
