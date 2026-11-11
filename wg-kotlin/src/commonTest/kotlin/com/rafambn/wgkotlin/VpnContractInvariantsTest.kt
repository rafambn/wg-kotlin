package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.util.resolveToIp
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertIs

class VpnContractInvariantsTest {

    private val privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs="
    private val peerKey = "6fX3drXr/7L0KleChX2NDSSSXWMQZnIcXtNCmieYw0I="

    @Test
    fun rejectsBlankInterfaceName() = runBlocking<Unit> {
        val result = testVpn().start(VpnConfiguration(interfaceName = " ", privateKey = privateKey))
        assertIs<IllegalArgumentException>(result.exceptionOrNull())
    }

    @Test
    fun rejectsDuplicatePeerPublicKeysOnStart() = runBlocking<Unit> {
        val duplicatedPeers = listOf(
            VpnPeer(publicKey = "peer-a", endpointAddress = "198.51.100.1".resolveToIp(), endpointPort = 51820),
            VpnPeer(publicKey = "peer-a", endpointAddress = "198.51.100.2".resolveToIp(), endpointPort = 51821),
        )

        val vpn = testVpn()

        val result = vpn.start(
            VpnConfiguration(
                interfaceName = "utun110",
                privateKey = privateKey,
                peers = duplicatedPeers,
            ),
        )
        assertIs<IllegalArgumentException>(result.exceptionOrNull())
    }

    @Test
    fun startRejectsInvalidInterfaceName() = runBlocking<Unit> {
        val vpn = testVpn()

        val result = vpn.start(
            VpnConfiguration(
                interfaceName = "wg0",
                privateKey = privateKey,
            ),
        )
        assertIs<IllegalArgumentException>(result.exceptionOrNull())
    }
}
