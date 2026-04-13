package com.rafambn.kmpvpn

import com.rafambn.kmpvpn.iface.VpnInterfaceInformation
import com.rafambn.kmpvpn.iface.InterfaceManager
import com.rafambn.kmpvpn.iface.VpnPeerStats
import com.rafambn.kmpvpn.session.TunnelManagerImpl
import com.rafambn.kmpvpn.session.UserspaceDataPlane
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VpnUserspaceDataPlaneIntegrationTest {
    private val privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs="
    private val peerKey = "6fX3drXr/7L0KleChX2NDSSSXWMQZnIcXtNCmieYw0I="

    @Test
    fun startStopAndDeleteOwnUserspaceDataPlaneLifecycle() {
        val dataPlaneFactory = RecordingDataPlaneFactory()
        val interfaceManager = RecordingInterfaceManager()
        val vpn = vpn(
            configuration = configuration(interfaceName = "utun140", listenPort = 51820),
            interfaceManager = interfaceManager,
            dataPlaneFactory = dataPlaneFactory,
        )

        vpn.start()
        assertEquals(1, dataPlaneFactory.dataPlanes.size)
        assertTrue(dataPlaneFactory.dataPlanes.single().isRunning())
        assertEquals(VpnState.Running, vpn.state())

        vpn.stop()
        assertFalse(dataPlaneFactory.dataPlanes.single().isRunning())
        assertEquals(VpnState.Created, vpn.state())

        vpn.start()
        assertEquals(2, dataPlaneFactory.dataPlanes.size)
        assertTrue(dataPlaneFactory.dataPlanes.last().isRunning())

        vpn.delete()
        assertFalse(vpn.exists())
        assertFalse(dataPlaneFactory.dataPlanes.last().isRunning())
        assertEquals(VpnState.NotCreated, vpn.state())
    }

    @Test
    fun dataPlaneStartFailureRollsBackToCreatedState() {
        val dataPlaneFactory = RecordingDataPlaneFactory(failOnCreate = true)
        val interfaceManager = RecordingInterfaceManager()
        val tunnelManager = TunnelManagerImpl(userspaceDataPlaneFactory = dataPlaneFactory::create)
        val vpn = Vpn(
            vpnConfiguration = configuration(interfaceName = "utun141"),
            tunnelManager = tunnelManager,
            interfaceManager = interfaceManager,
        )

        val failure = assertFailsWith<IllegalStateException> {
            vpn.start()
        }

        assertTrue(failure.message.orEmpty().contains("Session operation `startDataPlane` failed"))
        assertEquals(1, interfaceManager.downCalls)
        assertEquals(0, tunnelManager.sessionSnapshots().size)
        assertEquals(VpnState.Created, vpn.state())
    }

    @Test
    fun informationOverlaysDataPlanePeerStats() {
        val expectedStats = listOf(
            VpnPeerStats(
                publicKey = peerKey,
                receivedBytes = 321L,
                transmittedBytes = 654L,
                lastHandshakeEpochSeconds = null,
            ),
        )
        val dataPlaneFactory = RecordingDataPlaneFactory(stats = expectedStats)
        val vpn = vpn(
            configuration = configuration(interfaceName = "utun142"),
            dataPlaneFactory = dataPlaneFactory,
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
    fun reconfigureRestartsDataPlaneWhenListenPortChanges() {
        val dataPlaneFactory = RecordingDataPlaneFactory()
        val vpn = vpn(
            configuration = configuration(interfaceName = "utun144", listenPort = 51820),
            dataPlaneFactory = dataPlaneFactory,
        )

        vpn.start()
        val firstDataPlane = dataPlaneFactory.dataPlanes.single()

        vpn.reconfigure(
            configuration(interfaceName = "utun144", listenPort = 51821),
        )

        assertEquals(2, dataPlaneFactory.dataPlanes.size)
        assertFalse(firstDataPlane.isRunning())
        assertTrue(dataPlaneFactory.dataPlanes.last().isRunning())
    }

    private fun vpn(
        configuration: VpnConfiguration,
        interfaceManager: InterfaceManager = RecordingInterfaceManager(),
        dataPlaneFactory: RecordingDataPlaneFactory = RecordingDataPlaneFactory(),
    ): Vpn {
        return Vpn(
            vpnConfiguration = configuration,
            tunnelManager = TunnelManagerImpl(userspaceDataPlaneFactory = dataPlaneFactory::create),
            interfaceManager = interfaceManager,
        )
    }

    private fun configuration(
        interfaceName: String,
        listenPort: Int? = null,
        addresses: List<String> = emptyList(),
    ): VpnConfiguration {
        return VpnConfiguration(
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

    private class RecordingDataPlaneFactory(
        private val failOnCreate: Boolean = false,
        private val stats: List<VpnPeerStats> = emptyList(),
    ) {
        val dataPlanes: MutableList<RecordingDataPlane> = mutableListOf()

        fun create(
            configuration: VpnConfiguration,
            listenPort: Int,
            pollDataPlaneOnce: suspend (
                com.rafambn.kmpvpn.session.io.UdpPort,
                () -> Boolean,
            ) -> Boolean,
            peerStats: () -> List<VpnPeerStats>,
            onFailure: (Throwable) -> Unit,
        ): UserspaceDataPlane {
            if (failOnCreate) {
                throw IllegalStateException("boom")
            }
            require(configuration.interfaceName.isNotBlank())
            require(listenPort > 0)
            requireNotNull(pollDataPlaneOnce)
            requireNotNull(peerStats)
            requireNotNull(onFailure)

            return RecordingDataPlane(stats = stats).also { dataPlane ->
                dataPlanes += dataPlane
            }
        }
    }

    private class RecordingDataPlane(
        private val stats: List<VpnPeerStats>,
    ) : UserspaceDataPlane {
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
