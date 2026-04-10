package com.rafambn.kmpvpn.iface

import com.rafambn.kmpvpn.VpnConfiguration

actual object PlatformInterfaceFactory {
    actual fun create(configuration: VpnConfiguration): InterfaceManager {
        return when (
            System.getProperty(
                JvmPlatformProperties.INTERFACE_MODE,
                JvmPlatformProperties.INTERFACE_MODE_PRODUCTION,
            ).lowercase()
        ) {
            JvmPlatformProperties.INTERFACE_MODE_IN_MEMORY -> JvmInterfaceManager(
                interfaceName = configuration.interfaceName,
                commandExecutor = InMemoryInterfaceCommandExecutor(),
                tunProvider = InMemoryTunProvider(),
            )
            else -> JvmInterfaceManager(
                interfaceName = configuration.interfaceName,
                commandExecutor = DaemonBackedInterfaceCommandExecutor(),
                tunProvider = InMemoryTunProvider(),
            )
        }
    }
}
