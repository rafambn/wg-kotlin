package com.rafambn.kmpvpn

import com.rafambn.kmpvpn.address.VpnAddress
import com.rafambn.kmpvpn.info.VpnInterfaceInformation
import com.rafambn.kmpvpn.platform.PlatformService
import com.rafambn.kmpvpn.platform.createPlatformService

class Vpn(
    engine: Engine = Engine.BORINGTUN,
    val vpnConfiguration: VpnConfiguration,
    val onAlert: ((Pair<ErrorCode, String>) -> Unit)? = null
) : AutoCloseable {

    private val platformService: PlatformService<out VpnAddress>
    private var adapter: VpnAdapter? = null
    private val interfaceName: String
        get() = vpnConfiguration.interfaceName

    init {
        require(interfaceName.isNotEmpty()) { "Interface name cannot be empty" }

        platformService = createPlatformService(engine)

        require(platformService.isValidInterfaceName(interfaceName)) {
            "Interface name must match utun[0-9]+ format (e.g., utun0, utun1, utun42)"
        }

        try {
            adapter = platformService.adapter(interfaceName)
        } catch (_: Exception) {
            // Silently continue if adapter resolution fails
        }
    }

    fun exists(): Boolean {
        val exists = platformService.addressExists(interfaceName)
        if (!exists) {
            adapter = null
        }
        return exists
    }

    fun isRunning(): Boolean {
        return resolveExistingAdapter()?.isRunning() == true
    }

    fun create() {
        if (exists()) {
            resolveExistingAdapter()
            return
        }

        adapter = platformService.create(vpnConfiguration)
    }

    fun start() {
        val currentAdapter = resolveExistingAdapter()
        if (currentAdapter?.isRunning() == true) {
            val shortName = currentAdapter.address().shortName()
            alertUser(ErrorCode.INTERFACE_ALREADY_UP, "`$shortName` already exists and is up")
            throw IllegalStateException("`$shortName` already exists and is up")
        }

        adapter = platformService.start(vpnConfiguration)
    }

    fun stop() {
        val currentAdapter = resolveExistingAdapter() ?: return
        platformService.stop(vpnConfiguration, currentAdapter)
    }

    fun delete() {
        val currentAdapter = resolveExistingAdapter() ?: run {
            adapter = null
            return
        }

        if (currentAdapter.isRunning()) {
            platformService.stop(vpnConfiguration, currentAdapter)
        }

        currentAdapter.delete()
        adapter = null
    }

    fun information(): VpnInterfaceInformation? {
        return resolveExistingAdapter()?.information()
    }

    private fun alertUser(errorCode: ErrorCode, message: String) {
        val alert = Pair(errorCode, message)
        onAlert?.invoke(alert)
    }

    override fun close() {
        delete()
    }

    private fun resolveExistingAdapter(): VpnAdapter? {
        if (!platformService.addressExists(interfaceName)) {
            adapter = null
            return null
        }

        adapter?.let { return it }

        return VpnAdapter(
            service = platformService,
            ip = platformService.address(interfaceName)
        ).also { adapter = it }
    }

    companion object {
        const val DEFAULT_PORT: Int = 51820
    }
}
