package com.rafambn.kmpvpn.session.factory

import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.VpnPeer
import com.rafambn.kmpvpn.session.BoringTunVpnSession
import com.rafambn.kmpvpn.session.VpnSession
import uniffi.new_vpn.TunnelSession

class BoringTunVpnSessionFactory : VpnSessionFactory {
    override fun create(
        config: VpnConfiguration,
        peer: VpnPeer,
        sessionIndex: UInt,
    ): VpnSession {
        val keepAlive: UShort = peer.persistentKeepalive
            ?.coerceIn(0, UShort.MAX_VALUE.toInt())
            ?.toUShort()
            ?: 0u.toUShort()

        val tunnel = try {
            TunnelSession.createNewTunnel(
                argSecretKey = config.privateKey,
                argPublicKey = peer.publicKey,
                argPresharedKey = peer.presharedKey,
                keepAlive = keepAlive,
                index = sessionIndex,
            )
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Failed to create UniFFI tunnel session for `${peer.publicKey}`: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        return BoringTunVpnSession(
            peerPublicKey = peer.publicKey,
            sessionIndex = sessionIndex,
            tunnel = tunnel,
        )
    }
}
