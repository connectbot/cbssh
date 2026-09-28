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

import org.connectbot.sshlib.transport.TransportException
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM AEAD cipher for SSH as specified in RFC 5647 and
 * draft-miller-sshm-aes-gcm-01.
 *
 * The 12-byte nonce is structured as:
 * - 4-byte fixed field (from key derivation IV)
 * - 8-byte invocation counter (incremented after each operation)
 *
 * @param key AES key (16 bytes for AES-128, 32 bytes for AES-256)
 * @param iv Initial 12-byte IV from key derivation
 */
internal class AesGcmCipher(
    private val key: ByteArray,
    private val iv: ByteArray,
) : PacketAead {
    override val tagLength: Int = 16

    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val keySpec: SecretKeySpec
    private val fixedField = ByteArray(4)
    private var invocationCounter: Long

    init {
        require(key.size == 16 || key.size == 32) {
            "AES key must be 16 or 32 bytes, got ${key.size}"
        }
        require(iv.size == 12) {
            "IV must be 12 bytes, got ${iv.size}"
        }

        keySpec = SecretKeySpec(key, "AES")
        System.arraycopy(iv, 0, fixedField, 0, 4)
        invocationCounter = ByteBuffer.wrap(iv, 4, 8).long
    }

    private fun buildNonce(): ByteArray {
        val nonce = ByteArray(12)
        System.arraycopy(fixedField, 0, nonce, 0, 4)
        ByteBuffer.wrap(nonce, 4, 8).putLong(invocationCounter)
        return nonce
    }

    override fun encrypt(packetLength: ByteArray, plaintext: ByteArray): AeadResult {
        val output = encryptPacket(packetLength, plaintext)
        return AeadResult(
            output.copyOfRange(0, output.size - tagLength),
            output.copyOfRange(output.size - tagLength, output.size),
        )
    }

    override fun encryptPacket(packetLength: ByteArray, plaintext: ByteArray): ByteArray = encryptPacket(packetLength, listOf(plaintext))

    override fun encryptPacket(packetLength: ByteArray, plaintext: List<ByteArray>): ByteArray {
        val gcmSpec = GCMParameterSpec(128, buildNonce())
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        cipher.updateAAD(packetLength)
        val output = ByteArray(plaintext.sumOf { it.size } + tagLength)
        var written = 0
        for (index in 0 until plaintext.lastIndex) {
            val part = plaintext[index]
            written += cipher.update(part, 0, part.size, output, written)
        }
        if (plaintext.isEmpty()) {
            cipher.doFinal(output, written)
        } else {
            val last = plaintext.last()
            cipher.doFinal(last, 0, last.size, output, written)
        }
        invocationCounter++
        return output
    }

    override fun decrypt(packetLength: ByteArray, ciphertext: ByteArray, tag: ByteArray): ByteArray = decryptPacket(packetLength, ciphertext + tag)

    override fun decryptPacket(packetLength: ByteArray, encrypted: ByteArray): ByteArray {
        initDecrypt(packetLength, encrypted)

        try {
            val plaintext = cipher.doFinal(encrypted)
            invocationCounter++
            return plaintext
        } catch (e: AEADBadTagException) {
            throw TransportException("AEAD authentication failed", e)
        }
    }

    private fun initDecrypt(packetLength: ByteArray, encrypted: ByteArray) {
        if (encrypted.size < tagLength) throw TransportException("Truncated AEAD packet")
        val gcmSpec = GCMParameterSpec(128, buildNonce())
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        cipher.updateAAD(packetLength)
    }

    override fun destroy() {
        key.fill(0)
        iv.fill(0)
        fixedField.fill(0)
    }
}
