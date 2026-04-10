package com.rafambn.kmpvpn.session.factory

import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.VpnPeer
import com.rafambn.kmpvpn.session.VpnSession

interface VpnSessionFactory {
    fun create(
        config: VpnConfiguration,
        peer: VpnPeer,
        sessionIndex: UInt,
    ): VpnSession
}
