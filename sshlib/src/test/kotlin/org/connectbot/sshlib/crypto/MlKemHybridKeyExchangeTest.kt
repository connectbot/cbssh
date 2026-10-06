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

import org.connectbot.sshlib.SshException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class MlKemHybridKeyExchangeTest {

    private val fakeMlKemPublicKey = ByteArray(1184) { (it % 256).toByte() }
    private val fakeMlKemPrivateKey = ByteArray(2400) { ((it * 3) % 256).toByte() }
    private val fakeMlKemCiphertext = ByteArray(1088) { ((it * 7) % 256).toByte() }
    private val fakeMlKemSharedSecret = ByteArray(32) { ((it + 10) % 256).toByte() }

    private val fakeX25519Private = ByteArray(32) { ((it + 1) % 256).toByte() }
    private val fakeX25519Public = ByteArray(32) { ((it + 50) % 256).toByte() }
    private val fakeX25519ServerPublic = ByteArray(32) { ((it + 100) % 256).toByte() }
    private val fakeX25519SharedSecret = ByteArray(32) { ((it + 200) % 256).toByte() }

    private val mockMlKemProvider = object : MlKemProvider {
        override fun generateKeyPair(): MlKemKeyPair = MlKemKeyPair(fakeMlKemPublicKey.clone(), fakeMlKemPrivateKey.clone())

        override fun encapsulate(publicKey: ByteArray): MlKemEncapsulationResult = MlKemEncapsulationResult(fakeMlKemCiphertext.clone(), fakeMlKemSharedSecret.clone())

        override fun decapsulate(privateKey: ByteArray, ciphertext: ByteArray): ByteArray = fakeMlKemSharedSecret.clone()
    }

    private val mockX25519Provider = object : X25519Provider {
        override fun generatePrivateKey(): ByteArray = fakeX25519Private.clone()
        override fun publicFromPrivate(privateKey: ByteArray): ByteArray = fakeX25519Public.clone()
        override fun computeSharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray = fakeX25519SharedSecret.clone()
    }

    @Test
    fun `client init message is 1216 bytes`() {
        val kex = MlKemHybridKeyExchange(mockMlKemProvider, mockX25519Provider)
        val clientInit = kex.generateClientKeys()
        assertEquals(1216, clientInit.size)
    }

    @Test
    fun `client init message format is mlkem_pubkey then x25519_pubkey`() {
        val kex = MlKemHybridKeyExchange(mockMlKemProvider, mockX25519Provider)
        val clientInit = kex.generateClientKeys()

        val mlKemPart = clientInit.copyOfRange(0, 1184)
        val x25519Part = clientInit.copyOfRange(1184, 1216)

        assertContentEquals(fakeMlKemPublicKey, mlKemPart)
        assertContentEquals(fakeX25519Public, x25519Part)
    }

    @Test
    fun `server reply parsing - valid 1120 byte reply`() {
        val kex = MlKemHybridKeyExchange(mockMlKemProvider, mockX25519Provider)
        kex.generateClientKeys()

        val serverReply = ByteArray(1120)
        System.arraycopy(fakeMlKemCiphertext, 0, serverReply, 0, 1088)
        System.arraycopy(fakeX25519ServerPublic, 0, serverReply, 1088, 32)

        val sharedSecret = kex.computeSharedSecret(serverReply)
        assert(sharedSecret.isNotEmpty()) { "Shared secret should not be empty" }
        // 4 bytes length prefix + 32 bytes SHA-256 hash
        assertEquals(36, sharedSecret.size)
    }

    @Test
    fun `shared secret is wire-encoded SSH string of SHA-256(mlkem_ss x25519_ss)`() {
        val kex = MlKemHybridKeyExchange(mockMlKemProvider, mockX25519Provider)
        kex.generateClientKeys()

        val serverReply = ByteArray(1120)
        System.arraycopy(fakeMlKemCiphertext, 0, serverReply, 0, 1088)
        System.arraycopy(fakeX25519ServerPublic, 0, serverReply, 1088, 32)

        val sharedSecret = kex.computeSharedSecret(serverReply)

        val combined = fakeMlKemSharedSecret + fakeX25519SharedSecret
        val expectedK = MessageDigest.getInstance("SHA-256").digest(combined)
        val expectedEncoded = encodeSshString(expectedK)

        assertContentEquals(expectedEncoded, sharedSecret)
    }

    @Test
    fun `rejects server reply with wrong size`() {
        val kex = MlKemHybridKeyExchange(mockMlKemProvider, mockX25519Provider)
        kex.generateClientKeys()

        assertFailsWith<SshException> {
            kex.computeSharedSecret(ByteArray(100))
        }
    }

    @Test
    fun `rejects all-zero X25519 shared secret`() {
        val zeroX25519Provider = object : X25519Provider {
            override fun generatePrivateKey(): ByteArray = fakeX25519Private.clone()
            override fun publicFromPrivate(privateKey: ByteArray): ByteArray = fakeX25519Public.clone()
            override fun computeSharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray = ByteArray(32) // all zeros
        }

        val kex = MlKemHybridKeyExchange(mockMlKemProvider, zeroX25519Provider)
        kex.generateClientKeys()

        val serverReply = ByteArray(1120)
        assertFailsWith<SshException> {
            kex.computeSharedSecret(serverReply)
        }
    }

    @Test
    fun `hash algorithm is SHA-256`() {
        val kex = MlKemHybridKeyExchange(mockMlKemProvider, mockX25519Provider)
        assertEquals("SHA-256", kex.hashAlgorithm)
    }

    @Test
    fun `computeSharedSecret fails before generateClientKeys`() {
        val kex = MlKemHybridKeyExchange(mockMlKemProvider, mockX25519Provider)
        assertFailsWith<SshException> {
            kex.computeSharedSecret(ByteArray(1120))
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 31, 33])
    fun `rejects ML-KEM secrets that are not exactly 32 bytes`(size: Int) {
        val provider = object : MlKemProvider by mockMlKemProvider {
            override fun decapsulate(privateKey: ByteArray, ciphertext: ByteArray): ByteArray = ByteArray(size) { 1 }
        }
        val kex = MlKemHybridKeyExchange(provider, mockX25519Provider)
        kex.generateClientKeys()
        assertFailsWith<SshException> { kex.computeSharedSecret(ByteArray(1120)) }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 31, 33])
    fun `rejects X25519 secrets that are not exactly 32 bytes`(size: Int) {
        val provider = object : X25519Provider by mockX25519Provider {
            override fun computeSharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray = ByteArray(size) { 1 }
        }
        val kex = MlKemHybridKeyExchange(mockMlKemProvider, provider)
        kex.generateClientKeys()
        assertFailsWith<SshException> { kex.computeSharedSecret(ByteArray(1120)) }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1119, 1121])
    fun `invalid S_REPLY length is rejected before decapsulation`(size: Int) {
        var decapsulated = false
        val provider = object : MlKemProvider by mockMlKemProvider {
            override fun decapsulate(privateKey: ByteArray, ciphertext: ByteArray): ByteArray {
                decapsulated = true
                return fakeMlKemSharedSecret.clone()
            }
        }
        val kex = MlKemHybridKeyExchange(provider, mockX25519Provider)
        kex.generateClientKeys()
        assertFailsWith<SshException> { kex.computeSharedSecret(ByteArray(size)) }
        assertFalse(decapsulated)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 128])
    fun `classical secret keeps leading zeros and high bits without reversal or mpint padding`(firstByte: Int) {
        val provider = object : X25519Provider by mockX25519Provider {
            override fun computeSharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray = ByteArray(32) { it.toByte() }.also { it[0] = firstByte.toByte() }
        }
        val kex = MlKemHybridKeyExchange(mockMlKemProvider, provider)
        kex.generateClientKeys()
        // Independently calculated SHA-256 fixtures for RFC 10042 section 2.4.
        val expected = if (firstByte == 0) {
            "0000002085f723f0303811e5b5077b130289be04c47df6e505d5fdbec17caef278d49768"
        } else {
            "000000201932f7ce81dfad66450d3b020b995f182c09ae8194e74e8da09d93fcbdd4a829"
        }
        assertContentEquals(HexFormat.of().parseHex(expected), kex.computeSharedSecret(ByteArray(1120)))
    }

    @Test
    fun `RFC 10042 transcript and expanded transport keys match independent fixtures`() {
        val kex = MlKemHybridKeyExchange(mockMlKemProvider, mockX25519Provider)
        val clientInit = kex.generateClientKeys()
        val serverReply = fakeMlKemCiphertext + fakeX25519ServerPublic
        val secret = kex.computeSharedSecret(serverReply)
        val hash = kex.computeExchangeHash(
            "SSH-2.0-client".toByteArray(),
            "SSH-2.0-server".toByteArray(),
            byteArrayOf(20) + "client kexinit".toByteArray(),
            byteArrayOf(20) + "server kexinit".toByteArray(),
            "host key".toByteArray(),
            clientInit,
            serverReply,
            secret,
        )
        // Fixtures use SSH strings for every transcript field, including K; generated
        // independently with Python hashlib and struct, not the production encoders.
        val hex = HexFormat.of()
        assertContentEquals(hex.parseHex("00000020a8d2115b8207e37e0c76cfa48a2a49cdac17da22541eb562bfceeb90c0b23d88"), secret)
        assertContentEquals(hex.parseHex("5d1b8c171be3e89e826dedeeef668473c6f7e76a1066292a4b332c0b6f61ca96"), hash)
        val keys = KeyDerivation(secret, hash, hash, "SHA-256").deriveKeys(16, 32, 64)
        assertContentEquals(hex.parseHex("6314ce84fa25732f4adcf7a61d32a4bc"), keys.initialIvClientToServer)
        assertContentEquals(hex.parseHex("01702200b57717fd03bb7c53c028fafd"), keys.initialIvServerToClient)
        assertContentEquals(hex.parseHex("9af272ef84398ca5b5a52d20790d64fba70ad4e74ce4aaafbcc63026eb81a175"), keys.encryptionKeyClientToServer)
        assertContentEquals(hex.parseHex("63ab99bce0679aaf05e26a801d8d455b16dcf393ecaf60482bc0eebb84551414"), keys.encryptionKeyServerToClient)
        assertContentEquals(hex.parseHex("53b7392f738afd37b069d7c1583ac6dcf13e144f6fbae50b2bf7304280e20c1670f503f0f7a36ed68c892ad9f86abfbf49293ac1613860ed7bbea9990ad4b10b"), keys.integrityKeyClientToServer)
        assertContentEquals(hex.parseHex("faa236e949cd1d7d97f54e3b0c38216924189a4d50807a8054df7b75e4033f2eb2501176ccc68803407468d4d0f77ff7e0dd4205340d4527c2f948d1e3e3530f"), keys.integrityKeyServerToClient)
    }
}
