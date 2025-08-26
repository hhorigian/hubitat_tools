/**
 * Hubitat Zigbee Driver for the Tuya Zigbee IR Remote Control Model ZS06 (also known as TS1201)
 *
 * This driver is based largely on the work already done to integrate this device with Zigbee2MQTT, aka zigbee-herdsman
 * https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/lib/zosung.ts
 * https://github.com/Koenkk/zigbee-herdsman/blob/master/src/zcl/definition/cluster.ts#L5260-L5359
 *
 * Zigbee command payloads for the ZS06 seem to be largely hex encoded structs.
 * In this driver, this mapping is handled by the toPayload && toStruct functions which convert a Map of
 * struct data into a hex byte string according to a given struct layout definition.
 *
 * The learn && sendCode commands consist of a back-and-forth sequence of command messages between
 * the hub && the device. The names for these messages are not official && just guesses. 
 * Here's an outline of the flow:
 *
 * learn sequence:
 *  1. hub sends 0xe004 0x00 (learn) with the JSON {"study":0} (as an ASCII hex byte string)
 *  2. device led illuminates, user sends IR code to the device using original remote
 *  3. device sends 0xed00 0x00 (start transmit) with a sequence value it generates + the code length 
 *     - All subsequent messages generally include this same sequence value
 *  4. hub sends 0xed00 0x01 (start transmit ack)
 *  5. device sends 0xed00 0x0B (ACK) with 0x01 as the command being acked
 *  6. hub sends 0xed00 0x02 (code data request) with a position (initially 0)
 *  7. device sends 0xed00 0x03 (code data response) with a chunk of the code data && a crc checksum
 *  [repeat (5) && (6) until the received data length matches the length given in (3)]
 *  8. hub sends 0xed00 0x04 (done sending)
 *  9. device sends 0xed00 0x05 (done receiving)
 *  10. hub sets "lastLearnedCode" (base64 value), 
 *      clears data associated with this sequence, 
 *      && sends 0xe004 0x00 (learn) with the JSON {"study":1}
 *  11. device led turns off
 *
 * sendCode sequence:
 *  1. hub sends 0xed00 0x00 (start transmit) with a generated sequence value + the code length
 *     - All subsequent messages generally include this same sequence value
 *  2. device sends 0xed00 0x01 (start transmit ack)
 *     - We ignore this
 *  3. device sends 0xed00 0x02 (code data request) with a position (initially 0)
 *  4. hub sends 0xed00 0x03 (code data response) with a chunk of the code data && a crc checksum
 *  [repeat (3) && (4) until the device sends 0xed00 0x04 (done sendng)]
 *  5. device sends 0xed00 0x04 (done sending)
 *  6. hub sends 0xed00 0x05 (done receiving), 
 *     clears data associated with this sequence
 *  7. device emits the IR code
 *
 * There are also various other "ACK" messages sent after each command.
 * In general, we do nothing in response to these (and the device doesn't appear to require we
 * send them in response to its messages).
 *
 *
 * All this code is adapted from Sean Anastasi. HHorigian made a new version with some add ons. 
 *
 * 25/08/2025   - Learn GlobaCache(SendIr) codes and display in Device Screen.
 * 				- Use the MolSmart IR web database to get IR Codes ready for device to send. 
 */

import groovy.transform.Field

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

import java.util.concurrent.ConcurrentHashMap

// These BEGIN && END comments are so this section can be snipped out in unit tests.
// I'm not sure what's necessary to make this syntax work in standard Groovy
// BEGIN METADATA
metadata {
    attribute "lastLearnedCodeGC", "STRING"

    
        capability "Actuator"
        capability "Switch"
        capability "PushableButton"

        attribute "power", "string"
        attribute "input", "string"
        attribute "action", "string"

        // Informative attributes (kept from original)
        attribute "Controle", "string"
        attribute "TipoControle", "string"
        attribute "Formato", "string"
        attribute "GetRemoteData", "string"

    
    command "num9"

    command "num8"

    command "num7"

    command "num6"

    command "num5"

    command "num4"

    command "num3"

    command "num2"

    command "num1"

    command "num0"

    command "volumeDown"

    command "volumeUp"

    command "channelDown"

    command "channelUp"

    command "hdmi2"

    command "hdmi1"

    command "exit"

    command "confirm"

    command "right"

    command "left"

    command "down"

    command "up"

    command "home"

    command "back"

    command "menu"

    command "source"

    command "mute"

    command "poweroff"

    command "poweron"

    command "GetRemoteDATA"

    command "powertoggle"    

    definition (name: "Tuya Zigbee IR Remote Control", namespace: "hubitat.anasta.si", author: "Sean Anastasi <sean@anasta.si>") {
        capability "PushableButton"

        command "learn", [
            [name: "Code Name", type: "STRING", description: "Name for learned code (optional)"]
        ]
        
        command "savecode", [
            [name: "Code Name", type: "STRING", description: "Name for savedcode code (optional)"],
            [name: "Code*", type: "STRING", description: "raw Base64 bytes of code to send"]            
        ]        
        
        command "sendCode", [
            [name: "Code*", type: "STRING", description: "Name of learned code or raw Base64 bytes of code to send"]
        ]
        
        command "forgetCode", [
            [name: "Code Name*", type: "STRING", description: "Name of learned code to forget"]
        ]
        command "mapButton", [
            [name: "Button*", type: "NUMBER", description: "Button number to map"],
            [name: "Code Name*", type: "STRING", description: "Name of learned code to map to the given button"]
        ]
        command "unmapButton", [
            [name: "Button*", type: "NUMBER", description: "Button number to unmap"]
        ]
       
        attribute "lastLearnedCode", "STRING"
        
        // Note, my case says ZS06, but this is what Device Get Info tells me the fingerprint is
        fingerprint profileId: "0104", inClusters: "0000,0004,0005,0003,ED00,E004,0006", outClusters: "0019,000A", manufacturer: "_TZ3290_7v1k4vufotpowp9z", model: "TS1201", deviceJoinName: "Tuya Zigbee IR Remote Control"
        fingerprint profileId: "0104", inClusters: "0000,0004,0005,0003,ED00,E004,0006", outClusters: "0019,000A", manufacturer: "_TZ3290_zqrvetpkqltiuzro", model: "TS1201", deviceJoinName: "Tuya Zigbee IR Remote Control"
        
    }
    command "sendGC", [[name: "GC sendir*", type: "STRING", description: "Linha completa no formato Global Caché 'sendir,...'"]]


    preferences {
      input name: "logLevel", type: "enum", title: "Log Level", description: "Override logging level. Default is INFO.<br>DEBUG level will reset to INFO after 30 minutes", options: ["DEBUG","INFO","WARN","ERROR"], required: true, defaultValue: "INFO"
   
    input name: "webserviceurl", type: "string", title: "URL Do Controle Remoto (GetRemoteDATA)"
}
}
// END METADATA

