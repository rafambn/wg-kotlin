package com.rafambn.kmpvpn.daemon

import com.rafambn.kmpvpn.daemon.command.CommonsExecProcessLauncher
import com.rafambn.kmpvpn.daemon.command.ProcessLauncher
import com.rafambn.kmpvpn.daemon.planner.ApplyAddresses
import com.rafambn.kmpvpn.daemon.planner.ApplyDns
import com.rafambn.kmpvpn.daemon.planner.ApplyMtu
import com.rafambn.kmpvpn.daemon.planner.ApplyRoutes
import com.rafambn.kmpvpn.daemon.planner.DeleteInterface
import com.rafambn.kmpvpn.daemon.planner.InterfaceExists
import com.rafambn.kmpvpn.daemon.planner.PlanExecutor
import com.rafambn.kmpvpn.daemon.planner.PlatformOperationPlanner
import com.rafambn.kmpvpn.daemon.planner.CommandFailed
import com.rafambn.kmpvpn.daemon.planner.ProcessException
import com.rafambn.kmpvpn.daemon.planner.ProcessTimeout
import com.rafambn.kmpvpn.daemon.planner.ReadInterfaceInformation
import com.rafambn.kmpvpn.daemon.planner.SetInterfaceState
import com.rafambn.kmpvpn.daemon.protocol.CommandResult
import com.rafambn.kmpvpn.daemon.protocol.DaemonErrorKind
import com.rafambn.kmpvpn.daemon.protocol.DaemonProcessApi
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyAddressesResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyDnsResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyMtuResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyRoutesResponse
import com.rafambn.kmpvpn.daemon.protocol.response.DeleteInterfaceResponse
import com.rafambn.kmpvpn.daemon.protocol.response.InterfaceExistsResponse
import com.rafambn.kmpvpn.daemon.protocol.response.PingResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ReadInterfaceInformationResponse
import com.rafambn.kmpvpn.daemon.protocol.response.SetInterfaceStateResponse

