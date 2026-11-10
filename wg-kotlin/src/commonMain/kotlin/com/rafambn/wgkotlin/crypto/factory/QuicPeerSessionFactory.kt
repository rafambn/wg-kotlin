package com.rafambn.wgkotlin.crypto.factory

import com.rafambn.wgkotlin.VpnConfiguration
import com.rafambn.wgkotlin.VpnPeer
import com.rafambn.wgkotlin.crypto.PeerSession

internal class QuicPeerSessionFactory : PeerSessionFactory {
    override fun create(
        config: VpnConfiguration,
        peer: VpnPeer,
        peerIndex: Int,
    ): PeerSession {
        throw UnsupportedOperationException("QUIC engine is not supported yet")
    }
}
