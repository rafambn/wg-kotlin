package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.daemon.proto.IpAddr
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import kotlinx.io.bytestring.ByteString
import java.net.InetAddress

actual object PlatformInterfaceFactory {
    actual fun create(tunPipe: DuplexChannelPipe<ByteArray>): InterfaceManager {
        val hostStr = System.getProperty(
            JvmInterfaceProperties.DAEMON_HOST,
            JvmInterfaceProperties.DEFAULT_DAEMON_HOST,
        )
        val addr = InetAddress.getByName(hostStr)
        val sessionBridge = DaemonSessionBridge(
            host = when (addr.address.size) {
                4 -> IpAddr.Ip.V4(ByteString(addr.address))
                16 -> IpAddr.Ip.V6(ByteString(addr.address))
                else -> error("unexpected address size: ${addr.address.size}")
            },
            port = System.getProperty(JvmInterfaceProperties.DAEMON_PORT)?.toIntOrNull()
                ?: JvmInterfaceProperties.DEFAULT_DAEMON_PORT,
        )
        return JvmInterfaceManager(sessionBridge = sessionBridge, tunPipe = tunPipe)
    }
}
