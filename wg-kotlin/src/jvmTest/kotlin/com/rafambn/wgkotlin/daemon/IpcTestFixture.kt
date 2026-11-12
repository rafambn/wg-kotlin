package com.rafambn.wgkotlin.daemon

import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Runs the Rust IPC implementation without network privileges or TUN creation. */
internal class IpcTestFixture(
    mode: String = "normal",
    timeout: Duration = 3.seconds,
    handleFactory: (Process) -> DaemonHandle = ::ProcessDaemonHandle,
) : AutoCloseable {
    lateinit var process: Process
        private set
    lateinit var arguments: List<String>
        private set
    val endpoint: String get() = arguments[arguments.indexOf("--endpoint") + 1]
    val daemon: DaemonProcess

    init {
        try {
            daemon = DaemonProcess.launch(startupTimeout = timeout) { args ->
                arguments = args
                process = start(args + listOf("--mode", mode))
                handleFactory(process)
            }
        } catch (error: Throwable) {
            cleanup()
            throw error
        }
    }

    fun startForeignClient(): Process = start(arguments + listOf("--mode", "foreign-client"))

    private fun start(args: List<String>): Process = ProcessBuilder(
        listOf(requireNotNull(System.getProperty("wgkotlin.test.ipcDaemonExecutable"))) + args,
    ).redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT).start()

    override fun close() {
        try { daemon.close() } finally { cleanup() }
    }

    private fun cleanup() {
        if (::arguments.isInitialized && !System.getProperty("os.name").startsWith("Windows")) {
            Files.deleteIfExists(Path.of(endpoint))
            Files.deleteIfExists(Path.of(endpoint).parent)
        }
    }
}
