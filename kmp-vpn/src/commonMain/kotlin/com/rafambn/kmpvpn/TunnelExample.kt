package com.rafambn.kmpvpn

import uniffi.kmp_vpn.*

/**
 * Example of using the auto-generated UniFFI bindings from Rust
 */
object TunnelExample {

    fun createAndUseTunnel() {
        try {
            // Generate a new secret key
            val secretKey = generateSecretKey()
            println("Generated secret key: ${secretKey.size} bytes")

            // Convert to base64 for transmission
            val secretKeyBase64 = convertX25519KeyToBase64(secretKey)
            println("Secret key (base64): $secretKeyBase64")

            // Derive public key from secret key
            val publicKey = generatePublicKey(secretKey)
            if (publicKey != null) {
                val publicKeyBase64 = convertX25519KeyToBase64(publicKey)
                println("Public key (base64): $publicKeyBase64")

                // Create a tunnel session with the keys
                if (publicKeyBase64 != null && secretKeyBase64 != null) {
                    val tunnel = TunnelSession.createNewTunnel(
                        argSecretKey = secretKeyBase64,
                        argPublicKey = publicKeyBase64,
                        argPresharedKey = null,
                        keepAlive = 25u,
                        index = 0u,
                    )

                    tunnel.use { session ->
                        println("Tunnel created successfully")

                        // Example: Encrypt a packet
                        val plaintext = "Hello WireGuard".encodeToByteArray()
                        val encryptResult = session.encryptRawPacket(plaintext, 148u)
                        println(
                            "Encrypted packet: op=${encryptResult.op}, " +
                                "size=${encryptResult.size}, " +
                                "payload=${encryptResult.packet.size} bytes"
                        )

                        // Example: Run periodic task (for keep-alive)
                        val periodicResult = session.runPeriodicTask(148u)
                        println("Periodic task: op=${periodicResult.op}, size=${periodicResult.size}")
                    }
                } else {
                    println("Failed to convert keys to base64")
                }
            } else {
                println("Failed to generate public key")
            }
        } catch (e: TunnelException) {
            println("Tunnel error: $e")
            when (e) {
                is TunnelException.InvalidBase64Key -> println("Invalid base64 key format")
                is TunnelException.Internal -> println("Internal tunnel error")
            }
        }
    }
}
