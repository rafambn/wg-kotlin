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
import kotlin.test.assertTrue

class VpnUserspaceRuntimeIntegrationTest {
    private val privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs="
    private val publicKey = "V6w5nNq2WEYLRh3SeDsICoZ6irMIXja+6JGZveHFk/Q="
    private val peerKey = "6fX3drXr/7L0KleChX2NDSSSXWMQZnIcXtNCmieYw0I="

    @Test
    fun startStopAndDeleteOwnUserspaceRuntimeLifecycle() {
        val runtimeFactory = RecordingRuntimeFactory()
        val interfaceManager = RecordingInterfaceManager()
        val vpn = vpn(
            configuration = configuration(interfaceName = "wg-runtime", listenPort = 51820),
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
            vpnConfiguration = configuration(interfaceName = "wg-fail"),
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
            configuration = configuration(interfaceName = "wg-info"),
            runtimeFactory = runtimeFactory,
        )

        vpn.start()
        val information = requireNotNull(vpn.information())

        assertEquals(expectedStats, information.peerStats)
    }

    @Test
    fun reconfigureRestartsRuntimeWhenListenPortChanges() {
        val runtimeFactory = RecordingRuntimeFactory()
        val vpn = vpn(
            configuration = configuration(interfaceName = "wg-port", listenPort = 51820),
            runtimeFactory = runtimeFactory,
        )

        vpn.start()
        val firstHandle = runtimeFactory.handles.single()

        vpn.reconfigure(
            configuration(interfaceName = "wg-port", listenPort = 51821),
        )

        assertEquals(2, runtimeFactory.handles.size)
        assertFalse(firstHandle.isRunning())
        assertTrue(runtimeFactory.handles.last().isRunning())
    }

    private fun vpn(
        configuration: VpnConfiguration,
        interfaceManager: RecordingInterfaceManager = RecordingInterfaceManager(),
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
    ): VpnConfiguration {
        return DefaultVpnConfiguration(
            interfaceName = interfaceName,
            listenPort = listenPort,
            privateKey = privateKey,
            publicKey = publicKey,
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
            currentConfiguration = config
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
            return checkNotNull(currentConfiguration)
        }

        override fun tunPort(): TunPort = tun

        override fun reconfigure(config: VpnConfiguration) {
            currentConfiguration = config
        }

        override fun readInformation(): VpnInterfaceInformation {
            val configuration = checkNotNull(currentConfiguration)
            return VpnInterfaceInformation(
                interfaceName = configuration.interfaceName,
                isUp = up,
                addresses = configuration.addresses.toList(),
                dnsDomainPool = configuration.dnsDomainPool,
                mtu = configuration.mtu,
                peerStats = configuration.adapter.peers.map { peer ->
                    VpnPeerStats(
                        publicKey = peer.publicKey,
                        receivedBytes = 0L,
                        transmittedBytes = 0L,
                        lastHandshakeEpochSeconds = null,
                    )
                },
            )
        }
    }
}
