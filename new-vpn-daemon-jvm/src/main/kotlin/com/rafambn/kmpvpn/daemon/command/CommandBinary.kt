package com.rafambn.kmpvpn.daemon.command

internal enum class CommandBinary(val executable: String) {
    IP("ip"),
    RESOLVECTL("resolvectl"),
    IFCONFIG("ifconfig"),
    ROUTE("route"),
    SCUTIL("scutil"),
    NETSH("netsh"),
    POWERSHELL("powershell"),
}
