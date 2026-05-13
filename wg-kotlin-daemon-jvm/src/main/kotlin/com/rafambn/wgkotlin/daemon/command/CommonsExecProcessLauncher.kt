package com.rafambn.wgkotlin.daemon.command

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Duration
import org.apache.commons.exec.CommandLine
import org.apache.commons.exec.DefaultExecutor
import org.apache.commons.exec.ExecuteException
import org.apache.commons.exec.ExecuteWatchdog
import org.apache.commons.exec.PumpStreamHandler

internal class CommonsExecProcessLauncher(
    private val timeout: Duration = Duration.ofSeconds(20),
    private val maxOutputBytes: Int = 1 * 1024 * 1024,
) : ProcessLauncher {
    init {
        require(timeout.toMillis() > 0L) { "Command timeout must be positive" }
        require(maxOutputBytes > 0) { "maxOutputBytes must be positive" }
    }

    override fun run(invocation: ProcessInvocationModel): ProcessOutputModel {
        val executable = invocation.binary.executable
        val stdout = CappedByteArrayOutputStream(maxOutputBytes)
        val stderr = CappedByteArrayOutputStream(maxOutputBytes)
        val stdin = ByteArrayInputStream((invocation.stdin ?: "").toByteArray(Charsets.UTF_8))

        val watchdog = ExecuteWatchdog.builder()
            .setTimeout(timeout)
            .get()
        val executor = DefaultExecutor.builder()
            .setExecuteStreamHandler(PumpStreamHandler(stdout, stderr, stdin))
            .get()
        executor.watchdog = watchdog
        executor.setExitValues(null)

        val exitCode = try {
            executor.execute(toCommandLine(invocation), mergedEnvironment(invocation.environment))
        } catch (failure: ExecuteException) {
            if (watchdog.killedProcess()) {
                throw TimeoutFailure(
                    executable = executable,
                    timeout = timeout,
                    cause = failure,
                )
            }
            failure.exitValue
        } catch (failure: IOException) {
            throw StartFailure(
                executable = executable,
                message = "Failed to start `$executable`",
                cause = failure,
            )
        } catch (failure: Exception) {
            throw StartFailure(
                executable = executable,
                message = "Failed to execute `$executable`",
                cause = failure,
            )
        }

        return ProcessOutputModel(
            exitCode = exitCode,
            stdout = stdout.toString(Charsets.UTF_8).trim(),
            stderr = stderr.toString(Charsets.UTF_8).trim(),
        )
    }
}

private fun toCommandLine(invocation: ProcessInvocationModel): CommandLine {
    val commandLine = CommandLine(invocation.binary.executable)
    invocation.arguments.forEach { argument ->
        commandLine.addArgument(argument, true)
    }
    return commandLine
}

private fun mergedEnvironment(overrides: Map<String, String>): Map<String, String>? {
    if (overrides.isEmpty()) {
        return null
    }
    return System.getenv().toMutableMap().apply {
        putAll(overrides)
    }
}

/**
 * [ByteArrayOutputStream] that silently drops bytes once [maxBytes] is reached,
 * protecting the daemon from unbounded output from a misbehaving child process.
 */
private class CappedByteArrayOutputStream(private val maxBytes: Int) : ByteArrayOutputStream() {
    private var totalBytes = 0
    private var capped = false

    override fun write(b: Int) {
        if (capped) return
        if (totalBytes >= maxBytes) {
            capped = true
            return
        }
        super.write(b)
        totalBytes++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (capped) return
        val remaining = maxBytes - totalBytes
        if (remaining <= 0) {
            capped = true
            return
        }
        val toWrite = len.coerceAtMost(remaining)
        super.write(b, off, toWrite)
        totalBytes += toWrite
        if (toWrite < len) {
            capped = true
        }
    }
}
