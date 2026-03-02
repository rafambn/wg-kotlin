package com.rafambn.kmpvpn.dns

import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.platform.PlatformService
import com.rafambn.kmpvpn.address.VpnAddress

/**
 * Resolves VPN interface names to native platform names
 */
class InterfaceNameResolver(private val platformService: PlatformService<*>) {

    fun resolve(
        configuration: VpnConfiguration,
        interfaceName: String?,
        nativeInterfaceName: String?
    ): ResolvedInterface {
        // Placeholder: Resolve interface name using platform service
        val resolved = interfaceName?.let { name ->
            platformService.interfaceNameToNativeName(name)
        }

        return ResolvedInterface(resolved ?: nativeInterfaceName)
    }

    data class ResolvedInterface(val name: String?) {
        fun resolvedName(): String? = name
    }
}
