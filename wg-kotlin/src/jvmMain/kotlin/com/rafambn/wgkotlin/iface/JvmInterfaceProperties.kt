package com.rafambn.wgkotlin.iface

internal object JvmInterfaceProperties {
    const val INTERFACE_MODE: String = "wgkotlin.platform.interface.mode"
    const val INTERFACE_MODE_IN_MEMORY: String = "in-memory"
    const val INTERFACE_MODE_PRODUCTION: String = "production"

    const val DAEMON_HOST: String = "wgkotlin.daemon.host"
    const val DAEMON_PORT: String = "wgkotlin.daemon.port"
    const val DEFAULT_DAEMON_HOST: String = "127.0.0.1"
    const val DEFAULT_DAEMON_PORT: Int = 8787
}
