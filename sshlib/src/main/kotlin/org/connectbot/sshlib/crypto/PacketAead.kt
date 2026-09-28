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

import javax.security.auth.Destroyable

internal data class AeadResult(val ciphertext: ByteArray, val tag: ByteArray)

/**
 * Interface for SSH AEAD (Authenticated Encryption with Associated Data) ciphers.
 *
 * AEAD ciphers combine encryption and authentication in a single operation,
 * unlike the separate cipher + MAC approach. The packet_length field serves
 * as AAD (authenticated but not encrypted).
 */
internal interface PacketAead : Destroyable {
    override fun destroy() {
        // Stateless implementations have no key material to clear.
    }

    override fun isDestroyed() = false
    val tagLength: Int

    val encryptsLength: Boolean get() = false

    /**
     * Encrypt plaintext with the packet length as AAD.
     *
     * @param packetLength The 4-byte packet_length (used as AAD, not encrypted)
     * @param plaintext The data to encrypt (padding_length || payload || padding)
     * @return Ciphertext and authentication tag
     */
    fun encrypt(packetLength: ByteArray, plaintext: ByteArray): AeadResult

    /** Encrypt a complete packet body, retaining the tag adjacent to the ciphertext. */
    fun encryptPacket(packetLength: ByteArray, plaintext: ByteArray): ByteArray {
        val result = encrypt(packetLength, plaintext)
        return result.ciphertext + result.tag
    }

    /** Encrypt ordered plaintext buffers; implementations may consume them without concatenating. */
    fun encryptPacket(packetLength: ByteArray, plaintext: List<ByteArray>): ByteArray {
        val combined = java.nio.ByteBuffer.allocate(plaintext.sumOf { it.size })
        plaintext.forEach { combined.put(it) }
        return encryptPacket(packetLength, combined.array())
    }

    /**
     * Decrypt ciphertext and verify authentication tag.
     *
     * @param packetLength The 4-byte packet_length (used as AAD)
     * @param ciphertext The encrypted data
     * @param tag The authentication tag to verify
     * @return Decrypted plaintext
     * @throws org.connectbot.sshlib.transport.TransportException if authentication fails
     */
    fun decrypt(packetLength: ByteArray, ciphertext: ByteArray, tag: ByteArray): ByteArray

    /** Decrypt a contiguous ciphertext and trailing tag, authenticating before returning plaintext. */
    fun decryptPacket(packetLength: ByteArray, encrypted: ByteArray): ByteArray {
        if (encrypted.size < tagLength) throw org.connectbot.sshlib.transport.TransportException("Truncated AEAD packet")
        val length = encrypted.size - tagLength
        return decrypt(packetLength, encrypted.copyOfRange(0, length), encrypted.copyOfRange(length, encrypted.size))
    }

    fun encryptLength(sequenceNumber: Long, plainLength: ByteArray): ByteArray = throw UnsupportedOperationException("This cipher does not encrypt the length field")

    fun decryptLength(sequenceNumber: Long, encryptedLength: ByteArray): ByteArray = throw UnsupportedOperationException("This cipher does not encrypt the length field")
}
