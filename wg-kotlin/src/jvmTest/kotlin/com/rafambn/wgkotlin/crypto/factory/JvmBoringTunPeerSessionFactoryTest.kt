package com.rafambn.wgkotlin.crypto.factory

import com.rafambn.wgkotlin.ParsedVpnConfiguration
import com.rafambn.wgkotlin.ParsedVpnPeer
import com.rafambn.wgkotlin.crypto.PacketAction
import uniffi.wg_kotlin_uniffi_boringtun.convertX25519KeyToBase64
import uniffi.wg_kotlin_uniffi_boringtun.generatePublicKey
import uniffi.wg_kotlin_uniffi_boringtun.generateSecretKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JvmBoringTunPeerSessionFactoryTest {

    @Test
    fun quicStrategyIsExplicitlyUnsupported() {
        val factory = QuicPeerSessionFactory()
        val config = ParsedVpnConfiguration(
            interfaceName = "wg-test",
            privateKey = "private-key",
            peers = listOf(ParsedVpnPeer(publicKey = "peer-key")),
        )

        assertFailsWith<UnsupportedOperationException> {
            factory.create(
                config = config,
                peer = config.peers.first(),
                peerIndex = 1,
            )
        }
    }

    @Test
    fun boringTunStrategyCreatesAndClosesSession() {
        val localPrivateKey = toBase64(generateSecretKey())
        val remotePublicKey = toBase64(checkNotNull(generatePublicKey(generateSecretKey())))

        val config = ParsedVpnConfiguration(
            interfaceName = "wg-test",
            privateKey = localPrivateKey,
            peers = listOf(ParsedVpnPeer(publicKey = remotePublicKey)),
        )

        val factory = BoringTunPeerSessionFactory()

        val session = factory.create(
            config = config,
            peer = config.peers.first(),
            peerIndex = 1,
        )

        assertTrue(session.isActive)

        val encrypted = session.encryptRawPacket(src = "hello".encodeToByteArray(), dstSize = 512u)
        assertTrue(encrypted !is PacketAction.NotSupported)

        val periodic = session.runPeriodicTask(dstSize = 512u)
        assertTrue(periodic !is PacketAction.NotSupported)

        session.close()
        assertFalse(session.isActive)
    }

    @Test
    fun typedResultsSupportHandshakeIpv4Ipv6AndPacketErrors() {
        val initiatorKey = generateSecretKey()
        val responderKey = generateSecretKey()
        val factory = BoringTunPeerSessionFactory()
        val initiator = factory.create(
            config = ParsedVpnConfiguration(
                interfaceName = "wg-test",
                privateKey = toBase64(initiatorKey),
            ),
            peer = ParsedVpnPeer(publicKey = toBase64(checkNotNull(generatePublicKey(responderKey)))),
            peerIndex = 1,
        )
        val responder = factory.create(
            config = ParsedVpnConfiguration(
                interfaceName = "wg-test",
                privateKey = toBase64(responderKey),
            ),
            peer = ParsedVpnPeer(publicKey = toBase64(checkNotNull(generatePublicKey(initiatorKey)))),
            peerIndex = 2,
        )

        try {
            assertIs<PacketAction.Error>(
                responder.decryptToRawPacket(ByteArray(32) { 0xff.toByte() }, 0u, "192.0.2.1"),
            )

            val ipv4 = byteArrayOf(
                0x45, 0, 0, 20, 0, 0, 0, 0, 64, 0, 0, 0,
                10, 0, 0, 1, 10, 0, 0, 2,
            )
            val initiation = assertIs<PacketAction.WriteToNetwork>(initiator.encryptRawPacket(ipv4, 0u))
            val response = assertIs<PacketAction.WriteToNetwork>(
                responder.decryptToRawPacket(initiation.packet, 0u, "192.0.2.1"),
            )
            val keepalive = assertIs<PacketAction.WriteToNetwork>(
                initiator.decryptToRawPacket(response.packet, 0u, "192.0.2.2"),
            )
            assertIs<PacketAction.Done>(
                responder.decryptToRawPacket(keepalive.packet, 0u, "192.0.2.1"),
            )

            val queued = assertIs<PacketAction.WriteToNetwork>(
                initiator.decryptToRawPacket(ByteArray(0), 0u, "192.0.2.2"),
            )
            val decryptedIpv4 = assertIs<PacketAction.WriteToTunIpv4>(
                responder.decryptToRawPacket(queued.packet, 0u, "192.0.2.1"),
            )
            assertContentEquals(ipv4, decryptedIpv4.packet)

            val ipv6 = ByteArray(40)
            ipv6[0] = 0x60
            ipv6[6] = 59
            ipv6[7] = 64
            ipv6[8] = 0xfd.toByte()
            ipv6[23] = 2
            ipv6[24] = 0xfd.toByte()
            ipv6[39] = 1
            val encryptedIpv6 = assertIs<PacketAction.WriteToNetwork>(responder.encryptRawPacket(ipv6, 0u))
            val decryptedIpv6 = assertIs<PacketAction.WriteToTunIpv6>(
                initiator.decryptToRawPacket(encryptedIpv6.packet, 0u, "192.0.2.2"),
            )
            assertContentEquals(ipv6, decryptedIpv6.packet)
        } finally {
            responder.close()
            initiator.close()
        }
    }

    private fun toBase64(bytes: ByteArray): String {
        return assertNotNull(convertX25519KeyToBase64(bytes))
    }
}