/* 
 * Semi-persistent data
 * We don't need this permanently in state, but we do need it between message executions so just @Field doesn't work
 */
/* deviceId -> seq -> { buffer: List<byte> } */
@Field static final Map<String, Map<Integer, Map>> SEND_BUFFERS = new ConcurrentHashMap()
def sendBuffers() { return SEND_BUFFERS.computeIfAbsent(device.id, { k -> new HashMap<>() }); }
/* deviceId -> seq -> { expectedBufferLength: int, buffer: List<byte> } */
@Field static final Map<String, Map<Integer, Map>> RECEIVE_BUFFERS = new ConcurrentHashMap()
def receiveBuffers() { return RECEIVE_BUFFERS.computeIfAbsent(device.id, { k -> new HashMap<>() }); }
/* deviceId -> Stack<string|null> */
@Field static final Map<String, List<Integer>> PENDING_LEARN_CODE_NAMES = new ConcurrentHashMap()
def pendingLearnCodeNames() { return PENDING_LEARN_CODE_NAMES.computeIfAbsent(device.id, { k -> new LinkedList<>() }); }
/* deviceId -> Stack<seq> */
@Field static final Map<String, List<Integer>> PENDING_RECEIVE_SEQS = new ConcurrentHashMap()
def pendingReceiveSeqs() { return PENDING_RECEIVE_SEQS.computeIfAbsent(device.id, { k -> new LinkedList<>() }); }

/*********
 * ACTIONS
 */

def installed() {
    info "installed()"
}

def updated() {
    info "updated()"
    switch (logLevel) {
    case "DEBUG": 
        debug "log level is DEBUG. Will reset to INFO after 30 minutes"
        runIn(1800, "resetLogLevel")
        break;
    case "INFO": info "log level is INFO"; break;
    case "WARN": warn "log level is WARN"; break;
    case "ERROR": error "log level is ERROR"; break;
    default: error "Unexpected logLevel: ${logLevel}"
    }
}

def configure() {
    info "configure()"
}

def learn(final String optionalCodeName) {
    info "learn(${optionalCodeName})"
    pendingLearnCodeNames().push(optionalCodeName)
    sendLearn(true)
}

def savecode(final String optionalCodeName, final String codeNameOrBase64CodeInput) {
    info "savecode(${codeNameOrBase64CodeInput})"
    
    String learnedCode = null
    if (state.learnedCodes != null) {
        learnedCode = state.learnedCodes[codeNameOrBase64CodeInput]
    }
    
    final String base64Code
    if (learnedCode != null) {
        base64Code = learnedCode
    } else {
        // Remove all whitespace since we added newlines to the lastLearnedCode attribute + the hubitat HTML might add extra spaces
        base64Code = codeNameOrBase64CodeInput.replaceAll("\\s", "")
    }
    
    
}


def sendCode(final String codeNameOrBase64CodeInput) {
    info "sendCode(${codeNameOrBase64CodeInput})"

    String learnedCode = null
    if (state.learnedCodes != null) {
        learnedCode = state.learnedCodes[codeNameOrBase64CodeInput]
    }
    
    final String base64Code
    if (learnedCode != null) {
        base64Code = learnedCode
    } else {
        // Remove all whitespace since we added newlines to the lastLearnedCode attribute + the hubitat HTML might add extra spaces
        base64Code = codeNameOrBase64CodeInput.replaceAll("\\s", "")
    }
    
    // JSON format copied from zigbee-herdsman-converters
    // Unclear if any of this can be tweaked to get different behavior
    final String jsonToSend = "{\"key_num\":1,\"delay\":300,\"key1\":{\"num\":1,\"freq\":38000,\"type\":1,\"key_code\":\"${base64Code}\"}}"
    debug "JSON to send: ${jsonToSend}"

    def seq = nextSeq()
    sendBuffers()[seq] = [
        buffer: jsonToSend.bytes as List
    ]
    sendStartTransmit(seq, jsonToSend.bytes.length)
}

def forgetCode(final String codeName) {
    info "forgetCode(${codeName})"
    if (state.learnedCodes == null) {
        return
    }
    state.learnedCodes.remove(codeName)
}

def mapButton(final BigDecimal button, final String codeName) {
    info "mappButton(${button}, ${codeName})"
    final Map mappedButtons = state.computeIfAbsent("mappedButtons", {k -> new HashMap()})
    mappedButtons[button.toString()] = codeName
}

def unmapButton(final BigDecimal button) {
    info "unmapButton(${button})"
    if (state.mappedButtons == null) {
        return
    }
    state.mappedButtons.remove(button.toString())
}

def push(button) {
    info "push(${button})"
    if (state.mappedButtons == null) {
        return
    }
    final String codeName = state.mappedButtons[button.toString()]
    if (codeName == null) {
        warn "Unmapped button ${button}"
    } else {
        sendCode(codeName)
    }
    	
        pushed = button.toInteger()
	    switch(pushed) {
		case 1 : 
        //sendCode(codeName); break
        default:
			logDebug("push: Botão inválido.")
			break
        }
        
}

/*********
 * MESSAGES
 */

