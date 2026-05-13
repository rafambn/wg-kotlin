package com.rafambn.wgkotlin.daemon

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.rafambn.wgkotlin.daemon.di.DaemonKoinBootstrap
import com.rafambn.wgkotlin.daemon.protocol.DaemonTransport
import com.rafambn.wgkotlin.daemon.protocol.DaemonApi
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.protobuf.protobuf

fun main(args: Array<String>) {
    DaemonCli().main(args)
}

internal const val DAEMON_VERSION = "0.1.0"

internal class DaemonCli : CliktCommand(name = "vpn-daemon") {
    init {
        versionOption(version = DAEMON_VERSION, names = setOf("--version", "-v"))
    }

    private val host: String by option(
        "--host",
        help = "Host/interface to bind the daemon listener (default: 127.0.0.1). Non-loopback hosts require --allow-remote.",
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

    private val allowRemote: Boolean by option(
        "--allow-remote",
        help = "Allow binding to non-loopback addresses (e.g. 0.0.0.0 or LAN IPs). Use only in trusted environments.",
    ).flag(default = false)

    override fun run() {
        val address = try {
            InetAddress.getByName(host)
        } catch (_: Throwable) {
            throw UsageError("Daemon host `$host` is not a valid bind address.")
        }

        if (!allowRemote && !address.isLoopbackAddress) {
            throw UsageError(
                "Refusing to bind daemon to non-loopback host `$host`. Pass `--allow-remote` if you really want remote exposure.",
            )
        }
        val dependencies = DaemonKoinBootstrap.resolveDependencies()
        val adapter = dependencies.adapter
        val shutdownHook = Thread({ DaemonKoinBootstrap.close() }, "wg-kotlin-daemon-koin-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        try {
            val isPrivileged = hasRequiredPrivileges()
            if (!isPrivileged) {
                throw UsageError(
                    "Daemon must run with elevated privileges for `${adapter.platformId}` commands (current user: `${System.getProperty("user.name")}`).",
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

            createDaemonServer(
                host = host,
                port = port,
                service = dependencies.service,
            ).start(wait = true)
        } finally {
            DaemonKoinBootstrap.close()
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        }
    }
}

internal fun hasRequiredPrivileges(
    osName: String = System.getProperty("os.name"),
    unixUidProvider: () -> Long = ::currentUnixUid,
    commandRunner: (List<String>) -> Boolean = ::runCommandSuccessfully,
): Boolean {
    val normalizedOs = osName.lowercase()
    return when {
        normalizedOs.contains("win") -> {
            val script =
                "${'$'}identity = [Security.Principal.WindowsIdentity]::GetCurrent();" +
                    "${'$'}principal = [Security.Principal.WindowsPrincipal]::new(${'$'}identity);" +
                    "if (${'$'}identity.IsSystem -or ${'$'}principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { exit 0 } else { exit 1 }"
            commandRunner(
                listOf(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    script,
                ),
            )
        }
        else -> try {
            unixUidProvider() == 0L
        } catch (_: Exception) {
            false
        }
    }
}

internal fun runCommandSuccessfully(command: List<String>): Boolean {
    return try {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val finished = process.waitFor(2, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return false
        }
        process.exitValue() == 0
    } catch (_: Exception) {
        false
    }
}

private fun currentUnixUid(): Long {
    val process = ProcessBuilder("id", "-u")
        .redirectErrorStream(true)
        .start()

    val finished = process.waitFor(2, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        throw IllegalStateException("id -u timed out")
    }
    return process.inputStream.bufferedReader().readText().trim().toLong()
}

internal fun createDaemonServer(
    host: String,
    port: Int,
    service: DaemonApi,
) = embeddedServer(
    factory = Netty,
    host = host,
    port = port,
    module = { module(service = service) },
)

internal fun isBinaryAvailableOnPath(executable: String): Boolean {
    val lookupCommand = if (System.getProperty("os.name").lowercase().contains("win")) {
        listOf("where", executable)
    } else {
        listOf("which", executable)
    }

    return runCommandSuccessfully(lookupCommand)
}

@OptIn(ExperimentalSerializationApi::class)
fun Application.module(
    service: DaemonApi,
) {
    install(WebSockets)
    install(Krpc) {
        serialization {
            protobuf()
        }
    }

    routing {
        get("/version") {
            call.respondText(DAEMON_VERSION)
        }
        rpc(DaemonTransport.DAEMON_RPC_PATH) {
            rpcConfig {
                serialization {
                    protobuf()
                }
            }
            registerService<DaemonApi> {
                service
            }
        }
    }
}
