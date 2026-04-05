package com.rafambn.kmpvpn.daemon

import io.netty.util.NetUtil

internal object DaemonPayloadValidator {
    private const val MIN_MTU = 576
    private const val MAX_MTU = 65535
    private const val MAX_DNS_DOMAINS = 64
    private const val MAX_DNS_SERVERS = 64
    private const val MAX_ADDRESSES = 64
    private const val MAX_ROUTES = 256
    private const val MAX_ENDPOINT_LENGTH = 253
    private const val MAX_CIDR_LENGTH = 64
    private const val MAX_DOMAIN_LENGTH = 253
    private val MTU_RANGE: IntRange = MIN_MTU..MAX_MTU
    private val INTERFACE_NAME_REGEX = Regex("^utun(?:[0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$")
    private val HOSTNAME_REGEX =
        Regex("^(?=.{1,253}$)([a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)(\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$")

    fun validateInterfaceName(interfaceName: String) {
        if (!INTERFACE_NAME_REGEX.matches(interfaceName)) {
            throw PayloadValidationException("Interface name must match `${INTERFACE_NAME_REGEX.pattern}`.")
        }
    }

    fun validateMtu(mtu: Int) {
        if (mtu !in MTU_RANGE) {
            throw PayloadValidationException("MTU must be between ${MTU_RANGE.first} and ${MTU_RANGE.last}")
        }
    }

    fun validateDnsDomainPool(dnsDomainPool: Pair<List<String>, List<String>>) {
        val domains = dnsDomainPool.first
            .map { domain -> domain.trim().removePrefix(".") }
        val dnsServers = dnsDomainPool.second
            .map { dnsServer -> dnsServer.trim() }

        validateMaxCount(fieldName = "dnsDomainPool.domains", count = domains.size, max = MAX_DNS_DOMAINS)
        validateMaxCount(fieldName = "dnsDomainPool.dnsServers", count = dnsServers.size, max = MAX_DNS_SERVERS)

        if ((domains.isEmpty() && dnsServers.isNotEmpty()) || (domains.isNotEmpty() && dnsServers.isEmpty())) {
            throw PayloadValidationException("dnsDomainPool must provide both domains and dnsServers, or neither")
        }

        domains.forEachIndexed { index, normalizedDomain ->
            validateMaxLength(fieldName = "dnsDomainPool.domains[$index]", value = normalizedDomain, max = MAX_DOMAIN_LENGTH)
            if (normalizedDomain.isBlank() || !HOSTNAME_REGEX.matches(normalizedDomain)) {
                throw PayloadValidationException("dnsDomainPool.domains[$index] must be a valid hostname")
            }
        }

        dnsServers.forEachIndexed { index, normalizedDnsServer ->
            validateMaxLength(fieldName = "dnsDomainPool.dnsServers[$index]", value = normalizedDnsServer, max = MAX_ENDPOINT_LENGTH)
            val isValidIpLiteral =
                NetUtil.isValidIpV4Address(normalizedDnsServer) || NetUtil.isValidIpV6Address(normalizedDnsServer)
            if (!isValidIpLiteral) {
                throw PayloadValidationException("dnsDomainPool.dnsServers[$index] must be a valid IPv4 or IPv6 address")
            }
        }
    }

    fun validateAddresses(addresses: List<String>) {
        validateMaxCount(fieldName = "addresses", count = addresses.size, max = MAX_ADDRESSES)
        validateCidrs(fieldName = "addresses", values = addresses)
    }

    fun validateRoutes(routes: List<String>) {
        validateMaxCount(fieldName = "routes", count = routes.size, max = MAX_ROUTES)
        validateCidrs(fieldName = "routes", values = routes)
    }

    private fun validateCidrs(fieldName: String, values: List<String>) {
        values.forEachIndexed { index, cidr ->
            val normalizedCidr = cidr.trim()
            validateMaxLength(fieldName = "$fieldName[$index]", value = normalizedCidr, max = MAX_CIDR_LENGTH)
            val parts = normalizedCidr.split("/", limit = 2)
            if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty() || parts[1].contains('/')) {
                throw PayloadValidationException("$fieldName[$index] must use CIDR format")
            }

            val prefix = parts[1].toIntOrNull()
                ?: throw PayloadValidationException("$fieldName[$index] prefix must be numeric")

            val normalizedIp = parts[0].trim()
            val isIpV4 = NetUtil.isValidIpV4Address(normalizedIp)
            val isIpV6 = NetUtil.isValidIpV6Address(normalizedIp)
            if (!isIpV4 && !isIpV6) {
                throw PayloadValidationException("$fieldName[$index] has invalid IP address")
            }

            val maxPrefix = if (isIpV4) 32 else 128
            if (prefix !in 0..maxPrefix) {
                throw PayloadValidationException("$fieldName[$index] prefix must be between 0 and $maxPrefix")
            }
        }
    }

    private fun validateMaxCount(fieldName: String, count: Int, max: Int) {
        if (count > max) {
            throw PayloadValidationException("$fieldName supports at most $max entries (received: $count)")
        }
    }

    private fun validateMaxLength(fieldName: String, value: String, max: Int) {
        if (value.length > max) {
            throw PayloadValidationException("$fieldName must be at most $max characters")
        }
    }

}

internal class PayloadValidationException(
    message: String,
) : IllegalArgumentException(message)
