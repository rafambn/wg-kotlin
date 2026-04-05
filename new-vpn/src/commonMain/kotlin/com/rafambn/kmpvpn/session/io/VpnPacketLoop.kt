package com.rafambn.kmpvpn.session.io

import com.rafambn.kmpvpn.session.VpnSession

class VpnPacketLoop(
    private val session: VpnSession,
    private val tunPort: TunPort,
    private val udpPort: UdpPort,
    private val peerEndpoint: UdpEndpoint,
    private val periodicTicker: () -> Boolean = { false },
    private val packetBufferSize: UInt = DEFAULT_PACKET_BUFFER_SIZE,
    private val maxFlushIterations: Int = DEFAULT_MAX_FLUSH_ITERATIONS,
) {
    init {
        require(maxFlushIterations > 0) {
            "maxFlushIterations must be greater than zero"
        }
    }

    suspend fun pollOnce() {
        processTunInput()
        processUdpInput()
        if (periodicTicker()) {
            processPeriodicTask()
        }
    }

    private suspend fun processTunInput() {
        val packet = tunPort.readPacket() ?: return
        applyPacketResult(
            result = session.encryptRawPacket(packet, packetBufferSize),
            operation = "encryptRawPacket",
        )
    }

    private suspend fun processUdpInput() {
        val datagram = udpPort.receiveDatagram() ?: return
        if (datagram.endpoint != peerEndpoint) {
            return
        }

        var result = session.decryptToRawPacket(datagram.packet, packetBufferSize)
        var iterations = 0

        while (result !is VpnPacketResult.Done) {
            applyPacketResult(
                result = result,
                operation = "decryptToRawPacket",
            )

            iterations += 1
            if (iterations >= maxFlushIterations) {
                throw IllegalStateException(
                    "Session `${session.peerPublicKey}` exceeded flush limit while decrypting packets",
                )
            }

            result = session.decryptToRawPacket(EMPTY_PACKET, packetBufferSize)
        }
    }

    private suspend fun processPeriodicTask() {
        applyPacketResult(
            result = session.runPeriodicTask(packetBufferSize),
            operation = "runPeriodicTask",
        )
    }

    private suspend fun applyPacketResult(result: VpnPacketResult, operation: String) {
        when (result) {
            VpnPacketResult.Done -> Unit
            is VpnPacketResult.WriteToNetwork -> udpPort.sendDatagram(
                UdpDatagram(
                    packet = result.packet,
                    endpoint = peerEndpoint,
                ),
            )
            is VpnPacketResult.WriteToTunnelIpv4 -> tunPort.writePacket(result.packet)
            is VpnPacketResult.WriteToTunnelIpv6 -> tunPort.writePacket(result.packet)
            is VpnPacketResult.Error -> {
                throw IllegalStateException(
                    "Session `${session.peerPublicKey}` returned error code `${result.code}` for `$operation`",
                )
            }
            is VpnPacketResult.NotSupported -> {
                throw IllegalStateException(
                    "Session `${session.peerPublicKey}` does not support `${result.operation}`",
                )
            }
        }
    }

    companion object {
        private val EMPTY_PACKET: ByteArray = byteArrayOf()
        val DEFAULT_PACKET_BUFFER_SIZE: UInt = 65535u
        const val DEFAULT_MAX_FLUSH_ITERATIONS: Int = 16
    }
}
