package com.rafambn.kmpvpn.platform

import com.rafambn.kmpvpn.NATMode
import com.rafambn.kmpvpn.VpnAdapter
import com.rafambn.kmpvpn.VpnPeer
import com.rafambn.kmpvpn.address.VpnAddress
import com.rafambn.kmpvpn.dns.DNSProvider

abstract class AbstractPlatformService<I : VpnAddress>(private val interfacePrefix: String) : BasePlatformService<I>() {
    private var defaultGatewayPeer: VpnPeer? = null

    init {
        beforeStart()
        onInit()
    }

    protected open fun beforeStart() {
    }

    protected open fun onInit() {
    }

    override fun setNat(iface: String, nat: NATMode?) {
        if (nat != null) {
            throw UnsupportedOperationException("Only routed supported on this platform.")
        }
    }

    override fun getNat(iface: String): NATMode? {
        return null
    }

    override fun isIpForwardingEnabledOnSystem(): Boolean {
        return true
    }

    override fun setIpForwardingEnabledOnSystem(ipForwarding: Boolean) {
        throw UnsupportedOperationException()
    }

    final override fun defaultGatewayPeer(): VpnPeer? {
        return defaultGatewayPeer
    }

    final override fun defaultGatewayPeer(peer: VpnPeer) {
        val gw = defaultGateway()
            ?: throw IllegalStateException("No default gateway interface is currently set, so cannot set {0} to be it's new address.")
        resetDefaultGatewayPeer()
        defaultGatewayPeer = peer
        val peerAddr = peer.endpointAddress ?: throw IllegalArgumentException("Peer has no address.")
        onSetDefaultGateway(PlatformService.Gateway(gw.nativeIface, peerAddr))
    }

    final override fun resetDefaultGatewayPeer() {
        if (defaultGatewayPeer != null) {
            val gwOr = defaultGateway()
            val addr = defaultGatewayPeer?.endpointAddress ?: throw IllegalArgumentException("Peer has no address.")
            defaultGatewayPeer = null
            if (gwOr?.address == addr) {
                onResetDefaultGateway(gwOr)
            }
        }
    }

    override fun dns(): DNSProvider? {
        return null
    }

    final override fun defaultGateway(iface: PlatformService.Gateway?) {
        defaultGateway()?.let(this::onResetDefaultGateway)
        iface?.let(this::onSetDefaultGateway)
    }

    protected abstract fun onResetDefaultGateway(gateway: PlatformService.Gateway)

    protected abstract fun onSetDefaultGateway(gateway: PlatformService.Gateway)

    protected fun configureExistingSession(ip: I): VpnAdapter {
        return VpnAdapter(this, ip)
    }

    protected open fun getPublicKey(interfaceName: String?): String? {
        throw UnsupportedOperationException("Failed to get public key for $interfaceName")
    }

    override fun isValidNativeInterfaceName(name: String): Boolean {
        return name.matches(Regex("^utun[0-9]+$"))
    }

    final override fun address(name: String): I {
        return find(name, addresses()) ?: throw IllegalArgumentException("No address $name")
    }

    companion object {
        protected val MAX_INTERFACES: Int = 250
    }
}
