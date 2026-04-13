package com.rafambn.kmpvpn.session

import com.rafambn.kmpvpn.Engine
import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.VpnPeer
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
import com.rafambn.kmpvpn.session.io.TunPacketPort
import com.rafambn.kmpvpn.session.io.UdpDatagram
import com.rafambn.kmpvpn.session.io.UdpEndpoint
import com.rafambn.kmpvpn.session.io.UdpPort
import com.rafambn.kmpvpn.session.io.PacketAction

internal class TunnelManagerImpl(
    engine: Engine = Engine.BORINGTUN,
    private val peerSessionFactory: PeerSessionFactory = defaultFactory(engine),
    private val userspaceDataPlaneFactory: UserspaceDataPlaneFactory = PlatformUserspaceDataPlaneFactory::create,
    private val tunPacketPortProvider: (VpnConfiguration) -> TunPacketPort = { DiscardingTunPacketPort },
) : TunnelManager {
    private val sessionEntriesByPeer: LinkedHashMap<String, PeerSessionEntry> = linkedMapOf()
    private val peerStatsByPublicKey: MutableMap<String, MutablePeerStats> = linkedMapOf()
    private var dataPlane: UserspaceDataPlane? = null
    private var dataPlaneKey: DataPlaneKey? = null
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
        val nextSessions: LinkedHashMap<String, PeerSessionEntry> = linkedMapOf()
        val createdSessions: MutableList<PeerSessionEntry> = mutableListOf()

        try {
            desiredPeers.keys.sorted().forEach { publicKey ->
                val desiredPeer = desiredPeers.getValue(publicKey)
                val desiredIndex = desiredIndexes.getValue(publicKey)
                val previous = previousSessions[publicKey]

                if (shouldReuse(previous, desiredPeer, desiredIndex)) {
                    nextSessions[publicKey] = checkNotNull(previous)
                    return@forEach
                }

                val sessionEntry = createPeerSessionEntry(
                    config = config,
                    peerPublicKey = publicKey,
                    desiredPeer = desiredPeer,
                    desiredIndex = desiredIndex,
                )

                createdSessions += sessionEntry
                nextSessions[publicKey] = sessionEntry
            }
        } catch (throwable: Throwable) {
            createdSessions.forEach { entry -> safeClose(entry) }
            throw throwable
        }

        previousSessions.forEach { (publicKey, entry) ->
            val replacement = nextSessions[publicKey]
            if (replacement !== entry) {
                safeClose(entry)
            }
        }

        sessionEntriesByPeer.clear()
        sessionEntriesByPeer.putAll(nextSessions)

        if (sessionEntriesByPeer.isEmpty()) {
            safeStopDataPlane()
        }
    }

    override fun sessionSnapshots(): List<PeerSessionSnapshot> {
        return sessionEntriesByPeer.values
            .sortedBy { entry -> entry.session.peerIndex }
            .map { entry ->
                PeerSessionSnapshot(
                    peerPublicKey = entry.peer.publicKey,
                    endpointAddress = entry.peer.endpointAddress,
                    endpointPort = entry.peer.endpointPort,
                    allowedIps = entry.peer.allowedIps,
                    peerIndex = entry.session.peerIndex,
                    isActive = entry.session.isActive,
                )
            }
    }

    override fun sessionEntries(): List<PeerSessionEntry> {
        return sessionEntriesByPeer.values
            .sortedBy { entry -> entry.session.peerIndex }
            .map { entry -> entry.copy() }
    }

    override fun sessionSnapshot(peerKey: String): PeerSessionSnapshot? {
        val entry = sessionEntriesByPeer[peerKey] ?: return null
        return PeerSessionSnapshot(
            peerPublicKey = entry.peer.publicKey,
            endpointAddress = entry.peer.endpointAddress,
            endpointPort = entry.peer.endpointPort,
            allowedIps = entry.peer.allowedIps,
            peerIndex = entry.session.peerIndex,
            isActive = entry.session.isActive,
        )
    }

    override fun closePeerSession(peerKey: String) {
        val entry = sessionEntriesByPeer.remove(peerKey) ?: return
        safeClose(entry)
        if (sessionEntriesByPeer.isEmpty()) {
            safeStopDataPlane()
        }
    }

    override fun closeAll() {
        var dataPlaneFailure: Throwable? = null
        try {
            stopDataPlane()
        } catch (throwable: Throwable) {
            dataPlaneFailure = throwable
        }

        closePeerSessionsOnly()
        dataPlaneFailure?.let { throwable -> throw throwable }
    }

    override fun startDataPlane(
        configuration: VpnConfiguration,
        onFailure: (Throwable) -> Unit,
    ) {
        if (sessionEntriesByPeer.isEmpty()) {
            safeStopDataPlane()
            return
        }

        val desiredKey = DataPlaneKey(
            interfaceName = configuration.interfaceName,
            listenPort = configuration.listenPort ?: Vpn.DEFAULT_PORT,
        )
        val currentDataPlane = dataPlane
        if (currentDataPlane != null && dataPlaneKey == desiredKey && currentDataPlane.isRunning()) {
            return
        }

        safeStopDataPlane()
        dataPlaneTunPacketPort = tunPacketPortProvider(configuration)
        peerStatsByPublicKey.clear()

        val createdDataPlane = userspaceDataPlaneFactory(
            configuration,
            desiredKey.listenPort,
            ::pollDataPlaneOnce,
            ::currentPeerStats,
        ) { throwable ->
            clearDataPlaneState()
            closePeerSessionsOnly()
            onFailure(throwable)
        } ?: return

        dataPlane = createdDataPlane
        dataPlaneKey = desiredKey
    }

    override fun peerStats(): List<VpnPeerStats> {
        return dataPlane?.peerStats() ?: currentPeerStats()
    }

    private fun currentPeerStats(): List<VpnPeerStats> {
        return sessionEntriesByPeer.values
            .sortedBy { entry -> entry.session.peerIndex }
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

    internal suspend fun pollDataPlaneOnce(
        udpPort: UdpPort,
        periodicTicker: () -> Boolean,
    ): Boolean {
        var didWork = false
        didWork = processTunInput(tunPacketPort = dataPlaneTunPacketPort, udpPort = udpPort) || didWork
        didWork = processUdpInput(tunPacketPort = dataPlaneTunPacketPort, udpPort = udpPort) || didWork
        if (periodicTicker()) {
            didWork = processPeriodicTasks(udpPort = udpPort) || didWork
        }
        return didWork
    }

    private fun shouldReuse(
        previous: PeerSessionEntry?,
        desiredPeer: VpnPeer,
        desiredIndex: Int,
    ): Boolean {
        return previous != null &&
            previous.peer == desiredPeer &&
            previous.session.peerIndex == desiredIndex &&
            previous.session.isActive
    }

    private fun createPeerSessionEntry(
        config: VpnConfiguration,
        peerPublicKey: String,
        desiredPeer: VpnPeer,
        desiredIndex: Int,
    ): PeerSessionEntry {
        val createdSession = peerSessionFactory.create(
            config = config,
            peer = desiredPeer,
            peerIndex = desiredIndex,
        )

        require(createdSession.peerPublicKey == peerPublicKey) {
            "Session factory returned mismatched peer key `${createdSession.peerPublicKey}` for `$peerPublicKey`"
        }
        require(createdSession.peerIndex == desiredIndex) {
            "Session factory returned mismatched peer index `${createdSession.peerIndex}` for `$desiredIndex`"
        }
        require(createdSession.isActive) {
            "Session factory must return active peer sessions"
        }

        return PeerSessionEntry(
            peer = desiredPeer,
            session = createdSession,
        )
    }

    private fun safeClose(entry: PeerSessionEntry) {
        try {
            entry.session.close()
        } catch (_: Throwable) {
            // best-effort close for rollback and stale session cleanup
        }
    }

    private suspend fun processTunInput(
        tunPacketPort: TunPacketPort,
        udpPort: UdpPort,
    ): Boolean {
        val packet = tunPacketPort.readPacket() ?: return false
        val selected = selectSessionForPacket(
            packet = packet,
            sessionEntries = sessionEntries(),
        ) ?: return true

        applyPacketResult(
            tunPacketPort = tunPacketPort,
            udpPort = udpPort,
            result = selected.session.encryptRawPacket(packet, DEFAULT_PACKET_BUFFER_SIZE),
            sessionEntry = selected,
            endpoint = selected.peerEndpoint(),
            operation = "encryptRawPacket",
        )
        return true
    }

    private suspend fun processUdpInput(
        tunPacketPort: TunPacketPort,
        udpPort: UdpPort,
    ): Boolean {
        val datagram = udpPort.receiveDatagram() ?: return false
        val selected = sessionEntries().firstOrNull { entry ->
            entry.peer.endpointAddress == datagram.endpoint.host &&
                entry.peer.endpointPort == datagram.endpoint.port
        } ?: return true

        peerStatsByPublicKey.getOrPut(selected.peer.publicKey) { MutablePeerStats() }.receivedBytes += datagram.packet.size.toLong()

        var result = selected.session.decryptToRawPacket(
            datagram.packet,
            DEFAULT_PACKET_BUFFER_SIZE,
        )
        var iterations = 0

        while (result !is PacketAction.Done) {
            applyPacketResult(
                tunPacketPort = tunPacketPort,
                udpPort = udpPort,
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
        udpPort: UdpPort,
    ): Boolean {
        var didWork = false
        sessionEntries().forEach { entry ->
            applyPacketResult(
                tunPacketPort = dataPlaneTunPacketPort,
                udpPort = udpPort,
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
        tunPacketPort: TunPacketPort,
        udpPort: UdpPort,
        result: PacketAction,
        sessionEntry: PeerSessionEntry,
        endpoint: UdpEndpoint,
        operation: String,
    ) {
        when (result) {
            PacketAction.Done -> Unit
            is PacketAction.WriteToNetwork -> {
                udpPort.sendDatagram(UdpDatagram(packet = result.packet, endpoint = endpoint))
                peerStatsByPublicKey.getOrPut(sessionEntry.peer.publicKey) { MutablePeerStats() }.transmittedBytes += result.packet.size.toLong()
            }

            is PacketAction.WriteToTunIpv4 -> tunPacketPort.writePacket(result.packet)
            is PacketAction.WriteToTunIpv6 -> tunPacketPort.writePacket(result.packet)
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
        sessionEntries: List<PeerSessionEntry>,
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

    private fun PeerSessionEntry.peerEndpoint(): UdpEndpoint {
        return UdpEndpoint(
            host = checkNotNull(peer.endpointAddress) { "Peer `${peer.publicKey}` is missing endpointAddress" },
            port = checkNotNull(peer.endpointPort) { "Peer `${peer.publicKey}` is missing endpointPort" },
        )
    }

    private fun stopDataPlane() {
        val currentDataPlane = dataPlane
        try {
            currentDataPlane?.close()
        } finally {
            clearDataPlaneState()
        }
    }

    private fun safeStopDataPlane() {
        try {
            stopDataPlane()
        } catch (_: Throwable) {
            clearDataPlaneState()
        }
    }

    private fun closePeerSessionsOnly() {
        sessionEntriesByPeer.values.forEach { entry -> safeClose(entry) }
        sessionEntriesByPeer.clear()
        peerStatsByPublicKey.clear()
    }

    private fun clearDataPlaneState() {
        dataPlane = null
        dataPlaneKey = null
        dataPlaneTunPacketPort = DiscardingTunPacketPort
        peerStatsByPublicKey.clear()
    }

    private data class DataPlaneKey(
        val interfaceName: String,
        val listenPort: Int,
    )

    private class MutablePeerStats {
        var receivedBytes: Long = 0L
        var transmittedBytes: Long = 0L
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

    private data object DiscardingTunPacketPort : TunPacketPort {
        override suspend fun readPacket(): ByteArray? = null

        override suspend fun writePacket(packet: ByteArray) = Unit
    }
}
