package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.DnsConfig
import com.rafambn.wgkotlin.daemon.proto.TunSessionConfig
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import com.rafambn.wgkotlin.util.toCidrString
import com.rafambn.wgkotlin.util.toPlainString

class JvmInterfaceManager(
    private val sessionBridge: SessionBridge,
    private val tunPipe: DuplexChannelPipe<ByteArray>,
) : InterfaceManager {
    private val stateLock = Any()
    private var currentConfig: TunSessionConfig? = null
    private var activeBridge: AutoCloseable? = null

    override fun isRunning(): Boolean = synchronized(stateLock) { activeBridge != null }

    override suspend fun start(config: TunSessionConfig, onFailure: (Throwable) -> Unit) {
        stop()

        val startupLock = Any()
        var startupFinished = false
        var pendingFailure: Throwable? = null
        var openedBridge: AutoCloseable? = null
        val bridge = sessionBridge.openSession(
            config = config,
            pipe = tunPipe,
            onFailure = { throwable ->
                val handleRuntimeFailure = synchronized(startupLock) {
                    if (startupFinished) {
                        true
                    } else {
                        if (pendingFailure == null) pendingFailure = throwable
                        false
                    }
                }
                if (handleRuntimeFailure) {
                    handleFailure(openedBridge, throwable, onFailure)
                }
            },
        )
        openedBridge = bridge

        val startupFailure = synchronized(startupLock) {
            val failure = pendingFailure
            if (failure == null) {
                synchronized(stateLock) {
                    activeBridge = bridge
                    currentConfig = config
                }
            }
            startupFinished = true
            failure
        }

        if (startupFailure != null) {
            runCatching { bridge.close() }
            throw startupFailure
        }
    }

    override fun stop() {
        val bridge = synchronized(stateLock) {
            val currentBridge = activeBridge
            activeBridge = null
            currentConfig = null
            currentBridge
        }
        runCatching { bridge?.close() }
    }

    override fun information(): VpnInterfaceInformation? {
        val config = synchronized(stateLock) { currentConfig } ?: return null
        return VpnInterfaceInformation(
            interfaceName = config.interfaceName,
            isUp = isRunning(),
            addresses = config.addresses.map { it.toCidrString() },
            dns = DnsConfig(
                searchDomains = config.dns.searchDomains,
                servers = config.dns.servers.map { it.toPlainString() },
            ),
            mtu = if (config.mtu == 0) null else config.mtu,
        )
    }

    private fun handleFailure(
        expectedBridge: AutoCloseable?,
        throwable: Throwable,
        onFailure: (Throwable) -> Unit,
    ) {
        val bridge = synchronized(stateLock) {
            if (activeBridge !== expectedBridge) return
            val currentBridge = activeBridge
            activeBridge = null
            currentConfig = null
            currentBridge
        }
        runCatching { bridge?.close() }
        onFailure(throwable)
    }
}
