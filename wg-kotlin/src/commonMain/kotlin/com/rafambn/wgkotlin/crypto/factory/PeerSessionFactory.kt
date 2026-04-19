package com.rafambn.wgkotlin.crypto.factory

import com.rafambn.wgkotlin.VpnConfiguration
import com.rafambn.wgkotlin.VpnPeer
import com.rafambn.wgkotlin.crypto.PeerSession

interface PeerSessionFactory {
    fun create(
        config: VpnConfiguration,
        peer: VpnPeer,
        peerIndex: Int,
    ): PeerSession
}
