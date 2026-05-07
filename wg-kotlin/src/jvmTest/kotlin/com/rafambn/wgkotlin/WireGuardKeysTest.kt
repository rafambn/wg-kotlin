package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.util.WireGuardKeys
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class WireGuardKeysTest {

    @Test
    fun generateKeyPairReturnsValidBase64Keys() {
        val keyPair = WireGuardKeys.generateKeyPair()

        val privateBytes = Base64.getDecoder().decode(keyPair.privateKey)
        val publicBytes = Base64.getDecoder().decode(keyPair.publicKey)

        assertEquals(32, privateBytes.size)
        assertEquals(32, publicBytes.size)
        assertFalse(keyPair.privateKey.isBlank())
        assertFalse(keyPair.publicKey.isBlank())
    }

    @Test
    fun generatePublicKeyIsDeterministicForSameSecretKey() {
        val secretKey = WireGuardKeys.generateSecretKey()
        val first = WireGuardKeys.generatePublicKey(secretKey)
        val second = WireGuardKeys.generatePublicKey(secretKey)

        assertEquals(32, first.size)
        assertContentEquals(first, second)
    }

    @Test
    fun generatePublicKeyRejectsInvalidSecretKeyLength() {
        assertFailsWith<IllegalStateException> {
            WireGuardKeys.generatePublicKey(byteArrayOf(0x01, 0x02))
        }
    }

    @Test
    fun generatePresharedKeyReturnsValidBase64Key() {
        val preshared = WireGuardKeys.generatePresharedKey()

        val presharedBytes = Base64.getDecoder().decode(preshared)
        assertEquals(32, presharedBytes.size)
    }
}
