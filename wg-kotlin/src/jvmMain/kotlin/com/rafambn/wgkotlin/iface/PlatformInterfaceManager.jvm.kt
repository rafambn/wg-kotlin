package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.util.DuplexChannelPipe
import com.rafambn.wgkotlin.daemon.DaemonProcess
import com.rafambn.wgkotlin.daemon.IpcProtocol
import com.rafambn.wgkotlin.daemon.LocalConnection
import com.rafambn.wgkotlin.daemon.proto.ClientMessage
import com.rafambn.wgkotlin.daemon.proto.ServerMessage
import com.rafambn.wgkotlin.daemon.proto.TunSessionConfig
import java.util.concurrent.atomic.AtomicReference

actual object PlatformInterfaceManager {
    private val running = AtomicReference<DaemonProcess?>()

    actual fun create(
        config: TunSessionConfig,
        tunPipe: DuplexChannelPipe<ByteArray>,
        onFailure: (Throwable) -> Unit,
    ): InterfaceSession {
        val activeDaemon = requireDaemon()
        val daemonPid = activeDaemon.pid
        val connection = LocalConnection.connect(activeDaemon.endpoint, daemonPid)
        try {
            IpcProtocol.awaitReady(connection, daemonPid)
            connection.readTimeoutMillis = 30_000
            IpcProtocol.writeClient(connection.output, ClientMessage { payload = ClientMessage.Payload.Config(config) })
            when (val payload = IpcProtocol.readServer(connection.input).payload) {
                is ServerMessage.Payload.Started -> check(payload.value.interfaceName.isNotBlank()) { "Missing started interface name" }
                is ServerMessage.Payload.Error -> error("Daemon session failed (${payload.value.code}): ${payload.value.message}")
                else -> error("Daemon did not accept the session")
            }
            connection.readTimeoutMillis = 0
            return DaemonInterfaceSession(config, connection, tunPipe, onFailure)
        } catch (error: Throwable) {
            runCatching { connection.close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw IllegalStateException("Daemon did not accept the IPC session", error)
        }
    }

    internal fun requireDaemon(): DaemonProcess {
        val daemon = running.get()
        check(daemon != null && daemon.isAlive) { "Launch DaemonProcess before opening a VPN" }
        return daemon
    }

    internal fun registerDaemon(daemon: DaemonProcess) {
        while (true) {
            val current = running.get()
            if (current?.isAlive == true) return
            if (running.compareAndSet(current, daemon)) return
        }
    }

    internal fun unregisterDaemon(daemon: DaemonProcess) {
        running.compareAndSet(daemon, null)
    }
}
