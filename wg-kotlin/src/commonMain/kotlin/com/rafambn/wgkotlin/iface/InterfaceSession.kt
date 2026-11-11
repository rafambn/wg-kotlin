package com.rafambn.wgkotlin.iface

/** An open TUN session. Its owner must close it when the TUN is stopped. */
interface InterfaceSession : AutoCloseable {
    fun information(): VpnInterfaceInformation?
}
