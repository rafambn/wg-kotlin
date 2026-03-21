package com.rafambn.kmpvpn.daemon.protocol

import com.rafambn.kmpvpn.daemon.protocol.request.ApplyPeerConfigurationRequest
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyAddressesResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyDnsResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyMtuResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyPeerConfigurationResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyRoutesResponse
import com.rafambn.kmpvpn.daemon.protocol.response.CreateInterfaceResponse
import com.rafambn.kmpvpn.daemon.protocol.response.DeleteInterfaceResponse
import com.rafambn.kmpvpn.daemon.protocol.response.InterfaceExistsResponse
import com.rafambn.kmpvpn.daemon.protocol.response.PingResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ReadInterfaceInformationResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ReadPeerStatsResponse
import com.rafambn.kmpvpn.daemon.protocol.response.SetInterfaceStateResponse
import kotlinx.rpc.annotations.Rpc

/**
 * kRPC control-plane contract shared by daemon server and JVM client.
 *
 * Commands are intentionally split into dedicated RPC methods.
 * Primitive parameters are preferred, except for high-cardinality commands.
 */
@Rpc
interface DaemonProcessApi {
    suspend fun ping(
        nonce: String = "ping",
    ): DaemonCommandResult<PingResponse>

    suspend fun interfaceExists(
        interfaceName: String,
    ): DaemonCommandResult<InterfaceExistsResponse>

    suspend fun createInterface(
        interfaceName: String,
    ): DaemonCommandResult<CreateInterfaceResponse>

    suspend fun deleteInterface(
        interfaceName: String,
    ): DaemonCommandResult<DeleteInterfaceResponse>

    suspend fun setInterfaceState(
        interfaceName: String,
        up: Boolean,
    ): DaemonCommandResult<SetInterfaceStateResponse>

    suspend fun applyMtu(
        interfaceName: String,
        mtu: Int?,
    ): DaemonCommandResult<ApplyMtuResponse>

    suspend fun applyAddresses(
        interfaceName: String,
        addresses: List<String>,
    ): DaemonCommandResult<ApplyAddressesResponse>

    suspend fun applyRoutes(
        interfaceName: String,
        routes: List<String>,
        table: String? = null,
    ): DaemonCommandResult<ApplyRoutesResponse>

    suspend fun applyDns(
        interfaceName: String,
        dnsServers: List<String>,
    ): DaemonCommandResult<ApplyDnsResponse>

    suspend fun applyPeerConfiguration(
        request: ApplyPeerConfigurationRequest,
    ): DaemonCommandResult<ApplyPeerConfigurationResponse>

    suspend fun readInterfaceInformation(
        interfaceName: String,
    ): DaemonCommandResult<ReadInterfaceInformationResponse>

    suspend fun readPeerStats(
        interfaceName: String,
    ): DaemonCommandResult<ReadPeerStatsResponse>
}
