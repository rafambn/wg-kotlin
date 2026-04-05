package com.rafambn.kmpvpn.iface

import com.rafambn.kmpvpn.VpnConfiguration

/**
 * JVM actual platform factory.
 *
 * Phase 07 will replace the in-memory providers with real daemon-backed control
 * execution and native tun implementations.
 */
actual object PlatformInterfaceFactory {
    actual fun create(configuration: VpnConfiguration): VpnInterface {
        return JvmVpnInterface(
            commandExecutor = InMemoryInterfaceCommandExecutor(),
            tunProvider = InMemoryTunProvider(),
        )
    }
}