class DaemonProcessApiImpl internal constructor(
    private val operationPlanner: PlatformOperationPlanner = PlatformOperationPlanner.fromOs(),
    processLauncher: ProcessLauncher = CommonsExecProcessLauncher(),
) : DaemonProcessApi {
    private val planExecutor = PlanExecutor(
        operationPlanner = operationPlanner,
        processLauncher = processLauncher,
    )

    override suspend fun ping(): CommandResult<PingResponse> {
        return CommandResult.success(PingResponse)
    }

    override suspend fun interfaceExists(interfaceName: String): CommandResult<InterfaceExistsResponse> {
        return try {
            DaemonPayloadValidator.validateInterfaceName(interfaceName)
            val output = planExecutor.run(InterfaceExists(interfaceName = interfaceName)).lastOutput
                ?: error("INTERFACE_EXISTS must produce an execution output.")
            CommandResult.success(
                InterfaceExistsResponse(
                    interfaceName = interfaceName,
                    exists = output.exitCode == 0,
                ),
            )
        } catch (failure: Throwable) {
            toFailureResult(
                commandType = "INTERFACE_EXISTS",
                failure = failure,
            )
        }
    }

    override suspend fun setInterfaceState(
        interfaceName: String,
        up: Boolean,
    ): CommandResult<SetInterfaceStateResponse> {
        return try {
            DaemonPayloadValidator.validateInterfaceName(interfaceName)
            planExecutor.run(
                SetInterfaceState(
                interfaceName = interfaceName,
                up = up,
                ),
            )
            CommandResult.success(
                SetInterfaceStateResponse(
                    interfaceName = interfaceName,
                    up = up,
                ),
            )
        } catch (failure: Throwable) {
            toFailureResult(
                commandType = "SET_INTERFACE_STATE",
                failure = failure,
            )
        }
    }

    override suspend fun applyMtu(interfaceName: String, mtu: Int): CommandResult<ApplyMtuResponse> {
        return try {
            DaemonPayloadValidator.validateInterfaceName(interfaceName)
            DaemonPayloadValidator.validateMtu(mtu)
            planExecutor.run(
                ApplyMtu(
                interfaceName = interfaceName,
                mtu = mtu,
                ),
            )
            CommandResult.success(
                ApplyMtuResponse(
                    interfaceName = interfaceName,
                    mtu = mtu,
                ),
            )
        } catch (failure: Throwable) {
            toFailureResult(
                commandType = "APPLY_MTU",
                failure = failure,
            )
        }
    }

    override suspend fun applyAddresses(
        interfaceName: String,
        addresses: List<String>,
    ): CommandResult<ApplyAddressesResponse> {
        return try {
            DaemonPayloadValidator.validateInterfaceName(interfaceName)
            DaemonPayloadValidator.validateAddresses(addresses)
            planExecutor.run(
                ApplyAddresses(
                interfaceName = interfaceName,
                addresses = addresses,
                ),
            )
            CommandResult.success(
                ApplyAddressesResponse(
                    interfaceName = interfaceName,
                    addresses = addresses,
                ),
            )
        } catch (failure: Throwable) {
            toFailureResult(
                commandType = "APPLY_ADDRESSES",
                failure = failure,
            )
        }
    }

    override suspend fun applyRoutes(
        interfaceName: String,
        routes: List<String>,
    ): CommandResult<ApplyRoutesResponse> {
        return try {
            DaemonPayloadValidator.validateInterfaceName(interfaceName)
            DaemonPayloadValidator.validateRoutes(routes)
            planExecutor.run(
                ApplyRoutes(
                interfaceName = interfaceName,
                routes = routes,
                ),
            )
            CommandResult.success(
                ApplyRoutesResponse(
                    interfaceName = interfaceName,
                    routes = routes,
                ),
            )
        } catch (failure: Throwable) {
            toFailureResult(
                commandType = "APPLY_ROUTES",
                failure = failure,
            )
        }
    }

    override suspend fun applyDns(
        interfaceName: String,
        dnsDomainPool: Pair<List<String>, List<String>>,
    ): CommandResult<ApplyDnsResponse> {
        return try {
            DaemonPayloadValidator.validateInterfaceName(interfaceName)
            DaemonPayloadValidator.validateDnsDomainPool(dnsDomainPool)
            planExecutor.run(
                ApplyDns(
                interfaceName = interfaceName,
                dnsDomainPool = dnsDomainPool,
                ),
            )
            CommandResult.success(
                ApplyDnsResponse(
                    interfaceName = interfaceName,
                    dnsDomainPool = dnsDomainPool,
                ),
            )
        } catch (failure: Throwable) {
            toFailureResult(
                commandType = "APPLY_DNS",
                failure = failure,
            )
        }
    }

    override suspend fun readInterfaceInformation(
        interfaceName: String,
    ): CommandResult<ReadInterfaceInformationResponse> {
        return try {
            DaemonPayloadValidator.validateInterfaceName(interfaceName)
            val output = planExecutor.run(ReadInterfaceInformation(interfaceName = interfaceName)).lastOutput
                ?: error("READ_INTERFACE_INFORMATION must produce an execution output.")
            val parsedInformation = DaemonInterfaceInformationParser.parse(
                platformId = operationPlanner.platformId,
                interfaceName = interfaceName,
                dump = output.stdout,
            ) ?: error(
                "Unable to parse interface information for `${operationPlanner.platformId}` from daemon command output.",
            )
            CommandResult.success(parsedInformation,)
        } catch (failure: Throwable) {
            toFailureResult(
                commandType = "READ_INTERFACE_INFORMATION",
                failure = failure,
            )
        }
    }

    override suspend fun deleteInterface(
        interfaceName: String,
    ): CommandResult<DeleteInterfaceResponse> {
        return try {
            DaemonPayloadValidator.validateInterfaceName(interfaceName)
            planExecutor.run(DeleteInterface(interfaceName = interfaceName))
            CommandResult.success(DeleteInterfaceResponse(interfaceName = interfaceName))
        } catch (failure: Throwable) {
            toFailureResult(
                commandType = "DELETE_INTERFACE",
                failure = failure,
            )
        }
    }

    private fun <S> toFailureResult(
        commandType: String,
        failure: Throwable,
    ): CommandResult<S> {
        return when (failure) {
            is PayloadValidationException -> CommandResult.failure(
                kind = DaemonErrorKind.VALIDATION_ERROR,
                message = failure.message ?: "Invalid payload",
            )

            is ProcessException -> CommandResult.failure(
                kind = DaemonErrorKind.PROCESS_START_FAILURE,
                message = failure.message ?: "Failed to start privileged process",
                detail = failure.detail,
            )

            is ProcessTimeout -> CommandResult.failure(
                kind = DaemonErrorKind.PROCESS_TIMEOUT,
                message = failure.message ?: "Privileged process timed out",
                detail = failure.detail,
            )

            is CommandFailed -> CommandResult.failure(
                kind = DaemonErrorKind.COMMAND_FAILED,
                message = failure.message ?: "Privileged command failed",
                detail = failure.detail,
            )

            else -> CommandResult.failure(
                kind = DaemonErrorKind.INTERNAL_ERROR,
                message = "Unexpected daemon failure in `$commandType`: ${failure.message ?: "unknown"}",
            )
        }
    }
}