def parse(final String description) {
    final def descMap = zigbee.parseDescriptionAsMap(description)
     
    switch (descMap.clusterInt) {
    case LEARN_CLUSTER:
        switch (Integer.parseInt(descMap.command, 16)) {
        case LEARN_CLUSTER_LEARN:
            debug "received ${LEARN_CLUSTER_LEARN} (learn): ${descMap.data}"
            break
        case LEARN_CLUSTER_ACK:
            debug "received ${LEARN_CLUSTER_ACK} (learn ack): ${descMap.data}"
            break
        default:
            debug "received unknown message: ${descMap.command} (cluster ${descMap.clusterInt})"
        }
        break
    case TRANSMIT_CLUSTER:
        switch (Integer.parseInt(descMap.command, 16)) {
        case TRANSMIT_CLUSTER_START_TRANSMIT:
            debug "received ${TRANSMIT_CLUSTER_START_TRANSMIT} (start transmit): ${descMap.data}"
            handleStartTransmit(parseStartTransmit(descMap.data))
            break
        case TRANSMIT_CLUSTER_START_TRANSMIT_ACK:
            debug "received ${TRANSMIT_CLUSTER_START_TRANSMIT_ACK} (start transmit ack): ${descMap.data}"
            // I think this is just an ACK of the recieved initial msg 0
            // There's nothing do to here
            break
        case TRANSMIT_CLUSTER_CODE_DATA_REQUEST:
            debug "received ${TRANSMIT_CLUSTER_CODE_DATA_REQUEST} (code data request): ${descMap.data}"
            handleCodeDataRequest(parseCodeDataRequest(descMap.data))
            break
        case TRANSMIT_CLUSTER_CODE_DATA_RESPONSE: 
            debug "received ${TRANSMIT_CLUSTER_CODE_DATA_RESPONSE} (code data response):: ${descMap.data}"
            handleCodeDataResponse(parseCodeDataResponse(descMap.data))
            break
        case TRANSMIT_CLUSTER_DONE_SENDING: 
            debug "received ${TRANSMIT_CLUSTER_DONE_SENDING} (done sending):: ${descMap.data}"
            handleDoneSending(parseDoneSending(descMap.data))
            break
        case TRANSMIT_CLUSTER_DONE_RECEIVING: 
            debug "received ${TRANSMIT_CLUSTER_DONE_RECEIVING} (done receiving): ${descMap.data}"
            handleDoneReceiving(parseDoneReceiving(descMap.data))
            break
        case TRANSMIT_CLUSTER_ACK:
            debug "received ${TRANSMIT_CLUSTER_ACK} (ack): ${descMap.data}"
            handleAck(parseAck(descMap.data))
            break
        default:
            debug "received unknown message: ${descMap.command} (cluster ${descMap.clusterInt})"
        }
        break
    default:
        warn "received unknown message from unknown cluster: 0x${descMap.command} (cluster 0x${Integer.toHexString(descMap.clusterInt)}). Ignoring"
        debug "descMap = ${descMap}"
        break
    }
}

/*
 * Learn command cluster
 */
@Field static final int LEARN_CLUSTER = 0xe004

/**
 * 0x00 Learn
 */
@Field static final int LEARN_CLUSTER_LEARN = 0x00

String newLearnMessage(final boolean learn) {
    return command(
        LEARN_CLUSTER, 
        LEARN_CLUSTER_LEARN, 
        toPayload("{\"study\":${learn ? 0 : 1}}".bytes)
    )
}

def sendLearn(final boolean learn) {
    final def cmd = newLearnMessage(learn)
    debug "sending (learn(${learn})): ${cmd}"
    doSendHubCommand(cmd)
}

/**
 * 0x0B ACK
 */
@Field static final int LEARN_CLUSTER_ACK = 0x0B

/*
 * Transmit command cluster
 */

@Field static final int TRANSMIT_CLUSTER = 0xed00

/**
 * 0x0B ACK
 */
@Field static final int TRANSMIT_CLUSTER_ACK = 0x0B
@Field static final def ACK_PAYLOAD_FORMAT = [
    [ name: "cmd",    type: "uint16" ],
]

Map parseAck(final List<String> payload) {
    return toStruct(ACK_PAYLOAD_FORMAT, payload)
}

String newAckMessage(final int cmd) {
    return command(
        TRANSMIT_CLUSTER, 
        TRANSMIT_CLUSTER_ACK, 
        toPayload(ACK_PAYLOAD_FORMAT, [ cmd: cmd ])
    )
}

def handleAck(final Map message) {
    switch (message.cmd) {
    case TRANSMIT_CLUSTER_START_TRANSMIT_ACK:
        // This is the only ack we care about
        // zigbee-herdsman-converters seems to handle this by just delaying this by a fixed time after
        // sending 0x00, but I think this is better
        sendCodeDataRequest(pendingReceiveSeqs().pop(), 0)
        break
    }
}

/**
 * 0x00 Start Transmit
 */
@Field static final int TRANSMIT_CLUSTER_START_TRANSMIT = 0x00
@Field static final def START_TRANSMIT_PAYLOAD_FORMAT = [
    [ name: "seq",    type: "uint16" ],
    [ name: "length", type: "uint32" ],
    [ name: "unk1",   type: "uint32" ], 
    [ name: "unk2",   type: "uint16" ], // Cluster Id?
    [ name: "unk3",   type: "uint8" ], 
    [ name: "cmd",    type: "uint8" ], 
    [ name: "unk4",   type: "uint16" ],
]

def newStartTransmitMessage(final int seq, final int length) {
    return command(
        TRANSMIT_CLUSTER, 
        TRANSMIT_CLUSTER_START_TRANSMIT,
        toPayload(
            START_TRANSMIT_PAYLOAD_FORMAT, 
            [
                seq: seq,
                length: length,
                unk1: 0,
                unk2: LEARN_CLUSTER, // This seems to be what this is set to for some reason
                unk3: 0x01,
                cmd:  0x02,
                unk4: 0,
            ]
        )
    )
}

def sendStartTransmit(final int seq, final int length) {
    final def cmd = newStartTransmitMessage(seq, length)
    debug "sending (start transmit): ${cmd}"
    doSendHubCommand(cmd)
}

Map parseStartTransmit(final List<String> payload) {
    return toStruct(START_TRANSMIT_PAYLOAD_FORMAT, payload)
}

def handleStartTransmit(final Map message) {
    pendingReceiveSeqs().push(message.seq)
    receiveBuffers()[message.seq] = [
        expectedBufferLength: message.length,
        buffer: []
    ]
    sendStartTransmitAck(message)
}

