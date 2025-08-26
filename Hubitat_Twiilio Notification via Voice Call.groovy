/*
 * Twilio Voice Caller (Hubitat)
 * Corrige MissingMethodException (encodeAsHTML) usando xmlEscape()
 */

metadata {
    definition(name: "Twilio Voice Caller", namespace: "trato", author: "Hernan/ChatGPT") {
        capability "Actuator"
        command "placeCall", [[name:"To (E.164)", type:"STRING", description:"+55XXXXXXXXXXX (opcional se defaultTo)"],
                              [name:"Message (pt-BR)", type:"STRING", description:"Texto a falar"]]
        attribute "lastCallSid", "string"
    }
    preferences {
        input name: "accountSid", type: "text", title: "Twilio Account SID", required: true
        input name: "authToken",  type: "password", title: "Twilio Auth Token", required: true
        input name: "fromNumber", type: "text", title: "Twilio From (E.164, ex: +1XXX...)", required: true
        input name: "defaultTo",  type: "text", title: "Número padrão (E.164, opcional)", required: false
        input name: "usePolly",   type: "bool", title: "Usar voz Amazon Polly (quando disponível na conta)", defaultValue: false
        input name: "pollyVoice", type: "enum", title: "Voz Polly", options: ["Polly.Camila","Polly.Vitoria","Polly.Miguel"], defaultValue: "Polly.Camila", required: false
        input name: "logEnable",  type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

def installed(){}

def updated(){
    if (logEnable) runIn(1800, logsOff)
}

def logsOff(){ device.updateSetting("logEnable",[value:"false", type:"bool"]) }

/**
 * Faz a chamada de voz via Twilio
 * @param to      Número E.164 (ex: +5511999998888). Se vazio, usa defaultTo.
 * @param message Texto a ser falado
 */
def placeCall(String to, String message){
    def dest = (to?.trim()) ?: (defaultTo?.trim())
    if (!dest) { log.warn "Sem número destino. Informe 'to' ou configure defaultTo."; return }
    if (!message?.trim()){ log.warn "Mensagem vazia."; return }

    String sayAttrs = 'language="pt-BR"'
    if (usePolly && pollyVoice) sayAttrs += " voice=\"${xmlEscape(pollyVoice)}\""
    def twiml = "<Response><Say ${sayAttrs}>${xmlEscape(message)}</Say></Response>"

    def options = [
        uri: "https://api.twilio.com/2010-04-01/Accounts/${accountSid}/Calls.json",
        headers: [ Authorization: "Basic ${("${accountSid}:${authToken}").bytes.encodeBase64().toString()}" ],
        // IMPORTANTE: deixe o Hubitat fazer o URL-encode do Map
        requestContentType: "application/x-www-form-urlencoded",
        body: [
            To   : dest,
            From : fromNumber,
            Twiml: twiml
        ],
        timeout: 20
    ]

    if (logEnable) log.debug "Calling ${dest} via Twilio…"
    try {
        httpPost(options) { resp ->
            if (resp?.status in [200,201]) {
                def sid = resp?.data?.sid
                if (logEnable) log.debug "Call created: ${sid}"
                if (sid) sendEvent(name:"lastCallSid", value:"${sid}", isStateChange:true)
            } else {
                log.warn "Twilio HTTP ${resp?.status} -> ${safeToString(resp?.data)}"
            }
        }
    } catch (e) {
        // Mostra motivo detalhado do Twilio (code/message) se disponível
        def detail = extractTwilioError(e)
        log.error "Erro ao criar chamada Twilio: ${e.class.simpleName} ${e.message}${detail}"
    }
}

private String extractTwilioError(e){
    try {
        def data = e?.response?.data
        if (!data) return ""
        def code = data?.code ?: data?.error_code
        def msg  = data?.message ?: data?.error ?: data?.more_info
        return " | Twilio: code=${code} message=${msg}"
    } catch (ignored) { return "" }
}


/** Encode application/x-www-form-urlencoded */
private String toForm(Map m){
    m.collect { k,v -> "${URLEncoder.encode(k.toString(), 'UTF-8')}=${URLEncoder.encode(v.toString(),'UTF-8')}" }
     .join("&")
}

/** Escape mínimo para XML/TwiML */
private static String xmlEscape(String s){
    if (s == null) return ""
    String out = s
    out = out.replace("&", "&amp;")
             .replace("<", "&lt;")
             .replace(">", "&gt;")
             .replace("\"","&quot;")
             .replace("'", "&apos;")
    return out
}

/** Evita logar objetos gigantes/segredos por acidente */
private static String safeToString(obj){
    try { return obj?.toString() } catch(e) { return "<unprintable>" }
}
