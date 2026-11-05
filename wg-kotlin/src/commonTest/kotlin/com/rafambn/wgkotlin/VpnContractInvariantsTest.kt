package com.rafambn.wgkotlin

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class VpnContractInvariantsTest {

    private val privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs="
    private val peerKey = "6fX3drXr/7L0KleChX2NDSSSXWMQZnIcXtNCmieYw0I="

    @Test
    fun rejectsBlankInterfaceName() {
        assertFailsWith<IllegalArgumentException> {
            testVpn(interfaceName = " ")
        }
    }

    @Test
    fun rejectsDuplicatePeerPublicKeysOnOpen() = runTest {
        val duplicatedPeers = listOf(
            VpnPeer(publicKey = "peer-a", endpointAddress = "198.51.100.1", endpointPort = 51820),
            VpnPeer(publicKey = "peer-a", endpointAddress = "198.51.100.2", endpointPort = 51821),
        )

        val vpn = testVpn(interfaceName = "utun110")

        assertFailsWith<IllegalArgumentException> {
            vpn.open(
                VpnConfiguration(
                    interfaceName = "utun110",
                    privateKey = privateKey,
                    peers = duplicatedPeers,
                ),
            )
        }
    }

    @Test
    fun openRejectsMismatchedInterfaceName() = runTest {
        val vpn = testVpn(interfaceName = "utun112")

        assertFailsWith<IllegalArgumentException> {
            vpn.open(
                VpnConfiguration(
                    interfaceName = "utun113",
                    privateKey = privateKey,
                ),
            )
        }
    }
}
