package com.rafambn.kmpvpn.daemon.command

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
) : ProcessLauncher {
    init {
        require(timeout.toMillis() > 0L) { "Command timeout must be positive" }
    }

    override fun run(invocation: ProcessInvocationModel): ProcessOutputModel {
        val executable = invocation.binary.executable
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
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
            executor.execute(toCommandLine(invocation))
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
        commandLine.addArgument(argument, false)
    }
    return commandLine
}
