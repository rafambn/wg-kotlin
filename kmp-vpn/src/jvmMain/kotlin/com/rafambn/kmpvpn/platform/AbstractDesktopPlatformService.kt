package com.rafambn.kmpvpn.platform

import com.rafambn.kmpvpn.NoHandshakeException
import com.rafambn.kmpvpn.StartRequest
import com.rafambn.kmpvpn.VpnAdapter
import com.rafambn.kmpvpn.VpnAdapterConfiguration
import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.VpnPeer
import com.rafambn.kmpvpn.address.VpnAddress
import com.rafambn.kmpvpn.dns.DNSProvider
import com.rafambn.kmpvpn.dns.createDNSProviderFactory
import com.rafambn.kmpvpn.util.IpUtil
import com.rafambn.kmpvpn.util.Util
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration
import java.time.Instant
import java.util.Arrays
import java.util.Optional
import java.util.function.Consumer
import java.util.stream.Collectors
import kotlin.Throws
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaInstant

@OptIn(ExperimentalTime::class)
abstract class AbstractDesktopPlatformService<I : VpnAddress>(interfacePrefix: String) : AbstractPlatformService<I>(interfacePrefix) {
    private var dnsProvider: DNSProvider? = null

    protected fun findAddress(startRequest: StartRequest): I {
        val addresses = addresses()
        val configuration = startRequest.configuration
        val interfaceName = startRequest.interfaceName
        var ip: I? = null

        interfaceName?.let { nativeIName ->
            val addr = find(nativeIName, addresses)
            if (addr == null) {
                ip = add(nativeIName, "wireguard")
            } else {
                val publicKey = getPublicKey(nativeIName)
                publicKey?.let {
                    if (it == configuration.publicKey) {
                        ip = addr
                    } else {
                        throw IOException("$nativeIName is already in use")
                    }
                }
            }
        }

        if (ip == null) {
            var maxIface = -1
            for (i in 0..<MAX_INTERFACES) {
                val name = "utun$i"
                if (exists(name, addresses)) {
                    val publicKey = getPublicKey(name)
                    if (publicKey.isNullOrBlank()) {
                        ip = address(name)
                        maxIface = i
                    } else check(publicKey != configuration.publicKey) {
                        "Peer with public key $publicKey on $name is already active."
                    }
                } else if (maxIface == -1) {
                    maxIface = i
                    break
                }
            }
            if (maxIface == -1) throw IOException("Exceeds maximum of $MAX_INTERFACES interfaces.")

            if (ip == null) {
                val nativeName = "utun$maxIface"
                ip = add(nativeName, "wireguard")
            }
        }
        return ip
    }

    override fun remove(vpnAdapter: VpnAdapter, publicKey: String) {
//        context.commands()!!.privileged()
//            .run(context.nativeComponents()!!.tool(NativeComponents.Tool.WG), "set", adapter.address().name(), "peer", publicKey, "remove")
    }

    override fun reconfigure(vpnAdapter: VpnAdapter, cfg: VpnAdapterConfiguration) {
        val path = Files.createTempFile("wg", ".cfg")
        try {
//            cfg.write(path)
//            context.commands()!!.privileged()
//                .run(context.nativeComponents()!!.tool(NativeComponents.Tool.WG), "setconf", vpnAdapter.address().name(), path.toString())
        } finally {
            Files.delete(path)
        }
    }

    override fun sync(vpnAdapter: VpnAdapter, cfg: VpnAdapterConfiguration) {
        val path = Files.createTempFile("wg", ".cfg")
        try {
//            cfg.write(path)
//            context.commands()!!.privileged()
//                .run(context.nativeComponents()!!.tool(NativeComponents.Tool.WG), "syncconf", vpnAdapter.address().name(), path.toString())
        } finally {
            Files.delete(path)
        }
    }

    @Throws(IOException::class)
    override fun append(vpnAdapter: VpnAdapter, cfg: VpnAdapterConfiguration) {
        val path = Files.createTempFile("wg", ".cfg")
        try {
//            configuration.write(path)
//            context.commands()!!.privileged()
//                .run(context.nativeComponents()!!.tool(NativeComponents.Tool.WG), "addconf", adapter.address().name(), path.toString())
        } finally {
            Files.delete(path)
        }
    }

    final override fun dns(): DNSProvider? {
        if (dnsProvider == null) {
            val dnsProviderFactory = createDNSProviderFactory()

            val provider = dnsProviderFactory.available<DNSProvider>().firstOrNull()

            if (provider != null) {
                dnsProvider = dnsProviderFactory.create(provider)
                dnsProvider?.init(this)
            }
        }
        return dnsProvider
    }

