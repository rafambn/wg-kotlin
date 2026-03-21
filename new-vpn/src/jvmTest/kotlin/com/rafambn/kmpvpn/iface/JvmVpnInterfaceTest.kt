package com.rafambn.kmpvpn.iface

import com.rafambn.kmpvpn.DefaultVpnConfiguration
import com.rafambn.kmpvpn.VpnAdapterConfiguration
import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.VpnPeer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmVpnInterfaceTest {

    @Test
    fun createAndDeleteRemainIdempotent() {
        val executor = InMemoryInterfaceCommandExecutor()
        val vpnInterface = JvmVpnInterface(commandExecutor = executor)
        val config = configuration(
            interfaceName = "wg0",
            dns = listOf("1.1.1.1"),
            addresses = listOf("10.0.0.1/32"),
            peers = listOf(VpnPeer(publicKey = "peer-a", allowedIps = listOf("0.0.0.0/0"))),
        )

        vpnInterface.create(config)
        vpnInterface.create(config)
        vpnInterface.up()
        vpnInterface.up()
        vpnInterface.down()
        vpnInterface.down()
        vpnInterface.delete()
        vpnInterface.delete()

        assertEquals(1, executor.callLog.count { call -> call == "createInterface:wg0" })
        assertEquals(1, executor.callLog.count { call -> call == "setInterfaceUp:wg0:true" })
        assertEquals(1, executor.callLog.count { call -> call == "setInterfaceUp:wg0:false" })
        assertEquals(1, executor.callLog.count { call -> call == "deleteInterface:wg0" })
        assertFalse(vpnInterface.exists("wg0"))
    }

    @Test
    fun reconfigureRollsBackOnPeerApplyFailure() {
        val delegate = InMemoryInterfaceCommandExecutor()
        val executor = FailureInjectingExecutor(delegate)
        val vpnInterface = JvmVpnInterface(commandExecutor = executor)

        val baseConfiguration = configuration(
            interfaceName = "wg1",
            dns = listOf("1.1.1.1"),
            addresses = listOf("10.10.0.1/32"),
            peers = listOf(VpnPeer(publicKey = "peer-a", allowedIps = listOf("10.200.0.0/24"))),
        )
        val updatedConfiguration = configuration(
            interfaceName = "wg1",
            dns = listOf("9.9.9.9"),
            addresses = listOf("10.10.0.2/32"),
            peers = listOf(VpnPeer(publicKey = "peer-b", allowedIps = listOf("10.201.0.0/24"))),
        )

        vpnInterface.create(baseConfiguration)

        executor.failOnPeerConfiguration = true
        assertFailsWith<IllegalStateException> {
            vpnInterface.reconfigure(updatedConfiguration)
        }

        val current = vpnInterface.configuration()
        assertEquals(baseConfiguration.dns, current.dns)
        assertEquals(baseConfiguration.addresses, current.addresses)
        assertEquals(baseConfiguration.adapter.peers, current.adapter.peers)

        val info = vpnInterface.readInformation()
        assertEquals(baseConfiguration.dns, info.dnsServers)
        assertEquals(baseConfiguration.addresses, info.addresses)

        val dnsOperations = delegate.callLog.filter { call -> call.startsWith("applyDns:wg1:") }
        assertTrue(dnsOperations.any { call -> call.contains("9.9.9.9") })
        assertTrue(dnsOperations.last().contains("1.1.1.1"))
    }

    @Test
    fun readInformationIncludesExecutorPeerStats() {
        val executor = InMemoryInterfaceCommandExecutor()
        val vpnInterface = JvmVpnInterface(commandExecutor = executor)
        val config = configuration(
            interfaceName = "wg2",
            peers = listOf(
                VpnPeer(publicKey = "peer-a"),
                VpnPeer(publicKey = "peer-b"),
            ),
        )

        vpnInterface.create(config)
        executor.setPeerStats(
            interfaceName = "wg2",
            peerStats = listOf(
                VpnPeerStats(
                    publicKey = "peer-a",
                    receivedBytes = 1024L,
                    transmittedBytes = 2048L,
                    lastHandshakeEpochSeconds = 1_700_000_000L,
                ),
            ),
        )

        val information = vpnInterface.readInformation()

        assertEquals(1, information.peerStats.size)
        assertEquals("peer-a", information.peerStats.single().publicKey)
        assertEquals(1024L, information.peerStats.single().receivedBytes)
    }

    private fun configuration(
        interfaceName: String,
        dns: List<String> = emptyList(),
        addresses: List<String> = emptyList(),
        peers: List<VpnPeer> = emptyList(),
    ): VpnConfiguration {
        return DefaultVpnConfiguration(
            interfaceName = interfaceName,
            dns = dns.toMutableList(),
            addresses = addresses.toMutableList(),
            privateKey = LOCAL_PRIVATE_KEY,
            publicKey = LOCAL_PUBLIC_KEY,
            peers = peers,
        )
    }

    private class FailureInjectingExecutor(
        private val delegate: InMemoryInterfaceCommandExecutor,
    ) : InterfaceCommandExecutor by delegate {
        var failOnPeerConfiguration: Boolean = false

        override fun applyPeerConfiguration(interfaceName: String, adapter: VpnAdapterConfiguration) {
            if (failOnPeerConfiguration) {
                failOnPeerConfiguration = false
                throw IllegalStateException("forced peer configuration failure")
            }
            delegate.applyPeerConfiguration(interfaceName, adapter)
        }
    }

    private companion object {
        const val LOCAL_PRIVATE_KEY = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs="
        const val LOCAL_PUBLIC_KEY = "V6w5nNq2WEYLRh3SeDsICoZ6irMIXja+6JGZveHFk/Q="
    }
}
