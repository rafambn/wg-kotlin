package com.rafambn.kmpvpn.session

import com.rafambn.kmpvpn.DefaultVpnConfiguration
import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.VpnPeer
import com.rafambn.kmpvpn.session.factory.VpnSessionFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryTunnelManagerTest {

    @Test
    fun reconcileSessionsCreatesActiveSessionsForAllPeers() {
        val manager = InMemoryTunnelManager(sessionFactory = RecordingSessionFactory())

        manager.reconcileSessions(configurationWithPeers("peer-a", "peer-b"))

        assertEquals(2, manager.sessions().size)
        assertTrue(manager.sessions().all { session -> session.isActive })
    }

    @Test
    fun reconcileSessionsRemovesMissingPeers() {
        val factory = RecordingSessionFactory()
        val manager = InMemoryTunnelManager(
            sessionFactory = factory,
        )

        manager.reconcileSessions(configurationWithPeers("peer-a", "peer-b"))
        manager.reconcileSessions(configurationWithPeers("peer-b"))

        assertNull(manager.session("peer-a"))
        assertNotNull(manager.session("peer-b"))
        assertEquals(1, factory.sessionByPeer("peer-a")?.closeCalls)
    }

    @Test
    fun reconcileSessionsReplacesChangedPeerConfiguration() {
        val factory = RecordingSessionFactory()
        val manager = InMemoryTunnelManager(
            sessionFactory = factory,
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

        val session = manager.session("peer-a")
        assertNotNull(session)
        assertEquals("10.0.0.2", session.endpointAddress)
        assertEquals(51821, session.endpointPort)
    }

    @Test
    fun duplicatePeerKeysAreRejected() {
        val manager = InMemoryTunnelManager(sessionFactory = RecordingSessionFactory())

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
        val manager = InMemoryTunnelManager(
            sessionFactory = factory,
        )

        val throwable = assertFailsWith<IllegalStateException> {
            manager.reconcileSessions(configurationWithPeers("peer-a", "peer-b"))
        }

        assertTrue(throwable.message?.contains("factory forced failure") == true)
        assertTrue(manager.sessions().isEmpty())
        assertEquals(1, factory.createdSessions.size)
        assertEquals(1, factory.createdSessions.first().closeCalls)
    }

    @Test
    fun sessionIndexesAreDeterministicAcrossPeerOrderChanges() {
        val manager = InMemoryTunnelManager(sessionFactory = RecordingSessionFactory())

        manager.reconcileSessions(configurationWithPeers("peer-b", "peer-a"))
        val first = manager.sessions().associate { it.peerPublicKey to it.sessionIndex }

        manager.reconcileSessions(configurationWithPeers("peer-a", "peer-b"))
        val second = manager.sessions().associate { it.peerPublicKey to it.sessionIndex }

        assertEquals(first, second)
        assertEquals(1u, second["peer-a"])
        assertEquals(2u, second["peer-b"])
    }

    private fun configurationWithPeers(vararg peerKeys: String): DefaultVpnConfiguration {
        return DefaultVpnConfiguration(
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

    private fun configurationWithPeer(vararg peers: VpnPeer): DefaultVpnConfiguration {
        return DefaultVpnConfiguration(
            interfaceName = "wg-test",
            privateKey = "private-key",
            peers = peers.toList(),
        )
    }

    private class RecordingSessionFactory(
        private val failOnPeer: String? = null,
    ) : VpnSessionFactory {
        val createdSessions: MutableList<TestVpnSession> = mutableListOf()

        override fun create(
            config: VpnConfiguration,
            peer: VpnPeer,
            sessionIndex: UInt,
        ): VpnSession {
            if (peer.publicKey == failOnPeer) {
                throw IllegalStateException("factory forced failure for `${peer.publicKey}`")
            }

            val session = TestVpnSession(
                peerPublicKey = peer.publicKey,
                sessionIndex = sessionIndex,
            )
            createdSessions += session
            return session
        }

        fun sessionByPeer(peerPublicKey: String): TestVpnSession? {
            return createdSessions.find { session -> session.peerPublicKey == peerPublicKey }
        }
    }

    private class TestVpnSession(
        override val peerPublicKey: String,
        override val sessionIndex: UInt,
    ) : VpnSession {
        var closeCalls: Int = 0

        override val isActive: Boolean
            get() = closeCalls == 0

        override fun close() {
            closeCalls += 1
        }
    }
}
