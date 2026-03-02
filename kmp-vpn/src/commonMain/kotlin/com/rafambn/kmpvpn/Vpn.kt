package com.rafambn.kmpvpn

import com.rafambn.kmpvpn.address.VpnAddress
import com.rafambn.kmpvpn.info.VpnInterfaceInformation
import com.rafambn.kmpvpn.platform.PlatformService
import com.rafambn.kmpvpn.platform.createPlatformService

class Vpn(
    val nativeInterfaceName: String,
    engine: Engine = Engine.WG_GO,
    val vpnConfiguration: VpnConfiguration,
    val peer: VpnPeer? = null,
    val onAlert: ((Pair<ErrorCode, String>) -> Unit)? = null
) : AutoCloseable {

    private val platformService: PlatformService<out VpnAddress>
    private var adapter: VpnAdapter? = null
    var lastAlert: Pair<ErrorCode, String>? = null
        private set

    init {
        require(nativeInterfaceName.isNotEmpty()) { "Interface name cannot be empty" }
        require(nativeInterfaceName.matches(Regex("^utun[0-9]+$"))) {
            "Interface name must match utun[0-9]+ format (e.g., utun0, utun1, utun42)"
        }

        platformService = createPlatformService(engine)

        try {
            adapter = platformService.adapter(nativeInterfaceName)
        } catch (_: Exception) {
            // Silently continue if adapter resolution fails
        }
    }

    fun started(): Boolean = adapter != null

    @Throws(Exception::class)
    fun open() {
        if (adapter != null && adapter!!.address().isUp()) {
            val shortName = adapter!!.address().shortName()
            alertUser(ErrorCode.INTERFACE_ALREADY_UP, "`$shortName` already exists and is up")
            throw IllegalStateException("`$shortName` already exists and is up")
        }

        val req = StartRequest.Builder(vpnConfiguration)
            .withNativeInterfaceName(nativeInterfaceName)
            .withPeer(peer)
            .build()

        adapter = platformService.start(req)
    }

    @Throws(Exception::class)
    fun information(): VpnInterfaceInformation {
        return adapter?.information() ?: VpnInterfaceInformation.EMPTY
    }

    private fun alertUser(errorCode: ErrorCode, message: String) {
        val alert = Pair(errorCode, message)
        lastAlert = alert
        onAlert?.invoke(alert)
    }

    @Throws(Exception::class)
    override fun close() {
        if (adapter != null) {
            platformService.stop(vpnConfiguration, adapter!!)
        }
    }

    companion object {
        const val DEFAULT_PORT: Int = 51820
    }
}
