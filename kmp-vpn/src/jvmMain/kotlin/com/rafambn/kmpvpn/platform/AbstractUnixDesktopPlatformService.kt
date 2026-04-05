package com.rafambn.kmpvpn.platform

import com.rafambn.kmpvpn.VpnAdapter
import com.rafambn.kmpvpn.VpnAdapterConfiguration
import com.rafambn.kmpvpn.address.VpnAddress
import java.io.IOException
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
abstract class AbstractUnixDesktopPlatformService<I : VpnAddress> : AbstractDesktopPlatformService<I>() {

    override fun adapters(): MutableList<VpnAdapter> {
//        return try {
//            val m = HashMap<String, VpnAdapter>()
//            for (line in context.commands()!!.output(context.nativeComponents()!!.tool(NativeComponents.Tool.WG), "show", "interfaces")) {
//                for (ifaceName in line.split("\\s+".toRegex())) {
//                    if (ifaceName.isEmpty()) {
//                        continue
//                    }
//                    val addr = address(ifaceName)
//                    val iface = configureExistingSession(addr)
//                    if (m.containsKey(addr.name())) {
//                        if (addr.name() == addr.nativeName()) {
//                            LOG.warn(
//                                "Replacing interface {} [{}], as an interface with the same name already exists.",
//                                addr.name(),
//                                addr.nativeName()
//                            )
//                            m[addr.name()] = iface
//                        } else {
//                            LOG.warn(
//                                "Skipping interface {} [{}], an interface with the same name already exists.",
//                                addr.name(),
//                                addr.nativeName()
//                            )
//                        }
//                    } else {
//                        m[addr.name()] = iface
//                    }
//                }
//            }
//            m.values.toMutableList()
//        } catch (ioe: IOException) {
//            throw UncheckedIOException(ioe)
//        }
        return mutableListOf()
    }

    @Throws(IOException::class)
    override fun reconfigure(adapter: VpnAdapter, configuration: VpnAdapterConfiguration) {
        super.reconfigure(adapter, configuration)
        addRoutes(adapter)
    }

    @Throws(IOException::class)
    override fun sync(adapter: VpnAdapter, configuration: VpnAdapterConfiguration) {
        super.sync(adapter, configuration)
        addRoutes(adapter)
    }

    @Throws(IOException::class)
    override fun append(adapter: VpnAdapter, configuration: VpnAdapterConfiguration) {
        super.append(adapter, configuration)
        addRoutes(adapter)
    }

    override fun onSetDefaultGateway(gateway: PlatformService.Gateway) {
//        LOG.info("Routing traffic all through {} on {}", gateway.address, gateway.nativeIface)
//        try {
//            context.commands()!!.privileged().logged().run("ip", "route", "add", "default", "via", gateway.address, "dev", gateway.nativeIface)
//        } catch (e: IOException) {
//            throw UncheckedIOException(e)
//        }
    }

    override fun onResetDefaultGateway(gateway: PlatformService.Gateway) {
//        LOG.info("Stopping routing traffic all through {} on {}", gateway.address, gateway.nativeIface)
//        try {
//            context.commands()!!.privileged().logged().run("ip", "route", "del", "default", "via", gateway.address, "dev", gateway.nativeIface)
//        } catch (e: IOException) {
//            throw UncheckedIOException(e)
//        }
    }

    @Throws(IOException::class)
    override fun getLatestHandshake(address: VpnAddress, publicKey: String): Instant {
//        for (line in context.commands()!!.privileged().output(
//            context.nativeComponents()!!.tool(NativeComponents.Tool.WG),
//            "show",
//            address.nativeName(),
//            "latest-handshakes"
//        )) {
//            val args = line.trim().split("\\s+".toRegex())
//            if (args.size == 2 && args[0] == publicKey) {
//                return Instant.ofEpochSecond(args[1].toLong())
//            }
//        }
        return Instant.fromEpochSeconds(0)
    }

