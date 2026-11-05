package com.rafambn.wgkotlin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotSame

class VpnConstructorWiringTest {

    @Test
    fun secondaryConstructorBuildsIndependentInstances() {
        val first = Vpn(interfaceName = "utun101")
        val second = Vpn(interfaceName = "utun102")

        assertNotSame(first, second)
        assertFalse(first.isRunning())
        assertFalse(second.isRunning())

    }

    @Test
    fun explicitEngineBuildsStoppedInstance() {
        val vpn = Vpn(
            interfaceName = "utun103",
            engine = Engine.BORINGTUN,
        )

        assertFalse(vpn.isRunning())
    }
}
