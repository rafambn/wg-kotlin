package com.rafambn.wgkotlin.daemon.proto

import com.rafambn.wgkotlin.VpnConfiguration
import com.rafambn.wgkotlin.toParsedVpnConfiguration
import com.rafambn.wgkotlin.toTunSessionConfig
import com.rafambn.wgkotlin.util.toPlainString
import kotlinx.io.bytestring.ByteString
import kotlinx.rpc.internal.utils.ExperimentalRpcApi
import kotlinx.rpc.internal.utils.InternalRpcApi
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(InternalRpcApi::class, ExperimentalRpcApi::class)
class GrpcProtoWireCompatibilityTest {
    @Test
    fun vpnDnsConfigurationSurvivesConversionAndWireEncoding() {
        val servers = listOf("10.0.0.53", "fd00::53")
        for (domains in listOf(emptyList(), listOf("corp.example", "dev.example"))) {
            val configuration = VpnConfiguration(
                interfaceName = "utun1",
                privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs=",
                dns = com.rafambn.wgkotlin.DnsConfig(servers = servers, searchDomains = domains),
            )
            val config = configuration.toParsedVpnConfiguration().toTunSessionConfig()
            val encoded = TunSessionConfigInternal.MARSHALLER.encode(config, null)
            val decoded = TunSessionConfigInternal.MARSHALLER.decode(encoded, null)

            assertEquals(domains, decoded.dns.searchDomains)
            assertEquals(listOf("10.0.0.53", "fd00:0:0:0:0:0:0:53"), decoded.dns.servers.map { it.toPlainString() })
        }
    }

    @Test
    fun dnsConfigRoundTripPreservesFields() {
        val original = DnsConfig {
            servers = listOf(Ip { value = Ip.Value.V4(ByteString(byteArrayOf(1, 1, 1, 1))) })
            searchDomains = listOf("corp.example")
        }

        val encoded = DnsConfigInternal.MARSHALLER.encode(original, null)
        val decoded = DnsConfigInternal.MARSHALLER.decode(encoded, null)

        assertEquals(original.servers.size, decoded.servers.size)
        val server = decoded.servers.single()
        assertEquals(Ip.Value.V4(ByteString(byteArrayOf(1, 1, 1, 1))), server.value)
        assertEquals(listOf("corp.example"), decoded.searchDomains)
    }
}