    @Throws(IOException::class)
    override fun getPublicKey(interfaceName: String?): String? {
//        return try {
//            val iterator = context.commands()!!.privileged()
//                .silentOutput(context.nativeComponents()!!.tool(NativeComponents.Tool.WG), "show", interfaceName, "public-key")
//                .iterator()
//            val pk = if (iterator.hasNext()) iterator.next().trim() else ""
//            if (pk == "(none)" || pk == "") Optional.empty() else Optional.of(pk)
//        } catch (uioe: UncheckedIOException) {
//            val ioe = uioe.cause
//            if (ioe != null && ioe.message != null &&
//                (ioe.message!!.contains("The system cannot find the file specified") ||
//                        ioe.message!!.contains("Unable to access interface: No such file or directory"))
//            ) {
//                Optional.empty()
//            } else {
//                throw (ioe ?: uioe)
//            }
//        }
        return ""
    }

//    override fun information(adapter: VpnAdapter): VpnInterfaceInformation {
//        try {
//            val iface = adapter.address()
//            val peers = ArrayList<VpnPeerInformation>()
//            val lastHandshake = AtomicLong(0L)
//            val rx = AtomicLong(0L)
//            val tx = AtomicLong(0L)
//            val port = AtomicInteger()
//            val publicKey = StringBuffer()
//            val privateKey = StringBuffer()
//
//            for (line in context.commands()!!.privileged().output(
//                context.nativeComponents()!!.tool(NativeComponents.Tool.WG),
//                "show",
//                iface.nativeName(),
//                "dump"
//            )) {
//                val st = StringTokenizer(line)
//                if (st.countTokens() == 4) {
//                    privateKey.append(st.nextToken())
//                    publicKey.append(st.nextToken())
//                    port.set(st.nextToken().toInt())
//                } else {
//                    val peerPublicKey = st.nextToken()
//                    val presharedKeyVal = st.nextToken()
//                    val presharedKey = if (presharedKeyVal == "(none)") Optional.empty() else Optional.of(presharedKeyVal)
//                    val remoteAddress = Optional.of(OsUtil.parseInetSocketAddress(st.nextToken()))
//                    val allowedIps = Arrays.asList(*st.nextToken().split(",".toRegex()).toTypedArray())
//                    val thisLastHandshake = Instant.ofEpochSecond(st.nextToken().toLong())
//                    val thisRx = st.nextToken().toLong()
//                    val thisTx = st.nextToken().toLong()
//
//                    lastHandshake.set(kotlin.math.max(lastHandshake.get(), thisLastHandshake.toEpochMilli()))
//                    rx.addAndGet(thisRx)
//                    tx.addAndGet(thisTx)
//
//                    peers.add(object : VpnPeerInformation {
//                        override fun tx(): Long = thisTx
//                        override fun rx(): Long = thisRx
//                        override fun lastHandshake(): Instant = thisLastHandshake
//                        override fun error(): Optional<String> = Optional.empty()
//                        override fun remoteAddress(): Optional<InetSocketAddress> = remoteAddress
//                        override fun allowedIps(): MutableList<String> = allowedIps
//                        override fun publicKey(): String = peerPublicKey
//                        override fun presharedKey(): Optional<String> = presharedKey
//                    })
//                }
//            }
//
//            return object : VpnInterfaceInformation {
//                override fun interfaceName(): String = iface.name()
//                override fun tx(): Long = tx.get()
//                override fun rx(): Long = rx.get()
//                override fun peers(): MutableList<VpnPeerInformation> = peers
//                override fun lastHandshake(): Instant = Instant.ofEpochMilli(lastHandshake.get())
//                override fun error(): Optional<String> = Optional.empty()
//                override fun listenPort(): Optional<Int> = if (port.get() == 0) Optional.empty() else Optional.of(port.get())
//                override fun publicKey(): String = publicKey.toString()
//                override fun privateKey(): String = privateKey.toString()
//            }
//        } catch (ioe: IOException) {
//            throw UncheckedIOException(ioe)
//        }
//    }

//    final override fun configuration(adapter: VpnAdapter): VpnAdapterConfiguration {
//        return try {
//            try {
//                VpnAdapterConfiguration.Builder()
//                    .fromFileContent(
//                        java.lang.String.join(
//                            System.lineSeparator(),
//                            context.commands()!!.privileged().output(
//                                context.nativeComponents()!!.tool(NativeComponents.Tool.WG),
//                                "showconf",
//                                adapter.address().nativeName()
//                            )
//                        )
//                    )
//                    .build()
//            } catch (e: ParseException) {
//                throw IOException("Failed to parse configuration.", e)
//            }
//        } catch (ioe: IOException) {
//            throw UncheckedIOException(ioe)
//        }
//    }

    @Throws(IOException::class)
    protected fun addRoutes(session: VpnAdapter) {
//        session.allows().clear()
//
//        for (s in context().commands()!!.privileged().output(
//            context().nativeComponents()!!.tool(NativeComponents.Tool.WG),
//            "show",
//            session.address().nativeName(),
//            "allowed-ips"
//        )) {
//            val t = StringTokenizer(s)
//            if (t.hasMoreTokens()) {
//                t.nextToken()
//                while (t.hasMoreTokens()) {
//                    session.allows().add(t.nextToken())
//                }
//            }
//        }
//
//        session.allows().sortWith { a, b ->
//            val sa = a.split("/")
//            val sb = b.split("/")
//            val ia = if (sa.size == 1) 0 else sa[1].toInt()
//            val ib = if (sb.size == 1) 0 else sb[1].toInt()
//            val r = ia.compareTo(ib)
//            if (r == 0) a.compareTo(b) else r * -1
//        }
//
//        (session.address() as AbstractUnixAddress<*>).setRoutes(session.allows())
    }
}
