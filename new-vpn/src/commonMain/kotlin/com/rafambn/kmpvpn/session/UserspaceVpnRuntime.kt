package com.rafambn.kmpvpn.session

import com.rafambn.kmpvpn.matches
import com.rafambn.kmpvpn.parseCidr
import com.rafambn.kmpvpn.parsePacketDestination
import com.rafambn.kmpvpn.session.io.TunPort
import com.rafambn.kmpvpn.session.io.UdpDatagram
import com.rafambn.kmpvpn.session.io.UdpEndpoint
import com.rafambn.kmpvpn.session.io.UdpPort
import com.rafambn.kmpvpn.session.io.VpnPacketLoop
import com.rafambn.kmpvpn.session.io.VpnPacketResult
import com.rafambn.kmpvpn.iface.VpnPeerStats

class UserspaceVpnRuntime(
    private val sessionManager: SessionManager,
    private val tunPort: TunPort,
    private val udpPort: UdpPort,
    private val periodicTicker: () -> Boolean = { false },
    private val packetBufferSize: UInt = VpnPacketLoop.DEFAULT_PACKET_BUFFER_SIZE,
    private val maxFlushIterations: Int = VpnPacketLoop.DEFAULT_MAX_FLUSH_ITERATIONS,
) {
    private val peerStatsByPublicKey: MutableMap<String, MutablePeerStats> = linkedMapOf()

    init {
        require(maxFlushIterations > 0) {
            "maxFlushIterations must be greater than zero"
        }
    }

    suspend fun pollOnce() {
        processTunInput()
        processUdpInput()
        if (periodicTicker()) {
            processPeriodicTasks()
        }
    }

    fun peerStats(): List<VpnPeerStats> {
        return sessionManager.managedSessions().map { managed ->
            val stats = peerStatsByPublicKey[managed.peer.publicKey] ?: MutablePeerStats()
            VpnPeerStats(
                publicKey = managed.peer.publicKey,
                receivedBytes = stats.receivedBytes,
                transmittedBytes = stats.transmittedBytes,
                lastHandshakeEpochSeconds = null,
            )
        }
    }

    private suspend fun processTunInput() {
        val packet = tunPort.readPacket() ?: return
        val sessions = sessionManager.managedSessions()
        val selected = selectSessionForPacket(packet = packet, sessions = sessions) ?: return
        val endpoint = selected.peerEndpoint()
        val result = selected.session.encryptRawPacket(packet, packetBufferSize)

        applyPacketResult(
            result = result,
            managed = selected,
            endpoint = endpoint,
            operation = "encryptRawPacket",
        )
    }

    private suspend fun processUdpInput() {
        val datagram = udpPort.receiveDatagram() ?: return
        val sessions = sessionManager.managedSessions()
        val selected = sessions.firstOrNull { managed ->
            managed.peer.endpointAddress == datagram.endpoint.host &&
                managed.peer.endpointPort == datagram.endpoint.port
        } ?: return
        peerStatsByPublicKey.getOrPut(selected.peer.publicKey) { MutablePeerStats() }.receivedBytes += datagram.packet.size.toLong()

        var result = selected.session.decryptToRawPacket(datagram.packet, packetBufferSize)
        var iterations = 0

        while (result !is VpnPacketResult.Done) {
            applyPacketResult(
                result = result,
                managed = selected,
                endpoint = selected.peerEndpoint(),
                operation = "decryptToRawPacket",
            )

            iterations += 1
            if (iterations >= maxFlushIterations) {
                throw IllegalStateException(
                    "Session `${selected.session.peerPublicKey}` exceeded flush limit while decrypting packets",
                )
            }

            result = selected.session.decryptToRawPacket(ByteArray(0), packetBufferSize)
        }
    }

    private suspend fun processPeriodicTasks() {
        sessionManager.managedSessions().forEach { managed ->
            applyPacketResult(
                result = managed.session.runPeriodicTask(packetBufferSize),
                managed = managed,
                endpoint = managed.peerEndpoint(),
                operation = "runPeriodicTask",
            )
        }
    }

    private suspend fun applyPacketResult(
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

    private class MutablePeerStats {
        var receivedBytes: Long = 0L
        var transmittedBytes: Long = 0L
    }
}
