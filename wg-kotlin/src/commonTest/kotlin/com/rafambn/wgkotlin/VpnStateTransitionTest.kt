package com.rafambn.wgkotlin

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VpnStateTransitionTest {

    private val privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs="
    private val peerKey = "6fX3drXr/7L0KleChX2NDSSSXWMQZnIcXtNCmieYw0I="

    @Test
    fun lifecycleTransitionsFollowContract() = runTest {
        val vpn = testVpn(interfaceName = "utun130")

        assertFalse(vpn.isRunning())

        vpn.open(baseConfiguration(interfaceName = "utun130"))
        assertTrue(vpn.isRunning())

        vpn.stop()
        assertFalse(vpn.isRunning())
    }

    @Test
    fun failedDaemonStartLeavesVpnStopped() = runTest {
        val interfaceManager = TestInterfaceManager(
            startFailure = IllegalStateException("daemon rejected session"),
        )
        val vpn = testVpn(
            interfaceName = "utun131",
            interfaceManager = interfaceManager,
        )

        val failure = assertFailsWith<IllegalStateException> {
            vpn.open(baseConfiguration(interfaceName = "utun131"))
        }

        assertTrue(failure.message?.contains("daemon rejected session") == true)
        assertFalse(vpn.isRunning())
        assertFalse(interfaceManager.isRunning())
    }

    private fun baseConfiguration(interfaceName: String): VpnConfiguration {
        return VpnConfiguration(
            interfaceName = interfaceName,
            listenPort = 0,
            privateKey = privateKey,
            peers = listOf(VpnPeer(publicKey = peerKey, endpointAddress = "198.51.100.1", endpointPort = 51820)),
        )
    }
}
