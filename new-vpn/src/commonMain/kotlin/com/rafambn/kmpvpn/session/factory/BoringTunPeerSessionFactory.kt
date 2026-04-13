package com.rafambn.kmpvpn.session.factory

import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.VpnPeer
import com.rafambn.kmpvpn.session.BoringTunPeerSession
import com.rafambn.kmpvpn.session.PeerSession
import uniffi.new_vpn.TunnelSession

class BoringTunPeerSessionFactory : PeerSessionFactory {
    override fun create(
        config: VpnConfiguration,
        peer: VpnPeer,
        peerIndex: Int,
    ): PeerSession {
        require(peerIndex >= 0) {
            "Peer index must be non-negative"
        }

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
                index = peerIndex.toUInt(),
            )
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Failed to create UniFFI tunnel session for `${peer.publicKey}`: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        return BoringTunPeerSession(
            peerPublicKey = peer.publicKey,
            peerIndex = peerIndex,
            tunnel = tunnel,
        )
    }
}
