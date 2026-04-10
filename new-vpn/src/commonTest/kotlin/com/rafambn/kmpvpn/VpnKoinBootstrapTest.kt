package com.rafambn.kmpvpn

import com.rafambn.kmpvpn.iface.VpnInterfaceInformation
import com.rafambn.kmpvpn.iface.InterfaceManager
import com.rafambn.kmpvpn.session.InMemoryTunnelManager
import com.rafambn.kmpvpn.session.TunnelManager
import com.rafambn.kmpvpn.session.io.TunPort
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class VpnKoinBootstrapTest {

    private val privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs="

    @Test
    fun createBuildsIndependentVpnGraphsAndSupportsOverrides() {
        var sessionProviderCalls = 0
        var interfaceProviderCalls = 0

        val overrideModule = module {
            factory<TunnelManager> {
                sessionProviderCalls += 1
                InMemoryTunnelManager()
            }
            factory<InterfaceManager> {
                interfaceProviderCalls += 1
                AlwaysExistingInterfaceManager(configuration(interfaceName = "wg-koin"))
            }
        }

        val firstConfig = configuration(interfaceName = "wg-koin-1")
        val firstDependencies = VpnKoinBootstrap.resolveDependencies(
            configuration = firstConfig,
            overrideModules = listOf(overrideModule),
        )
        val first = Vpn(
            vpnConfiguration = firstConfig,
            tunnelManager = firstDependencies.tunnelManager,
            interfaceManager = firstDependencies.interfaceManager,
        )

        val secondConfig = configuration(interfaceName = "wg-koin-2")
        val secondDependencies = VpnKoinBootstrap.resolveDependencies(
            configuration = secondConfig,
            overrideModules = listOf(overrideModule),
        )
        val second = Vpn(
            vpnConfiguration = secondConfig,
            tunnelManager = secondDependencies.tunnelManager,
            interfaceManager = secondDependencies.interfaceManager,
        )

        assertTrue(first.exists())
        assertTrue(second.exists())
        assertNotSame(first, second)
        assertEquals(2, sessionProviderCalls)
        assertEquals(2, interfaceProviderCalls)
    }

    private fun configuration(interfaceName: String): VpnConfiguration {
        return DefaultVpnConfiguration(
            interfaceName = interfaceName,
            privateKey = privateKey,
        )
    }

    private class AlwaysExistingInterfaceManager(
        private var configuration: VpnConfiguration,
    ) : InterfaceManager {
        override fun exists(): Boolean = true

        override fun create(config: VpnConfiguration) {
            configuration = config
        }

        override fun up() = Unit

        override fun down() = Unit

        override fun delete() = Unit

        override fun isUp(): Boolean = false

        override fun configuration(): VpnConfiguration = configuration

        override fun tunPort(): TunPort {
            return object : TunPort {
                override suspend fun readPacket(): ByteArray? = null

                override suspend fun writePacket(packet: ByteArray) = Unit
            }
        }

        override fun reconfigure(config: VpnConfiguration) {
            configuration = config
        }

        override fun readInformation(): VpnInterfaceInformation {
            return VpnInterfaceInformation(
                interfaceName = configuration.interfaceName,
                isUp = false,
                addresses = emptyList(),
                dnsDomainPool = (emptyList<String>() to emptyList()),
                mtu = null,
            )
        }
    }
}
