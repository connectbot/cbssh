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
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertFailsWith

class AesGcmCipherTest {

    private fun makeIv(fixedField: ByteArray = ByteArray(4), counter: Long = 0L): ByteArray {
        val iv = ByteArray(12)
        System.arraycopy(fixedField, 0, iv, 0, 4)
        ByteBuffer.wrap(iv, 4, 8).putLong(counter)
        return iv
    }

    private fun packetLengthBytes(length: Int): ByteArray = ByteBuffer.allocate(4).putInt(length).array()

    @Test
    fun roundTripAes128() {
        val key = ByteArray(16) { it.toByte() }
        val iv = makeIv()
        val plaintext = ByteArray(32) { (it + 0x41).toByte() }
        val packetLength = packetLengthBytes(plaintext.size)

        val encryptor = AesGcmCipher(key, iv)
        val decryptor = AesGcmCipher(key, iv.copyOf())

        val result = encryptor.encrypt(packetLength, plaintext)
        val decrypted = decryptor.decrypt(packetLength, result.ciphertext, result.tag)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun roundTripAes256() {
        val key = ByteArray(32) { it.toByte() }
        val iv = makeIv()
        val plaintext = ByteArray(48) { (it + 0x30).toByte() }
        val packetLength = packetLengthBytes(plaintext.size)

        val encryptor = AesGcmCipher(key, iv)
        val decryptor = AesGcmCipher(key, iv.copyOf())

        val result = encryptor.encrypt(packetLength, plaintext)
        val decrypted = decryptor.decrypt(packetLength, result.ciphertext, result.tag)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun completePacketMatchesSplitEncryptionAcrossNonces() {
        for (keySize in listOf(16, 32)) {
            val key = ByteArray(keySize) { it.toByte() }
            val complete = AesGcmCipher(key.copyOf(), makeIv(counter = 127))
            val split = AesGcmCipher(key.copyOf(), makeIv(counter = 127))
            val receiver = AesGcmCipher(key.copyOf(), makeIv(counter = 127))
            repeat(3) { packet ->
                val plaintext = ByteArray(32768) { (it + packet).toByte() }
                val length = packetLengthBytes(plaintext.size)
                val encrypted = complete.encryptPacket(length, plaintext)
                val expected = split.encrypt(length, plaintext)
                assertArrayEquals(expected.ciphertext + expected.tag, encrypted)
                assertArrayEquals(plaintext, receiver.decryptPacket(length, encrypted))
            }
        }
    }

    @Test
    fun segmentedEncryptionMatchesJceAcrossBlockBoundariesAndNonces() {
        for (keySize in listOf(16, 32)) {
            val key = ByteArray(keySize) { it.toByte() }
            val sender = AesGcmCipher(key.copyOf(), makeIv(counter = 127))
            val receiver = AesGcmCipher(key.copyOf(), makeIv(counter = 127))
            for ((index, size) in listOf(0, 1, 15, 16, 17, 31, 32, 33, 1023, 32768).withIndex()) {
                val data = ByteArray(size) { (it + index).toByte() }
                val length = packetLengthBytes(size)
                val cut = minOf(2, size)
                val parts = listOf(data.copyOfRange(0, cut), data.copyOfRange(cut, size), byteArrayOf())
                val reference = Cipher.getInstance("AES/GCM/NoPadding")
                reference.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, makeIv(counter = 127L + index)))
                reference.updateAAD(length)
                val encrypted = sender.encryptPacket(length, parts)
                assertArrayEquals(reference.doFinal(data), encrypted)
                assertArrayEquals(data, receiver.decryptPacket(length, encrypted))
            }
            val length = packetLengthBytes(0)
            assertArrayEquals(byteArrayOf(), receiver.decryptPacket(length, sender.encryptPacket(length, emptyList())))
        }
    }

