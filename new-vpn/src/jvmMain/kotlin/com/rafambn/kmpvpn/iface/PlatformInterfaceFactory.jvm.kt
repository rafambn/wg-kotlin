package com.rafambn.kmpvpn.iface

import com.rafambn.kmpvpn.VpnConfiguration

/**
 * JVM actual platform factory.
 *
 * Phase 05 will replace the in-memory executor with daemon-backed command execution.
 */
actual object PlatformInterfaceFactory {
    actual fun create(configuration: VpnConfiguration): VpnInterface {
        return JvmVpnInterface(
            commandExecutor = InMemoryInterfaceCommandExecutor(),
        )
    }
}
