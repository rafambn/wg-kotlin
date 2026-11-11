package com.rafambn.wgkotlin.crypto

import com.rafambn.wgkotlin.daemon.proto.Ip
import com.rafambn.wgkotlin.network.io.UdpDatagram
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
import kotlin.time.Duration.Companion.milliseconds

/** Owns one TUN's peers and crypto workers. Close waits for workers before releasing peers. */
internal class CryptoSession(
    private var sessionEntries: List<PeerSessionEntry>,
    private val tunPipe: DuplexChannelPipe<ByteArray>,
    private val networkPipe: DuplexChannelPipe<UdpDatagram>,
    onFailure: (Throwable) -> Unit,
) {
    private val sessionMutex = Mutex()
    private val coroutineLabel = "kmpvpn-crypto"
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName(coroutineLabel),
    )

    init {
        scope.launch(CoroutineName("$coroutineLabel-cleartext-ingress")) {
            runCleartextIngressLoop(onFailure)
        }
        scope.launch(CoroutineName("$coroutineLabel-encrypted-ingress")) {
            runEncryptedIngressLoop(onFailure)
        }
        scope.launch(CoroutineName("$coroutineLabel-periodic")) {
            runPeriodicLoop(onFailure)
        }
    }

    suspend fun close() {
        scope.coroutineContext.job.cancelAndJoin()
        var firstError: Throwable? = null
        sessionEntries.forEach { entry ->
            try {
                entry.session.close()
            } catch (error: Throwable) {
                if (firstError == null) firstError = error else firstError.addSuppressed(error)
            }
        }
        sessionEntries = emptyList()
        firstError?.let { throw it }
    }

    fun peerStats(): List<VpnPeerStats> {
        return sessionEntries.map { entry ->
            VpnPeerStats(
                publicKey = entry.peer.publicKey,
                endpointAddress = entry.peer.endpointAddress?.toPlainString(),
                endpointPort = entry.peer.endpointPort,
                allowedIps = entry.peer.allowedIps.map { it.toCidrString() },
                receivedBytes = entry.stats.receivedBytes,
                transmittedBytes = entry.stats.transmittedBytes,
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
                            selected.stats.transmittedBytes += result.packet.size.toLong()
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

                    selected.stats.receivedBytes += datagram.payload.size.toLong()

                    var result = selected.session.decryptToRawPacket(
                        src = datagram.payload,
                        dstSize = DEFAULT_PACKET_BUFFER_SIZE,
                        sourceIp = datagram.remoteEndpoint.address,
                    )
                    var iterations = 0

                    while (result !is PacketAction.Done) {
                        when (result) {
                            is PacketAction.WriteToNetwork -> {
                                selected.stats.transmittedBytes += result.packet.size.toLong()
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
                delay(DEFAULT_PERIODIC_INTERVAL_MILLIS.milliseconds)

                val pendingNetwork = mutableListOf<UdpDatagram>()

                sessionMutex.withLock {
                    sessionEntries.forEach { entry ->
                        when (val result = entry.session.runPeriodicTask(DEFAULT_PACKET_BUFFER_SIZE)) {
                            is PacketAction.WriteToNetwork -> {
                                entry.stats.transmittedBytes += result.packet.size.toLong()
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
    }
}
