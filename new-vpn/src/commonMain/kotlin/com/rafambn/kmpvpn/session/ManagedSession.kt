package com.rafambn.kmpvpn.session

import com.rafambn.kmpvpn.VpnPeer

data class ManagedSession(
    val peer: VpnPeer,
    val session: VpnSession,
)
