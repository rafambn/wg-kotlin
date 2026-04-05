package com.rafambn.kmpvpn.daemon.planner

import com.rafambn.kmpvpn.daemon.command.CommandBinary
import com.rafambn.kmpvpn.daemon.command.ProcessInvocationModel
import com.rafambn.kmpvpn.daemon.command.ProcessOutputModel

internal data class ExecutionPlan(
    val operationLabel: String,
    val steps: List<ExecutionStep>,
)

internal data class ExecutionStep(
    val invocation: ProcessInvocationModel,
    val acceptedExitCodes: Set<Int> = setOf(0),
) {
    init {
        require(acceptedExitCodes.isNotEmpty()) { "Accepted exit codes cannot be empty." }
    }
}

internal data class ExecutionPlanResult(
    val outputs: List<ProcessOutputModel>,
) {
    val lastOutput: ProcessOutputModel?
        get() = outputs.lastOrNull()
}

internal fun executionStep(
    binary: CommandBinary,
    arguments: List<String> = emptyList(),
    stdin: String? = null,
    acceptedExitCodes: Set<Int> = setOf(0),
): ExecutionStep {
    return ExecutionStep(
        invocation = ProcessInvocationModel(
            binary = binary,
            arguments = arguments,
            stdin = stdin,
        ),
        acceptedExitCodes = acceptedExitCodes,
    )
}

internal fun DaemonOperation.executionPlanOf(vararg steps: ExecutionStep): ExecutionPlan {
    return ExecutionPlan(
        operationLabel = label,
        steps = steps.toList(),
    )
}
