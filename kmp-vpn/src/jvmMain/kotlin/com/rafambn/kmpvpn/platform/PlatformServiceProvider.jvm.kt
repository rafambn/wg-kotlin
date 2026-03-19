package com.rafambn.kmpvpn.platform

/**
 * JVM implementation of the QUIC platform service.
 * Delegates to OS-specific implementations.
 */
actual fun createQuicPlatformService(): PlatformService<*> {
    return when (Platform.currentOS) {
        OperatingSystem.LINUX -> LinuxQuicPlatformService()
        OperatingSystem.MACOS -> MacOsQuicPlatformService()
        OperatingSystem.WINDOWS -> WindowsQuicPlatformService()
        else -> throw UnsupportedOperationException("QUIC is not supported on ${Platform.currentOS}")
    }
}

/**
 * JVM implementation of the BoringTun platform service.
 * Delegates to OS-specific implementations.
 */
actual fun createBoringTunPlatformService(): PlatformService<*> {
    return when (Platform.currentOS) {
        OperatingSystem.LINUX -> LinuxBoringTunPlatformService()
        OperatingSystem.MACOS -> MacOsBoringTunPlatformService()
        OperatingSystem.WINDOWS -> WindowsBoringTunPlatformService()
        else -> throw UnsupportedOperationException("BoringTun is not supported on ${Platform.currentOS}")
    }
}
