package com.rafambn.kmpvpn.session

import com.rafambn.kmpvpn.Engine
import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.Vpn
import com.rafambn.kmpvpn.iface.VpnPeerStats
import com.rafambn.kmpvpn.matches
import com.rafambn.kmpvpn.parseCidr
import com.rafambn.kmpvpn.parsePacketDestination
import com.rafambn.kmpvpn.requireDistinctAllowedIpOwnership
import com.rafambn.kmpvpn.requireUserspacePeerEndpoints
import com.rafambn.kmpvpn.requireUniquePeerPublicKeys
import com.rafambn.kmpvpn.session.factory.BoringTunPeerSessionFactory
import com.rafambn.kmpvpn.session.factory.QuicPeerSessionFactory
import com.rafambn.kmpvpn.session.factory.PeerSessionFactory
import com.rafambn.kmpvpn.session.io.BufferedTunPacketPort
import com.rafambn.kmpvpn.session.io.DiscardingTunPacketPort
import com.rafambn.kmpvpn.session.io.TunPacketPort
import com.rafambn.kmpvpn.session.io.UdpDatagram
import com.rafambn.kmpvpn.session.io.UdpEndpoint
import com.rafambn.kmpvpn.session.io.UdpPort
import com.rafambn.kmpvpn.session.io.PacketAction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class TunnelManagerImpl(
    engine: Engine = Engine.BORINGTUN,
    private val peerSessionFactory: PeerSessionFactory = defaultFactory(engine),
) : TunnelManager {
    private val dataPlaneMutex = Mutex()
    private val sessionEntriesByPeer: MutableMap<String, PeerSessionEntry> = mutableMapOf()
    private var dataPlane: UserspaceDataPlane? = null
    private val peerStatsByPublicKey: MutableMap<String, MutablePeerStats> = mutableMapOf()

    private var dataPlaneTunPacketPort: TunPacketPort = DiscardingTunPacketPort

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
            createdSessions.forEach { entry ->
                entry.session.close()
            }
            throw throwable
        }

        previousSessions.forEach { (publicKey, entry) ->
            val replacement = nextSessions[publicKey]
            if (replacement !== entry) {
                entry.session.close()
            }
        }

        sessionEntriesByPeer.clear()
        sessionEntriesByPeer.putAll(nextSessions)

        if (sessionEntriesByPeer.isEmpty()) {
            stopDataPlane()
        }
    }

    override fun hasActiveSessions(): Boolean {
        return sessionEntriesByPeer.values.any { entry -> entry.session.isActive }
    }

    override fun closeAll() {
        try {
            stopDataPlane()
        } finally {
            closePeerSessionsOnly()
        }
    }

    override fun startDataPlane(configuration: VpnConfiguration) {
        if (sessionEntriesByPeer.isEmpty()) {
            stopDataPlaneQuietly()
            return
        }

        val desiredListenPort = configuration.listenPort ?: Vpn.DEFAULT_PORT

        val currentDataPlane = dataPlane
        if (currentDataPlane != null && currentDataPlane.isRunning()) {
            return
        }

        stopDataPlaneQuietly()
        dataPlaneTunPacketPort = BufferedTunPacketPort()
        peerStatsByPublicKey.clear()

        val createdDataPlane = UserspaceDataPlane(
            configuration = configuration,
            listenPort = desiredListenPort,
            onFailure = { _ ->
                clearDataPlaneState()
                closePeerSessionsOnly()
            },
            pollInboundPacketOnce = ::pollInboundPacketOnce,
            pollOutboundPacketOnce = ::pollOutboundPacketOnce,
            runPeriodicWorkOnce = ::runPeriodicWorkOnce,
        )

        dataPlane = createdDataPlane
    }

    override fun peerStats(): List<VpnPeerStats> {
        return sortedSessionEntries()
            .map { entry ->
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

    private suspend fun pollInboundPacketOnce(networkPort: UdpPort): Boolean {
        val datagram = networkPort.receiveDatagram() ?: return false
        return dataPlaneMutex.withLock {
            processNetworkInboundPacket(
                tunnelPort = dataPlaneTunPacketPort,
                networkPort = networkPort,
                datagram = datagram,
            )
        }
    }

    private suspend fun pollOutboundPacketOnce(networkPort: UdpPort): Boolean {
        val packet = dataPlaneTunPacketPort.readPacket() ?: return false
        return dataPlaneMutex.withLock {
            processTunnelOutboundPacket(
                tunnelPort = dataPlaneTunPacketPort,
                networkPort = networkPort,
                packet = packet,
            )
        }
    }

    private suspend fun runPeriodicWorkOnce(networkPort: UdpPort): Boolean {
        return dataPlaneMutex.withLock {
            processPeriodicTasks(networkPort = networkPort)
        }
    }

    private suspend fun processTunnelOutboundPacket(
        tunnelPort: TunPacketPort,
        networkPort: UdpPort,
        packet: ByteArray,
    ): Boolean {
        val selected = selectSessionForPacket(
            packet = packet,
            sessionEntries = sessionEntriesByPeer.values,
        ) ?: return true

        applyPacketResult(
            tunnelPort = tunnelPort,
            networkPort = networkPort,
            result = selected.session.encryptRawPacket(packet, DEFAULT_PACKET_BUFFER_SIZE),
            sessionEntry = selected,
            endpoint = selected.peerEndpoint(),
            operation = "encryptRawPacket",
        )
        return true
    }

    private suspend fun processNetworkInboundPacket(
        tunnelPort: TunPacketPort,
        networkPort: UdpPort,
        datagram: UdpDatagram,
    ): Boolean {
        val selected = sessionEntriesByPeer.values.firstOrNull { entry ->
            entry.peer.endpointAddress == datagram.remoteEndpoint.address &&
                    entry.peer.endpointPort == datagram.remoteEndpoint.port
        } ?: return true

        peerStatsByPublicKey.getOrPut(selected.peer.publicKey) { MutablePeerStats() }.receivedBytes += datagram.payload.size.toLong()

        var result = selected.session.decryptToRawPacket(
            datagram.payload,
            DEFAULT_PACKET_BUFFER_SIZE,
        )
        var iterations = 0

        while (result !is PacketAction.Done) {
            applyPacketResult(
                tunnelPort = tunnelPort,
                networkPort = networkPort,
                result = result,
                sessionEntry = selected,
                endpoint = selected.peerEndpoint(),
                operation = "decryptToRawPacket",
            )

            iterations += 1
            if (iterations >= DEFAULT_MAX_FLUSH_ITERATIONS) {
                throw IllegalStateException(
                    "Session `${selected.session.peerPublicKey}` exceeded flush limit while decrypting packets",
                )
            }

            result = selected.session.decryptToRawPacket(
                ByteArray(0),
                DEFAULT_PACKET_BUFFER_SIZE,
            )
        }

        return true
    }

    private suspend fun processPeriodicTasks(
        networkPort: UdpPort,
    ): Boolean {
        var didWork = false
        sortedSessionEntries().forEach { entry ->
            applyPacketResult(
                tunnelPort = dataPlaneTunPacketPort,
                networkPort = networkPort,
                result = entry.session.runPeriodicTask(DEFAULT_PACKET_BUFFER_SIZE),
                sessionEntry = entry,
                endpoint = entry.peerEndpoint(),
                operation = "runPeriodicTask",
            )
            didWork = true
        }
        return didWork
    }

    private suspend fun applyPacketResult(
        tunnelPort: TunPacketPort,
        networkPort: UdpPort,
        result: PacketAction,
        sessionEntry: PeerSessionEntry,
        endpoint: UdpEndpoint,
        operation: String,
    ) {
        when (result) {
            PacketAction.Done -> Unit
            is PacketAction.WriteToNetwork -> {
                networkPort.sendDatagram(UdpDatagram(payload = result.packet, remoteEndpoint = endpoint))
                peerStatsByPublicKey.getOrPut(sessionEntry.peer.publicKey) { MutablePeerStats() }.transmittedBytes += result.packet.size.toLong()
            }

            is PacketAction.WriteToTunIpv4 -> tunnelPort.writePacket(result.packet)
            is PacketAction.WriteToTunIpv6 -> tunnelPort.writePacket(result.packet)
            is PacketAction.Error -> {
                throw IllegalStateException(
                    "Session `${sessionEntry.session.peerPublicKey}` returned error code `${result.code}` for `$operation`",
                )
            }

            is PacketAction.NotSupported -> {
                throw IllegalStateException(
                    "Session `${sessionEntry.session.peerPublicKey}` does not support `${result.operation}`",
                )
            }
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

    private fun sortedSessionEntries(): List<PeerSessionEntry> {
        return sessionEntriesByPeer.values.sortedBy { entry -> entry.session.peerIndex }
    }

    private fun PeerSessionEntry.peerEndpoint(): UdpEndpoint {
        return UdpEndpoint(
            address = checkNotNull(peer.endpointAddress) { "Peer `${peer.publicKey}` is missing endpointAddress" },
            port = checkNotNull(peer.endpointPort) { "Peer `${peer.publicKey}` is missing endpointPort" },
        )
    }

    private fun closePeerSessionsOnly() {
        sessionEntriesByPeer.values.forEach { entry ->
            entry.session.close()
        }
        sessionEntriesByPeer.clear()
        peerStatsByPublicKey.clear()
    }

    private fun stopDataPlane() {
        try {
            dataPlane?.close()
        } finally {
            clearDataPlaneState()
        }
    }

    private fun stopDataPlaneQuietly() {
        try {
            stopDataPlane()
        } catch (_: Throwable) {
            clearDataPlaneState()
        }
    }

    private fun clearDataPlaneState() {
        dataPlane = null
        dataPlaneTunPacketPort = DiscardingTunPacketPort
        peerStatsByPublicKey.clear()
    }

    private companion object {
        val DEFAULT_PACKET_BUFFER_SIZE: UInt = 65535u
        const val DEFAULT_MAX_FLUSH_ITERATIONS: Int = 16

        fun defaultFactory(engine: Engine): PeerSessionFactory {
            return when (engine) {
                Engine.BORINGTUN -> BoringTunPeerSessionFactory()
                Engine.QUIC -> QuicPeerSessionFactory()
            }
        }
    }
}
