package com.rafambn.kmpvpn.session.io

interface TunProvider {
    fun exists(interfaceName: String): Boolean

    fun open(interfaceName: String): OwnedTunPort
}
