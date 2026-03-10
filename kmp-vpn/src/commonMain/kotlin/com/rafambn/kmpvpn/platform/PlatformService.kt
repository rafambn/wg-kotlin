package com.rafambn.kmpvpn.platform

import com.rafambn.kmpvpn.NATMode
import com.rafambn.kmpvpn.VpnAdapter
import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.StartRequest
import com.rafambn.kmpvpn.VpnAdapterConfiguration
import com.rafambn.kmpvpn.VpnPeer
import com.rafambn.kmpvpn.address.VpnAddress
import com.rafambn.kmpvpn.dns.DNSProvider
import com.rafambn.kmpvpn.info.VpnInterfaceInformation
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
interface PlatformService<ADDRESS : VpnAddress> {
    data class Gateway(
         val nativeIface: String,
        val address: String
    )

    fun stop(configuration: VpnConfiguration, session: VpnAdapter)

    fun start(startRequest: StartRequest): VpnAdapter

    fun getByPublicKey(publicKey: String): VpnAdapter? {
        for (ip in adapters()) {
            if (ip.address().isUp()) {
                if (publicKey == ip.information().publicKey()) {
                    return ip
                }
            }
        }
        return null
    }

    fun addresses(): MutableList<ADDRESS>

    fun adapters(): MutableList<VpnAdapter>

    fun runHook(configuration: VpnConfiguration, session: VpnAdapter, vararg hookScript: String)

    fun defaultGatewayPeer(): VpnPeer?

    fun defaultGatewayPeer(peer: VpnPeer)

    fun resetDefaultGatewayPeer()

    fun address(name: String): ADDRESS

    fun addressExists(nativeName: String): Boolean {
        for (addr in addresses()) {
            if (addr.name() == nativeName) return true
        }
        return false
    }

    fun adapter(nativeName: String): VpnAdapter

    fun adapterExists(nativeName: String): Boolean {
        for (addr in adapters()) {
            if (addr.address().nativeName() == nativeName) return true
        }
        return false
    }

    fun getLatestHandshake(address: VpnAddress, publicKey: String): Instant {
        return adapter(address.nativeName()).latestHandshake(publicKey)
    }

    fun information(adapter: VpnAdapter): VpnInterfaceInformation

    fun configuration(adapter: VpnAdapter): VpnAdapterConfiguration

    fun dns(): DNSProvider?

    fun reconfigure(vpnAdapter: VpnAdapter, cfg: VpnAdapterConfiguration)

    fun sync(vpnAdapter: VpnAdapter, cfg: VpnAdapterConfiguration)

    fun append(vpnAdapter: VpnAdapter, cfg: VpnAdapterConfiguration)

    fun remove(vpnAdapter: VpnAdapter, publicKey: String)

    fun isValidNativeInterfaceName(name: String): Boolean

    fun isIpForwardingEnabledOnSystem(): Boolean

    fun setIpForwardingEnabledOnSystem(ipForwarding: Boolean)

    fun setNat(iface: String, nat: NATMode?)

    fun getNat(iface: String): NATMode?

    fun defaultGateway(): Gateway?

    fun defaultGateway(iface: Gateway?)
}
