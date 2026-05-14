package com.rafambn.wgkotlin.daemon.platformAdapter

import com.rafambn.wgkotlin.daemon.command.CommandBinary
import com.rafambn.wgkotlin.daemon.command.CommandFailed
import com.rafambn.wgkotlin.daemon.command.ProcessInvocationModel
import com.rafambn.wgkotlin.daemon.command.ProcessLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

internal abstract class BasePlatformAdapter(
    protected val processLauncher: ProcessLauncher,
) : PlatformAdapter {
    protected val logger = LoggerFactory.getLogger(javaClass)

    protected suspend fun runCommand(
        operationLabel: String,
        binary: CommandBinary,
        arguments: List<String> = emptyList(),
        stdin: String? = null,
        environment: Map<String, String> = emptyMap(),
        acceptedExitCodes: Set<Int> = setOf(0),
        ignoredFailurePatterns: List<Regex> = emptyList(),
    ) {
        withContext(Dispatchers.IO) {
            runCommandBlocking(
                operationLabel = operationLabel,
                binary = binary,
                arguments = arguments,
                stdin = stdin,
                environment = environment,
                acceptedExitCodes = acceptedExitCodes,
                ignoredFailurePatterns = ignoredFailurePatterns,
            )
        }
    }

    protected fun runCommandBlocking(
        operationLabel: String,
        binary: CommandBinary,
        arguments: List<String> = emptyList(),
        stdin: String? = null,
        environment: Map<String, String> = emptyMap(),
        acceptedExitCodes: Set<Int> = setOf(0),
        ignoredFailurePatterns: List<Regex> = emptyList(),
    ) {
        val output = processLauncher.run(
            ProcessInvocationModel(
                binary = binary,
                arguments = arguments,
                stdin = stdin,
                environment = environment,
            ),
        )
        if (output.exitCode !in acceptedExitCodes) {
            val outputDetail = "${output.stdout}\n${output.stderr}"
            if (ignoredFailurePatterns.any { pattern -> pattern.containsMatchIn(outputDetail) }) {
                return
            }
            throw CommandFailed(
                operationLabel = operationLabel,
                exitCode = output.exitCode,
                stdout = output.stdout,
                stderr = output.stderr,
            )
        }
    }

    protected suspend fun runSuspendCleanup(
        operationLabel: String,
        primaryFailure: Throwable,
        cleanup: suspend () -> Unit,
    ) {
        try {
            cleanup()
        } catch (cleanupFailure: Throwable) {
            primaryFailure.addSuppressed(cleanupFailure)
            logger.warn("Cleanup `$operationLabel` failed", cleanupFailure)
        }
    }

    protected fun runBlockingCleanup(
        operationLabel: String,
        primaryFailure: Throwable,
        cleanup: () -> Unit,
    ) {
        runCatching(cleanup).onFailure { cleanupFailure ->
            primaryFailure.addSuppressed(cleanupFailure)
            logger.warn("Cleanup `$operationLabel` failed", cleanupFailure)
        }
    }
}
