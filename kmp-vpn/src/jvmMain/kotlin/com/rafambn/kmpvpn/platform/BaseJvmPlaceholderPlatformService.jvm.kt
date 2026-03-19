package com.rafambn.kmpvpn.platform

import com.rafambn.kmpvpn.NATMode
import com.rafambn.kmpvpn.VpnAdapter
import com.rafambn.kmpvpn.VpnAdapterConfiguration
import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.VpnPeer
import com.rafambn.kmpvpn.address.VpnAddress
import com.rafambn.kmpvpn.dns.DNSProvider
import com.rafambn.kmpvpn.info.VpnInterfaceInformation

internal abstract class BaseJvmPlaceholderPlatformService(
    private val implementationName: String
) : PlatformService<VpnAddress> {

    private fun notImplemented(): Nothing {
        throw UnsupportedOperationException("Placeholder: Implement $implementationName")
    }

    override fun create(configuration: VpnConfiguration): VpnAdapter = notImplemented()

    override fun stop(configuration: VpnConfiguration, session: VpnAdapter) = notImplemented()

    override fun start(configuration: VpnConfiguration): VpnAdapter = notImplemented()

    override fun addresses(): MutableList<VpnAddress> = notImplemented()

    override fun adapters(): MutableList<VpnAdapter> = notImplemented()

    override fun defaultGatewayPeer(): VpnPeer? = notImplemented()

    override fun defaultGatewayPeer(peer: VpnPeer) = notImplemented()

    override fun resetDefaultGatewayPeer() = notImplemented()

    override fun address(interfaceName: String): VpnAddress = notImplemented()

    override fun adapter(interfaceName: String): VpnAdapter = notImplemented()

    override fun information(adapter: VpnAdapter): VpnInterfaceInformation = notImplemented()

    override fun configuration(adapter: VpnAdapter): VpnAdapterConfiguration = notImplemented()

    override fun dns(): DNSProvider? = notImplemented()

    override fun reconfigure(vpnAdapter: VpnAdapter, cfg: VpnAdapterConfiguration) = notImplemented()

    override fun sync(vpnAdapter: VpnAdapter, cfg: VpnAdapterConfiguration) = notImplemented()

    override fun append(vpnAdapter: VpnAdapter, cfg: VpnAdapterConfiguration) = notImplemented()

    override fun remove(vpnAdapter: VpnAdapter, publicKey: String) = notImplemented()

    override fun isValidInterfaceName(interfaceName: String): Boolean {
        return interfaceName.matches(Regex("^utun[0-9]+$"))
    }

    override fun isIpForwardingEnabledOnSystem(): Boolean = notImplemented()

    override fun setIpForwardingEnabledOnSystem(ipForwarding: Boolean) = notImplemented()

    override fun setNat(iface: String, nat: NATMode?) = notImplemented()

    override fun getNat(iface: String): NATMode? = notImplemented()

    override fun defaultGateway(): PlatformService.Gateway? = notImplemented()

    override fun defaultGateway(iface: PlatformService.Gateway?) = notImplemented()
}
