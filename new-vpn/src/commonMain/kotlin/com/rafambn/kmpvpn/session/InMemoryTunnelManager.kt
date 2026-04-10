package com.rafambn.kmpvpn.session

import com.rafambn.kmpvpn.Engine
import com.rafambn.kmpvpn.VpnAdapterConfiguration
import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.VpnPeer
import com.rafambn.kmpvpn.Vpn
import com.rafambn.kmpvpn.iface.InterfaceManager
import com.rafambn.kmpvpn.iface.VpnPeerStats
import com.rafambn.kmpvpn.matches
import com.rafambn.kmpvpn.parseCidr
import com.rafambn.kmpvpn.parsePacketDestination
import com.rafambn.kmpvpn.requireDistinctAllowedIpOwnership
import com.rafambn.kmpvpn.requireUserspacePeerEndpoints
import com.rafambn.kmpvpn.requireUniquePeerPublicKeys
import com.rafambn.kmpvpn.session.factory.BoringTunVpnSessionFactory
import com.rafambn.kmpvpn.session.factory.QuicVpnSessionFactory
import com.rafambn.kmpvpn.session.factory.VpnSessionFactory
import com.rafambn.kmpvpn.session.io.TunPort
import com.rafambn.kmpvpn.session.io.UdpDatagram
import com.rafambn.kmpvpn.session.io.UdpEndpoint
import com.rafambn.kmpvpn.session.io.UdpPort
import com.rafambn.kmpvpn.session.io.VpnPacketLoop
import com.rafambn.kmpvpn.session.io.VpnPacketResult

