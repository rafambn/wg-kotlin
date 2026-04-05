package com.rafambn.kmpvpn.daemon.planner

import com.rafambn.kmpvpn.daemon.protocol.DaemonFailureDetail

internal sealed class DaemonExecutionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    open val detail: DaemonFailureDetail? = null
}

internal class ProcessException(
    val executable: String,
    message: String,
    cause: Throwable? = null,
) : DaemonExecutionException(message = message, cause = cause) {
    override val detail: DaemonFailureDetail = DaemonFailureDetail(executable = executable)
}

internal class ProcessTimeout(
    val executable: String,
    val timeoutMillis: Long,
    cause: Throwable? = null,
) : DaemonExecutionException(
    message = "Command `$executable` timed out after ${timeoutMillis}ms",
    cause = cause,
) {
    override val detail: DaemonFailureDetail = DaemonFailureDetail(executable = executable)
}

internal class CommandFailed(
    operationLabel: String,
    val executable: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) : DaemonExecutionException(
    message = "Command `$operationLabel` failed with exit code $exitCode: ${selectOutputDetail(stdout = stdout, stderr = stderr) ?: "no output"}",
) {
    override val detail: DaemonFailureDetail = DaemonFailureDetail(
        executable = executable,
        exitCode = exitCode,
    )
}

private fun selectOutputDetail(stdout: String, stderr: String): String? {
    return when {
        stderr.isNotBlank() -> stderr
        stdout.isNotBlank() -> stdout
        else -> null
    }
}
