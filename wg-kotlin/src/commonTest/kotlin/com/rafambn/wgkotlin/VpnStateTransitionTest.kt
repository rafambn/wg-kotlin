package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.iface.InterfaceManager
import com.rafambn.wgkotlin.iface.VpnInterfaceInformation
import com.rafambn.wgkotlin.crypto.VpnPeerStats
import com.rafambn.wgkotlin.crypto.CryptoSessionManager
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import com.rafambn.wgkotlin.network.SocketManager
import com.rafambn.wgkotlin.network.io.UdpDatagram
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VpnStateTransitionTest {

    private val privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs="
    private val peerKey = "6fX3drXr/7L0KleChX2NDSSSXWMQZnIcXtNCmieYw0I="

    @Test
    fun lifecycleTransitionsFollowContract() {
        val vpn = testVpn(configuration = baseConfiguration(interfaceName = "utun130"))

        assertFalse(vpn.isRunning())

        vpn.open()
        assertTrue(vpn.isRunning())

        vpn.close()
        assertFalse(vpn.isRunning())
    }

    // TODO: These tests require dependency injection of internal components.
    // The Vpn class no longer supports this. Tests need to be refactored to work
    // with the new design or use integration tests instead.
    /*
    @Test
    fun failedStartLeavesVpnStopped() {
        val vpn = testVpn(
            configuration = baseConfiguration(interfaceName = "utun131"),
            interfaceManager = FailingStartInterfaceManager(),
        )

        assertFailsWith<IllegalStateException> {
            vpn.open()
        }

        assertFalse(vpn.isRunning())
    }

    @Test
    fun stopContinuesCleanupWhenInterfaceStopFails() {
        val configuration = baseConfiguration(interfaceName = "utun132")
        val socketManager = RecordingSocketManager()
        val cryptoSessionManager = RecordingCryptoSessionManager()
        val vpn = testVpn(
            configuration = configuration,
            cryptoSessionManager = cryptoSessionManager,
            socketManager = socketManager,
            interfaceManager = StopFailingInterfaceManager(configuration),
        )

        assertFailsWith<IllegalStateException> {
            vpn.close()
        }

        assertEquals(1, socketManager.stopCalls)
        assertEquals(1, cryptoSessionManager.stopCalls)
    }
    */

    private fun baseConfiguration(interfaceName: String): VpnConfiguration {
        return VpnConfiguration(
            interfaceName = interfaceName,
            listenPort = 0,
            privateKey = privateKey,
            peers = listOf(VpnPeer(publicKey = peerKey, endpointAddress = "198.51.100.1", endpointPort = 51820)),
        )
    }

    private class FailingStartInterfaceManager : InterfaceManager {
        override fun isRunning(): Boolean = false

        override fun start(config: VpnConfiguration, onFailure: (Throwable) -> Unit) {
            error("boom")
        }

        override fun stop() {}

        override fun reconfigure(config: VpnConfiguration) {}

        override fun information(): VpnInterfaceInformation? = null
    }

    private class StopFailingInterfaceManager(
        private val currentConfiguration: VpnConfiguration,
    ) : InterfaceManager {
        override fun isRunning(): Boolean = false

        override fun start(config: VpnConfiguration, onFailure: (Throwable) -> Unit) {}

        override fun stop() {
            error("stop failed")
        }

        override fun reconfigure(config: VpnConfiguration) {}

        override fun information(): VpnInterfaceInformation {
            return VpnInterfaceInformation(
                interfaceName = currentConfiguration.interfaceName,
                isUp = false,
                addresses = currentConfiguration.addresses,
                dns = currentConfiguration.dns,
                mtu = currentConfiguration.mtu,
                listenPort = currentConfiguration.listenPort,
            )
        }
    }

    private class RecordingSocketManager : SocketManager {
        var stopCalls: Int = 0

        override fun start(listenPort: Int, networkPipe: DuplexChannelPipe<UdpDatagram>, onFailure: (Throwable) -> Unit) {}

        override fun stop() {
            stopCalls++
        }

        override fun isRunning(): Boolean = false
    }

    private class RecordingCryptoSessionManager : CryptoSessionManager {
        var stopCalls: Int = 0

        override fun reconcileSessions(config: VpnConfiguration) {}

        override fun start(
            tunPipe: DuplexChannelPipe<ByteArray>,
            networkPipe: DuplexChannelPipe<UdpDatagram>,
            onFailure: (Throwable) -> Unit,
        ) {}

        override fun stop() {
            stopCalls++
        }

        override fun hasActiveSessions(): Boolean = false

        override fun peerStats(): List<VpnPeerStats> = emptyList()
    }
}
