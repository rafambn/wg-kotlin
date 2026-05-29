package com.rafambn.wgkotlin.crypto.factory

import com.rafambn.wgkotlin.ParsedVpnConfiguration
import com.rafambn.wgkotlin.ParsedVpnPeer
import com.rafambn.wgkotlin.crypto.PeerSession

internal class QuicPeerSessionFactory : PeerSessionFactory {
    override fun create(
        config: ParsedVpnConfiguration,
        peer: ParsedVpnPeer,
        peerIndex: Int,
    ): PeerSession {
        throw UnsupportedOperationException("QUIC engine is not supported yet")
    }
}
