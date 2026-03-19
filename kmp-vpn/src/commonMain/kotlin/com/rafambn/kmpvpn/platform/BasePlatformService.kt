package com.rafambn.kmpvpn.platform

import com.rafambn.kmpvpn.VpnAdapter
import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.address.VpnAddress
import com.rafambn.kmpvpn.dns.DNSProvider
import com.rafambn.kmpvpn.util.IpUtil


abstract class BasePlatformService<I : VpnAddress> : PlatformService<I> {

    override fun stop(configuration: VpnConfiguration, session: VpnAdapter) {
        try {
            try {
                if (!configuration.addresses.isEmpty()) {
                    val dnsOr: DNSProvider? = dns()
                    dnsOr?.unset(
                        DNSProvider.DNSEntry(
                            iface = session.address().nativeName(),
                            ipv4Servers = IpUtil.filterIpV4Addresses(configuration.dns),
                            ipv6Servers = IpUtil.filterIpV6Addresses(configuration.dns),
                            domains = IpUtil.filterNames(configuration.dns)
                        )
                    )
                }
            } finally {
                if (!configuration.preDown.isEmpty()) {
                    val p: MutableList<String> = configuration.preDown
                    runHook(configuration, session, *p.toTypedArray())
                }
                session.stop()
            }
        } finally {
            try {
                onStop(configuration, session)
            } finally {
                if (!configuration.postDown.isEmpty()) {
                    val p: MutableList<String> = configuration.postDown
                    runHook(configuration, session, *p.toTypedArray())
                }
            }
        }
    }

    override fun adapter(interfaceName: String): VpnAdapter {
        return findAdapter(interfaceName, adapters()) ?: throw IllegalArgumentException("No adapter $interfaceName")
    }

    protected open fun onStop(configuration: VpnConfiguration, session: VpnAdapter) {
    }

    protected fun exists(interfaceName: String?, links: Iterable<I>): Boolean {
        return try {
            find(interfaceName, links) != null
        } catch (iae: IllegalArgumentException) {
            false
        }
    }

    protected fun find(interfaceName: String?, links: Iterable<I>): I? {
        for (link in links) if (interfaceName == link.nativeName()) return link
        return null
    }

    protected fun findAdapter(interfaceName: String?, links: Iterable<VpnAdapter>): VpnAdapter? {
        for (link in links) if (interfaceName == link.address().nativeName()) return link
        return null
    }
}
