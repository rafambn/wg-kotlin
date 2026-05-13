package com.rafambn.wgkotlin.daemon

import com.rafambn.wgkotlin.daemon.command.CommandBinary
import com.rafambn.wgkotlin.daemon.di.DaemonKoinBootstrap
import com.rafambn.wgkotlin.daemon.platformAdapter.PlatformAdapter
import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import com.rafambn.wgkotlin.daemon.tun.TunHandle
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class DaemonKoinBootstrapTest {

    @Test
    fun resolvingDependenciesAcceptsOverridesWithoutGlobalState() = runBlocking {
        val firstAdapter = RecordingAdapter()
        val secondAdapter = RecordingAdapter()
        val firstDependencies = DaemonKoinBootstrap.resolveDependencies(
            overrideModules = listOf(module { single<PlatformAdapter> { firstAdapter } }),
        )
        val secondDependencies = DaemonKoinBootstrap.resolveDependencies(
            overrideModules = listOf(module { single<PlatformAdapter> { secondAdapter } }),
        )

        firstDependencies.service.startSession(
            config = TunSessionConfig(interfaceName = "wg0", addresses = listOf("10.0.0.1/24")),
            outgoingPackets = emptyFlow(),
        ).first()
        secondDependencies.service.startSession(
            config = TunSessionConfig(interfaceName = "wg1", addresses = listOf("10.0.0.2/24")),
            outgoingPackets = emptyFlow(),
        ).first()

        assertEquals(1, firstAdapter.startCalls)
        assertEquals(1, secondAdapter.startCalls)
        assertNotSame(firstDependencies.service, secondDependencies.service)
        DaemonKoinBootstrap.close()
    }

    private class RecordingAdapter : PlatformAdapter {
        var startCalls: Int = 0
        override val platformId: String = "test"
        override val requiredBinaries: Set<CommandBinary> = emptySet()

        override suspend fun startSession(config: TunSessionConfig): TunHandle {
            startCalls++
            return object : TunHandle {
                override val interfaceName: String = config.interfaceName
                private var emitted = false

                override suspend fun readPacket(): ByteArray? {
                    return if (emitted) { kotlinx.coroutines.delay(10); null } else byteArrayOf(1).also { emitted = true }
                }

                override suspend fun writePacket(packet: ByteArray) {}

                override fun close() {}
            }
        }
    }
}
