package com.rafambn.kmpvpn.daemon.command

import java.time.Duration

internal sealed class ProcessException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal class StartFailure(
    val executable: String,
    message: String,
    cause: Throwable? = null,
) : ProcessException(message = message, cause = cause)

internal class TimeoutFailure(
    val executable: String,
    val timeout: Duration,
    cause: Throwable,
) : ProcessException(
    message = "Command `$executable` timed out after ${timeout.toMillis()}ms",
    cause = cause,
)
