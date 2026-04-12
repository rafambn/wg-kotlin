package com.rafambn.kmpvpn.session.io

class InMemoryTunProvider : TunProvider {
    private val openPorts: LinkedHashMap<String, InMemoryOwnedTunPort> = linkedMapOf()
    val callLog: MutableList<String> = mutableListOf()

    override fun exists(interfaceName: String): Boolean {
        callLog += "existsTun:$interfaceName"
        return openPorts.containsKey(interfaceName)
    }

    override fun open(interfaceName: String): OwnedTunPort {
        callLog += "openTun:$interfaceName"
        return openPorts.getOrPut(interfaceName) {
            InMemoryOwnedTunPort(
                interfaceName = interfaceName,
                onClose = {
                    callLog += "closeTun:$interfaceName"
                    openPorts.remove(interfaceName)
                },
            )
        }
    }

    fun port(interfaceName: String): InMemoryOwnedTunPort? {
        return openPorts[interfaceName]
    }
}
