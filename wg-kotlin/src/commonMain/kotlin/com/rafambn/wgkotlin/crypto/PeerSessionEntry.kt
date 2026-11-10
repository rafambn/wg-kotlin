package com.rafambn.wgkotlin.crypto

import com.rafambn.wgkotlin.VpnPeer
import com.rafambn.wgkotlin.network.io.UdpEndpoint
import com.rafambn.wgkotlin.util.toPlainString

internal data class PeerSessionEntry(
    val peer: VpnPeer,
    val session: PeerSession,
) {
    fun peerEndpoint(): UdpEndpoint {
        return UdpEndpoint(
            address = checkNotNull(peer.endpointAddress?.toPlainString()) { "Peer `${peer.publicKey}` is missing endpointAddress" },
            port = checkNotNull(peer.endpointPort) { "Peer `${peer.publicKey}` is missing endpointPort" },
        )
    }
}
