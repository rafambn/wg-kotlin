package com.rafambn.wgkotlin.util

import uniffi.wg_kotlin_uniffi_boringtun.convertX25519KeyToBase64 as boringTunConvertX25519KeyToBase64
import uniffi.wg_kotlin_uniffi_boringtun.convertX25519KeyToHex as boringTunConvertX25519KeyToHex
import uniffi.wg_kotlin_uniffi_boringtun.generatePublicKey as boringTunGeneratePublicKey
import uniffi.wg_kotlin_uniffi_boringtun.generateSecretKey as boringTunGenerateSecretKey

/**
 * User-facing WireGuard key helper backed by UniFFI BoringTun bindings.
 */
object WireGuardKeys {

    fun generateSecretKey(): ByteArray {
        return boringTunGenerateSecretKey()
    }

    fun generatePublicKey(secretKey: ByteArray): ByteArray {
        return checkNotNull(boringTunGeneratePublicKey(secretKey)) {
            "Secret key must contain exactly 32 bytes"
        }
    }

    fun toBase64(key: ByteArray): String {
        return checkNotNull(boringTunConvertX25519KeyToBase64(key)) {
            "Key must contain exactly 32 bytes"
        }
    }

    fun toHex(key: ByteArray): String {
        return checkNotNull(boringTunConvertX25519KeyToHex(key)) {
            "Key must contain exactly 32 bytes"
        }
    }

    fun generateKeyPair(): WireGuardKeyPair {
        val privateKey = generateSecretKey()
        val publicKey = generatePublicKey(privateKey)
        return WireGuardKeyPair(
            privateKey = toBase64(privateKey),
            publicKey = toBase64(publicKey),
        )
    }

    fun generatePresharedKey(): String {
        return toBase64(generateSecretKey())
    }
}
