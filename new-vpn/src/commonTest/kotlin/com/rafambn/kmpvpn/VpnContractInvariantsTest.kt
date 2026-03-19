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
            Vpn(
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
            VpnPeer(publicKey = "peer-a"),
            VpnPeer(publicKey = "peer-a"),
        )

        assertFailsWith<IllegalArgumentException> {
            Vpn(
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
        val vpn = Vpn(
            vpnConfiguration = DefaultVpnConfiguration(
                interfaceName = "wg0",
                privateKey = privateKey,
                publicKey = publicKey,
                peers = listOf(VpnPeer(publicKey = peerKey)),
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
                        VpnPeer(publicKey = peerKey),
                        VpnPeer(publicKey = peerKey),
                    ),
                ),
            )
        }
    }

    @Test
    fun reconfigureRejectsInterfaceNameChange() {
        val vpn = Vpn(
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
