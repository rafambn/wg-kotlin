package com.rafambn.wgkotlin.daemon

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.rafambn.wgkotlin.daemon.di.DaemonKoinBootstrap
import com.rafambn.wgkotlin.daemon.protocol.DaemonTransport
import java.util.concurrent.atomic.AtomicBoolean

internal class DaemonCli : CliktCommand(name = "vpn-daemon") {
    init {
        versionOption(version = DAEMON_VERSION, names = setOf("--version", "-v"))
    }

    private val host: String by option(
        "--host",
        help = "Loopback host/interface to bind the daemon listener (default: 127.0.0.1).",
    ).convert { value ->
        if (value.isBlank()) {
            fail("Daemon host cannot be blank.")
        }
        value
    }.default(DaemonTransport.DEFAULT_DAEMON_HOST)

    private val port: Int by option(
        "--port",
        help = "TCP port for the daemon listener (default: 8787, valid range: 1..65535).",
    ).convert { value ->
        val parsed = value.toIntOrNull()
            ?: fail("Daemon port must be numeric, but was `$value`.")

        if (parsed !in 1..65535) {
            fail("Daemon port must be between 1 and 65535, but was `$parsed`.")
        }

        parsed
    }.default(DaemonTransport.DEFAULT_DAEMON_PORT)

    override fun run() {
        val address = bindAddressOrUsageError(host)

        if (!address.isLoopbackAddress) {
            throw UsageError(
                "Refusing to bind daemon to non-loopback host `$host`.",
            )
        }

        val dependencies = DaemonKoinBootstrap.resolveDependencies()
        val adapter = dependencies.adapter
        val cleanupStarted = AtomicBoolean(false)
        fun closeDependenciesOnce() {
            if (cleanupStarted.compareAndSet(false, true)) {
                DaemonKoinBootstrap.close()
            }
        }

        val shutdownHook = Thread(::closeDependenciesOnce, "wg-kotlin-daemon-koin-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        try {
            val isPrivileged = hasRequiredPrivileges()
            if (!isPrivileged) {
                throw UsageError(
                    "Daemon must run with network administration privileges for `${adapter.platformId}` commands (current user: `${System.getProperty("user.name")}`).",
                )
            }

            val missingBinaries = adapter.requiredBinaries
                .filterNot { binary -> isBinaryAvailableOnPath(binary.executable) }
                .map { binary -> binary.executable }

            if (missingBinaries.isNotEmpty()) {
                throw UsageError(
                    "Missing required privileged binaries for `${adapter.platformId}`: ${missingBinaries.joinToString(", ")}.",
                )
            }

            adapter.cleanupStaleState()

            createDaemonServer(
                host = host,
                port = port,
                service = dependencies.service,
            ).start(wait = true)
        } finally {
            closeDependenciesOnce()
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        }
    }
}
