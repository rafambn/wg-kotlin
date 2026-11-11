package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.iface.PlatformInterfaceManager
import com.rafambn.wgkotlin.iface.VpnInterfaceInformation
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class Vpn(
    private val engine: Engine = Engine.BORINGTUN,
) {

    companion object {
        const val DEFAULT_PORT: Int = 51820
    }

    private val platformInterfaceManager = PlatformInterfaceManager
    private val lifecycleMutex = Mutex()
    @Volatile private var sessions: Map<String, VpnSession> = emptyMap()

    suspend fun start(configuration: VpnConfiguration): Result<Unit> = lifecycleMutex.withLock {
        val tunName = configuration.interfaceName
        if (tunName in sessions) {
            return@withLock Result.failure(IllegalStateException("TUN `$tunName` must be stopped before starting"))
        }
        var session: VpnSession? = null
        try {
            currentCoroutineContext().ensureActive()
            requireNonBlankInterfaceName(tunName)
            requireValidRegex(tunName)
            requireUniquePeerPublicKeys(configuration.peers)

            val created = VpnSession(configuration, engine)
            session = created
            val onFailure: (Throwable) -> Unit = { created.failureScope.launch { stop(tunName) } }

            created.cryptoSessionManager.createSessions(configuration)
            created.cryptoSessionManager.start(onFailure)
            created.socketManager.start(configuration.listenPort ?: DEFAULT_PORT, onFailure)
            created.interfaceSession = platformInterfaceManager.create(
                configuration.toTunSessionConfig(), created.tunPipe, onFailure,
            )
            currentCoroutineContext().ensureActive()
            sessions = sessions + (tunName to created)
            Result.success(Unit)
        } catch (error: Throwable) {
            if (session != null) stopComponents(session).exceptionOrNull()?.let(error::addSuppressed)
            if (error is CancellationException) throw error
            Result.failure(error)
        }
    }

    fun information(tunName: String): VpnInterfaceInformation? {
        val session = sessions[tunName] ?: return null
        val liveInformation = session.interfaceSession?.information() ?: return null

        val runtimePeerStats = session.cryptoSessionManager.peerStats()
        val informationWithPeerStats = if (runtimePeerStats.isEmpty()) {
            liveInformation
        } else {
            liveInformation.copy(peerStats = runtimePeerStats)
        }

        return informationWithPeerStats.copy(vpnConfiguration = session.configuration)
    }

    suspend fun stop(tunName: String): Result<Unit> = lifecycleMutex.withLock {
        val session = sessions[tunName] ?: return@withLock Result.success(Unit)
        val result = stopComponents(session)
        sessions = sessions - tunName
        result
    }

    private suspend fun stopComponents(session: VpnSession): Result<Unit> = withContext(NonCancellable) {
        session.failureScope.cancel()
        val interfaceStop = runCatching<Unit> { session.interfaceSession?.close() }
        val socketStop = runCatching { session.socketManager.stop() }
        val cryptoStop = runCatching { session.cryptoSessionManager.stop() }
        when {
            interfaceStop.isFailure -> interfaceStop
            socketStop.isFailure -> socketStop
            else -> cryptoStop
        }
    }
}