    @Throws(IOException::class)
    override fun start(startRequest: StartRequest): VpnAdapter {
        val session = VpnAdapter(this)
        val config = startRequest.configuration

        if (!config.preUp.isEmpty()) {
            runHook(config, session, *config.preUp.toTypedArray())
        }

        try {
            onStart(startRequest, session)
        } catch (ioe: IOException) {
            throw ioe
        } catch (re: RuntimeException) {
            throw re
        } catch (e: Exception) {
            throw IOException("Failed to start.", e)
        }

        val gw: VpnPeer? = defaultGatewayPeer()
        if (gw != null && config.peers.contains(gw)) {
            try {
                val addr = gw.endpointAddress ?: throw IllegalStateException("No endpoint for peer.")
                val iface = defaultGateway() ?: throw IllegalStateException("No current default gateway.")
                onSetDefaultGateway(PlatformService.Gateway(iface.nativeIface, addr))
            } catch (e: Exception) {
            }
        }

        if (!config.postUp.isEmpty()) {
            val p: MutableList<String> = config.postUp
            runHook(config, session, *p.toTypedArray())
        }

        return session
    }

    override fun onStop(configuration: VpnConfiguration, session: VpnAdapter) {
        try {
            try {
                if (defaultGatewayPeer() != null && configuration.peers.contains(defaultGatewayPeer())) {
                    try {
                        resetDefaultGatewayPeer()
                    } catch (e: Exception) {
                    }
                }
            } finally {
                onStopped(configuration, session)
            }
        } finally {
            unmap(session.address().name())
        }
    }

    protected fun unmap(name: String) {
        try {
//            val nativeName = context().commands()!!.privileged().task<String>(Prefs.RemoveKey(getNameToNativeNameNode(), name))
//            if (nativeName != null) {
//                context().commands()!!.privileged().task<String>(Prefs.RemoveKey(getNativeNameToNameNode(), nativeName))
//            }
        } catch (e: Exception) {
        }
    }

    @Throws(IOException::class)
    protected abstract fun add(nativeName: String, type: String): I

    protected fun onStopped(configuration: VpnConfiguration, session: VpnAdapter) {
    }

    override fun addresses(): MutableList<I> {
        val ips: MutableList<I> = ArrayList()
        try {
            val nifEn = NetworkInterface.getNetworkInterfaces()
            while (nifEn.hasMoreElements()
            ) {
                val nif = nifEn.nextElement()
                val vaddr = createVirtualInetAddress(nif)
                if (vaddr != null) ips.add(vaddr)
            }
        } catch (e: Exception) {
            throw IllegalStateException("Failed to get interfaces.", e)
        }
        return ips
    }

    @Throws(IOException::class)
    protected abstract fun createVirtualInetAddress(nif: NetworkInterface): I?

    @Throws(IOException::class)
    protected fun dns(configuration: VpnConfiguration, ip: I) {
        dns()?.set(
            DNSProvider.DNSEntry(
                iface = ip.nativeName(),
                ipv4Servers = IpUtil.filterIpV4Addresses(configuration.dns),
                ipv6Servers = IpUtil.filterIpV6Addresses(configuration.dns),
                domains = IpUtil.filterNames(configuration.dns)
            )
        )
    }

    protected fun isMatchesPrefix(nif: NetworkInterface): Boolean {
        return nif.name.startsWith("utun")
    }

    protected open fun isWireGuardInterface(nif: NetworkInterface): Boolean {
        return isMatchesPrefix(nif)
    }

    @Throws(Exception::class)
    protected abstract fun onStart(startRequest: StartRequest, session: VpnAdapter)

    @Throws(IOException::class)
    protected fun waitForFirstHandshake(
        configuration: VpnConfiguration,
        session: VpnAdapter,
        connectionStarted: Instant,
        peerOr: VpnPeer?,
        timeout: Duration
    ) {
        if (configuration.peers.size != 1) {
            return
        }

        if (peerOr == null) {
            return
        }

        val ip = session.address()

        if (peerOr.endpointAddress.isNullOrEmpty()) {
            return
        }

        for (i in 0..<timeout.toSeconds()) {
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                throw IOException(String.format("Interrupted connecting to %s", ip.shortName()))
            }
            try {
                val lastHandshake = getLatestHandshake(ip, peerOr.publicKey)
                if (lastHandshake == connectionStarted || lastHandshake.toJavaInstant().isAfter(connectionStarted)) {
                    return
                }
            } catch (iae: RuntimeException) {
                try {
                    ip.down()
                } catch (e: Exception) {
                } finally {
                    ip.delete()
                }
                throw iae
            }
        }

        try {
            ip.down()
        } catch (e: Exception) {
        } finally {
            ip.delete()
        }
        throw NoHandshakeException("No handshake received from ${peerOr.endpointAddress} for ${ip.shortName()} within ${timeout.toSeconds()} seconds.")
    }

    @Throws(IOException::class)
    override fun runHook(configuration: VpnConfiguration, session: VpnAdapter, vararg hookScript: String) {
        for (cmd in hookScript) {
            runCommand(Util.parseQuotedString(cmd))
        }
    }

    @Throws(IOException::class)
    protected abstract fun runCommand(commands: MutableList<String>)
}
