package com.rafambn.kmpvpn.iface

internal object DaemonInterfaceInformationParser {
    private val mtuRegex = Regex("""\bmtu\s+(\d+)\b""", RegexOption.IGNORE_CASE)
    private val macFlagsRegex = Regex("""flags=\d+<([^>]+)>""", RegexOption.IGNORE_CASE)
    private val angleBracketFlagsRegex = Regex("""<([^>]+)>""")
    private val inetRegex = Regex("""\binet6?\s+([0-9A-Fa-f:.]+(?:/\d+)?)""")
    private val windowsAdminStateRegex = Regex("""Administrative state:\s+Enabled""", RegexOption.IGNORE_CASE)
    private val windowsConnectStateRegex = Regex("""Connect state:\s+Connected""", RegexOption.IGNORE_CASE)
    private val windowsConnectedRegex = Regex("""\bConnected\b""", RegexOption.IGNORE_CASE)
    private val downRegex = Regex("""\bDOWN\b""", RegexOption.IGNORE_CASE)

    fun parse(
        interfaceName: String,
        dump: String,
    ): VpnInterfaceInformation? {
        if (dump.isBlank()) {
            return null
        }

        val isUp = inferIsUp(dump) ?: return null
        return VpnInterfaceInformation(
            interfaceName = interfaceName,
            isUp = isUp,
            addresses = inferAddresses(dump),
            dnsDomainPool = (emptyList<String>() to emptyList()),
            mtu = mtuRegex.find(dump)?.groupValues?.getOrNull(1)?.toIntOrNull(),
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
            windowsConnectedRegex.containsMatchIn(dump)
        ) {
            return true
        }
        if (downRegex.containsMatchIn(dump)) {
            return false
        }

        return null
    }

    private fun inferAddresses(dump: String): List<String> {
        return dump.lineSequence()
            .mapNotNull { line ->
                inetRegex.find(line)?.groupValues?.getOrNull(1)
                    ?.substringBefore("%")
                    ?.takeIf { value -> value.isNotBlank() && '/' in value }
            }
            .distinct()
            .toList()
    }
}
