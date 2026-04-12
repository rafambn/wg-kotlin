package com.rafambn.kmpvpn.daemon

import com.rafambn.kmpvpn.daemon.protocol.response.ReadInterfaceInformationResponse

internal object DaemonInterfaceInformationParser {
    private val mtuRegex = Regex("""\bmtu\s+(\d+)\b""", RegexOption.IGNORE_CASE)
    private val windowsMtuRegex = Regex("""\bMtu(?:Size)?\s*[:=]\s*(\d+)\b""", RegexOption.IGNORE_CASE)
    private val macFlagsRegex = Regex("""flags=\d+<([^>]+)>""", RegexOption.IGNORE_CASE)
    private val angleBracketFlagsRegex = Regex("""<([^>]+)>""")
    private val inetRegex = Regex("""\binet6?\s+([0-9A-Fa-f:.]+(?:/\d+)?)""")
    private val windowsAdminStateRegex = Regex("""Administrative state:\s+Enabled""", RegexOption.IGNORE_CASE)
    private val windowsConnectStateRegex = Regex("""Connect state:\s+Connected""", RegexOption.IGNORE_CASE)
    private val windowsEnabledRegex = Regex("""\bEnabled\b""", RegexOption.IGNORE_CASE)
    private val windowsConnectedRegex = Regex("""\bConnected\b""", RegexOption.IGNORE_CASE)
    private val windowsDisabledRegex = Regex("""\bDisabled\b""", RegexOption.IGNORE_CASE)
    private val windowsDisconnectedRegex = Regex("""\bDisconnected\b""", RegexOption.IGNORE_CASE)
    private val downRegex = Regex("""\bDOWN\b""", RegexOption.IGNORE_CASE)

    fun parse(
        platformId: String,
        interfaceName: String,
        dump: String,
    ): ReadInterfaceInformationResponse? {
        if (dump.isBlank()) {
            return null
        }

        val isUp = inferIsUp(dump) ?: return null
        return ReadInterfaceInformationResponse(
            interfaceName = interfaceName,
            isUp = isUp,
            addresses = inferAddresses(platformId = platformId, dump = dump),
            dnsDomainPool = (emptyList<String>() to emptyList()),
            mtu = inferMtu(dump),
            listenPort = null,
        )
    }

    private fun inferIsUp(dump: String): Boolean? {
        val macFlags = macFlagsRegex.find(dump)?.groupValues?.getOrNull(1)
        if (macFlags != null) {
            return macFlags.split(',').any { flag -> flag.trim().equals("UP", ignoreCase = true) }
        }

        angleBracketFlagsRegex.find(dump)?.groupValues?.getOrNull(1)?.let { flags ->
            val parsedFlags = flags.split(',').map { flag -> flag.trim() }
            if (parsedFlags.any { flag -> flag.equals("UP", ignoreCase = true) }) {
                return true
            }
            if (parsedFlags.any { flag -> flag.equals("DOWN", ignoreCase = true) }) {
                return false
            }
        }

        if (windowsAdminStateRegex.containsMatchIn(dump) ||
            windowsConnectStateRegex.containsMatchIn(dump) ||
            (windowsEnabledRegex.containsMatchIn(dump) && windowsConnectedRegex.containsMatchIn(dump))
        ) {
            return true
        }

        if (windowsDisabledRegex.containsMatchIn(dump) ||
            windowsDisconnectedRegex.containsMatchIn(dump) ||
            downRegex.containsMatchIn(dump)
        ) {
            return false
        }

        return null
    }

    private fun inferAddresses(platformId: String, dump: String): List<String> {
        return if (platformId.equals("macos", ignoreCase = true)) {
            inferMacOsAddresses(dump)
        } else {
            inferCidrAddresses(dump)
        }
    }

    private fun inferCidrAddresses(dump: String): List<String> {
        return dump.lineSequence()
            .mapNotNull { line ->
                inetRegex.find(line)?.groupValues?.getOrNull(1)
                    ?.substringBefore("%")
                    ?.takeIf { value -> value.isNotBlank() && '/' in value }
            }
            .distinct()
            .toList()
    }

    private fun inferMacOsAddresses(dump: String): List<String> {
        return dump.lineSequence()
            .mapNotNull { line -> parseMacOsAddressLine(line) }
            .distinct()
            .toList()
    }

    private fun parseMacOsAddressLine(line: String): String? {
        val tokens = line.trim().split(Regex("""\s+"""))
        if (tokens.size < 2) {
            return null
        }

        return when (tokens[0]) {
            "inet" -> parseMacOsIpv4Address(tokens)
            "inet6" -> parseMacOsIpv6Address(tokens)
            else -> null
        }
    }

    private fun parseMacOsIpv4Address(tokens: List<String>): String? {
        val address = tokens[1].substringBefore("%")
        if (address.isBlank()) {
            return null
        }
        if ('/' in address) {
            return address
        }

        val netmaskIndex = tokens.indexOf("netmask")
        val netmaskToken = tokens.getOrNull(netmaskIndex + 1) ?: return null
        val prefix = parseIpv4PrefixFromNetmask(netmaskToken) ?: return null
        return "$address/$prefix"
    }

    private fun parseMacOsIpv6Address(tokens: List<String>): String? {
        val rawAddress = tokens[1].substringBefore("%")
        if (rawAddress.isBlank()) {
            return null
        }
        if ('/' in rawAddress) {
            return rawAddress
        }

        val prefixIndex = tokens.indexOf("prefixlen")
        val prefix = tokens.getOrNull(prefixIndex + 1)?.toIntOrNull() ?: return null
        return "$rawAddress/$prefix"
    }

    private fun parseIpv4PrefixFromNetmask(netmask: String): Int? {
        return when {
            netmask.startsWith("0x") || netmask.startsWith("0X") -> {
                val parsed = netmask.removePrefix("0x").removePrefix("0X").toLongOrNull(radix = 16) ?: return null
                java.lang.Long.bitCount(parsed).takeIf { count -> count in 0..32 }
            }

            '.' in netmask -> {
                val octets = netmask.split('.')
                if (octets.size != 4) {
                    return null
                }
                val values = octets.map { octet -> octet.toIntOrNull() ?: return null }
                if (values.any { value -> value !in 0..255 }) {
                    return null
                }
                values.sumOf { value -> Integer.bitCount(value) }
            }

            else -> null
        }
    }

    private fun inferMtu(dump: String): Int? {
        return mtuRegex.find(dump)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: windowsMtuRegex.find(dump)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
}
