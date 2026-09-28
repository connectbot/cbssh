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

package org.connectbot.sshlib

import org.junit.jupiter.api.Test
import java.security.Security
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SshKeysTest {

    private fun readKey(resourcePath: String): String = javaClass.getResourceAsStream("/keys/$resourcePath")!!
        .bufferedReader().readText()

    /** Decodes an OpenSSH private key PEM, lets [change] edit the raw bytes, and re-encodes it. */
    private fun rewrapOpenSsh(pem: String, change: (ByteArray) -> ByteArray): String {
        val body = pem.lines().filter { it.isNotBlank() && !it.startsWith("-----") }.joinToString("")
        val changed = Base64.getEncoder().encodeToString(change(Base64.getDecoder().decode(body)))
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n" + changed.chunked(70).joinToString("\n") +
            "\n-----END OPENSSH PRIVATE KEY-----\n"
    }

    @Test
    fun `decodePemPrivateKey Ed25519 OpenSSH format`() {
        val keyPair = SshKeys.decodePemPrivateKey(readKey("ed25519_unencrypted"))
        assertNotNull(keyPair.public)
        assertNotNull(keyPair.private)
        assertTrue(keyPair.public.algorithm in listOf("Ed25519", "EdDSA"))
    }

    @Test
    fun `decodePemPrivateKey Ed25519 encrypted`() {
        val keyPair = SshKeys.decodePemPrivateKey(readKey("ed25519_encrypted"), "testpass")
        assertNotNull(keyPair.public)
        assertNotNull(keyPair.private)
    }

    @Test
    fun `decodePemPrivateKey ECDSA-256 OpenSSH format`() {
        val keyPair = SshKeys.decodePemPrivateKey(readKey("ecdsa256_unencrypted"))
        assertNotNull(keyPair.public)
        assertEquals("EC", keyPair.public.algorithm)
    }

    @Test
    fun `decodePemPrivateKey ECDSA-256 encrypted`() {
        val keyPair = SshKeys.decodePemPrivateKey(readKey("ecdsa256_encrypted"), "testpass")
        assertNotNull(keyPair.public)
    }

    @Test
    fun `decodePemPrivateKey RSA OpenSSH format`() {
        val keyPair = SshKeys.decodePemPrivateKey(readKey("rsa_unencrypted"))
        assertNotNull(keyPair.public)
        assertEquals("RSA", keyPair.public.algorithm)
    }

    @Test
    fun `decodePemPrivateKey RSA encrypted`() {
        val keyPair = SshKeys.decodePemPrivateKey(readKey("rsa_encrypted"), "testpass")
        assertNotNull(keyPair.public)
    }

    @Test
    fun `decodePemPrivateKey reads OpenSSH keys encrypted with AES-GCM`() {
        for (name in listOf("ed25519_aes256_gcm", "ecdsa256_aes128_gcm", "rsa_aes256_gcm")) {
            val keyPair = SshKeys.decodePemPrivateKey(readKey(name), "testpass")
            val expectedBlob = Base64.getDecoder().decode(readKey("$name.pub").trim().split(" ")[1])
            assertContentEquals(expectedBlob, SshSigning.encodePublicKeyBlob(keyPair.public), name)
        }
    }

    @Test
    fun `decodePemPrivateKey rejects a wrong passphrase for an AES-GCM key`() {
        assertFailsWith<SshException> {
            SshKeys.decodePemPrivateKey(readKey("ed25519_aes256_gcm"), "wrongpass")
        }
    }

    @Test
    fun `decodePemPrivateKey rejects an AES-GCM key whose tag was changed`() {
        // The 16-byte tag is the last thing in the key blob.
        val tampered = rewrapOpenSsh(readKey("ed25519_aes256_gcm")) { bytes ->
            bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        }
        assertFailsWith<SshException> {
            SshKeys.decodePemPrivateKey(tampered, "testpass")
        }
    }

    @Test
    fun `decodePemPrivateKey rejects an AES-GCM key without its tag`() {
        val truncated = rewrapOpenSsh(readKey("ed25519_aes256_gcm")) { it.copyOfRange(0, it.size - 16) }
        assertFailsWith<SshException> {
            SshKeys.decodePemPrivateKey(truncated, "testpass")
        }
    }

    @Test
    fun `decodePemPrivateKey RSA PEM unencrypted`() {
        val keyPair = SshKeys.decodePemPrivateKey(readKey("rsa_pem_unencrypted.pem"))
        assertNotNull(keyPair.public)
        assertEquals("RSA", keyPair.public.algorithm)
    }

    @Test
    fun `decodePemPrivateKey RSA PEM encrypted`() {
        val keyPair = SshKeys.decodePemPrivateKey(readKey("rsa_pem_encrypted.pem"), "testpass")
        assertNotNull(keyPair.public)
    }

    @Test
    fun `decodePemPrivateKey EC PEM unencrypted`() {
        val keyPair = SshKeys.decodePemPrivateKey(readKey("ec_pem_unencrypted.pem"))
        assertNotNull(keyPair.public)
        assertEquals("EC", keyPair.public.algorithm)
    }

    @Test
    fun `decodePemPrivateKey EC PEM encrypted`() {
        val keyPair = SshKeys.decodePemPrivateKey(readKey("ec_pem_encrypted.pem"), "testpass")
        assertNotNull(keyPair.public)
    }

    @Test
    fun `decodePemPrivateKey Ed25519 PKCS8`() {
        val keyPair = SshKeys.decodePemPrivateKey(readKey("ed25519_pkcs8.pem"))
        assertNotNull(keyPair.public)
    }

    @Test
    fun `decodePemPrivateKey RSA PKCS8`() {
        val keyPair = SshKeys.decodePemPrivateKey(readKey("rsa_pkcs8.pem"))
        assertNotNull(keyPair.public)
        assertEquals("RSA", keyPair.public.algorithm)
    }

    @Test
    fun `decodePemPrivateKey EC PKCS8`() {
        val keyPair = SshKeys.decodePemPrivateKey(readKey("ec_pkcs8.pem"))
        assertNotNull(keyPair.public)
        assertEquals("EC", keyPair.public.algorithm)
    }

    @Test
    fun `decodePemPrivateKey invalid data throws`() {
        assertFailsWith<SshException> {
            SshKeys.decodePemPrivateKey("not a valid key")
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `ensureEd25519Support does not throw`() {
        val providersBefore = Security.getProviders().map { it.name }
        SshKeys.ensureEd25519Support()
        assertEquals(providersBefore, Security.getProviders().map { it.name })
    }

    @Test
    fun `encodePemPrivateKey round-trip Ed25519`() {
        val original = SshKeys.decodePemPrivateKey(readKey("ed25519_unencrypted"))
        val encoded = SshKeys.encodePemPrivateKey(original)
        val decoded = SshKeys.decodePemPrivateKey(encoded)
        assertEquals(original.public, decoded.public)
    }

    @Test
    fun `encodePemPrivateKey round-trip RSA`() {
        val original = SshKeys.decodePemPrivateKey(readKey("rsa_unencrypted"))
        val encoded = SshKeys.encodePemPrivateKey(original)
        val decoded = SshKeys.decodePemPrivateKey(encoded)
        assertEquals(original.public, decoded.public)
    }

    @Test
    fun `encodePemPrivateKey round-trip ECDSA`() {
        val original = SshKeys.decodePemPrivateKey(readKey("ecdsa256_unencrypted"))
        val encoded = SshKeys.encodePemPrivateKey(original)
        val decoded = SshKeys.decodePemPrivateKey(encoded)
        assertEquals(original.public, decoded.public)
    }

    @Test
    fun `encodeOpenSshPrivateKey round-trip Ed25519`() {
        val original = SshKeys.decodePemPrivateKey(readKey("ed25519_unencrypted"))
        val encoded = SshKeys.encodeOpenSshPrivateKey(original)
        assertTrue(encoded.contains("BEGIN OPENSSH PRIVATE KEY"))
        val decoded = SshKeys.decodePemPrivateKey(encoded)
        assertEquals(original.public, decoded.public)
    }

    @Test
    fun `encodeOpenSshPrivateKey round-trip RSA`() {
        val original = SshKeys.decodePemPrivateKey(readKey("rsa_unencrypted"))
        val encoded = SshKeys.encodeOpenSshPrivateKey(original)
        val decoded = SshKeys.decodePemPrivateKey(encoded)
        assertEquals(original.public, decoded.public)
    }

    @Test
    fun `encodeOpenSshPrivateKey round-trip ECDSA`() {
        val original = SshKeys.decodePemPrivateKey(readKey("ecdsa256_unencrypted"))
        val encoded = SshKeys.encodeOpenSshPrivateKey(original)
        val decoded = SshKeys.decodePemPrivateKey(encoded)
        assertEquals(original.public, decoded.public)
    }

    @Test
    fun `encodeOpenSshPrivateKey encrypted round-trip`() {
        val original = SshKeys.decodePemPrivateKey(readKey("ed25519_unencrypted"))
        val encoded = SshKeys.encodeOpenSshPrivateKey(original, "mypassword")
        val decoded = SshKeys.decodePemPrivateKey(encoded, "mypassword")
        assertEquals(original.public, decoded.public)
    }
}
