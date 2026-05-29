package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.DnsConfig
import com.rafambn.wgkotlin.daemon.proto.TunSessionConfig
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import com.rafambn.wgkotlin.util.toCidrString
import com.rafambn.wgkotlin.util.toPlainString

class JvmInterfaceManager(
    private val sessionBridge: SessionBridge,
    private val tunPipe: DuplexChannelPipe<ByteArray>,
) : InterfaceManager {
    private var currentConfig: TunSessionConfig? = null
    private var activeBridge: AutoCloseable? = null

    override fun isRunning(): Boolean = activeBridge != null

    override fun start(config: TunSessionConfig, onFailure: (Throwable) -> Unit) {
        stop()

        val bridge = sessionBridge.openSession(
            config = config,
            pipe = tunPipe,
            onFailure = { throwable ->
                runCatching { activeBridge?.close() }
                activeBridge = null
                currentConfig = null
                onFailure(throwable)
            },
        )

        activeBridge = bridge
        currentConfig = config
    }

    override fun stop() {
        runCatching { activeBridge?.close() }
        activeBridge = null
        currentConfig = null
    }

    override fun information(): VpnInterfaceInformation? {
        val config = currentConfig ?: return null
        return VpnInterfaceInformation(
            interfaceName = config.interfaceName,
            isUp = isRunning(),
            addresses = config.addresses.map { it.toCidrString() },
            dns = DnsConfig(
                searchDomains = config.dns.searchDomains,
                servers = config.dns.servers.map { it.toPlainString() },
            ),
            mtu = if (config.mtu == 0) null else config.mtu,
        )
    }
}
