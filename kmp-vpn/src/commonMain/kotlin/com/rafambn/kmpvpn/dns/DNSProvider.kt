package com.rafambn.kmpvpn.dns

import com.rafambn.kmpvpn.platform.PlatformService
import kotlin.reflect.KClass

interface DNSProvider {
    enum class Mode {
        AUTO, PREFER_SPLIT, PREFER_OVERRIDE, REQUIRE_SPLIT, REQUIRE_OVERRIDE
    }

    interface Factory { //TODO add possibility for user to choose
        fun <P : DNSProvider> available(): List<KClass<P>>

        fun create(clazz: KClass<out DNSProvider>): DNSProvider?
    }

    data class DNSEntry(
        val iface: String,
        val ipv4Servers: List<String> = emptyList(),
        val ipv6Servers: List<String> = emptyList(),
        val domains: List<String>  = emptyList(),
    ) {

        fun servers(): List<String> {
            val all = mutableListOf<String>()
            all.addAll(ipv4Servers)
            all.addAll(ipv6Servers)
            return all
        }

        fun empty(): Boolean {
            return ipv4Servers.isEmpty() && ipv6Servers.isEmpty()
        }

        fun all(): List<String> {
            val all = servers().toMutableList()
            all.addAll(domains)
            return all
        }
    }

    fun init(platform: PlatformService<*>?)

    fun entries(): MutableList<DNSEntry>

    fun entry(iface: String?): DNSEntry? {
        for (e in entries()) {
            if (e.iface == iface) {
                return e
            }
        }
        return null
    }

    fun set(entry: DNSEntry?)

    fun unset(entry: DNSEntry?)

    fun unset(iface: String?) {
        unset(entry(iface) ?: throw IllegalArgumentException("No DNS set for interface $iface"))
    }
}
