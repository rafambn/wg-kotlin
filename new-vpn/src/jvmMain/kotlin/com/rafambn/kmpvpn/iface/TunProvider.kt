package com.rafambn.kmpvpn.iface

interface TunProvider {
    fun exists(interfaceName: String): Boolean

    fun open(interfaceName: String): OwnedTunPort
}