/**
 * 0x01 Start Transmit ACK 
 * ??? I don't actually know what this is for, but it needs to happen before 0x02.
 * The body seems to just be the same as 0x00 with an extra zero byte at the beginning
 */
@Field static final int TRANSMIT_CLUSTER_START_TRANSMIT_ACK = 0x01
@Field static final def START_TRANSMIT_ACK_PAYLOAD_FORMAT = [
    [ name: "zero",   type: "uint8" ],
    [ name: "seq",    type: "uint16" ],
    [ name: "length", type: "uint32" ],
    [ name: "unk1",   type: "uint32" ], 
    [ name: "unk2",   type: "uint16" ], // Cluster Id?
    [ name: "unk3",   type: "uint8" ], 
    [ name: "cmd",    type: "uint8" ], 
    [ name: "unk4",   type: "uint16" ],
]

String newStartTransmitAckMessage(final int seq, final int length) {
    return command(
        TRANSMIT_CLUSTER, 
        TRANSMIT_CLUSTER_START_TRANSMIT_ACK, 
        toPayload(
            START_TRANSMIT_ACK_PAYLOAD_FORMAT, 
            [
                zero: 0,
                seq: seq,
                length: length,
                unk1: 0,
                unk2: LEARN_CLUSTER, // This seems to be what this is set to for some reason
                unk3: 0x01,
                cmd:  0x02,
                unk4: 0,
            ]
        )
    )
}

void sendStartTransmitAck(final Map message) {
    final def cmd = newStartTransmitAckMessage(message.seq, message.length)
    debug "sending (start transmit ack): ${cmd}"
    doSendHubCommand(cmd)
}

Map parseStartTransmitAck(final List<String> payload) {
    return toStruct(START_TRANSMIT_ACK_PAYLOAD_FORMAT, payload)
}

/**
 * 0x02 Code Data Request
 */
@Field static final int TRANSMIT_CLUSTER_CODE_DATA_REQUEST = 0x02
@Field static final def CODE_DATA_REQUEST_PAYLOAD_FORMAT = [
    [ name: "seq",      type: "uint16" ],
    [ name: "position", type: "uint32" ],
    [ name: "maxlen",   type: "uint8" ],
]

String newCodeDataRequestMessage(final int seq, final int position) {
    return command(
        TRANSMIT_CLUSTER, 
        TRANSMIT_CLUSTER_CODE_DATA_REQUEST, 
        toPayload(
            CODE_DATA_REQUEST_PAYLOAD_FORMAT, 
            [
                seq: seq,
                position: position,
                maxlen: 0x38, // Limits? Unknown, this default copied from zigbee-herdsman-converters
            ]
        )
    )
}

void sendCodeDataRequest(final int seq, final int position) {
    final def cmd = newCodeDataRequestMessage(seq, position)
    debug "sending (code data request): ${cmd}"
    doSendHubCommand(cmd)
}

Map parseCodeDataRequest(final List<String> payload) {
    return toStruct(CODE_DATA_REQUEST_PAYLOAD_FORMAT, payload)
}

def handleCodeDataRequest(final Map message) {
    final int position = message.position
    final List<Byte> buffer = sendBuffers()[message.seq].buffer
    // Apparently 55 bytes at a time. TODO: experiment, should this be maxlen bytes?
    final byte[] part = buffer.subList(position, Math.min(position + 55, buffer.size())) as byte[]
    final int crc = checksum(part)

    sendCodeDataResponse(
        message.seq,
        position,
        part,
        crc
    )
}

/**
 * 0x03 Code Data Respoonse
 */
@Field static final int TRANSMIT_CLUSTER_CODE_DATA_RESPONSE = 0x03
@Field static final def CODE_DATA_RESPONSE_PAYLOAD_FORMAT = [
    [ name: "zero",       type: "uint8" ],
    [ name: "seq",        type: "uint16" ],
    [ name: "position",   type: "uint32" ],
    [ name: "msgpart",    type: 'octetStr' ],
    [ name: "msgpartcrc", type: "uint8"],
]

String newCodeDataResponseMessage(final int seq, final int position, final byte[] data, final int crc) {
    return command(
        TRANSMIT_CLUSTER, 
        TRANSMIT_CLUSTER_CODE_DATA_RESPONSE,
        toPayload(
            CODE_DATA_RESPONSE_PAYLOAD_FORMAT,
            [
                zero: 0,
                seq: seq,
                position: position,
                msgpart: data,
                msgpartcrc: crc
            ]
        )
    )
}

void sendCodeDataResponse(final int seq, final int position, final byte[] data, final int crc) {
    final def cmd = newCodeDataResponseMessage(seq, position, data, crc)
    debug "sending (code data response, position: ${position}) ${cmd}"
    doSendHubCommand(cmd)
}

Map parseCodeDataResponse(final List<String> payload) {
    return toStruct(CODE_DATA_RESPONSE_PAYLOAD_FORMAT, payload)
}

def handleCodeDataResponse(final Map message) {
    final Map seqData = receiveBuffers()[message.seq]
    if (seqData == null) {
        log.error "Unexpected seq: ${message.seq}"
        return
    }

    final List<Byte> buffer = seqData.buffer

    final int position = message.position
    if (position != buffer.size) {
        log.error "Position mismatch! expected: ${buffer.size} was: ${position}"
        return
    }

    final int actualCrc = checksum(message.msgpart)
    final int expectedCrc = message.msgpartcrc
    if (actualCrc != expectedCrc) {
        log.error "CRC mismatch! expected: ${expectedCrc} was: ${actualCrc}"
        return
    }

    buffer.addAll(message.msgpart)

    if (buffer.size < seqData.expectedBufferLength) {
        sendCodeDataRequest(message.seq, buffer.size)
    } else {
        sendDoneSending(message.seq)
    }   
}

/**
 * 0x04 Done Sending
 */
@Field static final int TRANSMIT_CLUSTER_DONE_SENDING = 0x04
@Field static final def DONE_SENDING_PAYLOAD_FORMAT = [
    [ name: "zero1", type: "uint8" ],
    [ name: "seq",   type: "uint16" ],
    [ name: "zero2", type: "uint16" ],
]

