package com.rafambn.kmpvpn.iface

import com.rafambn.kmpvpn.DefaultVpnConfiguration
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
        val tunProvider = InMemoryTunProvider()
        val vpnInterface = JvmVpnInterface(
            commandExecutor = executor,
            tunProvider = tunProvider,
        )
        val config = configuration(
            interfaceName = "wg0",
            dnsDomainPool = (listOf("corp.local") to listOf("1.1.1.1")),
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

        assertEquals(1, executor.callLog.count { call -> call == "setInterfaceUp:wg0:true" })
        assertEquals(1, executor.callLog.count { call -> call == "setInterfaceUp:wg0:false" })
        assertEquals(1, tunProvider.callLog.count { call -> call == "openTun:wg0" })
        assertEquals(1, tunProvider.callLog.count { call -> call == "closeTun:wg0" })
        assertFalse(vpnInterface.exists("wg0"))
    }

    @Test
    fun reconfigureRollsBackOnDnsApplyFailure() {
        val delegate = InMemoryInterfaceCommandExecutor()
        val tunProvider = InMemoryTunProvider()
        val executor = FailureInjectingExecutor(delegate)
        val vpnInterface = JvmVpnInterface(
            commandExecutor = executor,
            tunProvider = tunProvider,
        )

        val baseConfiguration = configuration(
            interfaceName = "wg1",
            dnsDomainPool = (listOf("corp.local") to listOf("1.1.1.1")),
            addresses = listOf("10.10.0.1/32"),
            peers = listOf(VpnPeer(publicKey = "peer-a", allowedIps = listOf("10.200.0.0/24"))),
        )
        val updatedConfiguration = configuration(
            interfaceName = "wg1",
            dnsDomainPool = (listOf("corp.local") to listOf("9.9.9.9")),
            addresses = listOf("10.10.0.2/32"),
            peers = listOf(VpnPeer(publicKey = "peer-b", allowedIps = listOf("10.201.0.0/24"))),
        )

        vpnInterface.create(baseConfiguration)

        executor.failOnDns = true
        assertFailsWith<IllegalStateException> {
            vpnInterface.reconfigure(updatedConfiguration)
        }

        val current = vpnInterface.configuration()
        assertEquals(baseConfiguration.dnsDomainPool, current.dnsDomainPool)
        assertEquals(baseConfiguration.addresses, current.addresses)
        assertEquals(baseConfiguration.adapter.peers, current.adapter.peers)

        val info = vpnInterface.readInformation()
        assertEquals(baseConfiguration.dnsDomainPool, info.dnsDomainPool)
        assertEquals(baseConfiguration.addresses, info.addresses)

        val dnsOperations = delegate.callLog.filter { call -> call.startsWith("applyDns:wg1:") }
        assertTrue(dnsOperations.any { call -> call.contains("domains=corp.local") && call.contains("dns=9.9.9.9") })
        assertTrue(dnsOperations.last().contains("domains=corp.local") && dnsOperations.last().contains("dns=1.1.1.1"))
    }

    @Test
    fun readInformationIncludesExecutorPeerStats() {
        val executor = InMemoryInterfaceCommandExecutor()
        val vpnInterface = JvmVpnInterface(
            commandExecutor = executor,
            tunProvider = InMemoryTunProvider(),
        )
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
        dnsDomainPool: Pair<List<String>, List<String>> = (emptyList<String>() to emptyList()),
        addresses: List<String> = emptyList(),
        peers: List<VpnPeer> = emptyList(),
    ): VpnConfiguration {
        return DefaultVpnConfiguration(
            interfaceName = interfaceName,
            dnsDomainPool = dnsDomainPool,
            addresses = addresses.toMutableList(),
            privateKey = LOCAL_PRIVATE_KEY,
            publicKey = LOCAL_PUBLIC_KEY,
            peers = peers,
        )
    }

    private class FailureInjectingExecutor(
        private val delegate: InMemoryInterfaceCommandExecutor,
    ) : InterfaceCommandExecutor by delegate {
        var failOnDns: Boolean = false

        override fun applyDns(interfaceName: String, dnsDomainPool: Pair<List<String>, List<String>>) {
            if (failOnDns) {
                failOnDns = false
                delegate.applyDns(interfaceName, dnsDomainPool)
                throw IllegalStateException("forced dns failure")
            }
            delegate.applyDns(interfaceName, dnsDomainPool)
        }
    }

    private companion object {
        const val LOCAL_PRIVATE_KEY = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs="
        const val LOCAL_PUBLIC_KEY = "V6w5nNq2WEYLRh3SeDsICoZ6irMIXja+6JGZveHFk/Q="
    }
}
