package com.rafambn.wgkotlin.crypto

import com.rafambn.wgkotlin.Engine
import com.rafambn.wgkotlin.VpnConfiguration
import com.rafambn.wgkotlin.requireDistinctAllowedIpOwnership
import com.rafambn.wgkotlin.requireUniquePeerPublicKeys
import com.rafambn.wgkotlin.requireUserspacePeerEndpoints
import com.rafambn.wgkotlin.crypto.factory.BoringTunPeerSessionFactory
import com.rafambn.wgkotlin.crypto.factory.PeerSessionFactory
import com.rafambn.wgkotlin.crypto.factory.QuicPeerSessionFactory
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import com.rafambn.wgkotlin.util.parseCidr
import com.rafambn.wgkotlin.util.parsePacketDestination
import com.rafambn.wgkotlin.util.matches
import com.rafambn.wgkotlin.network.io.UdpDatagram
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class CryptoSessionManagerImpl(
    engine: Engine = Engine.BORINGTUN,
    private val peerSessionFactory: PeerSessionFactory = defaultFactory(engine),
) : CryptoSessionManager {

    private val reconcileMutex = Mutex()

    /**
     * Immutable-map reference updated atomically under [reconcileMutex].
     * Reading the volatile reference outside the mutex is safe: callers see either the
     * old or the new map, never a torn intermediate state, because the map itself is
     * immutable once assigned.
     */
    @Volatile
    private var sessionEntriesByPeer: Map<String, PeerSessionEntry> = emptyMap()
    private val peerStatsByPublicKey: MutableMap<String, MutablePeerStats> = mutableMapOf()

    private var scope: CoroutineScope? = null

    override fun reconcileSessions(config: VpnConfiguration) {
        requireUniquePeerPublicKeys(config.peers)
        requireUserspacePeerEndpoints(config.peers)
        requireDistinctAllowedIpOwnership(config.peers)

        val desiredPeers = config.peers.associateBy { peer -> peer.publicKey }
        val desiredIndexes = desiredPeers.keys
            .toSortedSet()
            .mapIndexed { index, key -> key to (index + 1) }
            .toMap()
        val previousSessions = sessionEntriesByPeer.toMap()
        val nextSessions: MutableMap<String, PeerSessionEntry> = mutableMapOf()
        val createdSessions: MutableList<PeerSessionEntry> = mutableListOf()

        try {
            desiredPeers.keys.sorted().forEach { publicKey ->
                val desiredPeer = desiredPeers.getValue(publicKey)
                val desiredIndex = desiredIndexes.getValue(publicKey)
                val previous = previousSessions[publicKey]

                if (
                    previous != null &&
                    previous.peer == desiredPeer &&
                    previous.session.peerIndex == desiredIndex &&
                    previous.session.isActive
                ) {
                    nextSessions[publicKey] = checkNotNull(previous)
                    return@forEach
                }

                val createdSession = peerSessionFactory.create(
                    config = config,
                    peer = desiredPeer,
                    peerIndex = desiredIndex,
                )
                require(createdSession.isActive) {
                    "Session factory must return active peer sessions"
                }

                val sessionEntry = PeerSessionEntry(
                    peer = desiredPeer,
                    session = createdSession,
                )

                createdSessions += sessionEntry
                nextSessions[publicKey] = sessionEntry
            }
        } catch (throwable: Throwable) {
            createdSessions.forEach { entry -> entry.session.close() }
            throw throwable
        }

        previousSessions.forEach { (publicKey, entry) ->
            val replacement = nextSessions[publicKey]
            if (replacement !== entry) {
                entry.session.close()
            }
        }

        runBlocking {
            reconcileMutex.withLock {
                // Remove stats for peers that are no longer present so stale stats
                // from a previous session don't bleed into a fresh one.
                previousSessions.keys
                    .filter { key -> !nextSessions.containsKey(key) }
                    .forEach { key -> peerStatsByPublicKey.remove(key) }
                sessionEntriesByPeer = nextSessions.toMap()
            }
        }
    }

    override fun start(
        tunPipe: DuplexChannelPipe<ByteArray>,
        networkPipe: DuplexChannelPipe<UdpDatagram>,
        onFailure: (Throwable) -> Unit,
    ) {
        scope?.cancel("CryptoSessionManager restarted")
        peerStatsByPublicKey.clear()

        val coroutineLabel = "kmpvpn-crypto"
        val newScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineName(coroutineLabel),
        )
        scope = newScope

        newScope.launch(CoroutineName("$coroutineLabel-cleartext-ingress")) {
            runCleartextIngressLoop(tunPipe, networkPipe, onFailure)
        }
        newScope.launch(CoroutineName("$coroutineLabel-encrypted-ingress")) {
            runEncryptedIngressLoop(tunPipe, networkPipe, onFailure)
        }
        newScope.launch(CoroutineName("$coroutineLabel-periodic")) {
            runPeriodicLoop(networkPipe, onFailure)
        }
    }

    override fun stop() {
        scope?.cancel("CryptoSessionManager stopped")
        scope = null
        closePeerSessionsOnly()
    }

    override fun hasActiveSessions(): Boolean {
        return sessionEntriesByPeer.values.any { entry -> entry.session.isActive }
    }

    override fun peerStats(): List<VpnPeerStats> {
        return sessionEntriesByPeer.values.toList().map { entry ->
            val stats = peerStatsByPublicKey[entry.peer.publicKey] ?: MutablePeerStats()
            VpnPeerStats(
                publicKey = entry.peer.publicKey,
                endpointAddress = entry.peer.endpointAddress,
                endpointPort = entry.peer.endpointPort,
                allowedIps = entry.peer.allowedIps.toList(),
                receivedBytes = stats.receivedBytes,
                transmittedBytes = stats.transmittedBytes,
                lastHandshakeEpochSeconds = null,
            )
        }
    }

    private suspend fun runCleartextIngressLoop(
        tunPipe: DuplexChannelPipe<ByteArray>,
        networkPipe: DuplexChannelPipe<UdpDatagram>,
        onFailure: (Throwable) -> Unit,
    ) {
        try {
            while (true) {
                val packet = tunPipe.receive()

                // Collect action synchronously under the mutex — no suspension points inside.
                var pendingNetwork: UdpDatagram? = null
                var pendingTun: ByteArray? = null

                reconcileMutex.withLock {
                    val selected = selectSessionForPacket(packet, sessionEntriesByPeer.values)
                        ?: return@withLock
                    when (val result = selected.session.encryptRawPacket(packet, DEFAULT_PACKET_BUFFER_SIZE)) {
                        is PacketAction.WriteToNetwork -> {
                            peerStatsByPublicKey
                                .getOrPut(selected.peer.publicKey) { MutablePeerStats() }
                                .transmittedBytes += result.packet.size.toLong()
                            pendingNetwork = UdpDatagram(payload = result.packet, remoteEndpoint = selected.peerEndpoint())
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

                // Send outside the mutex so the lock is never held across a suspension point.
                pendingNetwork?.let { networkPipe.send(it) }
                pendingTun?.let { tunPipe.send(it) }
            }
        } catch (_: CancellationException) {
            // shutdown path
        } catch (throwable: Throwable) {
            onFailure(throwable)
        }
    }

    private suspend fun runEncryptedIngressLoop(
        tunPipe: DuplexChannelPipe<ByteArray>,
        networkPipe: DuplexChannelPipe<UdpDatagram>,
        onFailure: (Throwable) -> Unit,
    ) {
        try {
            while (true) {
                val datagram = networkPipe.receive()

                // Collect all actions synchronously under the mutex — no suspension points inside.
                val pendingNetwork = mutableListOf<UdpDatagram>()
                val pendingTun = mutableListOf<ByteArray>()

                reconcileMutex.withLock {
                    val selected = sessionEntriesByPeer.values.firstOrNull { entry ->
                        entry.peer.endpointAddress == datagram.remoteEndpoint.address &&
                            entry.peer.endpointPort == datagram.remoteEndpoint.port
                    } ?: return@withLock

                    peerStatsByPublicKey
                        .getOrPut(selected.peer.publicKey) { MutablePeerStats() }
                        .receivedBytes += datagram.payload.size.toLong()

                    var result = selected.session.decryptToRawPacket(datagram.payload, DEFAULT_PACKET_BUFFER_SIZE)
                    var iterations = 0

                    while (result !is PacketAction.Done) {
                        when (result) {
                            is PacketAction.WriteToNetwork -> {
                                peerStatsByPublicKey
                                    .getOrPut(selected.peer.publicKey) { MutablePeerStats() }
                                    .transmittedBytes += result.packet.size.toLong()
                                pendingNetwork += UdpDatagram(payload = result.packet, remoteEndpoint = selected.peerEndpoint())
                            }
                            is PacketAction.WriteToTunIpv4 -> pendingTun += result.packet
                            is PacketAction.WriteToTunIpv6 -> pendingTun += result.packet
                            PacketAction.Done -> Unit
                            is PacketAction.Error -> throw IllegalStateException(
                                "Session `${selected.session.peerPublicKey}` returned error code `${result.code}` for `decryptToRawPacket`",
                            )
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

                        result = selected.session.decryptToRawPacket(ByteArray(0), DEFAULT_PACKET_BUFFER_SIZE)
                    }
                }

                // Send outside the mutex so the lock is never held across a suspension point.
                for (d in pendingNetwork) networkPipe.send(d)
                for (p in pendingTun) tunPipe.send(p)
            }
        } catch (_: CancellationException) {
            // shutdown path
        } catch (throwable: Throwable) {
            onFailure(throwable)
        }
    }

    private suspend fun runPeriodicLoop(
        networkPipe: DuplexChannelPipe<UdpDatagram>,
        onFailure: (Throwable) -> Unit,
    ) {
        try {
            while (true) {
                delay(DEFAULT_PERIODIC_INTERVAL_MILLIS)

                // Collect all periodic actions synchronously under the mutex — no suspension points inside.
                val pendingNetwork = mutableListOf<UdpDatagram>()

                reconcileMutex.withLock {
                    sessionEntriesByPeer.values.toList().forEach { entry ->
                        when (val result = entry.session.runPeriodicTask(DEFAULT_PACKET_BUFFER_SIZE)) {
                            is PacketAction.WriteToNetwork -> {
                                peerStatsByPublicKey
                                    .getOrPut(entry.peer.publicKey) { MutablePeerStats() }
                                    .transmittedBytes += result.packet.size.toLong()
                                pendingNetwork += UdpDatagram(payload = result.packet, remoteEndpoint = entry.peerEndpoint())
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

                // Send outside the mutex so the lock is never held across a suspension point.
                for (d in pendingNetwork) networkPipe.send(d)
            }
        } catch (_: CancellationException) {
            // shutdown path
        } catch (throwable: Throwable) {
            onFailure(throwable)
        }
    }

    private fun selectSessionForPacket(
        packet: ByteArray,
        sessionEntries: Iterable<PeerSessionEntry>,
    ): PeerSessionEntry? {
        val destination = parsePacketDestination(packet) ?: return null
        return sessionEntries
            .mapNotNull { entry ->
                val match = entry.peer.allowedIps
                    .mapNotNull { allowedIp -> parseCidr(allowedIp) }
                    .filter { route -> route.matches(destination) }
                    .maxByOrNull { route -> route.prefixLength }
                    ?: return@mapNotNull null
                entry to match
            }
            .maxByOrNull { (_, route) -> route.prefixLength }
            ?.first
    }

    private fun closePeerSessionsOnly() {
        sessionEntriesByPeer.values.forEach { entry -> entry.session.close() }
        sessionEntriesByPeer = emptyMap()
        peerStatsByPublicKey.clear()
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
