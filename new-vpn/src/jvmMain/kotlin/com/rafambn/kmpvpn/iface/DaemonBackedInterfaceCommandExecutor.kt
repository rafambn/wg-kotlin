package com.rafambn.kmpvpn.iface

import com.rafambn.kmpvpn.daemon.client.DaemonClientConfig
import com.rafambn.kmpvpn.daemon.client.DaemonProcessClient
import com.rafambn.kmpvpn.daemon.protocol.CommandResult
import com.rafambn.kmpvpn.daemon.protocol.DEFAULT_DAEMON_HOST
import com.rafambn.kmpvpn.daemon.protocol.DEFAULT_DAEMON_PORT
import java.time.Duration
import kotlinx.coroutines.runBlocking

class DaemonBackedInterfaceCommandExecutor(
    private val host: String = System.getProperty(JvmPlatformProperties.DAEMON_HOST, DEFAULT_DAEMON_HOST),
    private val port: Int = System.getProperty(JvmPlatformProperties.DAEMON_PORT)?.toIntOrNull() ?: DEFAULT_DAEMON_PORT,
    private val timeout: Duration = Duration.ofMillis(
        System.getProperty(JvmPlatformProperties.DAEMON_TIMEOUT_MILLIS)?.toLongOrNull() ?: 15_000L,
    ),
    private val clientFactory: (String, Int, Duration) -> DaemonProcessClient = { resolvedHost, resolvedPort, resolvedTimeout ->
        DaemonProcessClient.create(
            config = DaemonClientConfig(
                host = resolvedHost,
                port = resolvedPort,
                timeout = resolvedTimeout,
            ),
        )
    },
) : InterfaceCommandExecutor {
    override fun interfaceExists(interfaceName: String): Boolean {
        val result = execute("interfaceExists") { client ->
            client.interfaceExists(interfaceName)
        }
        return when (result) {
            is CommandResult.Success -> result.data.exists
            is CommandResult.Failure -> throw failureFor("interfaceExists", interfaceName, result)
        }
    }

    override fun setInterfaceUp(interfaceName: String, up: Boolean) {
        expectSuccess(operation = "setInterfaceState", interfaceName = interfaceName) { client ->
            client.setInterfaceState(interfaceName = interfaceName, up = up)
        }
    }

    override fun applyMtu(interfaceName: String, mtu: Int?) {
        if (mtu == null) {
            return
        }

        expectSuccess(operation = "applyMtu", interfaceName = interfaceName) { client ->
            client.applyMtu(interfaceName = interfaceName, mtu = mtu)
        }
    }

    override fun applyAddresses(interfaceName: String, addresses: List<String>) {
        expectSuccess(operation = "applyAddresses", interfaceName = interfaceName) { client ->
            client.applyAddresses(interfaceName = interfaceName, addresses = addresses)
        }
    }

    override fun applyRoutes(interfaceName: String, routes: List<String>) {
        expectSuccess(operation = "applyRoutes", interfaceName = interfaceName) { client ->
            client.applyRoutes(interfaceName = interfaceName, routes = routes)
        }
    }

    override fun applyDns(interfaceName: String, dnsDomainPool: Pair<List<String>, List<String>>) {
        expectSuccess(operation = "applyDns", interfaceName = interfaceName) { client ->
            client.applyDns(interfaceName = interfaceName, dnsDomainPool = dnsDomainPool)
        }
    }

    override fun readInformation(interfaceName: String): VpnInterfaceInformation? {
        val result = runCatching {
            execute("readInterfaceInformation") { client ->
                client.readInterfaceInformation(interfaceName = interfaceName)
            }
        }.getOrNull() ?: return null

        return when (result) {
            is CommandResult.Success -> DaemonInterfaceInformationParser.parse(
                interfaceName = interfaceName,
                dump = result.data.dump,
            )

            is CommandResult.Failure -> null
        }
    }

    override fun deleteInterface(interfaceName: String) {
        expectSuccess(operation = "deleteInterface", interfaceName = interfaceName) { client ->
            client.deleteInterface(interfaceName = interfaceName)
        }
    }

    private fun <S> expectSuccess(
        operation: String,
        interfaceName: String,
        rpcCall: suspend (DaemonProcessClient) -> CommandResult<S>,
    ) {
        when (val result = execute(operation, rpcCall)) {
            is CommandResult.Success -> Unit
            is CommandResult.Failure -> throw failureFor(operation, interfaceName, result)
        }
    }

    private fun <S> execute(
        operation: String,
        rpcCall: suspend (DaemonProcessClient) -> CommandResult<S>,
    ): CommandResult<S> {
        return try {
            clientFactory(host, port, timeout).use { client ->
                runBlocking {
                    rpcCall(client)
                }
            }
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Daemon operation `$operation` failed to reach $host:$port: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }
    }

    private fun failureFor(
        operation: String,
        interfaceName: String,
        failure: CommandResult.Failure,
    ): IllegalStateException {
        return IllegalStateException(
            "Daemon operation `$operation` failed for `$interfaceName`: ${failure.kind} ${failure.message}",
        )
    }
}
