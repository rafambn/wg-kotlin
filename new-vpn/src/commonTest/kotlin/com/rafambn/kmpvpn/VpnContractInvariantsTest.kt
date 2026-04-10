package com.rafambn.kmpvpn

import kotlin.test.Test
import kotlin.test.assertFailsWith

class VpnContractInvariantsTest {

    private val privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs="
    private val publicKey = "V6w5nNq2WEYLRh3SeDsICoZ6irMIXja+6JGZveHFk/Q="
    private val peerKey = "6fX3drXr/7L0KleChX2NDSSSXWMQZnIcXtNCmieYw0I="

    @Test
    fun rejectsBlankInterfaceName() {
        assertFailsWith<IllegalArgumentException> {
            Vpn.create(
                vpnConfiguration = DefaultVpnConfiguration(
                    interfaceName = " ",
                    privateKey = privateKey,
                    publicKey = publicKey,
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
            Vpn.create(
                vpnConfiguration = DefaultVpnConfiguration(
                    interfaceName = "wg0",
                    privateKey = privateKey,
                    publicKey = publicKey,
                    peers = duplicatedPeers,
                ),
            )
        }
    }

    @Test
    fun reconfigureRejectsDuplicatedPeerPublicKeys() {
        val vpn = Vpn.create(
            vpnConfiguration = DefaultVpnConfiguration(
                interfaceName = "wg0",
                privateKey = privateKey,
                publicKey = publicKey,
                peers = listOf(VpnPeer(publicKey = peerKey, endpointAddress = "198.51.100.1", endpointPort = 51820)),
            ),
        )

        vpn.create()

        assertFailsWith<IllegalArgumentException> {
            vpn.reconfigure(
                DefaultVpnConfiguration(
                    interfaceName = "wg0",
                    privateKey = privateKey,
                    publicKey = publicKey,
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
        val vpn = Vpn.create(
            vpnConfiguration = DefaultVpnConfiguration(
                interfaceName = "wg0",
                privateKey = privateKey,
                publicKey = publicKey,
            ),
        )

        vpn.create()

        assertFailsWith<IllegalArgumentException> {
            vpn.reconfigure(
                DefaultVpnConfiguration(
                    interfaceName = "wg1",
                    privateKey = privateKey,
                    publicKey = publicKey,
                ),
            )
        }
    }
}
