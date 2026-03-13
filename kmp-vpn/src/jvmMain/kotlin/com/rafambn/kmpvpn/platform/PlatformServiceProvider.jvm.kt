package com.rafambn.kmpvpn.platform

import com.rafambn.kmpvpn.NATMode
import com.rafambn.kmpvpn.StartRequest
import com.rafambn.kmpvpn.VpnAdapter
import com.rafambn.kmpvpn.VpnAdapterConfiguration
import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.VpnPeer
import com.rafambn.kmpvpn.address.VpnAddress
import com.rafambn.kmpvpn.dns.DNSProvider
import com.rafambn.kmpvpn.info.VpnInterfaceInformation

/**
 * JVM implementation of WireGuard Go platform service
 * Delegates to OS-specific implementations
 */
actual fun createQuicPlatformService(): PlatformService<*> {
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

    override fun addresses(): MutableList<VpnAddress> {
        throw UnsupportedOperationException("Placeholder: Implement Linux WireGuard Go")
    }

    override fun adapters(): MutableList<VpnAdapter> {
        throw UnsupportedOperationException("Placeholder: Implement Linux WireGuard Go")
    }

    override fun runHook(configuration: VpnConfiguration, session: VpnAdapter, vararg hookScript: String) {
        throw UnsupportedOperationException("Placeholder: Implement Linux WireGuard Go")
    }

    override fun defaultGatewayPeer(): VpnPeer? {
        throw UnsupportedOperationException("Placeholder: Implement Linux WireGuard Go")
    }

    override fun defaultGatewayPeer(peer: VpnPeer) {
        throw UnsupportedOperationException("Placeholder: Implement Linux WireGuard Go")
    }

    override fun resetDefaultGatewayPeer() {
        throw UnsupportedOperationException("Placeholder: Implement Linux WireGuard Go")
    }

    override fun address(name: String): VpnAddress {
        throw UnsupportedOperationException("Placeholder: Implement Linux WireGuard Go")
    }

    override fun information(adapter: VpnAdapter): VpnInterfaceInformation {
        throw UnsupportedOperationException("Placeholder: Implement Linux WireGuard Go")
    }

    override fun configuration(adapter: VpnAdapter): VpnAdapterConfiguration {
        throw UnsupportedOperationException("Placeholder: Implement Linux WireGuard Go")
    }

    override fun dns(): DNSProvider? {
        throw UnsupportedOperationException("Placeholder: Implement Linux WireGuard Go")
    }

    override fun reconfigure(vpnAdapter: VpnAdapter, cfg: VpnAdapterConfiguration) {
        throw UnsupportedOperationException("Placeholder: Implement Linux WireGuard Go")
    }

    override fun sync(vpnAdapter: VpnAdapter, cfg: VpnAdapterConfiguration) {
        throw UnsupportedOperationException("Placeholder: Implement Linux WireGuard Go")
    }

    override fun append(vpnAdapter: VpnAdapter, cfg: VpnAdapterConfiguration) {
        throw UnsupportedOperationException("Placeholder: Implement Linux WireGuard Go")
    }

    override fun remove(vpnAdapter: VpnAdapter, publicKey: String) {
        throw UnsupportedOperationException("Placeholder: Implement Linux WireGuard Go")
    }

    override fun isValidNativeInterfaceName(name: String): Boolean {
        throw UnsupportedOperationException("Placeholder: Implement Linux WireGuard Go")
    }

    override fun isIpForwardingEnabledOnSystem(): Boolean {
        throw UnsupportedOperationException("Placeholder: Implement Linux WireGuard Go")
    }

    override fun setIpForwardingEnabledOnSystem(ipForwarding: Boolean) {
        throw UnsupportedOperationException("Placeholder: Implement Linux WireGuard Go")
    }

    override fun setNat(iface: String, nat: NATMode?) {
        throw UnsupportedOperationException("Placeholder: Implement Linux WireGuard Go")
    }

    override fun getNat(iface: String): NATMode? {
        throw UnsupportedOperationException("Placeholder: Implement Linux WireGuard Go")
    }

    override fun defaultGateway(): PlatformService.Gateway? {
        throw UnsupportedOperationException("Placeholder: Implement Linux WireGuard Go")
    }

    override fun defaultGateway(iface: PlatformService.Gateway?) {
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

    override fun addresses(): MutableList<VpnAddress> {
        throw UnsupportedOperationException("Placeholder: Implement macOS WireGuard Go")
    }

    override fun adapters(): MutableList<VpnAdapter> {
        throw UnsupportedOperationException("Placeholder: Implement macOS WireGuard Go")
    }

    override fun runHook(configuration: VpnConfiguration, session: VpnAdapter, vararg hookScript: String) {
        throw UnsupportedOperationException("Placeholder: Implement macOS WireGuard Go")
    }

    override fun defaultGatewayPeer(): VpnPeer? {
        throw UnsupportedOperationException("Placeholder: Implement macOS WireGuard Go")
    }

    override fun defaultGatewayPeer(peer: VpnPeer) {
        throw UnsupportedOperationException("Placeholder: Implement macOS WireGuard Go")
    }

    override fun resetDefaultGatewayPeer() {
        throw UnsupportedOperationException("Placeholder: Implement macOS WireGuard Go")
    }

    override fun address(name: String): VpnAddress {
        throw UnsupportedOperationException("Placeholder: Implement macOS WireGuard Go")
    }

    override fun information(adapter: VpnAdapter): VpnInterfaceInformation {
        throw UnsupportedOperationException("Placeholder: Implement macOS WireGuard Go")
    }

    override fun configuration(adapter: VpnAdapter): VpnAdapterConfiguration {
        throw UnsupportedOperationException("Placeholder: Implement macOS WireGuard Go")
    }

    override fun dns(): DNSProvider? {
        throw UnsupportedOperationException("Placeholder: Implement macOS WireGuard Go")
    }

    override fun reconfigure(vpnAdapter: VpnAdapter, cfg: VpnAdapterConfiguration) {
        throw UnsupportedOperationException("Placeholder: Implement macOS WireGuard Go")
    }

    override fun sync(vpnAdapter: VpnAdapter, cfg: VpnAdapterConfiguration) {
        throw UnsupportedOperationException("Placeholder: Implement macOS WireGuard Go")
    }

    override fun append(vpnAdapter: VpnAdapter, cfg: VpnAdapterConfiguration) {
        throw UnsupportedOperationException("Placeholder: Implement macOS WireGuard Go")
    }

    override fun remove(vpnAdapter: VpnAdapter, publicKey: String) {
        throw UnsupportedOperationException("Placeholder: Implement macOS WireGuard Go")
    }

    override fun isValidNativeInterfaceName(name: String): Boolean {
        throw UnsupportedOperationException("Placeholder: Implement macOS WireGuard Go")
    }

    override fun isIpForwardingEnabledOnSystem(): Boolean {
        throw UnsupportedOperationException("Placeholder: Implement macOS WireGuard Go")
    }

    override fun setIpForwardingEnabledOnSystem(ipForwarding: Boolean) {
        throw UnsupportedOperationException("Placeholder: Implement macOS WireGuard Go")
    }

    override fun setNat(iface: String, nat: NATMode?) {
        throw UnsupportedOperationException("Placeholder: Implement macOS WireGuard Go")
    }

    override fun getNat(iface: String): NATMode? {
        throw UnsupportedOperationException("Placeholder: Implement macOS WireGuard Go")
    }

    override fun defaultGateway(): PlatformService.Gateway? {
        throw UnsupportedOperationException("Placeholder: Implement macOS WireGuard Go")
    }

    override fun defaultGateway(iface: PlatformService.Gateway?) {
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

    override fun addresses(): MutableList<VpnAddress> {
        throw UnsupportedOperationException("Placeholder: Implement Windows WireGuard Go")
    }

    override fun adapters(): MutableList<VpnAdapter> {
        throw UnsupportedOperationException("Placeholder: Implement Windows WireGuard Go")
    }

    override fun runHook(configuration: VpnConfiguration, session: VpnAdapter, vararg hookScript: String) {
        throw UnsupportedOperationException("Placeholder: Implement Windows WireGuard Go")
    }

    override fun defaultGatewayPeer(): VpnPeer? {
        throw UnsupportedOperationException("Placeholder: Implement Windows WireGuard Go")
    }

    override fun defaultGatewayPeer(peer: VpnPeer) {
        throw UnsupportedOperationException("Placeholder: Implement Windows WireGuard Go")
    }

    override fun resetDefaultGatewayPeer() {
        throw UnsupportedOperationException("Placeholder: Implement Windows WireGuard Go")
    }

    override fun address(name: String): VpnAddress {
        throw UnsupportedOperationException("Placeholder: Implement Windows WireGuard Go")
    }

    override fun information(adapter: VpnAdapter): VpnInterfaceInformation {
        throw UnsupportedOperationException("Placeholder: Implement Windows WireGuard Go")
    }

    override fun configuration(adapter: VpnAdapter): VpnAdapterConfiguration {
        throw UnsupportedOperationException("Placeholder: Implement Windows WireGuard Go")
    }

    override fun dns(): DNSProvider? {
        throw UnsupportedOperationException("Placeholder: Implement Windows WireGuard Go")
    }

    override fun reconfigure(vpnAdapter: VpnAdapter, cfg: VpnAdapterConfiguration) {
        throw UnsupportedOperationException("Placeholder: Implement Windows WireGuard Go")
    }

    override fun sync(vpnAdapter: VpnAdapter, cfg: VpnAdapterConfiguration) {
        throw UnsupportedOperationException("Placeholder: Implement Windows WireGuard Go")
    }

    override fun append(vpnAdapter: VpnAdapter, cfg: VpnAdapterConfiguration) {
        throw UnsupportedOperationException("Placeholder: Implement Windows WireGuard Go")
    }

    override fun remove(vpnAdapter: VpnAdapter, publicKey: String) {
        throw UnsupportedOperationException("Placeholder: Implement Windows WireGuard Go")
    }

    override fun isValidNativeInterfaceName(name: String): Boolean {
        throw UnsupportedOperationException("Placeholder: Implement Windows WireGuard Go")
    }

    override fun isIpForwardingEnabledOnSystem(): Boolean {
        throw UnsupportedOperationException("Placeholder: Implement Windows WireGuard Go")
    }

    override fun setIpForwardingEnabledOnSystem(ipForwarding: Boolean) {
        throw UnsupportedOperationException("Placeholder: Implement Windows WireGuard Go")
    }

    override fun setNat(iface: String, nat: NATMode?) {
        throw UnsupportedOperationException("Placeholder: Implement Windows WireGuard Go")
    }

    override fun getNat(iface: String): NATMode? {
        throw UnsupportedOperationException("Placeholder: Implement Windows WireGuard Go")
    }

    override fun defaultGateway(): PlatformService.Gateway? {
        throw UnsupportedOperationException("Placeholder: Implement Windows WireGuard Go")
    }

    override fun defaultGateway(iface: PlatformService.Gateway?) {
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

    override fun addresses(): MutableList<VpnAddress> {
        throw UnsupportedOperationException("Placeholder: Implement Linux BoringTun")
    }

    override fun adapters(): MutableList<VpnAdapter> {
        throw UnsupportedOperationException("Placeholder: Implement Linux BoringTun")
    }

    override fun runHook(configuration: VpnConfiguration, session: VpnAdapter, vararg hookScript: String) {
        throw UnsupportedOperationException("Placeholder: Implement Linux BoringTun")
    }

    override fun defaultGatewayPeer(): VpnPeer? {
        throw UnsupportedOperationException("Placeholder: Implement Linux BoringTun")
    }

    override fun defaultGatewayPeer(peer: VpnPeer) {
        throw UnsupportedOperationException("Placeholder: Implement Linux BoringTun")
    }

    override fun resetDefaultGatewayPeer() {
        throw UnsupportedOperationException("Placeholder: Implement Linux BoringTun")
    }

    override fun address(name: String): VpnAddress {
        throw UnsupportedOperationException("Placeholder: Implement Linux BoringTun")
    }

    override fun information(adapter: VpnAdapter): VpnInterfaceInformation {
        throw UnsupportedOperationException("Placeholder: Implement Linux BoringTun")
    }

    override fun configuration(adapter: VpnAdapter): VpnAdapterConfiguration {
        throw UnsupportedOperationException("Placeholder: Implement Linux BoringTun")
    }

    override fun dns(): DNSProvider? {
        throw UnsupportedOperationException("Placeholder: Implement Linux BoringTun")
    }

    override fun reconfigure(vpnAdapter: VpnAdapter, cfg: VpnAdapterConfiguration) {
        throw UnsupportedOperationException("Placeholder: Implement Linux BoringTun")
    }

    override fun sync(vpnAdapter: VpnAdapter, cfg: VpnAdapterConfiguration) {
        throw UnsupportedOperationException("Placeholder: Implement Linux BoringTun")
    }

    override fun append(vpnAdapter: VpnAdapter, cfg: VpnAdapterConfiguration) {
        throw UnsupportedOperationException("Placeholder: Implement Linux BoringTun")
    }

    override fun remove(vpnAdapter: VpnAdapter, publicKey: String) {
        throw UnsupportedOperationException("Placeholder: Implement Linux BoringTun")
    }

    override fun isValidNativeInterfaceName(name: String): Boolean {
        throw UnsupportedOperationException("Placeholder: Implement Linux BoringTun")
    }

    override fun isIpForwardingEnabledOnSystem(): Boolean {
        throw UnsupportedOperationException("Placeholder: Implement Linux BoringTun")
    }

    override fun setIpForwardingEnabledOnSystem(ipForwarding: Boolean) {
        throw UnsupportedOperationException("Placeholder: Implement Linux BoringTun")
    }

    override fun setNat(iface: String, nat: NATMode?) {
        throw UnsupportedOperationException("Placeholder: Implement Linux BoringTun")
    }

    override fun getNat(iface: String): NATMode? {
        throw UnsupportedOperationException("Placeholder: Implement Linux BoringTun")
    }

    override fun defaultGateway(): PlatformService.Gateway? {
        throw UnsupportedOperationException("Placeholder: Implement Linux BoringTun")
    }

    override fun defaultGateway(iface: PlatformService.Gateway?) {
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

    override fun addresses(): MutableList<VpnAddress> {
        throw UnsupportedOperationException("Placeholder: Implement macOS BoringTun")
    }

    override fun adapters(): MutableList<VpnAdapter> {
        throw UnsupportedOperationException("Placeholder: Implement macOS BoringTun")
    }

    override fun runHook(configuration: VpnConfiguration, session: VpnAdapter, vararg hookScript: String) {
        throw UnsupportedOperationException("Placeholder: Implement macOS BoringTun")
    }

    override fun defaultGatewayPeer(): VpnPeer? {
        throw UnsupportedOperationException("Placeholder: Implement macOS BoringTun")
    }

    override fun defaultGatewayPeer(peer: VpnPeer) {
        throw UnsupportedOperationException("Placeholder: Implement macOS BoringTun")
    }

    override fun resetDefaultGatewayPeer() {
        throw UnsupportedOperationException("Placeholder: Implement macOS BoringTun")
    }

    override fun address(name: String): VpnAddress {
        throw UnsupportedOperationException("Placeholder: Implement macOS BoringTun")
    }

    override fun information(adapter: VpnAdapter): VpnInterfaceInformation {
        throw UnsupportedOperationException("Placeholder: Implement macOS BoringTun")
    }

    override fun configuration(adapter: VpnAdapter): VpnAdapterConfiguration {
        throw UnsupportedOperationException("Placeholder: Implement macOS BoringTun")
    }

    override fun dns(): DNSProvider? {
        throw UnsupportedOperationException("Placeholder: Implement macOS BoringTun")
    }

    override fun reconfigure(vpnAdapter: VpnAdapter, cfg: VpnAdapterConfiguration) {
        throw UnsupportedOperationException("Placeholder: Implement macOS BoringTun")
    }

    override fun sync(vpnAdapter: VpnAdapter, cfg: VpnAdapterConfiguration) {
        throw UnsupportedOperationException("Placeholder: Implement macOS BoringTun")
    }

    override fun append(vpnAdapter: VpnAdapter, cfg: VpnAdapterConfiguration) {
        throw UnsupportedOperationException("Placeholder: Implement macOS BoringTun")
    }

    override fun remove(vpnAdapter: VpnAdapter, publicKey: String) {
        throw UnsupportedOperationException("Placeholder: Implement macOS BoringTun")
    }

    override fun isValidNativeInterfaceName(name: String): Boolean {
        throw UnsupportedOperationException("Placeholder: Implement macOS BoringTun")
    }

    override fun isIpForwardingEnabledOnSystem(): Boolean {
        throw UnsupportedOperationException("Placeholder: Implement macOS BoringTun")
    }

    override fun setIpForwardingEnabledOnSystem(ipForwarding: Boolean) {
        throw UnsupportedOperationException("Placeholder: Implement macOS BoringTun")
    }

    override fun setNat(iface: String, nat: NATMode?) {
        throw UnsupportedOperationException("Placeholder: Implement macOS BoringTun")
    }

    override fun getNat(iface: String): NATMode? {
        throw UnsupportedOperationException("Placeholder: Implement macOS BoringTun")
    }

    override fun defaultGateway(): PlatformService.Gateway? {
        throw UnsupportedOperationException("Placeholder: Implement macOS BoringTun")
    }

    override fun defaultGateway(iface: PlatformService.Gateway?) {
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

    override fun addresses(): MutableList<VpnAddress> {
        throw UnsupportedOperationException("Placeholder: Implement Windows BoringTun")
    }

    override fun adapters(): MutableList<VpnAdapter> {
        throw UnsupportedOperationException("Placeholder: Implement Windows BoringTun")
    }

    override fun runHook(configuration: VpnConfiguration, session: VpnAdapter, vararg hookScript: String) {
        throw UnsupportedOperationException("Placeholder: Implement Windows BoringTun")
    }

    override fun defaultGatewayPeer(): VpnPeer? {
        throw UnsupportedOperationException("Placeholder: Implement Windows BoringTun")
    }

    override fun defaultGatewayPeer(peer: VpnPeer) {
        throw UnsupportedOperationException("Placeholder: Implement Windows BoringTun")
    }

    override fun resetDefaultGatewayPeer() {
        throw UnsupportedOperationException("Placeholder: Implement Windows BoringTun")
    }

    override fun address(name: String): VpnAddress {
        throw UnsupportedOperationException("Placeholder: Implement Windows BoringTun")
    }

    override fun information(adapter: VpnAdapter): VpnInterfaceInformation {
        throw UnsupportedOperationException("Placeholder: Implement Windows BoringTun")
    }

    override fun configuration(adapter: VpnAdapter): VpnAdapterConfiguration {
        throw UnsupportedOperationException("Placeholder: Implement Windows BoringTun")
    }

    override fun dns(): DNSProvider? {
        throw UnsupportedOperationException("Placeholder: Implement Windows BoringTun")
    }

    override fun reconfigure(vpnAdapter: VpnAdapter, cfg: VpnAdapterConfiguration) {
        throw UnsupportedOperationException("Placeholder: Implement Windows BoringTun")
    }

    override fun sync(vpnAdapter: VpnAdapter, cfg: VpnAdapterConfiguration) {
        throw UnsupportedOperationException("Placeholder: Implement Windows BoringTun")
    }

    override fun append(vpnAdapter: VpnAdapter, cfg: VpnAdapterConfiguration) {
        throw UnsupportedOperationException("Placeholder: Implement Windows BoringTun")
    }

    override fun remove(vpnAdapter: VpnAdapter, publicKey: String) {
        throw UnsupportedOperationException("Placeholder: Implement Windows BoringTun")
    }

    override fun isValidNativeInterfaceName(name: String): Boolean {
        throw UnsupportedOperationException("Placeholder: Implement Windows BoringTun")
    }

    override fun isIpForwardingEnabledOnSystem(): Boolean {
        throw UnsupportedOperationException("Placeholder: Implement Windows BoringTun")
    }

    override fun setIpForwardingEnabledOnSystem(ipForwarding: Boolean) {
        throw UnsupportedOperationException("Placeholder: Implement Windows BoringTun")
    }

    override fun setNat(iface: String, nat: NATMode?) {
        throw UnsupportedOperationException("Placeholder: Implement Windows BoringTun")
    }

    override fun getNat(iface: String): NATMode? {
        throw UnsupportedOperationException("Placeholder: Implement Windows BoringTun")
    }

    override fun defaultGateway(): PlatformService.Gateway? {
        throw UnsupportedOperationException("Placeholder: Implement Windows BoringTun")
    }

    override fun defaultGateway(iface: PlatformService.Gateway?) {
        throw UnsupportedOperationException("Placeholder: Implement Windows BoringTun")
    }
}
