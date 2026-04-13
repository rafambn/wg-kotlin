package com.rafambn.kmpvpn.session

import com.rafambn.kmpvpn.VpnPeer
import com.rafambn.kmpvpn.session.io.UdpEndpoint

data class PeerSessionEntry(
    val peer: VpnPeer,
    val session: PeerSession,
) {
    fun peerEndpoint(): UdpEndpoint {
        return UdpEndpoint(
            address = checkNotNull(peer.endpointAddress) { "Peer `${peer.publicKey}` is missing endpointAddress" },
            port = checkNotNull(peer.endpointPort) { "Peer `${peer.publicKey}` is missing endpointPort" },
        )
    }
}
