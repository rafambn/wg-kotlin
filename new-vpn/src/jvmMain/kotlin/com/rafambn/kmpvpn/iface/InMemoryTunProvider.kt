package com.rafambn.kmpvpn.iface

class InMemoryTunProvider : TunProvider {
    private val openPorts: LinkedHashMap<String, InMemoryOwnedTunPort> = linkedMapOf()


    override fun exists(interfaceName: String): Boolean {
        return openPorts.containsKey(interfaceName)
    }

    override fun open(interfaceName: String): OwnedTunPort {
        return openPorts.getOrPut(interfaceName) {
            InMemoryOwnedTunPort(
                interfaceName = interfaceName,
                onClose = { openPorts.remove(interfaceName) },
            )
        }
    }

    fun port(interfaceName: String): InMemoryOwnedTunPort? {
        return openPorts[interfaceName]
    }
}
