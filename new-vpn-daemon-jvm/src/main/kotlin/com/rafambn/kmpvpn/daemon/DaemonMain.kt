package com.rafambn.kmpvpn.daemon

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.rafambn.kmpvpn.daemon.di.DaemonKoinBootstrap
import com.rafambn.kmpvpn.daemon.planner.PlatformOperationPlanner
import com.rafambn.kmpvpn.daemon.protocol.DAEMON_RPC_PATH
import com.rafambn.kmpvpn.daemon.protocol.DEFAULT_DAEMON_HOST
import com.rafambn.kmpvpn.daemon.protocol.DEFAULT_DAEMON_PORT
import com.rafambn.kmpvpn.daemon.protocol.DaemonProcessApi
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import com.sun.security.auth.module.UnixSystem
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json
import org.koin.core.context.GlobalContext

fun main(args: Array<String>) {
    DaemonCli().main(args)
}

private class DaemonCli : CliktCommand(name = "vpn-daemon") {
    private val host: String by option(
        "--host",
        help = "Host/interface to bind the daemon listener (default: 127.0.0.1). Non-loopback hosts require --allow-remote.",
    ).convert { value ->
        if (value.isBlank()) {
            fail("Daemon host cannot be blank.")
        }
        value
    }.default(DEFAULT_DAEMON_HOST)

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
    }.default(DEFAULT_DAEMON_PORT)

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
        DaemonKoinBootstrap.ensureKoinStarted()
        val koin = GlobalContext.get()
        val operationPlanner = koin.get<PlatformOperationPlanner>()

        val isPrivileged = hasRequiredPrivileges()
        if (!isPrivileged) {
            throw UsageError(
                "Daemon must run with elevated privileges for `${operationPlanner.platformId}` commands (current user: `${System.getProperty("user.name")}`).",
            )
        }

        val missingBinaries = operationPlanner.requiredBinaries
            .filterNot { binary -> isBinaryAvailableOnPath(binary.executable) }
            .map { binary -> binary.executable }

        if (missingBinaries.isNotEmpty()) {
            throw UsageError(
                "Missing required privileged binaries for `${operationPlanner.platformId}`: ${missingBinaries.joinToString(", ")}.",
            )
        }

        createDaemonServer(
            host = host,
            port = port,
            service = koin.get(),
        ).start(wait = true)
    }
}

internal fun hasRequiredPrivileges(
    osName: String = System.getProperty("os.name"),
    unixUidProvider: () -> Long = { UnixSystem().uid },
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
        } catch (_: Throwable) {
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
        finished && process.exitValue() == 0
    } catch (_: Throwable) {
        false
    }
}

internal fun createDaemonServer(
    host: String,
    port: Int,
    service: DaemonProcessApi = DaemonProcessApiImpl(),
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

fun Application.module(
    service: DaemonProcessApi = DaemonProcessApiImpl(),
) {
    install(WebSockets)
    install(Krpc) {
        serialization {
            // TODO(vpn-rebuild): migrate kRPC serialization to Protobuf once protocol models are stable and annotated with @ProtoNumber.
            json()
        }
    }

    routing {
        rpc(DAEMON_RPC_PATH) {
            rpcConfig {
                serialization {
                    // TODO(vpn-rebuild): migrate kRPC serialization to Protobuf once protocol models are stable and annotated with @ProtoNumber.
                    json()
                }
            }
            registerService<DaemonProcessApi> {
                service
            }
        }
    }
}
