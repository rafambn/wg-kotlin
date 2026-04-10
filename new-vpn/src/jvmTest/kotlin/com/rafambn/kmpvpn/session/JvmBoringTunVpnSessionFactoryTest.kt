package com.rafambn.kmpvpn.session

import com.rafambn.kmpvpn.DefaultVpnConfiguration
import com.rafambn.kmpvpn.VpnPeer
import com.rafambn.kmpvpn.session.factory.BoringTunVpnSessionFactory
import com.rafambn.kmpvpn.session.factory.QuicVpnSessionFactory
import com.rafambn.kmpvpn.session.io.VpnPacketResult
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import uniffi.new_vpn.convertX25519KeyToBase64
import uniffi.new_vpn.generatePublicKey
import uniffi.new_vpn.generateSecretKey

class JvmBoringTunVpnSessionFactoryTest {

    @Test
    fun quicStrategyIsExplicitlyUnsupported() {
        val factory = QuicVpnSessionFactory()
        val config = DefaultVpnConfiguration(
            interfaceName = "wg-test",
            privateKey = "private-key",
            peers = listOf(VpnPeer(publicKey = "peer-key")),
        )

        assertFailsWith<UnsupportedOperationException> {
            factory.create(
                config = config,
                peer = config.peers.first(),
                sessionIndex = 1u,
            )
        }
    }

    @Test
    fun boringTunStrategyCreatesAndClosesSession() {
        val localPrivateKey = toBase64(generateSecretKey())
        val remotePublicKey = toBase64(checkNotNull(generatePublicKey(generateSecretKey())))

        val config = DefaultVpnConfiguration(
            interfaceName = "wg-test",
            privateKey = localPrivateKey,
            peers = listOf(VpnPeer(publicKey = remotePublicKey)),
        )

        val factory = BoringTunVpnSessionFactory()

        val session = factory.create(
            config = config,
            peer = config.peers.first(),
            sessionIndex = 1u,
        )

        assertTrue(session.isActive)

        val encrypted = session.encryptRawPacket(src = "hello".encodeToByteArray(), dstSize = 512u)
        assertTrue(encrypted !is VpnPacketResult.NotSupported)

        val periodic = session.runPeriodicTask(dstSize = 512u)
        assertTrue(periodic !is VpnPacketResult.NotSupported)

        session.close()
        assertFalse(session.isActive)
    }

    private fun toBase64(bytes: ByteArray): String {
        return assertNotNull(convertX25519KeyToBase64(bytes))
    }
}
