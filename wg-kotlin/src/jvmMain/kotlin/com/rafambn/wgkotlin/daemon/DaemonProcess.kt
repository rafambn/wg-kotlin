package com.rafambn.wgkotlin.daemon

import com.rafambn.wgkotlin.iface.PlatformInterfaceFactory
import com.rafambn.wgkotlin.daemon.proto.ClientMessage
import com.rafambn.wgkotlin.daemon.proto.invoke
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Owns one daemon and its local endpoint. Share this object between
 * VPNs in the app; close VPNs before closing the daemon. Blocking lifecycle calls
 * belong off the UI thread. Peer identity is obtained from the operating system.
 */
class DaemonProcess private constructor(
    private val process: Process,
    internal val endpoint: String,
) : AutoCloseable {
    val pid: Long = process.pid()
    val isAlive: Boolean get() = process.isAlive

    /**
     * Requests cleanup over an authenticated IPC connection, then forces exit if
     * cleanup exceeds [timeout]. A failed forced termination is reported.
     */
    fun stop(timeout: Duration = 5.seconds) {
        require(timeout.isFinite() && !timeout.isNegative()) { "timeout must be finite and non-negative" }
        if (!process.isAlive) {
            closeStreams(process)
            PlatformInterfaceFactory.unregisterDaemon(this)
            return
        }
        if (timeout.inWholeMilliseconds == 0L) { kill(); return }
        val started = System.nanoTime()
        try {
            LocalConnection.connect(endpoint, pid).use { connection ->
                val remaining = ((timeout.inWholeNanoseconds - (System.nanoTime() - started)) / 1_000_000).coerceIn(1, 5_000).toInt()
                IpcProtocol.awaitReady(connection, pid, remaining)
                IpcProtocol.writeClient(connection.output, ClientMessage {
                    payload = ClientMessage.Payload.Shutdown(true)
                })
            }
            if (!process.waitFor((timeout.inWholeNanoseconds - (System.nanoTime() - started)).coerceAtLeast(0), TimeUnit.NANOSECONDS)) {
                kill()
            }
        } catch (_: IOException) {
            if (process.isAlive) kill()
        } catch (error: Throwable) {
            if (process.isAlive) {
                try {
                    kill()
                } catch (cleanupError: Throwable) {
                    error.addSuppressed(cleanupError)
                }
            }
            throw error
        } finally {
            closeStreams(process)
            if (!process.isAlive) PlatformInterfaceFactory.unregisterDaemon(this)
        }
    }

    /** Forces OS termination without daemon cooperation; waits up to five seconds for exit. */
    fun kill() {
        try {
            if (process.isAlive) process.destroyForcibly()
            check(process.waitFor(5, TimeUnit.SECONDS)) { "Daemon did not exit after forced termination" }
        } finally {
            closeStreams(process)
            if (!process.isAlive) PlatformInterfaceFactory.unregisterDaemon(this)
        }
    }

    override fun close() = stop()

    companion object {
        /**
         * [launcher] receives daemon arguments and supplies the elevated daemon's
         * Process, not a shell or permission-dialog process. Forced termination
         * must already be authorized. Standard streams are not used for IPC;
         * the app must redirect or drain output to avoid blocking the daemon.
         * Native launchers may adapt Process to their OS handles and release
         * those handles after exit. The app owns elevation and packaging.
         *
         * The timeout starts after  launcher returns, so permission dialogs are
         * owned by the app. On failure the library forcibly terminates the process.
         */
        fun launch(
            logPath: Path? = null,
            startupTimeout: Duration = 30.seconds,
            launcher: (arguments: List<String>) -> Process,
        ): DaemonProcess {
            require(startupTimeout.isFinite() && startupTimeout.inWholeMilliseconds > 0) { "startupTimeout must be positive and finite" }
            val app = ProcessHandle.current()
            val osName = System.getProperty("os.name")
            val startTime = if (osName.equals("Linux", ignoreCase = true)) {
                // Field 22 is measured in ticks since boot, independent of wall-clock adjustments.
                // The parenthesized process name can contain spaces and closing parentheses.
                val stat = Files.readString(Path.of("/proc", app.pid().toString(), "stat"))
                stat.substringAfterLast(')').trim().split(Regex("\\s+"))[19].toLong()
            } else {
                app.info().startInstant().orElseThrow { IllegalStateException("Owner creation time is unavailable") }.toEpochMilli()
            }
            val name = "wg-kotlin-${UUID.randomUUID()}"
            val endpoint = if (osName.startsWith("Windows", ignoreCase = true)) {
                "\\\\.\\pipe\\$name"
            } else "/tmp/$name/ipc.sock"
            val arguments = mutableListOf("--endpoint", endpoint, "--owner-pid", app.pid().toString(), "--owner-start-time", startTime.toString())
            if (logPath != null) arguments += listOf("--log-path", logPath.toAbsolutePath().toString())
            val process = launcher(arguments.toList())
            val executor = Executors.newSingleThreadExecutor { task ->
                Thread(task, "wg-kotlin-daemon-bootstrap").apply { isDaemon = true }
            }
            try {
                val bootstrap = executor.submit<DaemonProcess> {
                    while (process.isAlive) {
                        try {
                            LocalConnection.connect(endpoint, process.pid()).use { IpcProtocol.awaitReady(it, process.pid()) }
                            return@submit DaemonProcess(process, endpoint)
                        } catch (_: IOException) {
                            // The endpoint does not exist until elevation and bind complete.
                            Thread.sleep(25)
                        }
                    }
                    error("Daemon exited before readiness; inspect launcher stderr")
                }
                val daemon = bootstrap.get(startupTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                PlatformInterfaceFactory.registerDaemon(daemon)
                return daemon
            } catch (error: Throwable) {
                try {
                    process.destroyForcibly()
                    check(process.waitFor(5, TimeUnit.SECONDS)) { "Failed to terminate daemon after bootstrap failure" }
                } catch (cleanupError: Throwable) {
                    error.addSuppressed(cleanupError)
                } finally {
                    closeStreams(process)
                }
                throw IllegalStateException("Failed to initialize owned daemon", error)
            } finally {
                executor.shutdownNow()
            }
        }

        private fun closeStreams(process: Process) {
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }
}
