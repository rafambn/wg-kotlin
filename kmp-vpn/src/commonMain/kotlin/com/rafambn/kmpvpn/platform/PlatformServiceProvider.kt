package com.rafambn.kmpvpn.platform

import com.rafambn.kmpvpn.Engine

/**
 * Create a WireGuard Go platform service
 */
expect fun createWireGuardGoPlatformService(): PlatformService<*>

/**
 * Create a BoringTun platform service
 */
expect fun createBoringTunPlatformService(): PlatformService<*>

/**
 * Create a platform service based on the engine type
 */
fun createPlatformService(engine: Engine): PlatformService<*> {
    return when (engine) {
        Engine.WG_GO -> createWireGuardGoPlatformService()
        Engine.BORINGTUN -> createBoringTunPlatformService()
    }
}
