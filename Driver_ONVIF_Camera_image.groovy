/**
 *  Vetra - ONVIF Camera Snapshot Driver
 *  Faz polling do snapshot da câmera e salva no File Manager do Hubitat.
 *  A URL local (/local/camera_DEVICE_ID.jpg) fica acessível na LAN sem autenticação.
 *
 *  Copyright 2026 TRATO / VH
 *  Licensed under the Apache License, Version 2.0
 *
 *  V1.0 - 31/03/2026 - Initial version
 *
 *
 *  Precisa colocar o IP, Usuario e Senha da sua camera que está locala na rede. Escolher o tempo que vão ser tomadas as capturas. 
 *  A hubitat vai publicar uma imagem no http://enderecohubitat/local/xxxx_xxxx.jpg com a imagem. 
 *  
 *  Se for colocar dentro do Vetra. Só precisa a) Compartilhar essa camera no MakerAPi que envia devices para o Vetra. b) Importar em devices, a camera, como tipo de device "Camera".  Não preencher mais nenhuma informação.
 *
 *

 
 */

metadata {
    definition(name: "ONVIF Camera Snapshot", namespace: "TRATO", author: "VH") {
        capability "Refresh"
        capability "Sensor"
        capability "ImageCapture"

        attribute "camera_snapshot_url", "STRING"
        attribute "last_snapshot_at",    "STRING"
        attribute "snapshot_status",     "STRING"
        attribute "image",               "STRING"

        command "refreshSnapshot"
        command "startPolling"
        command "stopPolling"
    }
}

preferences {
    input name: "cameraIp",       type: "text",     title: "IP da Câmera",            required: true,  defaultValue: "192.168.1.108"
    input name: "cameraPort",     type: "number",   title: "Porta HTTP",               required: true,  defaultValue: 80
    input name: "cameraUser",     type: "text",     title: "Usuário",                  required: true,  defaultValue: "admin"
    input name: "cameraPassword", type: "password", title: "Senha",                    required: true,  defaultValue: ""
    input name: "snapshotPath",   type: "text",     title: "Path do Snapshot",         required: true,  defaultValue: "/cgi-bin/snapshot.cgi"
    input name: "pollInterval",   type: "enum",     title: "Intervalo de Atualização", required: true,
        options: ["5": "5 segundos", "10": "10 segundos", "30": "30 segundos", "60": "1 minuto", "300": "5 minutos"],
        defaultValue: "10"
    input name: "logEnable",      type: "bool",     title: "Ativar logs de debug",     defaultValue: false
}

def installed() {
    log.info "MolSmart ONVIF Camera installed"
    initialize()
}

def updated() {
    log.info "MolSmart ONVIF Camera updated"
    unschedule()
    initialize()
}

def uninstalled() {
    unschedule()
    log.info "MolSmart ONVIF Camera uninstalled"
}

def initialize() {
    def filename = getSnapshotFilename()
    def localUrl = "http://${location.hubs[0].localIP}/local/${filename}"
    sendEvent(name: "camera_snapshot_url", value: localUrl)
    sendEvent(name: "snapshot_status", value: "initialized")
    startPolling()
}

def refresh() {
    refreshSnapshot()
}

def refreshSnapshot() {
    if (!cameraIp || !cameraUser || !cameraPassword) {
        log.warn "Câmera não configurada corretamente"
        sendEvent(name: "snapshot_status", value: "error: configuração incompleta")
        return
    }

    def port = (cameraPort ?: 80) as int
    def path = snapshotPath ?: "/cgi-bin/snapshot.cgi"

    // Hubitat httpGet não aceita headers de Authorization — credenciais embutidas na URI
    def user = URLEncoder.encode(cameraUser ?: "", "UTF-8")
    def pass = URLEncoder.encode(cameraPassword ?: "", "UTF-8")

    def params = [
        uri    : "http://${user}:${pass}@${cameraIp}:${port}${path}",
        timeout: 10
    ]

    if (logEnable) log.debug "Buscando snapshot: http://${cameraIp}:${port}${path}"

    try {
        httpGet(params) { resp ->
            if (resp.status == 200) {
                def imageBytes = resp.data.bytes
                def filename = getSnapshotFilename()

                if (logEnable) log.debug "Snapshot recebido: ${imageBytes.length} bytes — salvando como ${filename}"

                // Salva no File Manager do Hubitat
                uploadHubFile(filename, imageBytes)

                def localUrl = "http://${location.hubs[0].localIP}/local/${filename}"
                def timestamp = new Date().format("yyyy-MM-dd HH:mm:ss")

                sendEvent(name: "camera_snapshot_url", value: localUrl)
                sendEvent(name: "last_snapshot_at",    value: timestamp)
                sendEvent(name: "snapshot_status",     value: "ok")
                sendEvent(name: "image",               value: localUrl)

                if (logEnable) log.debug "Snapshot salvo: ${localUrl}"
            } else {
                log.warn "Câmera retornou status ${resp.status}"
                sendEvent(name: "snapshot_status", value: "error: HTTP ${resp.status}")
            }
        }
    } catch (Exception e) {
        log.error "Erro ao buscar snapshot: ${e.message}"
        sendEvent(name: "snapshot_status", value: "error: ${e.message}")
    }
}

def startPolling() {
    unschedule("refreshSnapshot")
    def interval = (pollInterval ?: "10") as int

    if (interval == 5)   runEvery5Minutes("refreshSnapshot")   // fallback para muito curto
    else if (interval <= 10)  schedule("0/${interval} * * * * ?", "refreshSnapshot")
    else if (interval <= 60)  schedule("0/${interval} * * * * ?", "refreshSnapshot")
    else                      runEvery5Minutes("refreshSnapshot")

    // Para intervalos curtos usa runIn em loop
    if (interval < 60) {
        unschedule("refreshSnapshot")
        scheduleShortInterval(interval)
    }

    sendEvent(name: "snapshot_status", value: "polling a cada ${interval}s")
    log.info "Polling iniciado: a cada ${interval} segundos"

    // Dispara imediatamente
    refreshSnapshot()
}

def scheduleShortInterval(int seconds) {
    runIn(seconds, "refreshAndReschedule")
}

def refreshAndReschedule() {
    refreshSnapshot()
    def interval = (pollInterval ?: "10") as int
    if (interval < 60) {
        runIn(interval, "refreshAndReschedule")
    }
}

def stopPolling() {
    unschedule()
    sendEvent(name: "snapshot_status", value: "parado")
    log.info "Polling parado"
}

private String getSnapshotFilename() {
    // Usa o ID do device para que múltiplas câmeras não se sobrescrevam
    return "camera_${device.id}.jpg"
}

private logDebug(msg) {
    if (logEnable) log.debug "${device.displayName}: ${msg}"
}
