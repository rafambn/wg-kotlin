package com.rafambn.kmpvpn.session.factory

import com.rafambn.kmpvpn.VpnAdapterConfiguration
import com.rafambn.kmpvpn.VpnPeer
import com.rafambn.kmpvpn.session.VpnSession

interface VpnSessionFactory {
    fun create(
        config: VpnAdapterConfiguration,
        peer: VpnPeer,
        sessionIndex: UInt,
    ): VpnSession
}
