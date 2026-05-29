package com.rafambn.wgkotlin.crypto.factory

import com.rafambn.wgkotlin.ParsedVpnConfiguration
import com.rafambn.wgkotlin.ParsedVpnPeer
import com.rafambn.wgkotlin.crypto.PeerSession

internal interface PeerSessionFactory {
    fun create(
        config: ParsedVpnConfiguration,
        peer: ParsedVpnPeer,
        peerIndex: Int,
    ): PeerSession
}
