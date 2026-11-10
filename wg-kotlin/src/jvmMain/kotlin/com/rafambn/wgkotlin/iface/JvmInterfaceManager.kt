package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.DnsConfig
import com.rafambn.wgkotlin.daemon.proto.TunSessionConfig
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import com.rafambn.wgkotlin.util.toCidrString
import com.rafambn.wgkotlin.util.toPlainString
import java.util.concurrent.atomic.AtomicReference

class JvmInterfaceManager(
    private val sessionBridge: SessionBridge,
    private val tunPipe: DuplexChannelPipe<ByteArray>,
) : InterfaceManager {
    private val active = AtomicReference<Session?>()

    private class Session(val config: TunSessionConfig) {
        val bridge = AtomicReference<AutoCloseable?>()
        val failure = AtomicReference<Throwable?>()
    }

    override fun isRunning(): Boolean {
        val session = active.get() ?: return false
        return session.failure.get() == null && session.bridge.get() != null
    }

    override fun start(config: TunSessionConfig, onFailure: (Throwable) -> Unit) {
        val session = Session(config)
        runCatching { active.getAndSet(session)?.bridge?.getAndSet(null)?.close() }
        try {
            val bridge = sessionBridge.openSession(
                config = config,
                pipe = tunPipe,
                onFailure = { error ->
                    session.failure.compareAndSet(null, error)
                    if (active.compareAndSet(session, null)) {
                        runCatching { session.bridge.getAndSet(null)?.close() }
                        onFailure(error)
                    }
                },
            )
            session.bridge.set(bridge)
            if (active.get() !== session || session.failure.get() != null) {
                val error = session.failure.get() ?: IllegalStateException("IPC session stopped during opening")
                runCatching { session.bridge.getAndSet(null)?.close() }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
        } catch (error: Throwable) {
            if (active.compareAndSet(session, null)) {
                runCatching { session.bridge.getAndSet(null)?.close() }.exceptionOrNull()?.let(error::addSuppressed)
                try {
                    onFailure(error)
                } catch (cleanupError: Throwable) {
                    if (cleanupError !== error) error.addSuppressed(cleanupError)
                }
            }
            throw error
        }
    }

    override fun stop() {
        runCatching { active.getAndSet(null)?.bridge?.getAndSet(null)?.close() }
    }

    override fun information(): VpnInterfaceInformation? {
        val session = active.get() ?: return null
        if (session.failure.get() != null || session.bridge.get() == null) return null
        val config = session.config
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
}
