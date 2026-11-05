package com.rafambn.wgkotlin.daemon

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

/** Exercises unchanged common VPN tests through the actual JVM IPC implementation. */
class VpnPlatformTestExtension : BeforeAllCallback, AfterAllCallback {
    private val namespace = ExtensionContext.Namespace.create(VpnPlatformTestExtension::class.java)
    private val testClasses = setOf(
        "com.rafambn.wgkotlin.VpnConstructorWiringTest",
        "com.rafambn.wgkotlin.VpnContractInvariantsTest",
        "com.rafambn.wgkotlin.VpnFoundationWiringTest",
        "com.rafambn.wgkotlin.VpnStateTransitionTest",
    )

    override fun beforeAll(context: ExtensionContext) {
        if (context.requiredTestClass.name in testClasses) {
            context.getStore(namespace).put("daemon", IpcTestFixture("echo"))
        }
    }

    override fun afterAll(context: ExtensionContext) {
        context.getStore(namespace).remove("daemon", IpcTestFixture::class.java)?.close()
    }
}
