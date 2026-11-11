package com.rafambn.wgkotlin.crypto

import com.rafambn.wgkotlin.Engine
import com.rafambn.wgkotlin.VpnConfiguration
import com.rafambn.wgkotlin.crypto.factory.BoringTunPeerSessionFactory
import com.rafambn.wgkotlin.crypto.factory.PeerSessionFactory
import com.rafambn.wgkotlin.crypto.factory.QuicPeerSessionFactory
import com.rafambn.wgkotlin.network.io.UdpDatagram
import com.rafambn.wgkotlin.requireDistinctAllowedIpOwnership
import com.rafambn.wgkotlin.requireUniquePeerPublicKeys
import com.rafambn.wgkotlin.requireUserspacePeerEndpoints
import com.rafambn.wgkotlin.util.DuplexChannelPipe

internal class CryptoManager(
    engine: Engine = Engine.BORINGTUN,
    private val peerSessionFactory: PeerSessionFactory = when (engine) {
        Engine.BORINGTUN -> BoringTunPeerSessionFactory()
        Engine.QUIC -> QuicPeerSessionFactory()
    },
) {
    fun create(
        config: VpnConfiguration,
        tunPipe: DuplexChannelPipe<ByteArray>,
        networkPipe: DuplexChannelPipe<UdpDatagram>,
        onFailure: (Throwable) -> Unit = {},
    ): CryptoSession {
        requireUniquePeerPublicKeys(config.peers)
        requireUserspacePeerEndpoints(config.peers)
        requireDistinctAllowedIpOwnership(config.peers)

        val createdSessions: MutableList<PeerSessionEntry> = mutableListOf()

        try {
            config.peers.sortedBy { peer -> peer.publicKey }.forEachIndexed { index, peer ->
                val createdSession = peerSessionFactory.create(
                    config = config,
                    peer = peer,
                    peerIndex = index + 1,
                )
                createdSessions += PeerSessionEntry(peer = peer, session = createdSession)
                require(createdSession.isActive) {
                    "Session factory must return active peer sessions"
                }
            }
            return CryptoSession(createdSessions.toList(), tunPipe, networkPipe, onFailure)
        } catch (error: Throwable) {
            createdSessions.forEach { entry ->
                runCatching { entry.session.close() }.exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        }
    }
}
