package com.rafambn.kmpvpn.iface

internal object JvmInterfaceProperties {
    const val INTERFACE_MODE: String = "kmpvpn.platform.interface.mode"
    const val INTERFACE_MODE_IN_MEMORY: String = "in-memory"
    const val INTERFACE_MODE_PRODUCTION: String = "production"

    const val DAEMON_HOST: String = "kmpvpn.daemon.host"
    const val DAEMON_PORT: String = "kmpvpn.daemon.port"
    const val DAEMON_TIMEOUT_MILLIS: String = "kmpvpn.daemon.timeoutMillis"
}
