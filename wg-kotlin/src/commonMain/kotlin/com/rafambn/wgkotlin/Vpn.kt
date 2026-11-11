package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.crypto.CryptoManager
import com.rafambn.wgkotlin.iface.PlatformInterfaceManager
import com.rafambn.wgkotlin.iface.VpnInterfaceInformation
import com.rafambn.wgkotlin.network.SocketManager
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Vpn(
    engine: Engine = Engine.BORINGTUN,
) {

    companion object {
        const val DEFAULT_PORT: Int = 51820
    }

    private val platformInterfaceManager = PlatformInterfaceManager
    private val socketManager = SocketManager
    private val cryptoManager = CryptoManager(engine)
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
            requireValidInterfaceName(tunName)
            requireUniquePeerPublicKeys(configuration.peers)
            requireUserspacePeerEndpoints(configuration.peers)
            requireDistinctAllowedIpOwnership(configuration.peers)

            val created = VpnSession(configuration)
            session = created
            val onFailure: (Throwable) -> Unit = { created.failureScope.launch { stop(tunName) } }

            created.cryptoSession = cryptoManager.create(
                configuration, created.cryptoTunPipe, created.cryptoNetworkPipe, onFailure,
            )
            created.socketSession = socketManager.create(
                configuration.listenPort ?: DEFAULT_PORT, created.networkPipe, onFailure,
            )
            created.interfaceSession = platformInterfaceManager.create(
                configuration.toTunSessionConfig(), created.tunPipe, onFailure,
            )
            currentCoroutineContext().ensureActive()
            sessions = sessions + (tunName to created)
            Result.success(Unit)
        } catch (error: Throwable) {
            session?.close()?.exceptionOrNull()?.let(error::addSuppressed)
            if (error is CancellationException) throw error
            Result.failure(error)
        }
    }

    fun information(tunName: String): VpnInterfaceInformation? {
        val session = sessions[tunName] ?: return null
        val liveInformation = session.interfaceSession?.information() ?: return null

        return liveInformation.copy(
            peerStats = session.cryptoSession?.peerStats().orEmpty(),
            vpnConfiguration = session.configuration,
        )
    }

    suspend fun stop(tunName: String): Result<Unit> = lifecycleMutex.withLock {
        val session = sessions[tunName] ?: return@withLock Result.success(Unit)
        val result = session.close()
        sessions = sessions - tunName
        result
    }
}
