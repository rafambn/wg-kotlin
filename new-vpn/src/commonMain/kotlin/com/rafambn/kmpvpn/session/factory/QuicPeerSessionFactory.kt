package com.rafambn.kmpvpn.session.factory

import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.VpnPeer
import com.rafambn.kmpvpn.session.PeerSession

class QuicPeerSessionFactory : PeerSessionFactory {
    override fun create(
        config: VpnConfiguration,
        peer: VpnPeer,
        peerIndex: Int,
    ): PeerSession {
        throw UnsupportedOperationException("QUIC engine is not supported yet")
    }
}
