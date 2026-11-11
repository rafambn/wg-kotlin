package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.daemon.proto.Cidr
import com.rafambn.wgkotlin.daemon.proto.Ip
import com.rafambn.wgkotlin.daemon.proto.invoke
import com.rafambn.wgkotlin.util.resolveToIp
import com.rafambn.wgkotlin.util.toCidr
import kotlinx.coroutines.runBlocking
import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

    @Test
    fun invalidAllowedNetworksAreRejectedOnStart() = runBlocking {
        val vpn = testVpn()
        for ((address, prefix) in listOf(
            "192.0.2.1" to 33u, "2001:db8::1" to 129u,
            "192.0.2.1" to 4294967288u, "2001:db8::1" to UInt.MAX_VALUE,
        )) {
            val route = Cidr { ip = address.resolveToIp(); this.prefix = prefix }
            val peer = VpnPeer(
                publicKey = peerKey, endpointAddress = "192.0.2.1".resolveToIp(), endpointPort = 51820,
                allowedIps = listOf(route),
            )
            val result = vpn.start(VpnConfiguration(
                interfaceName = "utun110", privateKey = privateKey, peers = listOf(peer),
            ))
            assertIs<IllegalArgumentException>(result.exceptionOrNull(), "$address/$prefix")
        }
    }

    @Test
    fun missingOrMalformedPeerEndpointsAreRejectedOnStart() = runBlocking {
        val vpn = testVpn()
        val peer = VpnPeer(publicKey = peerKey, endpointAddress = "192.0.2.1".resolveToIp(), endpointPort = 51820)
        val peers = listOf(
            peer.copy(endpointAddress = null),
            peer.copy(endpointPort = null),
            peer.copy(endpointAddress = Ip { }),
            peer.copy(endpointAddress = Ip { value = Ip.Value.V4(ByteString(ByteArray(3))) }),
            peer.copy(endpointAddress = Ip { value = Ip.Value.V6(ByteString(ByteArray(15))) }),
        )
        for (invalidPeer in peers) {
            val result = vpn.start(VpnConfiguration(
                interfaceName = "utun110", privateKey = privateKey, peers = listOf(invalidPeer),
            ))
            assertIs<IllegalArgumentException>(result.exceptionOrNull())
        }
    }

    @Test
    fun conflictingAllowedIpOwnershipIsRejectedOnStart() = runBlocking {
        val peer = VpnPeer(
            publicKey = peerKey, endpointAddress = "192.0.2.1".resolveToIp(), endpointPort = 51820,
            allowedIps = listOf("10.0.0.0/24".toCidr()),
        )
        val result = testVpn().start(VpnConfiguration(
            interfaceName = "utun110", privateKey = privateKey,
            peers = listOf(peer, peer.copy(publicKey = "another-peer")),
        ))
        val error = assertIs<IllegalStateException>(result.exceptionOrNull())
        assertTrue(error.message.orEmpty().contains("overlaps between"))
    }
}