String newDoneSendingMessage(final int seq) {
    return command(
        TRANSMIT_CLUSTER, 
        TRANSMIT_CLUSTER_DONE_SENDING, 
        toPayload(
            DONE_SENDING_PAYLOAD_FORMAT,
            [
                zero1: 0,
                seq: seq,
                zero2: 0
            ]
        )
    )
}

def sendDoneSending(final int seq) {
    final def cmd = newDoneSendingMessage(seq)
    debug "sending (done sending) ${cmd}"
    doSendHubCommand(cmd)
}

Map parseDoneSending(final List<String> payload) {
    return toStruct(DONE_SENDING_PAYLOAD_FORMAT, payload)
}

def handleDoneSending(final Map message) {
    info "code fully sent"
    sendBuffers().remove(message.seq)
    sendDoneReceiving(message.seq) 
}

/**
 * 0x05 Done Receiving
 */
@Field static final int TRANSMIT_CLUSTER_DONE_RECEIVING = 0x05
@Field static final def DONE_RECEIVING_PAYLOAD_FORMAT = [
    [ name: "seq",        type: "uint16" ],
    [ name: "zero",       type: "uint16" ],
]

String newDoneReceivingMessage(final int seq) {
    return command(
        TRANSMIT_CLUSTER, 
        TRANSMIT_CLUSTER_DONE_RECEIVING, 
        toPayload(
            DONE_RECEIVING_PAYLOAD_FORMAT,
            [
                seq: seq,
                zero: 0
            ]
        )
    )
}

def sendDoneReceiving(final int seq) {
    final def cmd = newDoneReceivingMessage(seq)
    debug "sending (done receiving): ${cmd}"
    doSendHubCommand(cmd)
}

Map parseDoneReceiving(final List<String> payload) {
    return toStruct(DONE_RECEIVING_PAYLOAD_FORMAT, payload)
}

def handleDoneReceiving(final Map message) {
    final Map seqData = receiveBuffers().remove(message.seq)
    final String code = encodeBase64(seqData.buffer.toArray() as byte[])
    info "learned code: ${code}"

    // Add a newline every 25 characters so it wraps on the Hubitat UI
    // Otherwise the code overflows the page, making it hard to copy
    // We remove all whitespace in sendCode to undo this
    final String eventValue = code.split("(?<=\\G.{25})").join("\n")
    doSendEvent(name: "lastLearnedCode", value: eventValue, descriptionText: "${device} lastLearnedCode is ${code}".toString())

    final String optionalCodeName = pendingLearnCodeNames().pop()
    if (optionalCodeName != null) {
        final Map learnedCodes = state.computeIfAbsent("learnedCodes", {k -> new HashMap()})
        learnedCodes[optionalCodeName] = code
    }

    try { convertLastLearnedToGC() } catch (e) { warn "auto-convert failed: ${e}" }
    sendLearn(false)
}


/*************
 * BASIC UTILS
 */

/**
 * Format a byte[] as a string of space-separated hex bytes,
 * used for the payload of most commands.
 */
String toPayload(final byte[] bytes) {
    return bytes.collect({b -> String.format("%02X", b)}).join(' ') 
}

/**
 * Parse a string of space separated hex bytes (the payload of most messages)
 * as a byte[]
 */
byte[] toBytes(final List<String> payload) {
    return payload.collect({x -> Integer.parseInt(x, 16) as byte}) as byte[]
}

/**
 * Format a struct as a string of space-separated hex bytes.
 * @param format   a description of the struct's byte layout
 * @param payload  a struct to format
 */
String toPayload(final List<Map> format, final Map<String, Object> payload) {
    final def output = new ByteArrayOutputStream()
    for (def entry in format) {
        def value = payload[entry.name]
        switch (entry.type) {
        case "uint8": writeIntegerLe(output, value, 1); break
        case "uint16": writeIntegerLe(output, value, 2); break
        case "uint24": writeIntegerLe(output, value, 3); break
        case "uint32": writeIntegerLe(output, value, 4); break
        case "octetStr": 
            writeIntegerLe(output, value.length, 1)
            output.write(value, 0, value.length)
            break
        default: throw new RuntimeException("Unknown type: ${entry.type} (name: ${entry.name})")
        }
    }
    return toPayload(output.toByteArray())
}

/**
 * Parse a struct from a string of space-separated hex bytes
 * @param format   a description of the struct's byte layout
 * @param payload  a string of space-separate hex bytes
 */
Map toStruct(final List<Map> format, final List<String> payload) {
    final def input = new ByteArrayInputStream(toBytes(payload))
    final def result = [:]
    for (def entry in format) {
        switch (entry.type) {
        case "uint8":  result[entry.name] = readIntegerLe(input, 1); break
        case "uint16": result[entry.name] = readIntegerLe(input, 2); break
        case "uint24": result[entry.name] = readIntegerLe(input, 3); break
        case "uint32": result[entry.name] = readIntegerLe(input, 4); break
        case "octetStr": 
            final int length = readIntegerLe(input, 1)
            result[entry.name] = new byte[length]
            input.read(result[entry.name], 0, length)
            break
        default: throw new RuntimeException("Unknown type: ${entry.type} (name: ${entry.name})")
        }
    }
    return result
}

/**
 * Write an integer in twos complement little endian byte order to the given
 * output stream, taking up the number of bytes given
 */
def writeIntegerLe(final ByteArrayOutputStream out, int value, final int numBytes) { 
    for (int p = 0; p < numBytes; p++) { 
        final int digit1 = value % 16
        value = value.intdiv(16)
        final int digit2 = value % 16 
        out.write(digit2 * 16 + digit1)
        value = value.intdiv(16)
    }
}

/**
 * Read `numBytes` bytes from the input stream as an integer in twos complement litle endian order
 */
def readIntegerLe(final ByteArrayInputStream input, final int numBytes) {
    int value = 0
    int pos = 1
    for (int i = 0; i < numBytes; i++) {
        value += input.read()*pos
        pos *= 0x100
    }
    return value
}

/**
 * @return the next value in a sequence, persisted in the driver state
 */
def nextSeq() {
    return state.nextSeq = ((state.nextSeq ?: 0) + 1) % 0x10000;
}

