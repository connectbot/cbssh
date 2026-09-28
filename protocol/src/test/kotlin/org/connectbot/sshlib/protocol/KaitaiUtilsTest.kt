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

package org.connectbot.sshlib.protocol

import io.kaitai.struct.KaitaiStream
import io.kaitai.struct.KaitaiStruct
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class KaitaiUtilsTest {

    @Test
    fun `toByteArray serializes generated Kaitai struct`() {
        val message = ByteString().apply {
            setLenData(3)
            setData(byteArrayOf(1, 2, 3))
        }

        assertContentEquals(byteArrayOf(0, 0, 0, 3, 1, 2, 3), message.toByteArray())
    }

    @Test
    fun `toByteArray grows buffer when initial capacity is too small`() {
        val payloadSize = 20 * 1024
        val message = FixedPayloadStruct(payloadSize)

        val bytes = message.toByteArray()

        assertEquals(payloadSize, bytes.size)
        assertEquals(0, bytes.first())
        assertEquals((payloadSize - 1).toByte(), bytes.last())
    }

    @Test
    fun `toByteArray fails when message exceeds maximum serialization buffer`() {
        val message = FixedPayloadStruct(1024 * 1024 + 1)

        val exception = assertFailsWith<IllegalStateException> {
            message.toByteArray()
        }
        assertEquals("Kaitai message exceeds 1048576 byte serialization limit", exception.message)
    }

    @Test
    fun `channel data round trips with exact capacity and small capacity hint`() {
        val payload = ByteArray(32768) { it.toByte() }
        val message = SshMsgChannelData().apply {
            setRecipientChannel(7)
            setData(
                ByteString().apply {
                    setLenData(payload.size.toLong())
                    setData(payload)
                    _check()
                },
            )
        }
        val exact = message.toByteArray(8L + payload.size)
        assertContentEquals(exact, message.toByteArray(1))
        val parsed = SshMsgChannelData(io.kaitai.struct.ByteBufferKaitaiStream(exact))
        parsed._read()
        assertEquals(7L, parsed.recipientChannel())
        assertContentEquals(payload, parsed.data().data())
    }

    @Test
    fun `channel data source ranges match full serialization and own their bytes`() {
        val source = ByteArray(65550) { (it * 13).toByte() }
        for (recipient in listOf(0, 7, -1)) {
            for ((offset, length) in listOf(0 to 0, source.size to 0, 1 to 1, 13 to 32768, 7 to 65536)) {
                val expectedData = source.copyOfRange(offset, offset + length)
                val whole = SshMsgChannelData().apply {
                    setRecipientChannel(recipient.toLong())
                    setData(
                        ByteString().apply {
                            setLenData(length.toLong())
                            setData(expectedData)
                            _check()
                        },
                    )
                    _check()
                }.toByteArray(8L + length)
                val bytes = serializeChannelData(recipient, source, offset, length)
                assertContentEquals(whole, bytes)
                val decoded = SshMsgChannelData(io.kaitai.struct.ByteBufferKaitaiStream(bytes))
                decoded._read()
                assertEquals(recipient.toLong() and 0xffffffffL, decoded.recipientChannel())
                assertContentEquals(expectedData, decoded.data().data())
            }
        }
        val expected = source.copyOfRange(13, 32781)
        val owned = serializeChannelData(7, source, 13, 32768)
        source.fill(0)
        val decoded = SshMsgChannelData(io.kaitai.struct.ByteBufferKaitaiStream(owned))
        decoded._read()
        assertContentEquals(expected, decoded.data().data())
    }

    @Test
    fun `channel data ranges reject invalid bounds and excessive serialization sizes`() {
        val source = ByteArray(3)
        for ((offset, length) in listOf(-1 to 1, 0 to -1, 4 to 0, 2 to 2, 0 to Int.MAX_VALUE, Int.MAX_VALUE to 0)) {
            assertFailsWith<IllegalArgumentException> { serializeChannelData(7, source, offset, length) }
        }
        assertFailsWith<IllegalArgumentException> { serializeChannelData(7, ByteArray(1048576), 0, 1048569) }
    }

    @Test
    fun `serialization rejects capacity hints outside bounded allocation`() {
        val message = FixedPayloadStruct(1)
        for (capacity in listOf(0L, -1L, 1048577L)) {
            assertFailsWith<IllegalArgumentException> { message.toByteArray(capacity) }
        }
    }

    @Test
    fun `bounded payload parses identically with and without packet envelope`() {
        val message = SshMsgIgnore().apply {
            setData(
                ByteString().apply {
                    setLenData(32768)
                    setData(ByteArray(32768) { it.toByte() })
                    _check()
                },
            )
        }
        val body = message.toByteArray()
        val payloadBytes = byteArrayOf(SshEnums.MessageType.SSH_MSG_IGNORE.id().toByte()) + body
        val payload = UnencryptedPacket.UnencryptedPayload(io.kaitai.struct.ByteBufferKaitaiStream(payloadBytes), false)
        payload._read()
        val frame = java.nio.ByteBuffer.allocate(5 + payloadBytes.size + 4)
            .putInt(1 + payloadBytes.size + 4).put(4.toByte()).put(payloadBytes).put(ByteArray(4)).array()
        val packet = UnencryptedPacket(io.kaitai.struct.ByteBufferKaitaiStream(frame))
        packet._read()
        assertEquals(packet.payload().messageType(), payload.messageType())
        assertContentEquals(packet.payload()._raw_body(), payload._raw_body())
        assertContentEquals(
            (packet.payload().body() as SshMsgIgnore).data().data(),
            (payload.body() as SshMsgIgnore).data().data(),
        )
    }

    @Test
    fun `typed channel payloads match raw parsing and reject truncated strings`() {
        val data = ByteArray(32768) { it.toByte() }
        for (extended in listOf(false, true)) {
            val message = if (extended) {
                SshMsgChannelExtendedData().apply {
                    setRecipientChannel(123)
                    setDataTypeCode(1)
                    setData(
                        ByteString().apply {
                            setLenData(data.size.toLong())
                            setData(data)
                            _check()
                        },
                    )
                    _check()
                }
            } else {
                SshMsgChannelData().apply {
                    setRecipientChannel(123)
                    setData(
                        ByteString().apply {
                            setLenData(data.size.toLong())
                            setData(data)
                            _check()
                        },
                    )
                    _check()
                }
            }
            val type = if (extended) SshEnums.MessageType.SSH_MSG_CHANNEL_EXTENDED_DATA else SshEnums.MessageType.SSH_MSG_CHANNEL_DATA
            val bytes = byteArrayOf(type.id().toByte()) + message.toByteArray()
            val typed = UnencryptedPacket.UnencryptedPayload(io.kaitai.struct.ByteBufferKaitaiStream(bytes), true)
            typed._read()
            val raw = UnencryptedPacket.UnencryptedPayload(io.kaitai.struct.ByteBufferKaitaiStream(bytes), false)
            raw._read()
            assertEquals(raw.messageType(), typed.messageType())
            if (extended) {
                val expected = SshMsgChannelExtendedData(io.kaitai.struct.ByteBufferKaitaiStream(raw._raw_body()))
                expected._read()
                assertEquals(expected.recipientChannel(), typed.channelExtendedData().recipientChannel())
                assertEquals(expected.dataTypeCode(), typed.channelExtendedData().dataTypeCode())
                assertContentEquals(expected.data().data(), typed.channelExtendedData().data().data())
            } else {
                val expected = SshMsgChannelData(io.kaitai.struct.ByteBufferKaitaiStream(raw._raw_body()))
                expected._read()
                assertEquals(expected.recipientChannel(), typed.channelData().recipientChannel())
                assertContentEquals(expected.data().data(), typed.channelData().data().data())
            }
            assertNull(typed._raw_body())
            assertFailsWith<RuntimeException> {
                UnencryptedPacket.UnencryptedPayload(io.kaitai.struct.ByteBufferKaitaiStream(bytes.copyOf(bytes.size - 1)), true)._read()
            }
        }
    }

    @Test
    fun `decrypted packet body validates padding bounds and preserves payload`() {
        for (padding in listOf(4, 16, 255)) {
            val payload = ByteArray(32769) { it.toByte() }
            val body = byteArrayOf(padding.toByte()) + payload + ByteArray(padding)
            val stream = io.kaitai.struct.ByteBufferKaitaiStream(body)
            val parsed = SshPacketHeader(stream)
            parsed._read()
            assertEquals(padding, parsed.lenRandomPadding())
            val view = stream.substream(parsed.payloadLength().toLong())
            assertContentEquals(payload, view.readBytesFull())
            assertFailsWith<java.nio.BufferUnderflowException> { view.readU1() }
        }
        for (body in listOf(byteArrayOf(0, 94, 0, 0, 0, 0), byteArrayOf(3, 94, 0, 0, 0, 0), byteArrayOf(5, 94, 0, 0, 0, 0))) {
            assertFailsWith<io.kaitai.struct.KaitaiStream.ValidationExprError> {
                SshPacketHeader(io.kaitai.struct.ByteBufferKaitaiStream(body))._read()
            }
        }
    }

    @Test
    fun `unknown message number is preserved independently of the enum`() {
        val payload = UnencryptedPacket.UnencryptedPayload(io.kaitai.struct.ByteBufferKaitaiStream(byteArrayOf(255.toByte(), 42)), true)
        payload._read()
        assertNull(payload.messageType())
        assertEquals(255, payload.messageNumber())
        assertContentEquals(byteArrayOf(42), payload._raw_body())
    }

    private class FixedPayloadStruct(
        private val payloadSize: Int,
    ) : KaitaiStruct.ReadWrite(null) {
        override fun _write_Seq() {
            _io.writeBytes(ByteArray(payloadSize) { it.toByte() })
        }

        override fun _check() {
            _dirty = false
        }

        override fun _fetchInstances() = Unit

        override fun _read() = Unit
    }
}
