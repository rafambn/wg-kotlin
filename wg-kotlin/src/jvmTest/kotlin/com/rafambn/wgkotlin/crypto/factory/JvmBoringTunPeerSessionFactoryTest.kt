package com.rafambn.wgkotlin.crypto.factory

import com.rafambn.wgkotlin.VpnConfiguration
import com.rafambn.wgkotlin.VpnPeer
import com.rafambn.wgkotlin.crypto.PacketAction
import uniffi.wg_kotlin.convertX25519KeyToBase64
import uniffi.wg_kotlin.generatePublicKey
import uniffi.wg_kotlin.generateSecretKey
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JvmBoringTunPeerSessionFactoryTest {

    @Test
    fun quicStrategyIsExplicitlyUnsupported() {
        val factory = QuicPeerSessionFactory()
        val config = VpnConfiguration(
            interfaceName = "wg-test",
            privateKey = "private-key",
            peers = listOf(VpnPeer(publicKey = "peer-key")),
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

        val config = VpnConfiguration(
            interfaceName = "wg-test",
            privateKey = localPrivateKey,
            peers = listOf(VpnPeer(publicKey = remotePublicKey)),
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

    private fun toBase64(bytes: ByteArray): String {
        return assertNotNull(convertX25519KeyToBase64(bytes))
    }
}
