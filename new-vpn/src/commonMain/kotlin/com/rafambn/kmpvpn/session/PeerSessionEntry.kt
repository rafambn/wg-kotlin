package com.rafambn.kmpvpn.session

import com.rafambn.kmpvpn.VpnPeer

data class PeerSessionEntry(
    val peer: VpnPeer,
    val session: PeerSession,
)
