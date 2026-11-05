package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.crypto.CryptoSessionManager
import com.rafambn.wgkotlin.crypto.VpnPeerStats
import com.rafambn.wgkotlin.daemon.proto.TunSessionConfig
import com.rafambn.wgkotlin.iface.InterfaceManager
import com.rafambn.wgkotlin.iface.VpnInterfaceInformation
import com.rafambn.wgkotlin.network.SocketManager
import com.rafambn.wgkotlin.util.toCidrString
import com.rafambn.wgkotlin.util.toPlainString

internal fun testVpn(
    interfaceName: String,
    interfaceManager: InterfaceManager = TestInterfaceManager(),
): Vpn {
    return Vpn(
        interfaceName = interfaceName,
        cryptoSessionManager = TestCryptoSessionManager(),
        socketManager = TestSocketManager(),
        interfaceManager = interfaceManager,
    )
}

internal class TestInterfaceManager(
    private val startFailure: Throwable? = null,
) : InterfaceManager {
    private var currentConfig: TunSessionConfig? = null

    override fun isRunning(): Boolean = currentConfig != null

    override suspend fun start(config: TunSessionConfig, onFailure: (Throwable) -> Unit) {
        startFailure?.let { throw it }
        currentConfig = config
    }

    override fun stop() {
        currentConfig = null
    }

    override fun information(): VpnInterfaceInformation? {
        val config = currentConfig ?: return null
        return VpnInterfaceInformation(
            interfaceName = config.interfaceName,
            isUp = true,
            addresses = config.addresses.map { it.toCidrString() },
            dns = DnsConfig(
                searchDomains = config.dns.searchDomains,
                servers = config.dns.servers.map { it.toPlainString() },
            ),
            mtu = if (config.mtu == 0) null else config.mtu,
        )
    }
}

private class TestCryptoSessionManager : CryptoSessionManager {
    private var running = false

    override fun reconcileSessions(config: ParsedVpnConfiguration) = Unit

    override fun start(onFailure: (Throwable) -> Unit) {
        running = true
    }

    override fun stop() {
        running = false
    }

    override fun peerStats(): List<VpnPeerStats> = emptyList()

    override fun hasActiveSessions(): Boolean = running
}

private class TestSocketManager : SocketManager {
    private var running = false

    override fun start(listenPort: Int, onFailure: (Throwable) -> Unit) {
        running = true
    }

    override fun stop() {
        running = false
    }

    override fun isRunning(): Boolean = running
}

internal fun snapshotConfiguration(config: VpnConfiguration): VpnConfiguration {
    return VpnConfiguration(
        interfaceName = config.interfaceName,
        dns = DnsConfig(
            searchDomains = config.dns.searchDomains.toList(),
            servers = config.dns.servers.toList(),
        ),
        mtu = config.mtu,
        addresses = config.addresses.toMutableList(),
        listenPort = config.listenPort,
        privateKey = config.privateKey,
        peers = config.peers.map { peer ->
            VpnPeer(
                endpointPort = peer.endpointPort,
                endpointAddress = peer.endpointAddress,
                publicKey = peer.publicKey,
                allowedIps = peer.allowedIps.toList(),
                persistentKeepalive = peer.persistentKeepalive,
                presharedKey = peer.presharedKey,
            )
        },
    )
}

internal fun normalizedTestConfiguration(config: VpnConfiguration): VpnConfiguration {
    return if (config.listenPort != null) {
        config
    } else {
        snapshotConfiguration(config).copy(listenPort = 0)
    }
}
