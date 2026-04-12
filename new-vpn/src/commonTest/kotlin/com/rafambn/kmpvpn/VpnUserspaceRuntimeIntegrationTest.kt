package com.rafambn.kmpvpn

import com.rafambn.kmpvpn.iface.VpnInterfaceInformation
import com.rafambn.kmpvpn.iface.InterfaceManager
import com.rafambn.kmpvpn.iface.VpnPeerStats
import com.rafambn.kmpvpn.session.InMemoryTunnelManager
import com.rafambn.kmpvpn.session.UserspaceRuntimeHandle
import com.rafambn.kmpvpn.session.io.InMemoryTunPort
import com.rafambn.kmpvpn.session.io.TunPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VpnUserspaceRuntimeIntegrationTest {
    private val privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs="
    private val peerKey = "6fX3drXr/7L0KleChX2NDSSSXWMQZnIcXtNCmieYw0I="

    @Test
    fun startStopAndDeleteOwnUserspaceRuntimeLifecycle() {
        val runtimeFactory = RecordingRuntimeFactory()
        val interfaceManager = RecordingInterfaceManager()
        val vpn = vpn(
            configuration = configuration(interfaceName = "utun140", listenPort = 51820),
            interfaceManager = interfaceManager,
            runtimeFactory = runtimeFactory,
        )

        vpn.start()
        assertEquals(1, runtimeFactory.handles.size)
        assertTrue(runtimeFactory.handles.single().isRunning())
        assertEquals(VpnState.Running, vpn.state())

        vpn.stop()
        assertFalse(runtimeFactory.handles.single().isRunning())
        assertEquals(VpnState.Created, vpn.state())

        vpn.start()
        assertEquals(2, runtimeFactory.handles.size)
        assertTrue(runtimeFactory.handles.last().isRunning())

        vpn.delete()
        assertFalse(vpn.exists())
        assertFalse(runtimeFactory.handles.last().isRunning())
        assertEquals(VpnState.NotCreated, vpn.state())
    }

    @Test
    fun runtimeStartFailureRollsBackToCreatedState() {
        val runtimeFactory = RecordingRuntimeFactory(failOnCreate = true)
        val interfaceManager = RecordingInterfaceManager()
        val tunnelManager = InMemoryTunnelManager(userspaceRuntimeFactory = runtimeFactory::create)
        val vpn = Vpn(
            vpnConfiguration = configuration(interfaceName = "utun141"),
            tunnelManager = tunnelManager,
            interfaceManager = interfaceManager,
        )

        val failure = assertFailsWith<IllegalStateException> {
            vpn.start()
        }

        assertTrue(failure.message.orEmpty().contains("Session operation `startRuntime` failed"))
        assertEquals(1, interfaceManager.downCalls)
        assertEquals(0, tunnelManager.sessions().size)
        assertEquals(VpnState.Created, vpn.state())
    }

    @Test
    fun informationOverlaysRuntimePeerStats() {
        val expectedStats = listOf(
            VpnPeerStats(
                publicKey = peerKey,
                receivedBytes = 321L,
                transmittedBytes = 654L,
                lastHandshakeEpochSeconds = null,
            ),
        )
        val runtimeFactory = RecordingRuntimeFactory(stats = expectedStats)
        val vpn = vpn(
            configuration = configuration(interfaceName = "utun142"),
            runtimeFactory = runtimeFactory,
        )

        vpn.start()
        val information = requireNotNull(vpn.information())

        assertEquals(expectedStats, information.peerStats)
    }

    @Test
    fun informationIncludesDefinedConfigurationAlongsideObservedInterfaceState() {
        val definedConfiguration = configuration(
            interfaceName = "utun143",
            listenPort = 51820,
            addresses = listOf("10.20.30.2/32"),
        )
        val interfaceManager = DriftedInformationInterfaceManager(
            initialConfiguration = definedConfiguration,
            observedAddresses = listOf("10.88.0.1/32"),
            observedDnsDomainPool = listOf("os.local") to listOf("9.9.9.9"),
            observedMtu = 1280,
            observedListenPort = 60000,
        )
        val vpn = vpn(
            configuration = definedConfiguration,
            interfaceManager = interfaceManager,
        )

        vpn.create()
        definedConfiguration.addresses += "10.20.30.3/32"

        val information = requireNotNull(vpn.information())
        val configured = requireNotNull(information.vpnConfiguration)

        assertEquals(listOf("10.88.0.1/32"), information.addresses)
        assertEquals(1280, information.mtu)
        assertEquals(60000, information.listenPort)
        assertEquals(listOf("10.20.30.2/32"), configured.addresses)
        assertEquals(51820, configured.listenPort)
        assertNotEquals(information.addresses, configured.addresses)
        assertNotEquals(information.listenPort, configured.listenPort)
    }

    @Test
    fun reconfigureRestartsRuntimeWhenListenPortChanges() {
        val runtimeFactory = RecordingRuntimeFactory()
        val vpn = vpn(
            configuration = configuration(interfaceName = "utun144", listenPort = 51820),
            runtimeFactory = runtimeFactory,
        )

        vpn.start()
        val firstHandle = runtimeFactory.handles.single()

        vpn.reconfigure(
            configuration(interfaceName = "utun144", listenPort = 51821),
        )

        assertEquals(2, runtimeFactory.handles.size)
        assertFalse(firstHandle.isRunning())
        assertTrue(runtimeFactory.handles.last().isRunning())
    }

    private fun vpn(
        configuration: VpnConfiguration,
        interfaceManager: InterfaceManager = RecordingInterfaceManager(),
        runtimeFactory: RecordingRuntimeFactory = RecordingRuntimeFactory(),
    ): Vpn {
        return Vpn(
            vpnConfiguration = configuration,
            tunnelManager = InMemoryTunnelManager(userspaceRuntimeFactory = runtimeFactory::create),
            interfaceManager = interfaceManager,
        )
    }

    private fun configuration(
        interfaceName: String,
        listenPort: Int? = null,
        addresses: List<String> = emptyList(),
    ): VpnConfiguration {
        return DefaultVpnConfiguration(
            interfaceName = interfaceName,
            listenPort = listenPort,
            addresses = addresses.toMutableList(),
            privateKey = privateKey,
            peers = listOf(
                VpnPeer(
                    publicKey = peerKey,
                    endpointAddress = "198.51.100.1",
                    endpointPort = 51820,
                    allowedIps = listOf("0.0.0.0/0"),
                ),
            ),
        )
    }

    private class DriftedInformationInterfaceManager(
        initialConfiguration: VpnConfiguration,
        private val observedAddresses: List<String>,
        private val observedDnsDomainPool: Pair<List<String>, List<String>>,
        private val observedMtu: Int?,
        private val observedListenPort: Int?,
    ) : InterfaceManager {
        private var created: Boolean = false
        private var currentConfiguration: VpnConfiguration = snapshotConfiguration(initialConfiguration)
        private val tun = InMemoryTunPort()

        override fun exists(): Boolean = created

        override fun create(config: VpnConfiguration) {
            created = true
            currentConfiguration = snapshotConfiguration(config)
        }

        override fun up() = Unit

        override fun down() = Unit

        override fun delete() {
            created = false
        }

        override fun isUp(): Boolean = true

        override fun configuration(): VpnConfiguration = snapshotConfiguration(currentConfiguration)

        override fun tunPort(): TunPort = tun

        override fun reconfigure(config: VpnConfiguration) {
            currentConfiguration = snapshotConfiguration(config)
        }

        override fun readInformation(): VpnInterfaceInformation {
            return VpnInterfaceInformation(
                interfaceName = currentConfiguration.interfaceName,
                isUp = true,
                addresses = observedAddresses,
                dnsDomainPool = observedDnsDomainPool,
                mtu = observedMtu,
                listenPort = observedListenPort,
            )
        }
    }

    private class RecordingRuntimeFactory(
        private val failOnCreate: Boolean = false,
        private val stats: List<VpnPeerStats> = emptyList(),
    ) {
        val handles: MutableList<RecordingRuntimeHandle> = mutableListOf()

        fun create(
            configuration: VpnConfiguration,
            listenPort: Int,
            pollOnce: suspend (
                com.rafambn.kmpvpn.session.io.UdpPort,
                () -> Boolean,
            ) -> Boolean,
            peerStats: () -> List<VpnPeerStats>,
            onFailure: (Throwable) -> Unit,
        ): UserspaceRuntimeHandle {
            if (failOnCreate) {
                throw IllegalStateException("boom")
            }
            require(configuration.interfaceName.isNotBlank())
            require(listenPort > 0)
            requireNotNull(pollOnce)
            requireNotNull(peerStats)
            requireNotNull(onFailure)

            return RecordingRuntimeHandle(stats = stats).also { handle ->
                handles += handle
            }
        }
    }

    private class RecordingRuntimeHandle(
        private val stats: List<VpnPeerStats>,
    ) : UserspaceRuntimeHandle {
        private var running: Boolean = true

        override fun isRunning(): Boolean = running

        override fun peerStats(): List<VpnPeerStats> = stats

        override fun close() {
            running = false
        }
    }

    private class RecordingInterfaceManager : InterfaceManager {
        private var created: Boolean = false
        private var up: Boolean = false
        private var currentConfiguration: VpnConfiguration? = null
        private val tun = InMemoryTunPort()
        var downCalls: Int = 0

        override fun exists(): Boolean = created

        override fun create(config: VpnConfiguration) {
            created = true
            currentConfiguration = snapshotConfiguration(config)
        }

        override fun up() {
            up = true
        }

        override fun down() {
            downCalls += 1
            up = false
        }

        override fun delete() {
            created = false
            up = false
            currentConfiguration = null
        }

        override fun isUp(): Boolean = up

        override fun configuration(): VpnConfiguration {
            return snapshotConfiguration(checkNotNull(currentConfiguration))
        }

        override fun tunPort(): TunPort = tun

        override fun reconfigure(config: VpnConfiguration) {
            currentConfiguration = snapshotConfiguration(config)
        }

        override fun readInformation(): VpnInterfaceInformation {
            val configuration = checkNotNull(currentConfiguration)
            return VpnInterfaceInformation(
                interfaceName = configuration.interfaceName,
                isUp = up,
                addresses = configuration.addresses.toList(),
                dnsDomainPool = configuration.dnsDomainPool,
                mtu = configuration.mtu,
                listenPort = configuration.listenPort,
                peerStats = configuration.peers.map { peer ->
                    VpnPeerStats(
                        publicKey = peer.publicKey,
                        endpointAddress = peer.endpointAddress,
                        endpointPort = peer.endpointPort,
                        allowedIps = peer.allowedIps.toList(),
                        receivedBytes = 0L,
                        transmittedBytes = 0L,
                        lastHandshakeEpochSeconds = null,
                    )
                },
            )
        }
    }
}
