package com.rafambn.wgkotlin.crypto

import com.rafambn.wgkotlin.VpnPeer
import com.rafambn.wgkotlin.network.io.UdpEndpoint
import com.rafambn.wgkotlin.util.toPlainString
import com.rafambn.wgkotlin.util.toTransportIp

internal data class PeerSessionEntry(
    val peer: VpnPeer,
    val session: PeerSession,
) {
    val endpoint = UdpEndpoint(
        address = checkNotNull(peer.endpointAddress?.toTransportIp()?.toPlainString()) { "Peer `${peer.publicKey}` is missing endpointAddress" },
        port = checkNotNull(peer.endpointPort) { "Peer `${peer.publicKey}` is missing endpointPort" },
    )
}
