package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.DnsConfig
import com.rafambn.wgkotlin.VpnConfiguration
import com.rafambn.wgkotlin.VpnPeer
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmInterfaceManagerTest {

    @Test
    fun startStopTracksRunningStateAndInformation() {
        val executor = InMemoryInterfaceCommandExecutor()
        val manager = JvmInterfaceManager(commandExecutor = executor, tunPipe = DuplexChannelPipe.create<ByteArray>().first)
        val config = configuration(interfaceName = "utun150")

        manager.start(config)

        assertTrue(manager.isRunning())
        val info = requireNotNull(manager.information())
        assertEquals(config.interfaceName, info.interfaceName)
        assertEquals(config.dns, info.dns)

        manager.stop()

        assertFalse(manager.isRunning())
        assertNull(manager.information())
    }

    @Test
    fun reconfigureRestartsSessionWithNewConfig() {
        val executor = InMemoryInterfaceCommandExecutor()
        val manager = JvmInterfaceManager(commandExecutor = executor, tunPipe = DuplexChannelPipe.create<ByteArray>().first)
        manager.start(configuration(interfaceName = "utun151", dns = DnsConfig(searchDomains = listOf("corp.local"), servers = listOf("1.1.1.1"))))

        manager.reconfigure(configuration(interfaceName = "utun151", dns = DnsConfig(searchDomains = listOf("corp.local"), servers = listOf("9.9.9.9"))))

        assertEquals(listOf("9.9.9.9"), executor.getConfig("utun151")?.dns?.servers)
    }

    @Test
    fun asyncFailureClearsRunningState() {
        val executor = InMemoryInterfaceCommandExecutor()
        val manager = JvmInterfaceManager(commandExecutor = executor, tunPipe = DuplexChannelPipe.create<ByteArray>().first)
        var failureCalled = false

        manager.start(configuration(interfaceName = "utun152")) {
            failureCalled = true
        }
        executor.failSession("utun152", IllegalStateException("boom"))

        assertFalse(manager.isRunning())
        assertTrue(failureCalled)
    }

    private fun configuration(
        interfaceName: String,
        dns: DnsConfig = DnsConfig(),
    ): VpnConfiguration {
        return VpnConfiguration(
            interfaceName = interfaceName,
            dns = dns,
            listenPort = 0,
            privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs=",
            peers = listOf(
                VpnPeer(
                    publicKey = "6fX3drXr/7L0KleChX2NDSSSXWMQZnIcXtNCmieYw0I=",
                    endpointAddress = "198.51.100.1",
                    endpointPort = 51820,
                ),
            ),
        )
    }
}
