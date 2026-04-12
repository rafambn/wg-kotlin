package com.rafambn.kmpvpn

import kotlin.test.Test
import kotlin.test.assertFailsWith

class VpnContractInvariantsTest {

    private val privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs="
    private val peerKey = "6fX3drXr/7L0KleChX2NDSSSXWMQZnIcXtNCmieYw0I="

    @Test
    fun rejectsBlankInterfaceName() {
        assertFailsWith<IllegalArgumentException> {
            testVpn(
                configuration = VpnConfiguration(
                    interfaceName = " ",
                    privateKey = privateKey,
                ),
            )
        }
    }

    @Test
    fun rejectsDuplicatePeerPublicKeysOnConstruction() {
        val duplicatedPeers = listOf(
            VpnPeer(publicKey = "peer-a", endpointAddress = "198.51.100.1", endpointPort = 51820),
            VpnPeer(publicKey = "peer-a", endpointAddress = "198.51.100.2", endpointPort = 51821),
        )

        assertFailsWith<IllegalArgumentException> {
            testVpn(
                configuration = VpnConfiguration(
                    interfaceName = "utun110",
                    privateKey = privateKey,
                    peers = duplicatedPeers,
                ),
            )
        }
    }

    @Test
    fun reconfigureRejectsDuplicatedPeerPublicKeys() {
        val vpn = testVpn(
            configuration = VpnConfiguration(
                interfaceName = "utun111",
                privateKey = privateKey,
                peers = listOf(VpnPeer(publicKey = peerKey, endpointAddress = "198.51.100.1", endpointPort = 51820)),
            ),
        )

        vpn.create()

        assertFailsWith<IllegalArgumentException> {
            vpn.reconfigure(
                VpnConfiguration(
                    interfaceName = "utun111",
                    privateKey = privateKey,
                    peers = listOf(
                        VpnPeer(publicKey = peerKey, endpointAddress = "198.51.100.1", endpointPort = 51820),
                        VpnPeer(publicKey = peerKey, endpointAddress = "198.51.100.2", endpointPort = 51821),
                    ),
                ),
            )
        }
    }

    @Test
    fun reconfigureRejectsInterfaceNameChange() {
        val vpn = testVpn(
            configuration = VpnConfiguration(
                interfaceName = "utun112",
                privateKey = privateKey,
            ),
        )

        vpn.create()

        assertFailsWith<IllegalArgumentException> {
            vpn.reconfigure(
                VpnConfiguration(
                    interfaceName = "utun113",
                    privateKey = privateKey,
                ),
            )
        }
    }
}
