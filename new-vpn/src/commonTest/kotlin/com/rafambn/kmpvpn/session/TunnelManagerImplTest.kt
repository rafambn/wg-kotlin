package com.rafambn.kmpvpn.session

import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.VpnPeer
import com.rafambn.kmpvpn.session.factory.PeerSessionFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TunnelManagerImplTest {

    @Test
    fun reconcileSessionsCreatesActiveSessionsForAllPeers() {
        val manager = TunnelManagerImpl(peerSessionFactory = RecordingSessionFactory())

        manager.reconcileSessions(configurationWithPeers("peer-a", "peer-b"))

        assertTrue(manager.hasActiveSessions())
        assertEquals(2, manager.sessionSnapshots().size)
        assertTrue(manager.sessionSnapshots().all { session -> session.isActive })
    }

    @Test
    fun reconcileSessionsRemovesMissingPeers() {
        val factory = RecordingSessionFactory()
        val manager = TunnelManagerImpl(
            peerSessionFactory = factory,
        )

        manager.reconcileSessions(configurationWithPeers("peer-a", "peer-b"))
        manager.reconcileSessions(configurationWithPeers("peer-b"))

        assertNull(manager.sessionSnapshot("peer-a"))
        assertNotNull(manager.sessionSnapshot("peer-b"))
        assertEquals(1, factory.sessionByPeer("peer-a")?.closeCalls)
    }

    @Test
    fun reconcileSessionsReplacesChangedPeerConfiguration() {
        val factory = RecordingSessionFactory()
        val manager = TunnelManagerImpl(
            peerSessionFactory = factory,
        )

        manager.reconcileSessions(
            configurationWithPeer(
                VpnPeer(
                    publicKey = "peer-a",
                    endpointAddress = "10.0.0.1",
                    endpointPort = 51820,
                ),
            ),
        )

        manager.reconcileSessions(
            configurationWithPeer(
                VpnPeer(
                    publicKey = "peer-a",
                    endpointAddress = "10.0.0.2",
                    endpointPort = 51821,
                ),
            ),
        )

        assertEquals(2, factory.createdSessions.size)
        assertEquals(1, factory.createdSessions.first().closeCalls)
        assertEquals(0, factory.createdSessions.last().closeCalls)

        val session = manager.sessionSnapshot("peer-a")
        assertNotNull(session)
        assertEquals("10.0.0.2", session.endpointAddress)
        assertEquals(51821, session.endpointPort)
    }

    @Test
    fun duplicatePeerKeysAreRejected() {
        val manager = TunnelManagerImpl(peerSessionFactory = RecordingSessionFactory())

        val duplicated = configurationWithPeer(
            VpnPeer(publicKey = "peer-a"),
            VpnPeer(publicKey = "peer-a"),
        )

        assertFailsWith<IllegalArgumentException> {
            manager.reconcileSessions(duplicated)
        }
    }

    @Test
    fun partialCreateFailureRollsBackNewlyCreatedSessions() {
        val factory = RecordingSessionFactory(failOnPeer = "peer-b")
        val manager = TunnelManagerImpl(
            peerSessionFactory = factory,
        )

        val throwable = assertFailsWith<IllegalStateException> {
            manager.reconcileSessions(configurationWithPeers("peer-a", "peer-b"))
        }

        assertTrue(throwable.message?.contains("factory forced failure") == true)
        assertTrue(manager.sessionSnapshots().isEmpty())
        assertEquals(1, factory.createdSessions.size)
        assertEquals(1, factory.createdSessions.first().closeCalls)
    }

    @Test
    fun peerIndexesAreDeterministicAcrossPeerOrderChanges() {
        val manager = TunnelManagerImpl(peerSessionFactory = RecordingSessionFactory())

        manager.reconcileSessions(configurationWithPeers("peer-b", "peer-a"))
        val first = manager.sessionSnapshots().associate { it.peerPublicKey to it.peerIndex }

        manager.reconcileSessions(configurationWithPeers("peer-a", "peer-b"))
        val second = manager.sessionSnapshots().associate { it.peerPublicKey to it.peerIndex }

        assertEquals(first, second)
        assertEquals(1, second["peer-a"])
        assertEquals(2, second["peer-b"])
    }

    private fun configurationWithPeers(vararg peerKeys: String): VpnConfiguration {
        return VpnConfiguration(
            interfaceName = "wg-test",
            privateKey = "private-key",
            peers = peerKeys.mapIndexed { index, key ->
                VpnPeer(
                    publicKey = key,
                    endpointAddress = "198.51.100.${index + 1}",
                    endpointPort = 51820 + index,
                )
            },
        )
    }

    private fun configurationWithPeer(vararg peers: VpnPeer): VpnConfiguration {
        return VpnConfiguration(
            interfaceName = "wg-test",
            privateKey = "private-key",
            peers = peers.toList(),
        )
    }

    private class RecordingSessionFactory(
        private val failOnPeer: String? = null,
    ) : PeerSessionFactory {
        val createdSessions: MutableList<TestPeerSession> = mutableListOf()

        override fun create(
            config: VpnConfiguration,
            peer: VpnPeer,
            peerIndex: Int,
        ): PeerSession {
            if (peer.publicKey == failOnPeer) {
                throw IllegalStateException("factory forced failure for `${peer.publicKey}`")
            }

            val session = TestPeerSession(
                peerPublicKey = peer.publicKey,
                peerIndex = peerIndex,
            )
            createdSessions += session
            return session
        }

        fun sessionByPeer(peerPublicKey: String): TestPeerSession? {
            return createdSessions.find { session -> session.peerPublicKey == peerPublicKey }
        }
    }

    private class TestPeerSession(
        override val peerPublicKey: String,
        override val peerIndex: Int,
    ) : PeerSession {
        var closeCalls: Int = 0

        override val isActive: Boolean
            get() = closeCalls == 0

        override fun close() {
            closeCalls += 1
        }
    }
}
