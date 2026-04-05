package com.rafambn.kmpvpn.daemon.planner

import com.rafambn.kmpvpn.daemon.command.CommonsExecProcessLauncher
import com.rafambn.kmpvpn.daemon.command.ProcessLauncher
import com.rafambn.kmpvpn.daemon.command.ProcessOutputModel
import com.rafambn.kmpvpn.daemon.command.StartFailure
import com.rafambn.kmpvpn.daemon.command.TimeoutFailure

internal class PlanExecutor(
    private val operationPlanner: PlatformOperationPlanner,
    private val processLauncher: ProcessLauncher = CommonsExecProcessLauncher(),
) {
    fun run(operation: DaemonOperation): ExecutionPlanResult {
        val plan = operationPlanner.plan(operation)
        val outputs = mutableListOf<ProcessOutputModel>()
        plan.steps.forEach { step ->
            val output = try {
                processLauncher.run(step.invocation)
            } catch (failure: TimeoutFailure) {
                throw ProcessTimeout(
                    executable = failure.executable,
                    timeoutMillis = failure.timeout.toMillis(),
                    cause = failure,
                )
            } catch (failure: StartFailure) {
                throw ProcessException(
                    executable = failure.executable,
                    message = failure.message ?: "Failed to start `${failure.executable}`",
                    cause = failure,
                )
            }

            if (output.exitCode !in step.acceptedExitCodes) {
                throw CommandFailed(
                    operationLabel = plan.operationLabel,
                    executable = step.invocation.binary.executable,
                    exitCode = output.exitCode,
                    stdout = output.stdout,
                    stderr = output.stderr,
                )
            }

            outputs += output
        }
        return ExecutionPlanResult(outputs = outputs)
    }
}
