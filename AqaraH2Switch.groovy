/**
 *  Aqara H2 US 2-Button 1-Channel Switch - Hubitat Driver
 *
 *  Supports: lumi.switch.agl004, lumi.switch.agl009
 *
 *  Based on prestonbrown/hubitat-drivers (AqaraZigbeeDimmer.groovy)
 *  https://github.com/prestonbrown/hubitat-drivers
 *
 *  Features:
 *  - Relay on/off control via endpoint 01
 *  - Button 1 (top) and Button 3 (bottom) push/hold/double-tap events on parent device
 *  - No child device creation
 */

metadata {
    definition(name: "Aqara H2 US 2-Button Switch", namespace: "krazik", author: "Rylan Hazelton") {
        capability "Switch"
        capability "PushableButton"
        capability "HoldableButton"
        capability "DoubleTapableButton"
        capability "Refresh"
        capability "Configuration"

        // Top button = 1, bottom button = 3 (as reported by hardware)
        attribute "numberOfButtons", "number"

        fingerprint profileId: "0104", endpointId: "01",
                    inClusters: "0000,0003,0004,0005,0006,000A,0B05,FCC0",
                    outClusters: "000A,0019",
                    model: "lumi.switch.agl004", manufacturer: "LUMI"

        fingerprint profileId: "0104", endpointId: "01",
                    inClusters: "0000,0003,0004,0005,0006,000A,0B05,FCC0",
                    outClusters: "000A,0019",
                    model: "lumi.switch.agl009", manufacturer: "LUMI"
    }

    preferences {
        input name: "enableDebugLogging", type: "bool", title: "Enable Debug Logging", defaultValue: false
    }
}

def installed() {
    logInfo("installed()")
    sendEvent(name: "numberOfButtons", value: 3)
    refresh()
}

def updated() {
    logInfo("updated()")
    sendEvent(name: "numberOfButtons", value: 3)
    refresh()
}

def configure() {
    logInfo("configure()")
    def cmds = []
    cmds += zigbee.configureReporting(0x0006, 0x0000, 0x10, 0, 3600, null)
    cmds += zigbee.readAttribute(0x0006, 0x0000)
    return cmds
}

def refresh() {
    logDebug("refresh()")
    return zigbee.readAttribute(0x0006, 0x0000)
}

def on() {
    logDebug("on()")
    return zigbee.on()
}

def off() {
    logDebug("off()")
    return zigbee.off()
}

def parse(String description) {
    logDebug("parse: $description")

    if (description?.startsWith("read attr -") || description?.startsWith("attr report -")) {
        def descMap = zigbee.parseDescriptionAsMap(description)
        if (descMap.cluster == "0006" && descMap.attrId == "0000") {
            def value = descMap.value == "01" ? "on" : "off"
            logInfo("Switch state: $value")
            return createEvent(name: "switch", value: value)
        }
    }

    if (description?.startsWith("catchall:")) {
        def descMap = zigbee.parseDescriptionAsMap(description)
        logDebug("catchall descMap: $descMap")

        if (descMap.clusterInt == 0x0006) {
            return parseButtonEvent(descMap)
        }
    }

    return []
}

private parseButtonEvent(Map descMap) {
    // Endpoint determines which button: 01 = top (button 1), 03 = bottom (button 3)
    def endpointInt = Integer.parseInt(descMap.sourceEndpoint ?: descMap.endpoint ?: "0", 16)
    def buttonNumber = endpointInt == 0x03 ? 3 : 1

    def commandInt = descMap.commandInt != null ? descMap.commandInt : Integer.parseInt(descMap.command ?: "0", 16)

    String action
    String eventName

    switch (commandInt) {
        case 0x01:
            action = "pushed"
            eventName = "pushed"
            break
        case 0xFD:
            action = "held"
            eventName = "held"
            break
        case 0x02:
            action = "doubleTapped"
            eventName = "doubleTapped"
            break
        default:
            logDebug("Unhandled button command: 0x${Integer.toHexString(commandInt)} on endpoint 0x${Integer.toHexString(endpointInt)}")
            return []
    }

    logInfo("Button $buttonNumber $action")
    return createEvent(name: eventName, value: buttonNumber, isStateChange: true)
}

private logInfo(String msg) {
    log.info("[AqaraH2Switch] $msg")
}

private logDebug(String msg) {
    if (enableDebugLogging) log.debug("[AqaraH2Switch] $msg")
}
