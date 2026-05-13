package com.rafambn.wgkotlin.iface

import kotlin.test.Test
import kotlin.test.assertTrue

class DaemonBackedInterfaceCommandExecutorTest {

    @Test
    fun executorConstructsLazilyWithoutImmediateConnection() {
        val executor = DaemonBackedInterfaceCommandExecutor(
            host = "127.0.0.1",
            port = 65535,
        )

        assertTrue(executor is InterfaceCommandExecutor)
    }
}