/**
 * Checksum used to ensure the code parts are assembled correctly
 * @return the sum of all bytes in the byte array, mod 256 
 *  (yes, this is a terrible CRC as the order could be completely wrong && still get the right value)
 */
def checksum(final byte[] byteArray) {
    // Java/Groovy bytes are signed, Byte.toUnsignedInt gets us the right integer value
    return byteArray.inject(0, {acc, val -> acc + Byte.toUnsignedInt(val)}) % 0x100
}

/**
 * Logging helpers
 * Why does Hubitat's LogWrapper even have these separate methods if this isn't built in??
 */
def error(msg) {
    log.error(msg)
}
def warn(msg) {
    if (logLevel == "WARN" || logLevel == "INFO" || logLevel == "DEBUG") {
        log.warn(msg)
    }
}
def info(msg) {
    if (logLevel == "INFO" || logLevel == "DEBUG") {
        log.info(msg)
    }
}
def debug(msg) {
    if (logLevel == "DEBUG") {
        log.debug(msg)
    }
}
def resetLogLevel() {
    info "logLevel auto reset to INFO"
    device.updateSetting("logLevel", [value:"INFO", type:"enum"])
}

/*************
 * MOCKING STUBS
 */

/**
 * Determine if hub commands should be mocked (based on the presence of variables from the unit tests)
 */
def mockHubCommands() {
    try {
        return sentCommands != null
    } catch (ex) {
        return false
    }
}

/**
 * Mocking facade for sendHubCommand
 */
def doSendHubCommand(cmd) {
    if (mockHubCommands()) {
        sentCommands.add(cmd)
    } else {
        sendHubCommand(new hubitat.device.HubAction(cmd, hubitat.device.Protocol.ZIGBEE))
    }
}

/**
 * Mocking facade for sendEvent
 */
def doSendEvent(final Map event) {
    if (mockHubCommands()) {
        sentEvents.add(event)
    } else {
        sendEvent(event)
    }
}

/**
 * Alternative to direct org.apache.commons.codec.binary.Base64 usage
 * so we don't have to have that dependency in tests
 */
def encodeBase64(final byte[] bytes) {
    try {
        return org.apache.commons.codec.binary.Base64.encodeBase64String(bytes)
    } catch (ex) {
        // Fallback for tests
        return encodeToString(bytes)
    }
}

/**
 * Alternative to zigbee.command so we don't have to stub that
 */
String command(final int clusterId, final int commandId, final String payload) {
    return "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x${Integer.toHexString(clusterId)} 0x${Integer.toHexString(commandId)} {${payload}}"
    //return zigbee.command(clusterId, commandId, payload)[0]
}

/* ===== Global Caché (sendir) -> Tuya raw Base64 support ===== */

def sendGC(final String sendirLine) {
    info "sendGC(...)"
    try {
        final Map conv = gcToTuyaBase64(sendirLine)
        if (conv == null) {
        error "sendGC: linha inválida (esperado 'sendir,addr:id,freq,repeat,offset,seq...')"
        return
    }
    sendTuyaBase64(conv.base64 as String, (conv.freqHz ?: 38000) as int)
    } catch (Exception ex) {
        error "sendGC falhou: ${ex} | input='${sendirLine}'"
    }
}

/** Envia um key_code base64 para o blaster, com a frequência desejada */
private void sendTuyaBase64(final String base64Code, final int freqHz) {
    try {
        final int f = (freqHz > 0 ? freqHz : 38000)
        final String jsonToSend = "{\"key_num\":1,\"delay\":300," +
            "\"key1\":{\"num\":1,\"freq\":" + f + ",\"type\":1," +
            "\"key_code\":\"${base64Code}\"}}"
        debug "JSON to send: ${jsonToSend}"
        def seq = nextSeq()
        sendBuffers()[seq] = [ buffer: jsonToSend.bytes as List ]
        sendStartTransmit(seq, jsonToSend.bytes.length)
    } catch (Exception e) {
        error "sendTuyaBase64 failed: ${e}"
    }
}

private Map gcToTuyaBase64(final String line) {
    debug "gcToTuyaBase64: raw='${line}'"
    if (line == null) return null

    // ===== INÍCIO ALTERADO =====
    String trimmed = line.trim()
    boolean hasSendir = trimmed.toLowerCase().startsWith("sendir,")
    String rest
    if (hasSendir) {
        // caminho original (sendir,addr:id,...)
        rest = trimmed.substring(7)
    } else {
        // NOVO: formato compacto "<freqHz>,<repeat>,<offset>,<on1>,<off1>,..."
        String[] toks = trimmed.split(",")
        if (toks.length >= 3) {
            int freqHz = gcSafeInt(toks[0], 38000)
            int repeat = gcSafeInt(toks[1], 1)
            int offset = gcSafeInt(toks[2], 1)

            // pega o resto como pares on/off
            int firstComma = trimmed.indexOf(',')
            int secondComma = trimmed.indexOf(',', firstComma + 1)
            int thirdComma = trimmed.indexOf(',', secondComma + 1)
            String pairsStr = (thirdComma >= 0) ? trimmed.substring(thirdComma + 1) : ""

            debug "gcToTuyaBase64: compact head=[freq=${freqHz}, repeat=${repeat}, offset=${offset}]"

            List<int[]> pairs = gcParsePairs(pairsStr)
            debug "gcToTuyaBase64: pairsCount=${pairs?.size() ?: 0}"
            if (!pairs || pairs.isEmpty()) return null

            int startPairIndex = (((offset - 1) as int) >> 1)
            if (startPairIndex < 0) startPairIndex = 0

            List<int[]> preamble = (startPairIndex > 0) ? pairs.subList(0, startPairIndex) : []
            List<int[]> repeatSection = pairs.subList(startPairIndex, pairs.size())

            List<Integer> durationsUs = []
            def appendPairsUs = { List<int[]> src ->
                for (int[] p2 : src) {
                    durationsUs << gcPeriodsToUs(p2[0], freqHz)
                    durationsUs << gcPeriodsToUs(p2[1], freqHz)
                }
            }
            appendPairsUs(preamble)
            for (int r = 0; r < (repeat > 1 ? repeat : 1); r++) appendPairsUs(repeatSection)

            byte[] packed = gcPackDurationsToBlocks(durationsUs)
            debug "gcToTuyaBase64: durations=${durationsUs.size()} packedBytes=${packed.length}"
            String b64 = encodeBase64(packed)
            return [ base64: b64, freqHz: freqHz ]
        }
        // se não deu para tratar como compacto, cai no parser antigo abaixo
        rest = trimmed
    }
    // ===== FIM ALTERADO =====

    // ... (resto do método segue inalterado: parsing do cabeçalho "sendir,..." original)
}


