package com.rafambn.kmpvpn

import com.rafambn.kmpvpn.iface.VpnInterface
import com.rafambn.kmpvpn.iface.VpnInterfaceInformation
import com.rafambn.kmpvpn.session.InMemorySessionManager
import com.rafambn.kmpvpn.session.io.TunPort
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VpnKoinBootstrapTest {

    private val privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs="
    private val publicKey = "V6w5nNq2WEYLRh3SeDsICoZ6irMIXja+6JGZveHFk/Q="

    @Test
    fun globalBootstrapSupportsOverridesAndIdempotentCreation() {
        Vpn.resetKoinForTests()
        var sessionProviderCalls = 0
        var interfaceProviderCalls = 0

        val overrideModule = module {
            single<SessionManagerProvider> {
                SessionManagerProvider { engine ->
                    sessionProviderCalls += 1
                    InMemorySessionManager(engine = engine)
                }
            }
            single<VpnInterfaceProvider> {
                VpnInterfaceProvider { configuration ->
                    interfaceProviderCalls += 1
                    AlwaysExistingVpnInterface(configuration)
                }
            }
        }

        try {
            val first = Vpn.create(
                vpnConfiguration = configuration(interfaceName = "wg-koin-1"),
                overrideModules = listOf(overrideModule),
            )
            val second = Vpn.create(
                vpnConfiguration = configuration(interfaceName = "wg-koin-2"),
                overrideModules = listOf(overrideModule),
            )

            assertTrue(first.exists())
            assertTrue(second.exists())
            assertEquals(2, sessionProviderCalls)
            assertEquals(2, interfaceProviderCalls)
        } finally {
            Vpn.resetKoinForTests()
        }
    }

    private fun configuration(interfaceName: String): VpnConfiguration {
        return DefaultVpnConfiguration(
            interfaceName = interfaceName,
            privateKey = privateKey,
            publicKey = publicKey,
        )
    }

    private class AlwaysExistingVpnInterface(
        private var configuration: VpnConfiguration,
    ) : VpnInterface {
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
