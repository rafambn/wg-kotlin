package com.rafambn.kmpvpn.iface

import com.rafambn.kmpvpn.session.io.TunPort

interface OwnedTunPort : TunPort, AutoCloseable {
    val interfaceName: String
}
