package com.rafambn.wgkotlin.crypto

import com.rafambn.wgkotlin.ParsedVpnConfiguration
import com.rafambn.wgkotlin.ParsedVpnPeer
import com.rafambn.wgkotlin.crypto.factory.BoringTunPeerSessionFactory
import com.rafambn.wgkotlin.network.io.UdpDatagram
import com.rafambn.wgkotlin.network.io.UdpEndpoint
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import com.rafambn.wgkotlin.util.resolveToIp
import com.rafambn.wgkotlin.util.toCidr
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import uniffi.wg_kotlin_uniffi_boringtun.convertX25519KeyToBase64
import uniffi.wg_kotlin_uniffi_boringtun.generatePublicKey
import uniffi.wg_kotlin_uniffi_boringtun.generateSecretKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InvalidDatagramRecoveryTest {
    @Test
    fun invalidAndReplayedDatagramsDoNotStopTheVpnOrPreventAHandshake() = runBlocking {
        fun base64(bytes: ByteArray) = checkNotNull(convertX25519KeyToBase64(bytes))
        val clientKey = generateSecretKey()
        val serverKey = generateSecretKey()
        val factory = BoringTunPeerSessionFactory()
        val client = factory.create(
            ParsedVpnConfiguration(interfaceName = "utun1", privateKey = base64(clientKey)),
            ParsedVpnPeer(publicKey = base64(checkNotNull(generatePublicKey(serverKey)))), 1,
        )
        val (tun, cryptoTun) = DuplexChannelPipe.create<ByteArray>()
        val (network, cryptoNetwork) = DuplexChannelPipe.create<UdpDatagram>()
        val manager = CryptoSessionManagerImpl(cryptoTun, cryptoNetwork)
        val failure = CompletableDeferred<Throwable>()
        val endpoint = UdpEndpoint("192.0.2.1", 51820)
        manager.reconcileSessions(ParsedVpnConfiguration(
            interfaceName = "utun1", privateKey = base64(serverKey),
            peers = listOf(ParsedVpnPeer(
                publicKey = base64(checkNotNull(generatePublicKey(clientKey))),
                endpointAddress = endpoint.address.resolveToIp(), endpointPort = endpoint.port,
                allowedIps = listOf("10.0.0.0/24".toCidr()),
            )),
        ))
        manager.start { failure.complete(it) }
        try {
            val packet = byteArrayOf(
                0x45, 0, 0, 20, 0, 0, 0, 0, 64, 0, 0, 0,
                10, 0, 0, 1, 10, 0, 0, 2,
            )
            network.send(UdpDatagram(ByteArray(32) { 0xff.toByte() }, endpoint))
            val initiation = assertIs<PacketAction.WriteToNetwork>(client.encryptRawPacket(packet, 0u))
            network.send(UdpDatagram(initiation.packet, endpoint))
            val response = withTimeout(2_000) { network.receive() }
            val keepalive = assertIs<PacketAction.WriteToNetwork>(client.decryptToRawPacket(response.payload, 0u, "192.0.2.2"))
            network.send(UdpDatagram(keepalive.packet, endpoint))
            val queued = assertIs<PacketAction.WriteToNetwork>(client.decryptToRawPacket(ByteArray(0), 0u, "192.0.2.2"))
            network.send(UdpDatagram(queued.packet, endpoint))
            assertContentEquals(packet, withTimeout(2_000) { tun.receive() })

            // Replays are packet errors too. A later valid transport must still work.
            network.send(UdpDatagram(queued.packet, endpoint))
            val nextPacket = packet.copyOf().also { it[19] = 3 }
            val next = assertIs<PacketAction.WriteToNetwork>(client.encryptRawPacket(nextPacket, 0u))
            network.send(UdpDatagram(next.packet, endpoint))
            assertContentEquals(nextPacket, withTimeout(2_000) { tun.receive() })
            assertTrue(manager.hasActiveSessions())
            assertFalse(failure.isCompleted)
        } finally {
            manager.stop()
            client.close()
        }
    }
}