/** Expande '4,5A8,9ABB...' para lista de pares [on,off] */
private List<int[]> gcParsePairs(final String s) {
    // fast path: only digits/commas
    if (!(s ==~ /.*[A-Oa-o].*/)) {
        List<int[]> out = []
        if (s == null || s.trim() == "") return out
        String[] tokens = s.split(",")
        List<Integer> nums = []
        for (String t : tokens) {
            int n = gcSafeInt(t, Integer.MIN_VALUE)
            if (n != Integer.MIN_VALUE) nums << n
            if (nums.size() == 2) {
                out << ([nums[0], nums[1]] as int[])
                nums.clear()
            }
        }
        return out
    }
    // fallback: alias-aware parser (A..O/a..o)
    List<int[]> out = []
    Map<Character,int[]> dict = [:]
    int dictCount = 0
    List<Integer> nums = []
    StringBuilder cur = new StringBuilder()
    for (int i=0; i<=s.length(); i++) {
        char c = (i < s.length()) ? s.charAt(i) : ','
        char cu = (char)Character.toUpperCase((int)c)
        if (Character.isDigit(c)) {
            cur.append(c)
        } else {
            if (cur.length() > 0) {
                nums << gcSafeInt(cur.toString(), 0)
                cur.setLength(0)
                if (nums.size() == 2) {
                    int on = nums[0], off = nums[1]
                    int[] pair = [on, off] as int[]
                    out << pair
                    if (dictCount < 15) {
                        char key = (char)(('A' as int) + dictCount)
                        if (!dict.containsKey(key)) { dict[key] = pair; dictCount++ }
                    }
                    nums.clear()
                }
            }
            if (cu >= 'A' && cu <= 'O') {
                int[] ref = dict[cu]
                if (ref != null) out << [ref[0], ref[1]] as int[]
            }
        }
    }
    return out
}
private int gcSafeInt(String v, int deflt) {
    try {
        if (v == null) return deflt
        String cleaned = v.trim().replaceAll(/[^0-9+-]/, '')
        if (!(cleaned ==~ /[+-]?\d+/)) return deflt
        return Integer.parseInt(cleaned)
    } catch (e) { return deflt }
}

private int gcPeriodsToUs(final int periods, final int freqHz) {
    if (freqHz <= 0) return (periods > 0 ? periods : 1)
    int val = (int)Math.round((periods * 1_000_000.0d) / (double)freqHz)
    return (val > 0 ? val : 1)
}

/** Empacota em blocos: [len-1][16-bit LE ...] com len<=32 */
private byte[] gcPackDurationsToBlocks(final List<Integer> durationsUs) {
    ByteArrayOutputStream out = new ByteArrayOutputStream()
    int i = 0
    while (i < durationsUs.size()) {
        int items = Math.min(16, durationsUs.size() - i)
        int lenBytes = items * 2
        out.write((byte)(lenBytes - 1))
        for (int k=0; k<items; k++) {
            int v = durationsUs[i + k] & 0xFFFF
            out.write((byte)(v & 0xFF))
            out.write((byte)((v >> 8) & 0xFF))
        }
        i += items
    }
    return out.toByteArray()
}
/* ===== end of GC support ===== */


/* ===== Remote data fetch (Global Caché 'sendir' list) ===== */
/** Envia o comando guardado em state[alias] se existir e se o conversor for 'sendir'. */
private void gcSendFromState(final String alias) {
    try {
        final String encoding = (state?.encoding ?: "sendir") as String
        final String code = state?."${alias}"
        if (!code) { warn "gcSendFromState: sem código para ${alias}"; return }
        if (encoding?.toLowerCase() != "sendir") {
            warn "gcSendFromState: formato '${encoding}' ainda não suportado neste driver (esperado 'sendir')"
            return
        }
        sendGC(code as String)
    } catch (Exception ex) {
        error "gcSendFromState(${alias}) falhou: ${ex}"
    }
}

/** Baixa JSON da URL (preferences.webserviceurl) e popula os principais comandos no state.* */
def GetRemoteDATA() {
    final String url = settings?.webserviceurl
    if (!url) { warn "GetRemoteDATA: configure 'URL Do Controle Remoto (GetRemoteDATA)' nas Preferences"; return }
    info "GetRemoteDATA: buscando ${url}"
    try {
        httpGet([uri: url, contentType: "application/json"]) { resp ->
            if (resp?.status != 200 || !resp?.data) {
                warn "GetRemoteDATA: HTTP status=${resp?.status}"
                return
            }
            def data = resp.data
            state.encoding = (data?.conversor ?: "sendir").toString()
            // mapeia functions.function[] em variáveis de estado como no driver de referência
            def f = data?.functions?.function
            if (!(f instanceof List)) {
                warn "GetRemoteDATA: formato inesperado (functions.function não é lista)"
                return
            }
            def F = { int idx -> (idx < f.size()) ? (f[idx] as String) : null }
            state.OFFIRsend             = F(0)
            state.OnIRsend              = F(1)
            state.muteIRsend            = F(2)
            state.sourceIRsend          = F(3)
            state.backIRsend            = F(4)
            state.menuIRsend            = F(5)
            state.hdmi1IRsend           = F(6)
            state.hdmi2IRsend           = F(7)
            state.leftIRsend            = F(8)
            state.rightIRsend           = F(9)
            state.upIRsend              = F(10)
            state.downIRsend            = F(11)
            state.confirmIRsend         = F(12)
            state.exitIRsend            = F(13)
            state.homeIRsend            = F(14)
            state.ChanUpIRsend          = F(15)
            state.ChanDownIRsend        = F(16)
            state.VolUpIRsend           = F(17)
            state.VolDownIRsend         = F(18)
            state.num0IRsend            = F(19)
            state.num1IRsend            = F(20)
            state.num2IRsend            = F(21)
            state.num3IRsend            = F(22)
            state.num4IRsend            = F(23)
            state.num5IRsend            = F(24)
            state.num6IRsend            = F(25)
            state.num7IRsend            = F(26)
            state.num8IRsend            = F(27)
            state.num9IRsend            = F(28)
            state.btnextra1IRsend         = F(29)
                state.btnextra2IRsend         = F(30)
                state.btnextra3IRsend         = F(31)
                state.amazonIRsend            = F(32)
                state.youtubeIRsend           = F(33)
                state.netflixIRsend           = F(34)
                state.btnextra4IRsend         = F(35)
                state.btnextra5IRsend         = F(36)
                state.btnextra6IRsend         = F(37)
                state.powertoggle	          = F(38)
                state.btnAIRsend              = F(39)
                state.btnBIRsend              = F(40)
                state.btnCIRsend              = F(41)
                state.btnDIRsend              = F(42)
                state.playIRsend              = F(43)
                state.pauseIRsend             = F(44)
                state.nextIRsend              = F(45)
                state.guideIRsend             = F(46)
                state.infoIRsend              = F(47)
                state.toolsIRsend             = F(48)
                state.smarthubIRsend          = F(49)
                state.previouschannelIRsend   = F(50)
            info "GetRemoteDATA: carregado. encoding=${state.encoding}, funções=${f.size()}"
        }
    } catch (Exception e) {
        warn "GetRemoteDATA falhou: ${e?.message}"
    }
}

