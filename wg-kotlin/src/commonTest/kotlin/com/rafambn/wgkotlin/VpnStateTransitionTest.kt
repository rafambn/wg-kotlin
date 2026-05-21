package com.rafambn.wgkotlin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VpnStateTransitionTest {

    private val privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs="
    private val peerKey = "6fX3drXr/7L0KleChX2NDSSSXWMQZnIcXtNCmieYw0I="

    @Test
    fun lifecycleTransitionsFollowContract() {
        val vpn = testVpn(interfaceName = "utun130")

        assertFalse(vpn.isRunning())

        vpn.open(baseConfiguration(interfaceName = "utun130"))
        assertTrue(vpn.isRunning())

        vpn.stop()
        assertFalse(vpn.isRunning())
    }

    // TODO: These tests require dependency injection of internal components.
    // The Vpn class no longer supports this. Tests need to be refactored to work
    // with the new design or use integration tests instead.
    /*
    @Test
    fun failedStartLeavesVpnStopped() {
        val vpn = testVpn(
            interfaceName = "utun131",
            interfaceManager = FailingStartInterfaceManager(),
        )

        assertFailsWith<IllegalStateException> {
            vpn.open(baseConfiguration(interfaceName = "utun131"))
        }

        assertFalse(vpn.isRunning())
    }

    @Test
    fun stopContinuesCleanupWhenInterfaceStopFails() {
        val configuration = baseConfiguration(interfaceName = "utun132")
        val socketManager = RecordingSocketManager()
        val cryptoSessionManager = RecordingCryptoSessionManager()
        val vpn = testVpn(
            interfaceName = "utun132",
            cryptoSessionManager = cryptoSessionManager,
            socketManager = socketManager,
            interfaceManager = StopFailingInterfaceManager(configuration),
        )

        assertFailsWith<IllegalStateException> {
            vpn.stop()
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
}
