package com.rafambn.wgkotlin.crypto

import com.rafambn.wgkotlin.Engine
import com.rafambn.wgkotlin.VpnConfiguration
import com.rafambn.wgkotlin.crypto.factory.BoringTunPeerSessionFactory
import com.rafambn.wgkotlin.crypto.factory.PeerSessionFactory
import com.rafambn.wgkotlin.crypto.factory.QuicPeerSessionFactory
import com.rafambn.wgkotlin.daemon.proto.Ip
import com.rafambn.wgkotlin.network.io.UdpDatagram
import com.rafambn.wgkotlin.requireDistinctAllowedIpOwnership
import com.rafambn.wgkotlin.requireUniquePeerPublicKeys
import com.rafambn.wgkotlin.requireUserspacePeerEndpoints
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import com.rafambn.wgkotlin.util.matches
import com.rafambn.wgkotlin.util.parsePacketDestination
import com.rafambn.wgkotlin.util.parsePacketSource
import com.rafambn.wgkotlin.util.toCidrString
import com.rafambn.wgkotlin.util.toPlainString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class CryptoSessionManagerImpl(
    private val tunPipe: DuplexChannelPipe<ByteArray>,
    private val networkPipe: DuplexChannelPipe<UdpDatagram>,
    engine: Engine = Engine.BORINGTUN,
    private val peerSessionFactory: PeerSessionFactory = defaultFactory(engine),
) : CryptoSessionManager {

    private val sessionMutex = Mutex()
    private var sessionEntries: List<PeerSessionEntry> = emptyList()
    private val peerStatsByPublicKey: MutableMap<String, MutablePeerStats> = mutableMapOf()

    private var scope: CoroutineScope? = null

    override fun createSessions(config: VpnConfiguration) {
        check(scope == null && sessionEntries.isEmpty()) { "Crypto sessions must be stopped before creating sessions" }
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
        } catch (error: Throwable) {
            createdSessions.forEach { entry ->
                runCatching { entry.session.close() }.exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        }
        sessionEntries = createdSessions.toList()
    }

    override fun start(onFailure: (Throwable) -> Unit) {
        check(scope == null) { "Crypto workers must be stopped before starting" }
        peerStatsByPublicKey.clear()

        val coroutineLabel = "kmpvpn-crypto"
        val newScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineName(coroutineLabel),
        )
        scope = newScope

        newScope.launch(CoroutineName("$coroutineLabel-cleartext-ingress")) {
            runCleartextIngressLoop(onFailure)
        }
        newScope.launch(CoroutineName("$coroutineLabel-encrypted-ingress")) {
            runEncryptedIngressLoop(onFailure)
        }
        newScope.launch(CoroutineName("$coroutineLabel-periodic")) {
            runPeriodicLoop(onFailure)
        }
    }

    override suspend fun stop() {
        scope?.coroutineContext?.job?.cancelAndJoin()
        scope = null
        var firstError: Throwable? = null
        sessionEntries.forEach { entry ->
            try {
                entry.session.close()
            } catch (error: Throwable) {
                if (firstError == null) firstError = error else firstError.addSuppressed(error)
            }
        }
        sessionEntries = emptyList()
        peerStatsByPublicKey.clear()
        firstError?.let { throw it }
    }

    override fun hasActiveSessions(): Boolean {
        return sessionEntries.any { entry -> entry.session.isActive }
    }

    override fun peerStats(): List<VpnPeerStats> {
        return sessionEntries.map { entry ->
            val stats = peerStatsByPublicKey[entry.peer.publicKey] ?: MutablePeerStats()
            VpnPeerStats(
                publicKey = entry.peer.publicKey,
                endpointAddress = entry.peer.endpointAddress?.toPlainString(),
                endpointPort = entry.peer.endpointPort,
                allowedIps = entry.peer.allowedIps.map { it.toCidrString() },
                receivedBytes = stats.receivedBytes,
                transmittedBytes = stats.transmittedBytes,
                lastHandshakeEpochSeconds = null,
            )
        }
    }

    private suspend fun runCleartextIngressLoop(onFailure: (Throwable) -> Unit) {
        try {
            while (true) {
                val packet = tunPipe.receive()
                var pendingNetwork: UdpDatagram? = null
                var pendingTun: ByteArray? = null

                sessionMutex.withLock {
                    val selected = selectSessionForPacket(packet, sessionEntries)
                        ?: return@withLock
                    when (val result = selected.session.encryptRawPacket(packet, DEFAULT_PACKET_BUFFER_SIZE)) {
                        is PacketAction.WriteToNetwork -> {
                            peerStatsByPublicKey
                                .getOrPut(selected.peer.publicKey) { MutablePeerStats() }
                                .transmittedBytes += result.packet.size.toLong()
                            pendingNetwork = UdpDatagram(payload = result.packet, remoteEndpoint = selected.endpoint)
                        }
                        is PacketAction.WriteToTunIpv4 -> pendingTun = result.packet
                        is PacketAction.WriteToTunIpv6 -> pendingTun = result.packet
                        PacketAction.Done -> Unit
                        is PacketAction.Error -> throw IllegalStateException(
                            "Session `${selected.session.peerPublicKey}` returned error code `${result.code}` for `encryptRawPacket`",
                        )
                        is PacketAction.NotSupported -> throw IllegalStateException(
                            "Session `${selected.session.peerPublicKey}` does not support `${result.operation}`",
                        )
                    }
                }

                pendingNetwork?.let { networkPipe.send(it) }
                pendingTun?.let { tunPipe.send(it) }
            }
        } catch (_: CancellationException) {
        } catch (throwable: Throwable) {
            onFailure(throwable)
        }
    }

    private suspend fun runEncryptedIngressLoop(onFailure: (Throwable) -> Unit) {
        try {
            while (true) {
                val datagram = networkPipe.receive()

                val pendingNetwork = mutableListOf<UdpDatagram>()
                val pendingTun = mutableListOf<ByteArray>()

                sessionMutex.withLock {
                    val selected = sessionEntries.firstOrNull { entry ->
                        entry.endpoint == datagram.remoteEndpoint
                    } ?: return@withLock

                    peerStatsByPublicKey
                        .getOrPut(selected.peer.publicKey) { MutablePeerStats() }
                        .receivedBytes += datagram.payload.size.toLong()

                    var result = selected.session.decryptToRawPacket(
                        src = datagram.payload,
                        dstSize = DEFAULT_PACKET_BUFFER_SIZE,
                        sourceIp = datagram.remoteEndpoint.address,
                    )
                    var iterations = 0

                    while (result !is PacketAction.Done) {
                        when (result) {
                            is PacketAction.WriteToNetwork -> {
                                peerStatsByPublicKey
                                    .getOrPut(selected.peer.publicKey) { MutablePeerStats() }
                                    .transmittedBytes += result.packet.size.toLong()
                                pendingNetwork += UdpDatagram(payload = result.packet, remoteEndpoint = selected.endpoint)
                            }
                            is PacketAction.WriteToTunIpv4 -> if (acceptsSource(result.packet, selected)) pendingTun += result.packet
                            is PacketAction.WriteToTunIpv6 -> if (acceptsSource(result.packet, selected)) pendingTun += result.packet
                            PacketAction.Done -> Unit
                            // A rejected datagram does not invalidate the peer's session.
                            is PacketAction.Error -> break
                            is PacketAction.NotSupported -> throw IllegalStateException(
                                "Session `${selected.session.peerPublicKey}` does not support `${result.operation}`",
                            )
                        }

                        iterations += 1
                        if (iterations >= DEFAULT_MAX_FLUSH_ITERATIONS) {
                            throw IllegalStateException(
                                "Session `${selected.session.peerPublicKey}` exceeded flush limit while decrypting packets",
                            )
                        }

                        result = selected.session.decryptToRawPacket(
                            src = ByteArray(0),
                            dstSize = DEFAULT_PACKET_BUFFER_SIZE,
                            sourceIp = datagram.remoteEndpoint.address,
                        )
                    }
                }

                for (d in pendingNetwork) networkPipe.send(d)
                for (p in pendingTun) tunPipe.send(p)
            }
        } catch (_: CancellationException) {
        } catch (throwable: Throwable) {
            onFailure(throwable)
        }
    }

    private suspend fun runPeriodicLoop(onFailure: (Throwable) -> Unit) {
        try {
            while (true) {
                delay(DEFAULT_PERIODIC_INTERVAL_MILLIS)

                val pendingNetwork = mutableListOf<UdpDatagram>()

                sessionMutex.withLock {
                    sessionEntries.forEach { entry ->
                        when (val result = entry.session.runPeriodicTask(DEFAULT_PACKET_BUFFER_SIZE)) {
                            is PacketAction.WriteToNetwork -> {
                                peerStatsByPublicKey
                                    .getOrPut(entry.peer.publicKey) { MutablePeerStats() }
                                    .transmittedBytes += result.packet.size.toLong()
                                pendingNetwork += UdpDatagram(payload = result.packet, remoteEndpoint = entry.endpoint)
                            }
                            is PacketAction.WriteToTunIpv4 -> Unit
                            is PacketAction.WriteToTunIpv6 -> Unit
                            PacketAction.Done -> Unit
                            is PacketAction.Error -> throw IllegalStateException(
                                "Session `${entry.session.peerPublicKey}` returned error code `${result.code}` for `runPeriodicTask`",
                            )
                            is PacketAction.NotSupported -> throw IllegalStateException(
                                "Session `${entry.session.peerPublicKey}` does not support `${result.operation}`",
                            )
                        }
                    }
                }

                for (d in pendingNetwork) networkPipe.send(d)
            }
        } catch (_: CancellationException) {
        } catch (throwable: Throwable) {
            onFailure(throwable)
        }
    }

    private fun selectSessionForPacket(
        packet: ByteArray,
        sessionEntries: Iterable<PeerSessionEntry>,
    ): PeerSessionEntry? {
        val destination = try { parsePacketDestination(packet) } catch (_: Exception) { return null }
        return selectSessionForAddress(destination, sessionEntries)
    }

    private fun acceptsSource(packet: ByteArray, authenticatedPeer: PeerSessionEntry): Boolean {
        val source = try { parsePacketSource(packet) } catch (_: Exception) { return false }
        return selectSessionForAddress(source, sessionEntries) === authenticatedPeer
    }

    private fun selectSessionForAddress(destination: Ip, sessionEntries: Iterable<PeerSessionEntry>): PeerSessionEntry? {
        return sessionEntries
            .mapNotNull { entry ->
                val match = entry.peer.allowedIps
                    .filter { allowedIp -> allowedIp.matches(destination) }
                    .maxByOrNull { route -> route.prefix }
                    ?: return@mapNotNull null
                entry to match
            }
            .maxByOrNull { (_, route) -> route.prefix }
            ?.first
    }

    private companion object {
        val DEFAULT_PACKET_BUFFER_SIZE: UInt = 65535u
        const val DEFAULT_MAX_FLUSH_ITERATIONS: Int = 256
        const val DEFAULT_PERIODIC_INTERVAL_MILLIS: Long = 100L

        fun defaultFactory(engine: Engine): PeerSessionFactory = when (engine) {
            Engine.BORINGTUN -> BoringTunPeerSessionFactory()
            Engine.QUIC -> QuicPeerSessionFactory()
        }
    }
}