/* ===== Ações convenientes (usam gcSendFromState) ===== */
def poweron(){ gcSendFromState('OnIRsend') }
def poweroff(){ gcSendFromState('OFFIRsend') }
def powertoggle() { gcSendFromState('powertoggle') }
def mute(){ gcSendFromState('muteIRsend') }
def source(){ gcSendFromState('sourceIRsend') }
def back(){ gcSendFromState('backIRsend') }
def menu(){ gcSendFromState('menuIRsend') }
def hdmi1(){ gcSendFromState('hdmi1IRsend') }
def hdmi2(){ gcSendFromState('hdmi2IRsend') }
def left(){ gcSendFromState('leftIRsend') }
def right(){ gcSendFromState('rightIRsend') }
def up(){ gcSendFromState('upIRsend') }
def down(){ gcSendFromState('downIRsend') }
def confirm(){ gcSendFromState('confirmIRsend') }
def exit(){ gcSendFromState('exitIRsend') }
def home(){ gcSendFromState('homeIRsend') }
def channelUp(){ gcSendFromState('ChanUpIRsend') }
def channelDown(){ gcSendFromState('ChanDownIRsend') }
def volumeUp(){ gcSendFromState('VolUpIRsend') }
def volumeDown(){ gcSendFromState('VolDownIRsend') }
def num0(){ gcSendFromState('num0IRsend') }
def num1(){ gcSendFromState('num1IRsend') }
def num2(){ gcSendFromState('num2IRsend') }
def num3(){ gcSendFromState('num3IRsend') }
def num4(){ gcSendFromState('num4IRsend') }
def num5(){ gcSendFromState('num5IRsend') }
def num6(){ gcSendFromState('num6IRsend') }
def num7(){ gcSendFromState('num7IRsend') }
def num8(){ gcSendFromState('num8IRsend') }
def num9(){ gcSendFromState('num9IRsend') }


/* ==== Learn decode: Base64(Tuya blocks) -> Global Caché sendir ==== */
/** Converte o state.lastLearnedCode (Base64) para 'sendir,...' e publica em atributo lastLearnedCodeGC. */
def convertLastLearnedToGC() {
    try {
        final String b64 = (state?.lastLearnedCode ?: device.currentValue('lastLearnedCode')) as String
        if (!b64) { warn "convertLastLearnedToGC: não há lastLearnedCode"; return }
        Integer freq = null
        if (state?.lastLearnedFreqHz instanceof Number) freq = (state.lastLearnedFreqHz as int)
        if (!freq) freq = (settings?.defaultLearnFreqHz as Integer) ?: 38000

        def res = tuyaBase64ToGC(b64, freq as int)
        if (res == null) { warn "convertLastLearnedToGC: falha na conversão (Base64 inválido?)"; return }
        String sendir = res.sendir as String
        state.lastLearnedCodeGC = sendir
        sendEvent(name: "lastLearnedCodeGC", value: sendir)
        info "Aprendido convertido: pairs=${res.count}, freq=${res.freqHz}Hz"
        debug "sendir completo: ${sendir}"
    } catch (Exception e) {
        error "convertLastLearnedToGC falhou: ${e}"
    }
}

/** Decodifica o formato de blocos do Tuya (Base64) e gera formato Global Caché. */
private Map tuyaBase64ToGC(final String base64Code, final int freqHz) {
    try {
        byte[] data = base64Code?.replaceAll(/\s/,'')?.decodeBase64()
        List<Integer> durationsUs = []
        int i = 0
        while (i < data.length) {
            int lenBytes = (data[i] & 0xFF) + 1; i++
            if (i + lenBytes > data.length) break
            int j = 0
            while (j < lenBytes && (i + 1) < data.length) {
                int lo = data[i] & 0xFF
                int hi = data[i+1] & 0xFF
                int us = ((hi << 8) | lo) & 0xFFFF
                durationsUs << us
                i += 2; j += 2
            }
        }
        if (durationsUs.isEmpty()) return null
        if ((durationsUs.size() % 2) == 1) durationsUs.remove(durationsUs.size()-1)

        List<Integer> periods = []
        for (Integer us : durationsUs) {
            int p = (int)Math.round(((us as double) * (freqHz as double)) / 1_000_000.0d)
            periods << (p > 0 ? p : 1)
        }
        String pairsCsv = periods.join(",")
        String sendir = "sendir,1:1,${freqHz},1,1,${pairsCsv}"
        return [sendir: sendir, freqHz: freqHz, count: (periods.size()/2) as int]
    } catch (Exception e) {
        error "tuyaBase64ToGC falhou: ${e}"
        return null
    }
}
