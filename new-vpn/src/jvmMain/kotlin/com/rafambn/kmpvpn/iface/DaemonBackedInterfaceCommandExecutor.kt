package com.rafambn.kmpvpn.iface

import com.rafambn.kmpvpn.daemon.client.DaemonProcessClient
import com.rafambn.kmpvpn.daemon.protocol.CommandResult
import com.rafambn.kmpvpn.daemon.protocol.DaemonProcessApi
import com.rafambn.kmpvpn.daemon.protocol.daemonRpcUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService

class DaemonBackedInterfaceCommandExecutor(
    private val host: String,
    private val port: Int,
    private val timeout: Duration,
) : InterfaceCommandExecutor {
    private val client: DaemonProcessClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val httpClient = HttpClient(CIO) {
            install(WebSockets)
            installKrpc {
                serialization {
                    json()
                }
            }
        }
        val rpcClient = httpClient.rpc(daemonRpcUrl(host = host, port = port))
        DaemonProcessClient(
            service = rpcClient.withService<DaemonProcessApi>(),
            timeout = timeout,
            resourceCloser = { httpClient.close() },
        )
    }

    override fun interfaceExists(interfaceName: String): Boolean {
        val result = callDaemon(operation = "interfaceExists", interfaceName = interfaceName) { client ->
            client.interfaceExists(interfaceName)
        }
        return when (result) {
            is CommandResult.Success -> result.data.exists
            is CommandResult.Failure -> throw IllegalStateException(
                "Daemon operation `interfaceExists` failed for `$interfaceName`: ${result.kind} ${result.message}",
            )
        }
    }

    override fun setInterfaceUp(interfaceName: String, up: Boolean) {
        callDaemon(operation = "setInterfaceState", interfaceName = interfaceName) { client ->
            client.setInterfaceState(interfaceName = interfaceName, up = up)
        }
    }

    override fun applyMtu(interfaceName: String, mtu: Int) {
        callDaemon(operation = "applyMtu", interfaceName = interfaceName) { client ->
            client.applyMtu(interfaceName = interfaceName, mtu = mtu)
        }
    }

    override fun applyAddresses(interfaceName: String, addresses: List<String>) {
        callDaemon(operation = "applyAddresses", interfaceName = interfaceName) { client ->
            client.applyAddresses(interfaceName = interfaceName, addresses = addresses)
        }
    }

    override fun applyRoutes(interfaceName: String, routes: List<String>) {
        callDaemon(operation = "applyRoutes", interfaceName = interfaceName) { client ->
            client.applyRoutes(interfaceName = interfaceName, routes = routes)
        }
    }

    override fun applyDns(interfaceName: String, dnsDomainPool: Pair<List<String>, List<String>>) {
        callDaemon(operation = "applyDns", interfaceName = interfaceName) { client ->
            client.applyDns(interfaceName = interfaceName, dnsDomainPool = dnsDomainPool)
        }
    }

    override fun readInformation(interfaceName: String): VpnInterfaceInformation? {
        val result = runCatching {
            callDaemon(
                operation = "readInterfaceInformation",
                interfaceName = interfaceName,
                throwOnFailure = false,
            ) { client ->
                client.readInterfaceInformation(interfaceName = interfaceName)
            }
        }.getOrNull() ?: return null

        return when (result) {
            is CommandResult.Success -> VpnInterfaceInformation(
                interfaceName = result.data.interfaceName,
                isUp = result.data.isUp,
                addresses = result.data.addresses,
                dnsDomainPool = result.data.dnsDomainPool,
                mtu = result.data.mtu,
                listenPort = result.data.listenPort,
            )

            is CommandResult.Failure -> null
        }
    }

    override fun deleteInterface(interfaceName: String) {
        callDaemon(operation = "deleteInterface", interfaceName = interfaceName) { client ->
            client.deleteInterface(interfaceName = interfaceName)
        }
    }

    private fun <S> callDaemon(
        operation: String,
        interfaceName: String,
        throwOnFailure: Boolean = true,
        rpcCall: suspend (DaemonProcessClient) -> CommandResult<S>,
    ): CommandResult<S> {
        val result = try {
            runBlocking {
                rpcCall(client)
            }
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Daemon operation `$operation` failed to reach $host:$port: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }
        if (throwOnFailure && result is CommandResult.Failure) {
            throw IllegalStateException(
                "Daemon operation `$operation` failed for `$interfaceName`: ${result.kind} ${result.message}",
            )
        }
        return result
    }
}