internal class InMemoryTunnelManager(
    engine: Engine = Engine.BORINGTUN,
    private val sessionFactory: VpnSessionFactory = defaultFactory(engine),
    private val userspaceRuntimeFactory: UserspaceRuntimeFactory = PlatformUserspaceRuntimeFactory::create,
) : TunnelManager {
    private val sessionsByPeer: LinkedHashMap<String, ManagedSession> = linkedMapOf()
    private val peerStatsByPublicKey: MutableMap<String, MutablePeerStats> = linkedMapOf()
    private var runtimeHandle: UserspaceRuntimeHandle? = null
    private var runtimeKey: RuntimeKey? = null
    private var runtimeTunPort: TunPort? = null

    override fun reconcileSessions(config: VpnAdapterConfiguration) {
        requireUniquePeerPublicKeys(config.peers)
        requireUserspacePeerEndpoints(config.peers)
        requireDistinctAllowedIpOwnership(config.peers)

        val desiredPeers = config.peers.associateBy { peer -> peer.publicKey }
        val desiredIndexes = desiredPeers.keys
            .toSortedSet()
            .mapIndexed { index, key -> key to (index + 1).toUInt() }
            .toMap()
        val previousSessions = sessionsByPeer.toMap()
        val nextSessions: LinkedHashMap<String, ManagedSession> = linkedMapOf()
        val createdSessions: MutableList<ManagedSession> = mutableListOf()

        try {
            desiredPeers.keys.sorted().forEach { publicKey ->
                val desiredPeer = desiredPeers.getValue(publicKey)
                val desiredIndex = desiredIndexes.getValue(publicKey)
                val previous = previousSessions[publicKey]

                if (shouldReuse(previous, desiredPeer, desiredIndex)) {
                    nextSessions[publicKey] = checkNotNull(previous)
                    return@forEach
                }

                val managedSession = createManagedSession(
                    config = config,
                    peerPublicKey = publicKey,
                    desiredPeer = desiredPeer,
                    desiredIndex = desiredIndex,
                )

                createdSessions += managedSession
                nextSessions[publicKey] = managedSession
            }
        } catch (throwable: Throwable) {
            createdSessions.forEach { managed -> safeClose(managed) }
            throw throwable
        }

        previousSessions.forEach { (publicKey, managed) ->
            val replacement = nextSessions[publicKey]
            if (replacement !== managed) {
                safeClose(managed)
            }
        }

        sessionsByPeer.clear()
        sessionsByPeer.putAll(nextSessions)

        if (sessionsByPeer.isEmpty()) {
            safeStopRuntime()
        }
    }

    override fun sessions(): List<SessionSnapshot> {
        return sessionsByPeer.values
            .sortedBy { managed -> managed.session.sessionIndex.toInt() }
            .map { managed ->
                SessionSnapshot(
                    peerPublicKey = managed.peer.publicKey,
                    endpointAddress = managed.peer.endpointAddress,
                    endpointPort = managed.peer.endpointPort,
                    allowedIps = managed.peer.allowedIps,
                    sessionIndex = managed.session.sessionIndex,
                    isActive = managed.session.isActive,
                )
            }
    }

    override fun managedSessions(): List<ManagedSession> {
        return sessionsByPeer.values
            .sortedBy { managed -> managed.session.sessionIndex.toInt() }
            .map { managed -> managed.copy() }
    }

    override fun session(peerKey: String): SessionSnapshot? {
        val managed = sessionsByPeer[peerKey] ?: return null
        return SessionSnapshot(
            peerPublicKey = managed.peer.publicKey,
            endpointAddress = managed.peer.endpointAddress,
            endpointPort = managed.peer.endpointPort,
            allowedIps = managed.peer.allowedIps,
            sessionIndex = managed.session.sessionIndex,
            isActive = managed.session.isActive,
        )
    }

    override fun closeSession(peerKey: String) {
        val managed = sessionsByPeer.remove(peerKey) ?: return
        safeClose(managed)
        if (sessionsByPeer.isEmpty()) {
            safeStopRuntime()
        }
    }

    override fun closeAll() {
        var runtimeFailure: Throwable? = null
        try {
            stopRuntime()
        } catch (throwable: Throwable) {
            runtimeFailure = throwable
        }

        closeSessionsOnly()
        runtimeFailure?.let { throwable -> throw throwable }
    }

    override fun startRuntime(
        configuration: VpnConfiguration,
        interfaceManager: InterfaceManager,
        onFailure: (Throwable) -> Unit,
    ) {
        if (sessionsByPeer.isEmpty()) {
            safeStopRuntime()
            return
        }

        val desiredKey = RuntimeKey(
            interfaceName = configuration.interfaceName,
            listenPort = configuration.adapter.listenPort ?: Vpn.DEFAULT_PORT,
        )
        val currentRuntime = runtimeHandle
        if (currentRuntime != null && runtimeKey == desiredKey && currentRuntime.isRunning()) {
            return
        }

        safeStopRuntime()
        runtimeTunPort = interfaceManager.tunPort()
        peerStatsByPublicKey.clear()

        val createdRuntime = userspaceRuntimeFactory(
            configuration,
            desiredKey.listenPort,
            ::pollRuntimeOnce,
            ::currentPeerStats,
        ) { throwable ->
            clearRuntimeState()
            closeSessionsOnly()
            onFailure(throwable)
        } ?: return

        runtimeHandle = createdRuntime
        runtimeKey = desiredKey
    }

    override fun peerStats(): List<VpnPeerStats> {
        return runtimeHandle?.peerStats() ?: currentPeerStats()
    }

    private fun currentPeerStats(): List<VpnPeerStats> {
        return sessionsByPeer.values
            .sortedBy { managed -> managed.session.sessionIndex.toInt() }
            .map { managed ->
                val stats = peerStatsByPublicKey[managed.peer.publicKey] ?: MutablePeerStats()
                VpnPeerStats(
                    publicKey = managed.peer.publicKey,
                    receivedBytes = stats.receivedBytes,
                    transmittedBytes = stats.transmittedBytes,
                    lastHandshakeEpochSeconds = null,
                )
            }
    }

    internal suspend fun pollRuntimeOnce(
        udpPort: UdpPort,
        periodicTicker: () -> Boolean,
    ): Boolean {
        val tunPort = runtimeTunPort ?: return false

        var didWork = false
        didWork = processTunInput(tunPort = tunPort, udpPort = udpPort) || didWork
        didWork = processUdpInput(tunPort = tunPort, udpPort = udpPort) || didWork
        if (periodicTicker()) {
            didWork = processPeriodicTasks(udpPort = udpPort) || didWork
        }
        return didWork
    }

    private fun shouldReuse(
        previous: ManagedSession?,
        desiredPeer: VpnPeer,
        desiredIndex: UInt,
    ): Boolean {
        return previous != null &&
            previous.peer == desiredPeer &&
            previous.session.sessionIndex == desiredIndex &&
            previous.session.isActive
    }

    private fun createManagedSession(
        config: VpnAdapterConfiguration,
        peerPublicKey: String,
        desiredPeer: VpnPeer,
        desiredIndex: UInt,
    ): ManagedSession {
        val createdSession = sessionFactory.create(
            config = config,
            peer = desiredPeer,
            sessionIndex = desiredIndex,
        )

        require(createdSession.peerPublicKey == peerPublicKey) {
            "Session factory returned mismatched peer key `${createdSession.peerPublicKey}` for `$peerPublicKey`"
        }
        require(createdSession.sessionIndex == desiredIndex) {
            "Session factory returned mismatched index `${createdSession.sessionIndex}` for `$desiredIndex`"
        }
        require(createdSession.isActive) {
            "Session factory must return active sessions"
        }

        return ManagedSession(
            peer = desiredPeer,
            session = createdSession,
        )
    }

    private fun safeClose(managed: ManagedSession) {
        try {
            managed.session.close()
        } catch (_: Throwable) {
            // best-effort close for rollback and stale session cleanup
        }
    }

    private suspend fun processTunInput(
        tunPort: TunPort,
        udpPort: UdpPort,
    ): Boolean {
        val packet = tunPort.readPacket() ?: return false
        val selected = selectSessionForPacket(
            packet = packet,
            sessions = managedSessions(),
        ) ?: return true

        applyPacketResult(
            tunPort = tunPort,
            udpPort = udpPort,
            result = selected.session.encryptRawPacket(packet, VpnPacketLoop.DEFAULT_PACKET_BUFFER_SIZE),
            managed = selected,
            endpoint = selected.peerEndpoint(),
            operation = "encryptRawPacket",
        )
        return true
    }

    private suspend fun processUdpInput(
        tunPort: TunPort,
        udpPort: UdpPort,
    ): Boolean {
        val datagram = udpPort.receiveDatagram() ?: return false
        val selected = managedSessions().firstOrNull { managed ->
            managed.peer.endpointAddress == datagram.endpoint.host &&
                managed.peer.endpointPort == datagram.endpoint.port
        } ?: return true

        peerStatsByPublicKey.getOrPut(selected.peer.publicKey) { MutablePeerStats() }.receivedBytes += datagram.packet.size.toLong()

        var result = selected.session.decryptToRawPacket(
            datagram.packet,
            VpnPacketLoop.DEFAULT_PACKET_BUFFER_SIZE,
        )
        var iterations = 0

        while (result !is VpnPacketResult.Done) {
            applyPacketResult(
                tunPort = tunPort,
                udpPort = udpPort,
                result = result,
                managed = selected,
                endpoint = selected.peerEndpoint(),
                operation = "decryptToRawPacket",
            )

            iterations += 1
            if (iterations >= VpnPacketLoop.DEFAULT_MAX_FLUSH_ITERATIONS) {
                throw IllegalStateException(
                    "Session `${selected.session.peerPublicKey}` exceeded flush limit while decrypting packets",
                )
            }

            result = selected.session.decryptToRawPacket(
                ByteArray(0),
                VpnPacketLoop.DEFAULT_PACKET_BUFFER_SIZE,
            )
        }

        return true
    }

    private suspend fun processPeriodicTasks(
        udpPort: UdpPort,
    ): Boolean {
        val tunPort = runtimeTunPort ?: return false
        var didWork = false
        managedSessions().forEach { managed ->
            applyPacketResult(
                tunPort = tunPort,
                udpPort = udpPort,
                result = managed.session.runPeriodicTask(VpnPacketLoop.DEFAULT_PACKET_BUFFER_SIZE),
                managed = managed,
                endpoint = managed.peerEndpoint(),
                operation = "runPeriodicTask",
            )
            didWork = true
        }
        return didWork
    }

    private suspend fun applyPacketResult(
        tunPort: TunPort,
        udpPort: UdpPort,
        result: VpnPacketResult,
        managed: ManagedSession,
        endpoint: UdpEndpoint,
        operation: String,
    ) {
        when (result) {
            VpnPacketResult.Done -> Unit
            is VpnPacketResult.WriteToNetwork -> {
                udpPort.sendDatagram(UdpDatagram(packet = result.packet, endpoint = endpoint))
                peerStatsByPublicKey.getOrPut(managed.peer.publicKey) { MutablePeerStats() }.transmittedBytes += result.packet.size.toLong()
            }

            is VpnPacketResult.WriteToTunnelIpv4 -> tunPort.writePacket(result.packet)
            is VpnPacketResult.WriteToTunnelIpv6 -> tunPort.writePacket(result.packet)
            is VpnPacketResult.Error -> {
                throw IllegalStateException(
                    "Session `${managed.session.peerPublicKey}` returned error code `${result.code}` for `$operation`",
                )
            }

            is VpnPacketResult.NotSupported -> {
                throw IllegalStateException(
                    "Session `${managed.session.peerPublicKey}` does not support `${result.operation}`",
                )
            }
        }
    }

    private fun selectSessionForPacket(
        packet: ByteArray,
        sessions: List<ManagedSession>,
    ): ManagedSession? {
        val destination = parsePacketDestination(packet) ?: return null
        return sessions
            .mapNotNull { managed ->
                val match = managed.peer.allowedIps
                    .mapNotNull { allowedIp -> parseCidr(allowedIp) }
                    .filter { route -> route.matches(destination) }
                    .maxByOrNull { route -> route.prefixLength }
                    ?: return@mapNotNull null
                managed to match
            }
            .maxByOrNull { (_, route) -> route.prefixLength }
            ?.first
    }

    private fun ManagedSession.peerEndpoint(): UdpEndpoint {
        return UdpEndpoint(
            host = checkNotNull(peer.endpointAddress) { "Peer `${peer.publicKey}` is missing endpointAddress" },
            port = checkNotNull(peer.endpointPort) { "Peer `${peer.publicKey}` is missing endpointPort" },
        )
    }

    private fun stopRuntime() {
        val currentRuntime = runtimeHandle
        try {
            currentRuntime?.close()
        } finally {
            clearRuntimeState()
        }
    }

    private fun safeStopRuntime() {
        try {
            stopRuntime()
        } catch (_: Throwable) {
            clearRuntimeState()
        }
    }

    private fun closeSessionsOnly() {
        sessionsByPeer.values.forEach { managed -> safeClose(managed) }
        sessionsByPeer.clear()
        peerStatsByPublicKey.clear()
    }

    private fun clearRuntimeState() {
        runtimeHandle = null
        runtimeKey = null
        runtimeTunPort = null
        peerStatsByPublicKey.clear()
    }

    private data class RuntimeKey(
        val interfaceName: String,
        val listenPort: Int,
    )

    private class MutablePeerStats {
        var receivedBytes: Long = 0L
        var transmittedBytes: Long = 0L
    }

    private companion object {
        fun defaultFactory(engine: Engine): VpnSessionFactory {
            return when (engine) {
                Engine.BORINGTUN -> BoringTunVpnSessionFactory()
                Engine.QUIC -> QuicVpnSessionFactory()
            }
        }
    }
}
