/*
 * ConnectBot SSH Library
 * Copyright 2025-2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.connectbot.sshlib.crypto

import org.connectbot.sshlib.sk.SkAlgorithm
import org.connectbot.sshlib.sk.SkPublicKeyEncoder
import org.connectbot.sshlib.sk.SkSignatureBlob
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkHostKeyTest {
    @Test
    fun `native security key host signatures authenticate all FIDO fields`() {
        for (algorithm in SkAlgorithm.entries) {
            val ed = algorithm == SkAlgorithm.ED25519
            val generator = KeyPairGenerator.getInstance(if (ed) "Ed25519" else "EC")
            if (!ed) generator.initialize(ECGenParameterSpec("secp256r1"))
            val pair = generator.generateKeyPair()
            fun coordinate(bytes: ByteArray) = bytes.takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
            val raw = if (ed) {
                pair.public.encoded.takeLast(32).toByteArray()
            } else {
                val point = (pair.public as ECPublicKey).w
                byteArrayOf(4) + coordinate(point.affineX.toByteArray()) + coordinate(point.affineY.toByteArray())
            }
            val key = SkPublicKeyEncoder.encode(algorithm, raw, "ssh:test")
            val message = "exchange hash".toByteArray()
            fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            for (flags in listOf(0, 1, 5)) {
                val counter = byteArrayOf(-1, -1, -1, -1)
                val signer = Signature.getInstance(if (ed) "Ed25519" else "SHA256withECDSA")
                signer.initSign(pair.private)
                signer.update(hash("ssh:test".toByteArray()) + byteArrayOf(flags.toByte()) + counter + hash(message))
                val signature = SkSignatureBlob.pack(algorithm, signer.sign(), flags.toByte(), UInt.MAX_VALUE)
                assertTrue(SignatureVerifier.verify(key, signature, message, algorithm.sshName))
                assertFalse(SignatureVerifier.verify(key, signature, message + 0, algorithm.sshName))
                val changed = signature.copyOf().also { it[it.size - 1] = 0 }
                assertFalse(SignatureVerifier.verify(key, changed, message, algorithm.sshName))
                assertFalse(SignatureVerifier.verify(key + 0, signature, message, algorithm.sshName))
                assertFalse(SignatureVerifier.verify(key, signature + 0, message, algorithm.sshName))
            }
        }
    }

    @Test
    fun `webauthn host signature binds client data and extensions`() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        fun coordinate(bytes: ByteArray) = bytes.takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
        val point = (pair.public as ECPublicKey).w
        val raw = byteArrayOf(4) + coordinate(point.affineX.toByteArray()) + coordinate(point.affineY.toByteArray())
        val key = SkPublicKeyEncoder.encode(SkAlgorithm.ECDSA_P256, raw, "ssh:test")
        val message = "exchange hash".toByteArray()
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(message)
        val client = "{\"type\":\"webauthn.get\",\"challenge\":\"$challenge\",\"origin\":\"https://example.com\",\"crossOrigin\":false}".toByteArray()
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        for (extended in listOf(false, true)) {
            val flags = if (extended) 0x81.toByte() else 1.toByte()
            val extensions = if (extended) byteArrayOf(0xa0.toByte()) else byteArrayOf()
            val auth = hash("ssh:test".toByteArray()) + byteArrayOf(flags) + byteArrayOf(0, 0, 0, 1) + extensions
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(pair.private)
            signer.update(auth + hash(client))
            val signature = SkSignatureBlob.packWebAuthn(signer.sign(), auth, client, "https://example.com")
            val algorithm = SkAlgorithm.ECDSA_P256.sshName
            assertTrue(SignatureVerifier.verify(key, signature, message, algorithm))
            assertFalse(SignatureVerifier.verify(key, signature, message + 0, algorithm))
            assertTrue(SignatureVerifier.verifyWithKeyType(key, signature, message))
        }
    }
}