    @Test
    fun contiguousDecryptionMatchesJceAcrossBlockBoundariesAndNonces() {
        for (keySize in listOf(16, 32)) {
            val key = ByteArray(keySize) { it.toByte() }
            val receiver = AesGcmCipher(key.copyOf(), makeIv(counter = 127))
            for ((index, size) in listOf(0, 1, 15, 16, 17, 31, 32, 33, 1023, 32768).withIndex()) {
                val data = ByteArray(size) { (it + index).toByte() }
                val length = packetLengthBytes(size)
                val reference = Cipher.getInstance("AES/GCM/NoPadding")
                reference.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, makeIv(counter = 127L + index)))
                reference.updateAAD(length)
                val encrypted = reference.doFinal(data)
                val original = encrypted.copyOf()
                val view = ByteBuffer.wrap(receiver.decryptPacket(length, encrypted))
                assertArrayEquals(original, encrypted)
                assertEquals(size, view.capacity())
                assertEquals(size, view.remaining())
                val decoded = ByteArray(size)
                view.get(decoded)
                assertArrayEquals(data, decoded)
            }
        }
    }

    @Test
    fun contiguousDecryptionDoesNotAdvanceNonceOnFailure() {
        for (keySize in listOf(16, 32)) {
            val key = ByteArray(keySize) { it.toByte() }
            val data = ByteArray(32768) { it.toByte() }
            val length = packetLengthBytes(data.size)
            val encrypted = AesGcmCipher(key.copyOf(), makeIv()).encryptPacket(length, data)
            val receiver = AesGcmCipher(key.copyOf(), makeIv())
            val changed = encrypted.copyOf()
            changed[changed.lastIndex] = (changed.last().toInt() xor 1).toByte()
            assertFailsWith<TransportException> { receiver.decryptPacket(length, changed) }
            assertFailsWith<TransportException> { receiver.decryptPacket(length, ByteArray(15)) }
            val view = ByteBuffer.wrap(receiver.decryptPacket(length, encrypted))
            val decoded = ByteArray(view.remaining())
            view.get(decoded)
            assertArrayEquals(data, decoded)
        }
    }

    @Test
    fun contiguousDecryptionRejectsTamperingAndTruncatedTags() {
        for (keySize in listOf(16, 32)) {
            val key = ByteArray(keySize) { it.toByte() }
            val plaintext = ByteArray(32768) { it.toByte() }
            val length = packetLengthBytes(plaintext.size)
            val encrypted = AesGcmCipher(key.copyOf(), makeIv()).encryptPacket(length, plaintext)
            for (offset in listOf(0, encrypted.lastIndex)) {
                val changed = encrypted.copyOf()
                changed[offset] = (changed[offset].toInt() xor 1).toByte()
                assertFailsWith<TransportException> { AesGcmCipher(key.copyOf(), makeIv()).decryptPacket(length, changed) }
            }
            assertFailsWith<TransportException> { AesGcmCipher(key.copyOf(), makeIv()).decryptPacket(length, ByteArray(15)) }
        }
    }

    @Test
    fun tagLengthIs16() {
        val key = ByteArray(16) { it.toByte() }
        val iv = makeIv()
        val cipher = AesGcmCipher(key, iv)

        kotlin.test.assertEquals(16, cipher.tagLength)

        val result = cipher.encrypt(packetLengthBytes(16), ByteArray(16))
        kotlin.test.assertEquals(16, result.tag.size)
    }

    @Test
    fun aadMismatchCausesAuthFailure() {
        val key = ByteArray(16) { it.toByte() }
        val iv = makeIv()
        val plaintext = ByteArray(32) { 0xAA.toByte() }
        val packetLength = packetLengthBytes(plaintext.size)

        val encryptor = AesGcmCipher(key, iv)
        val decryptor = AesGcmCipher(key, iv.copyOf())

        val result = encryptor.encrypt(packetLength, plaintext)

        val wrongPacketLength = packetLengthBytes(plaintext.size + 1)
        assertFailsWith<TransportException> {
            decryptor.decrypt(wrongPacketLength, result.ciphertext, result.tag)
        }
    }

    @Test
    fun ciphertextTamperingDetected() {
        val key = ByteArray(16) { it.toByte() }
        val iv = makeIv()
        val plaintext = ByteArray(32) { 0xBB.toByte() }
        val packetLength = packetLengthBytes(plaintext.size)

        val encryptor = AesGcmCipher(key, iv)
        val decryptor = AesGcmCipher(key, iv.copyOf())

        val result = encryptor.encrypt(packetLength, plaintext)

        val tampered = result.ciphertext.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0xFF).toByte()

        assertFailsWith<TransportException> {
            decryptor.decrypt(packetLength, tampered, result.tag)
        }
    }

    @Test
    fun tagTamperingDetected() {
        val key = ByteArray(16) { it.toByte() }
        val iv = makeIv()
        val plaintext = ByteArray(32) { 0xCC.toByte() }
        val packetLength = packetLengthBytes(plaintext.size)

        val encryptor = AesGcmCipher(key, iv)
        val decryptor = AesGcmCipher(key, iv.copyOf())

        val result = encryptor.encrypt(packetLength, plaintext)

        val tamperedTag = result.tag.copyOf()
        tamperedTag[0] = (tamperedTag[0].toInt() xor 0xFF).toByte()

        assertFailsWith<TransportException> {
            decryptor.decrypt(packetLength, result.ciphertext, tamperedTag)
        }
    }

    @Test
    fun ivIncrementsProduceDifferentCiphertext() {
        val key = ByteArray(16) { it.toByte() }
        val iv = makeIv()
        val plaintext = ByteArray(32) { 0xDD.toByte() }
        val packetLength = packetLengthBytes(plaintext.size)

        val cipher = AesGcmCipher(key, iv)

        val result1 = cipher.encrypt(packetLength, plaintext)
        val result2 = cipher.encrypt(packetLength, plaintext)

        // Same plaintext, same AAD, but different nonce → different ciphertext
        kotlin.test.assertFalse(result1.ciphertext.contentEquals(result2.ciphertext))
    }

    @Test
    fun multiplePacketsRoundTrip() {
        val key = ByteArray(32) { it.toByte() }
        val iv = makeIv()

        val encryptor = AesGcmCipher(key, iv)
        val decryptor = AesGcmCipher(key, iv.copyOf())

        for (i in 0 until 5) {
            val plaintext = ByteArray(32) { (it + i).toByte() }
            val packetLength = packetLengthBytes(plaintext.size)

            val result = encryptor.encrypt(packetLength, plaintext)
            val decrypted = decryptor.decrypt(packetLength, result.ciphertext, result.tag)

            assertArrayEquals(plaintext, decrypted, "Packet $i failed")
        }
    }

    @Test
    fun rejectsInvalidKeySize() {
        val badKey = ByteArray(24) // neither 16 nor 32
        val iv = makeIv()

        assertFailsWith<IllegalArgumentException> {
            AesGcmCipher(badKey, iv)
        }
    }

    @Test
    fun rejectsInvalidIvSize() {
        val key = ByteArray(16) { it.toByte() }
        val badIv = ByteArray(16) // must be 12

        assertFailsWith<IllegalArgumentException> {
            AesGcmCipher(key, badIv)
        }
    }
}
