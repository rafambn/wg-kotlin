package com.rafambn.kmpvpn.session

import com.rafambn.kmpvpn.Engine
import com.rafambn.kmpvpn.VpnAdapterConfiguration
import com.rafambn.kmpvpn.VpnPeer
import com.rafambn.kmpvpn.requireUniquePeerPublicKeys
import com.rafambn.kmpvpn.session.factory.BoringTunVpnSessionFactory
import com.rafambn.kmpvpn.session.factory.QuicVpnSessionFactory
import com.rafambn.kmpvpn.session.factory.VpnSessionFactory

internal class InMemorySessionManager(
    engine: Engine = Engine.BORINGTUN,
    private val sessionFactory: VpnSessionFactory = defaultFactory(engine),
) : SessionManager {
    private val sessionsByPeer: LinkedHashMap<String, ManagedSession> = linkedMapOf()

    override fun reconcileSessions(config: VpnAdapterConfiguration) {
        requireUniquePeerPublicKeys(config.peers)

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
    }

    override fun closeAll() {
        sessionsByPeer.values.forEach { managed -> safeClose(managed) }
        sessionsByPeer.clear()
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

    private companion object {
        fun defaultFactory(engine: Engine): VpnSessionFactory {
            return when (engine) {
                Engine.BORINGTUN -> BoringTunVpnSessionFactory()
                Engine.QUIC -> QuicVpnSessionFactory()
            }
        }
    }
}
