package com.rafambn.wgkotlin.daemon

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.rafambn.wgkotlin.daemon.di.DaemonKoinBootstrap
import com.rafambn.wgkotlin.daemon.protocol.DaemonTransport
import io.netty.util.NetUtil
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit

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

        if (!isPrivileged())
            throw UsageError("Daemon must run in privileged mode")

        val dependencies = DaemonKoinBootstrap.resolveDependencies()
        val adapter = dependencies.adapter

        Runtime.getRuntime().addShutdownHook(
            Thread({ DaemonKoinBootstrap.close() }, "wg-kotlin-daemon-koin-shutdown")
        )

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
    }

    private fun isLoopbackAddress(host: String): Boolean {
        val normalizedHost = host.trim().removeSurrounding("[", "]")
        val addressBytes = NetUtil.createByteArrayFromIpAddressString(normalizedHost)
        return if (addressBytes == null)
            throw UsageError("Daemon host `$host` is not a valid bind address.")
        else
            InetAddress.getByAddress(addressBytes).isLoopbackAddress
    }

    private fun isPrivileged(
        osName: String = System.getProperty("os.name"),
        unixUidProvider: () -> Long = ::currentUnixUid,
    ): Boolean {
        val os = osName.lowercase(Locale.ROOT)
        val isRoot = runCatching { unixUidProvider() == 0L }.getOrDefault(false)
        return when {
            "win" in os ->
                runCommandSuccessfully(
                    listOf(
                        "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                        "${'$'}i=[Security.Principal.WindowsIdentity]::GetCurrent();${'$'}p=[Security.Principal.WindowsPrincipal]::new(${'$'}i);" +
                                "if(${'$'}i.IsSystem -or ${'$'}p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){exit 0}else{exit 1}"
                    )
                )

            else -> isRoot
        }
    }

    private fun isBinaryAvailableOnPath(executable: String): Boolean {
        val isWin = System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")
        return runCommandSuccessfully(listOf(if (isWin) "where" else "which", executable))
    }

    private fun runCommandSuccessfully(command: List<String>): Boolean = runCatching {
        ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .let {
                it.outputStream.close()
                it.waitFor(2, TimeUnit.SECONDS) && it.exitValue() == 0
            }
    }.getOrDefault(false)

    private fun currentUnixUid(): Long {
        val p = ProcessBuilder("id", "-u").redirectErrorStream(true).start()
        p.outputStream.close()
        if (!p.waitFor(2, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            throw IllegalStateException("id -u timed out")
        }
        return p.inputStream.bufferedReader().use { it.readText().trim().toLong() }
    }
}
