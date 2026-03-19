/**
 *  Internet Check - Auto Setup App v2.1 (FIXED)
 *
 *  Versión corregida - removido acceso a propiedades inexistentes
 *
 *  Cria automaticamente:
 *    1. Virtual Switch "Internet Check"
 *    2. Regra de ping a cada 5 minutos
 *
 *  Instalação:
*    1.Apps Code > Add App > cole este código > Save > Done
*    2.Apps > Add User App > Seleccionar o "Internet Check app - by TRATO" > Done
*    
*    v.1.0
*/

definition(
    name: "Internet Check app - by TRATO",
    namespace: "custom",
    author: "Custom",
    description: "Cria o Virtual Switch e a lógica de verificação de internet via ping",
    category: "Utility",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Internet Check Setup v2.1", install: true, uninstall: true) {
        section("Informações") {
            paragraph "Este app cria automaticamente um Virtual Switch chamado <b>Internet Check</b> e verifica a internet a cada 5 minutos fazendo ping em 8.8.8.8."
        }
        
        section("Status Atual") {
            if (state.switchId) {
                def sw = getChildDevice("internet-check-switch-${app.id}")
                if (sw) {
                    paragraph "✅ Virtual Switch criado: <b>${sw.displayName}</b>"
                    paragraph "📊 Estado atual: <b>${sw.currentValue('switch')}</b>"
                } else {
                    paragraph "⚠️ Virtual Switch ID salvo, mas device não encontrado"
                    paragraph "ID: ${state.switchId}"
                }
            } else {
                paragraph "⏳ Virtual Switch será criado ao clicar em Done"
            }
            
            if (state.lastCheck) {
                paragraph "🕐 Última verificação: ${state.lastCheck}"
                paragraph "📡 Resultado: ${state.lastResult}"
            }
        }
        
        section("Debug") {
            input "enableDebug", "bool", title: "Ativar logs de debug?", defaultValue: true
        }
    }
}

def installed() {
    log.info "Internet Check Setup v2.1: instalando..."
    initialize()
}

def updated() {
    log.info "Internet Check Setup v2.1: atualizando..."
    unschedule()
    initialize()
}

def initialize() {
    logDebug "Initialize: iniciando..."
    
    // Criar ou verificar Virtual Switch
    createVirtualSwitch()
    
    // Agendar ping a cada 5 minutos
    // Usando runEvery5Minutes para maior confiabilidade
    runEvery5Minutes("checkInternet")
    logDebug "Initialize: agendamento configurado (a cada 5 minutos)"
    
    // Fazer primeira verificação imediatamente
    runIn(5, "checkInternet")
    logDebug "Initialize: primeira verificação agendada para daqui 5 segundos"
    
    log.info "Internet Check Setup v2.1: inicializado com sucesso"
}

def createVirtualSwitch() {
    def dni = "internet-check-switch-${app.id}"
    logDebug "createVirtualSwitch: procurando device com DNI=${dni}"
    
    // Verificar se já existe
    def existing = getChildDevice(dni)
    if (existing) {
        log.info "Virtual Switch já existe: ${existing.displayName} (ID: ${existing.id})"
        state.switchId = existing.id
        return existing
    }
    
    logDebug "createVirtualSwitch: criando novo Virtual Switch..."
    
    try {
        def sw = addChildDevice(
            "hubitat",
            "Virtual Switch",
            dni,
            null,
            [
                name: "Internet Check",
                label: "Internet Check",
                isComponent: false
            ]
        )
        
        state.switchId = sw.id
        log.info "Virtual Switch criado com sucesso: ${sw.displayName} (ID: ${sw.id})"
        
        // Setar estado inicial como ON (assumindo que tem internet)
        sw.on()
        
        return sw
        
    } catch (Exception e) {
        log.error "ERRO ao criar Virtual Switch: ${e.message}"
        return null
    }
}

def checkInternet() {
    logDebug "checkInternet: iniciando verificação..."
    
    // Obter o Virtual Switch
    def dni = "internet-check-switch-${app.id}"
    def sw = getChildDevice(dni)
    
    if (!sw) {
        log.error "checkInternet: Virtual Switch não encontrado! DNI=${dni}"
        log.error "checkInternet: Tentando recriar..."
        sw = createVirtualSwitch()
        if (!sw) {
            log.error "checkInternet: Falha ao recriar Virtual Switch. Abortando."
            return
        }
    }
    
    logDebug "checkInternet: Virtual Switch encontrado: ${sw.displayName}"
    
    // Fazer ping para 8.8.8.8 (Google DNS)
    logDebug "checkInternet: executando ping para 8.8.8.8..."
    
    try {
        def pingData = hubitat.helper.NetworkUtils.ping("8.8.8.8", 3)
        
        // ← CORRIGIDO: removido transmitted/received que não existem
        logDebug "checkInternet: ping retornou - packetLoss=${pingData?.packetLoss}%"
        
        // Atualizar timestamp
        state.lastCheck = new Date().format("dd/MM/yyyy HH:mm:ss")
        
        // Verificar resultado
        // pingData pode ser null se falhar completamente
        if (pingData && pingData.packetLoss != null && pingData.packetLoss < 100) {
            // Tem internet (mesmo que com alguma perda de pacotes)
            def avgRtt = pingData.rttAvg ? "${pingData.rttAvg}ms" : "N/A"
            log.info "Internet Check: ✅ ONLINE (avg ${avgRtt}, loss ${pingData.packetLoss}%)"
            state.lastResult = "ONLINE - ${avgRtt}"
            
            if (sw.currentValue('switch') != 'on') {
                logDebug "checkInternet: ligando switch (estava OFF)"
                sw.on()
            } else {
                logDebug "checkInternet: switch já está ON"
            }
            
        } else {
            // Sem internet ou ping falhou completamente
            def lossInfo = pingData?.packetLoss != null ? "${pingData.packetLoss}%" : "total"
            log.warn "Internet Check: ❌ OFFLINE (${lossInfo} packet loss)"
            state.lastResult = "OFFLINE"
            
            if (sw.currentValue('switch') != 'off') {
                logDebug "checkInternet: desligando switch (estava ON)"
                sw.off()
            } else {
                logDebug "checkInternet: switch já está OFF"
            }
        }
        
    } catch (Exception e) {
        log.error "Internet Check: ERRO ao fazer ping: ${e.message}"
        state.lastResult = "ERRO: ${e.message}"
        
        // Em caso de erro, desligar o switch por segurança
        if (sw.currentValue('switch') != 'off') {
            log.warn "checkInternet: desligando switch devido a erro"
            sw.off()
        }
    }
}

def logDebug(msg) {
    if (enableDebug) {
        log.debug msg
    }
}

def uninstalled() {
    log.info "Internet Check Setup v2.1: desinstalando..."
    unschedule()
    
    def children = getChildDevices()
    logDebug "uninstalled: removendo ${children.size()} child device(s)"
    
    children.each { 
        logDebug "uninstalled: removendo ${it.displayName}"
        deleteChildDevice(it.deviceNetworkId) 
    }
    
    log.info "Internet Check Setup v2.1: desinstalado com sucesso"
}
