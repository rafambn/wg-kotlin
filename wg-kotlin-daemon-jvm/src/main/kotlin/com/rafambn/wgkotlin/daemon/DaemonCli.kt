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
import io.netty.util.NetUtil
import java.net.InetAddress
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
        if (!isLoopbackAddress(host))
            throw UsageError("Refusing to bind daemon to non-loopback host `$host`.")

        val dependencies = DaemonKoinBootstrap.resolveDependencies()
        val adapter = dependencies.adapter

        Runtime.getRuntime().addShutdownHook(
            Thread({ DaemonKoinBootstrap.close() }, "wg-kotlin-daemon-koin-shutdown")
        )

        val isPrivileged = hasRequiredPrivileges()
        if (!isPrivileged) {
            throw UsageError(
                "Daemon must run with network administration privileges for `${adapter.platformId}` commands (current user: `${
                    System.getProperty(
                        "user.name"
                    )
                }`).",
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
    }

    private fun isLoopbackAddress(host: String): Boolean {
        val normalizedHost = host.trim().removeSurrounding("[", "]")
        val addressBytes = NetUtil.createByteArrayFromIpAddressString(normalizedHost)
        return if (addressBytes == null)
            throw UsageError("Daemon host `$host` is not a valid bind address.")
        else
            InetAddress.getByAddress(addressBytes).isLoopbackAddress
    }
}
