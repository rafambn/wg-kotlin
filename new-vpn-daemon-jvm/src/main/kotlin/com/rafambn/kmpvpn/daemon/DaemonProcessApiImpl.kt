package com.rafambn.kmpvpn.daemon

import com.rafambn.kmpvpn.daemon.protocol.response.ApplyAddressesResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyDnsResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyMtuResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyPeerConfigurationResponse
import com.rafambn.kmpvpn.daemon.protocol.request.ApplyPeerConfigurationRequest
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyRoutesResponse
import com.rafambn.kmpvpn.daemon.protocol.response.CreateInterfaceResponse
import com.rafambn.kmpvpn.daemon.protocol.DAEMON_HELLO_TOKEN
import com.rafambn.kmpvpn.daemon.protocol.DaemonCommandResult
import com.rafambn.kmpvpn.daemon.protocol.DaemonProcessApi
import com.rafambn.kmpvpn.daemon.protocol.DaemonErrorKind
import com.rafambn.kmpvpn.daemon.protocol.response.DeleteInterfaceResponse
import com.rafambn.kmpvpn.daemon.protocol.response.InterfaceExistsResponse
import com.rafambn.kmpvpn.daemon.protocol.response.PingResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ReadInterfaceInformationResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ReadPeerStatsResponse
import com.rafambn.kmpvpn.daemon.protocol.response.SetInterfaceStateResponse

class DaemonProcessApiImpl : DaemonProcessApi {
    override suspend fun ping(nonce: String): DaemonCommandResult<PingResponse> {
        if (nonce.isBlank()) {
            return validationFailure(message = "Ping nonce cannot be blank")
        }

        return DaemonCommandResult.success(
            data = PingResponse(helloToken = DAEMON_HELLO_TOKEN),
        )
    }

    override suspend fun interfaceExists(
        interfaceName: String,
    ): DaemonCommandResult<InterfaceExistsResponse> {
        return unsupportedCommand(command = "INTERFACE_EXISTS")
    }

    override suspend fun createInterface(
        interfaceName: String,
    ): DaemonCommandResult<CreateInterfaceResponse> {
        return unsupportedCommand(command = "CREATE_INTERFACE")
    }

    override suspend fun deleteInterface(
        interfaceName: String,
    ): DaemonCommandResult<DeleteInterfaceResponse> {
        return unsupportedCommand(command = "DELETE_INTERFACE")
    }

    override suspend fun setInterfaceState(
        interfaceName: String,
        up: Boolean,
    ): DaemonCommandResult<SetInterfaceStateResponse> {
        return unsupportedCommand(command = "SET_INTERFACE_STATE")
    }

    override suspend fun applyMtu(
        interfaceName: String,
        mtu: Int?,
    ): DaemonCommandResult<ApplyMtuResponse> {
        return unsupportedCommand(command = "APPLY_MTU")
    }

    override suspend fun applyAddresses(
        interfaceName: String,
        addresses: List<String>,
    ): DaemonCommandResult<ApplyAddressesResponse> {
        return unsupportedCommand(command = "APPLY_ADDRESSES")
    }

    override suspend fun applyRoutes(
        interfaceName: String,
        routes: List<String>,
        table: String?,
    ): DaemonCommandResult<ApplyRoutesResponse> {
        return unsupportedCommand(command = "APPLY_ROUTES")
    }

    override suspend fun applyDns(
        interfaceName: String,
        dnsServers: List<String>,
    ): DaemonCommandResult<ApplyDnsResponse> {
        return unsupportedCommand(command = "APPLY_DNS")
    }

    override suspend fun applyPeerConfiguration(
        request: ApplyPeerConfigurationRequest,
    ): DaemonCommandResult<ApplyPeerConfigurationResponse> {
        return unsupportedCommand(command = "APPLY_PEER_CONFIGURATION")
    }

    override suspend fun readInterfaceInformation(
        interfaceName: String,
    ): DaemonCommandResult<ReadInterfaceInformationResponse> {
        return unsupportedCommand(command = "READ_INTERFACE_INFORMATION")
    }

    override suspend fun readPeerStats(
        interfaceName: String,
    ): DaemonCommandResult<ReadPeerStatsResponse> {
        return unsupportedCommand(command = "READ_PEER_STATS")
    }

    private fun <S> validationFailure(message: String): DaemonCommandResult<S> {
        return DaemonCommandResult.failure(
            kind = DaemonErrorKind.VALIDATION_ERROR,
            message = message,
        )
    }

    private fun <S> unsupportedCommand(command: String): DaemonCommandResult<S> {
        return DaemonCommandResult.failure(
            kind = DaemonErrorKind.UNKNOWN_COMMAND,
            message = "Unsupported command `$command`",
        )
    }
}
