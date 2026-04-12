package com.rafambn.kmpvpn.session.io

interface OwnedTunPort : TunPort, AutoCloseable {
    val interfaceName: String
}
