package com.rafambn.kmpvpn.platform

import com.rafambn.kmpvpn.StartRequest
import com.rafambn.kmpvpn.VpnAdapter
import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.address.VpnAddress

/**
 * JVM implementation of WireGuard Go platform service
 * Delegates to OS-specific implementations
 */
actual fun createWireGuardGoPlatformService(): PlatformService<*> {
    return when (Platform.currentOS) {
        OperatingSystem.LINUX -> LinuxWireGuardGoService()
        OperatingSystem.MACOS -> MacosWireGuardGoService()
        OperatingSystem.WINDOWS -> WindowsWireGuardGoService()
        else -> throw UnsupportedOperationException("WireGuard Go not supported on ${Platform.currentOS}")
    }
}

/**
 * JVM implementation of BoringTun platform service
 * Delegates to OS-specific implementations
 */
actual fun createBoringTunPlatformService(): PlatformService<*> {
    return when (Platform.currentOS) {
        OperatingSystem.LINUX -> LinuxBoringTunService()
        OperatingSystem.MACOS -> MacosBoringTunService()
        OperatingSystem.WINDOWS -> WindowsBoringTunService()
        else -> throw UnsupportedOperationException("BoringTun not supported on ${Platform.currentOS}")
    }
}

// ==================== WireGuard Go Implementations ====================

private class LinuxWireGuardGoService : PlatformService<VpnAddress> {
    override fun adapter(name: String): VpnAdapter {
        throw UnsupportedOperationException("Placeholder: Implement Linux WireGuard Go")
    }

    override fun getByPublicKey(publicKey: String): VpnAdapter? {
        throw UnsupportedOperationException("Placeholder: Implement Linux WireGuard Go")
    }

    override fun start(request: StartRequest): VpnAdapter {
        throw UnsupportedOperationException("Placeholder: Implement Linux WireGuard Go")
    }

    override fun stop(configuration: VpnConfiguration, adapter: VpnAdapter) {
        throw UnsupportedOperationException("Placeholder: Implement Linux WireGuard Go")
    }

    override fun interfaceNameToNativeName(interfaceName: String): String? {
        throw UnsupportedOperationException("Placeholder: Implement Linux WireGuard Go")
    }
}

private class MacosWireGuardGoService : PlatformService<VpnAddress> {
    override fun adapter(name: String): VpnAdapter {
        throw UnsupportedOperationException("Placeholder: Implement macOS WireGuard Go")
    }

    override fun getByPublicKey(publicKey: String): VpnAdapter? {
        throw UnsupportedOperationException("Placeholder: Implement macOS WireGuard Go")
    }

    override fun start(request: StartRequest): VpnAdapter {
        throw UnsupportedOperationException("Placeholder: Implement macOS WireGuard Go")
    }

    override fun stop(configuration: VpnConfiguration, adapter: VpnAdapter) {
        throw UnsupportedOperationException("Placeholder: Implement macOS WireGuard Go")
    }

    override fun interfaceNameToNativeName(interfaceName: String): String? {
        throw UnsupportedOperationException("Placeholder: Implement macOS WireGuard Go")
    }
}

private class WindowsWireGuardGoService : PlatformService<VpnAddress> {
    override fun adapter(name: String): VpnAdapter {
        throw UnsupportedOperationException("Placeholder: Implement Windows WireGuard Go")
    }

    override fun getByPublicKey(publicKey: String): VpnAdapter? {
        throw UnsupportedOperationException("Placeholder: Implement Windows WireGuard Go")
    }

    override fun start(request: StartRequest): VpnAdapter {
        throw UnsupportedOperationException("Placeholder: Implement Windows WireGuard Go")
    }

    override fun stop(configuration: VpnConfiguration, adapter: VpnAdapter) {
        throw UnsupportedOperationException("Placeholder: Implement Windows WireGuard Go")
    }

    override fun interfaceNameToNativeName(interfaceName: String): String? {
        throw UnsupportedOperationException("Placeholder: Implement Windows WireGuard Go")
    }
}

// ==================== BoringTun Implementations ====================

private class LinuxBoringTunService : PlatformService<VpnAddress> {
    override fun adapter(name: String): VpnAdapter {
        throw UnsupportedOperationException("Placeholder: Implement Linux BoringTun")
    }

    override fun getByPublicKey(publicKey: String): VpnAdapter? {
        throw UnsupportedOperationException("Placeholder: Implement Linux BoringTun")
    }

    override fun start(request: StartRequest): VpnAdapter {
        throw UnsupportedOperationException("Placeholder: Implement Linux BoringTun")
    }

    override fun stop(configuration: VpnConfiguration, adapter: VpnAdapter) {
        throw UnsupportedOperationException("Placeholder: Implement Linux BoringTun")
    }

    override fun interfaceNameToNativeName(interfaceName: String): String? {
        throw UnsupportedOperationException("Placeholder: Implement Linux BoringTun")
    }
}

private class MacosBoringTunService : PlatformService<VpnAddress> {
    override fun adapter(name: String): VpnAdapter {
        throw UnsupportedOperationException("Placeholder: Implement macOS BoringTun")
    }

    override fun getByPublicKey(publicKey: String): VpnAdapter? {
        throw UnsupportedOperationException("Placeholder: Implement macOS BoringTun")
    }

    override fun start(request: StartRequest): VpnAdapter {
        throw UnsupportedOperationException("Placeholder: Implement macOS BoringTun")
    }

    override fun stop(configuration: VpnConfiguration, adapter: VpnAdapter) {
        throw UnsupportedOperationException("Placeholder: Implement macOS BoringTun")
    }

    override fun interfaceNameToNativeName(interfaceName: String): String? {
        throw UnsupportedOperationException("Placeholder: Implement macOS BoringTun")
    }
}

private class WindowsBoringTunService : PlatformService<VpnAddress> {
    override fun adapter(name: String): VpnAdapter {
        throw UnsupportedOperationException("Placeholder: Implement Windows BoringTun")
    }

    override fun getByPublicKey(publicKey: String): VpnAdapter? {
        throw UnsupportedOperationException("Placeholder: Implement Windows BoringTun")
    }

    override fun start(request: StartRequest): VpnAdapter {
        throw UnsupportedOperationException("Placeholder: Implement Windows BoringTun")
    }

    override fun stop(configuration: VpnConfiguration, adapter: VpnAdapter) {
        throw UnsupportedOperationException("Placeholder: Implement Windows BoringTun")
    }

    override fun interfaceNameToNativeName(interfaceName: String): String? {
        throw UnsupportedOperationException("Placeholder: Implement Windows BoringTun")
    }
}
