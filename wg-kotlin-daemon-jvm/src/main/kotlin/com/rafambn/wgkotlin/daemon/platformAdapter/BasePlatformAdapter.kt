package com.rafambn.wgkotlin.daemon.platformAdapter

import com.rafambn.wgkotlin.daemon.command.CommandBinary
import com.rafambn.wgkotlin.daemon.command.CommandFailed
import com.rafambn.wgkotlin.daemon.command.ProcessInvocationModel
import com.rafambn.wgkotlin.daemon.command.ProcessLauncher

internal abstract class BasePlatformAdapter(
    protected val processLauncher: ProcessLauncher,
) : PlatformAdapter {

    protected fun runCommand(
        operationLabel: String,
        binary: CommandBinary,
        arguments: List<String> = emptyList(),
        stdin: String? = null,
        environment: Map<String, String> = emptyMap(),
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
        if (output.exitCode != 0) {
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
}
