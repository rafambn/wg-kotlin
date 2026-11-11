package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.util.DuplexChannelPipe
import com.rafambn.wgkotlin.daemon.DaemonProcess
import com.rafambn.wgkotlin.daemon.proto.TunSessionConfig
import java.util.concurrent.atomic.AtomicReference

actual object PlatformInterfaceManager {
    private val running = AtomicReference<DaemonProcess?>()

    actual fun create(
        config: TunSessionConfig,
        tunPipe: DuplexChannelPipe<ByteArray>,
        onFailure: (Throwable) -> Unit,
    ): InterfaceSession {
        return DaemonInterfaceSession(config, tunPipe, onFailure)
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
