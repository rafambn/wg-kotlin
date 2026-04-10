package com.rafambn.kmpvpn.session.factory

import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.VpnPeer
import com.rafambn.kmpvpn.session.VpnSession

class QuicVpnSessionFactory : VpnSessionFactory {
    override fun create(
        config: VpnConfiguration,
        peer: VpnPeer,
        sessionIndex: UInt,
    ): VpnSession {
        throw UnsupportedOperationException("QUIC engine is not supported yet")
    }
}
