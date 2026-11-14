package com.rafambn.wgkotlin.daemon

import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** Adapts a JVM process when it already represents the actual daemon, such as a pkexec launch. */
class ProcessDaemonHandle(private val process: Process) : DaemonHandle {
    override val pid: Long = process.pid()
    override val isAlive: Boolean get() = process.isAlive

    override fun awaitExit(timeout: Duration): Boolean =
        process.waitFor(timeout.inWholeNanoseconds, TimeUnit.NANOSECONDS)

    override fun kill() {
        process.destroyForcibly()
    }

    override fun close() {
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }
}
