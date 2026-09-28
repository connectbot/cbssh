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

import io.kaitai.struct.ByteBufferKaitaiStream
import io.kaitai.struct.KaitaiStruct
import java.nio.BufferOverflowException

/**
 * Serialize a Kaitai struct to a byte array.
 *
 * Kaitai's [ByteBufferKaitaiStream] is fixed-capacity, so the underlying
 * `ByteBuffer.put` throws [BufferOverflowException] if the
 * pre-allocated buffer is too small. We don't have a cheap way to know
 * the encoded size up front, so start at 16 KiB and double on overflow
 * until the message fits or we cross [MAX_BUFFER]. A caller that knows the encoded
 * size can supply [initialCapacity] to avoid retries. Most SSH messages
 * encode in well under 16 KiB; this only matters for [SshMsgChannelData]
 * carrying near-`maxPacketSize` (32 KiB) of data — e.g. SFTP transfers.
 */
fun KaitaiStruct.ReadWrite.toByteArray(initialCapacity: Long = INITIAL_BUFFER): ByteArray {
    require(initialCapacity in 1..MAX_BUFFER) { "Invalid serialization capacity: $initialCapacity" }
    _check()
    var capacity = initialCapacity
    while (true) {
        try {
            val bytes = ByteArray(capacity.toInt())
            val io = ByteBufferKaitaiStream(bytes)
            _write(io)
            val size = io.pos()
            return if (size == bytes.size) bytes else bytes.copyOf(size)
        } catch (_: BufferOverflowException) {
            if (capacity >= MAX_BUFFER) throw IllegalStateException("Kaitai message exceeds $MAX_BUFFER byte serialization limit")
            capacity = minOf(capacity * 2, MAX_BUFFER)
        }
    }
}

/** Serialize channel data while snapshotting only the requested source range into its owned body. */
fun serializeChannelData(recipientChannel: Int, data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteArray {
    require(offset >= 0 && length >= 0 && offset <= data.size - length) { "Invalid channel data range" }
    require(length <= MAX_BUFFER - 8) { "Channel data exceeds serialization limit" }
    val bytes = ByteArray(8 + length)
    val header = SshMsgChannelData.Header().apply {
        setRecipientChannel(recipientChannel.toLong())
        setDataLength(length.toLong())
        _check()
    }
    header._write(ByteBufferKaitaiStream(bytes))
    data.copyInto(bytes, 8, offset, offset + length)
    return bytes
}

private const val INITIAL_BUFFER = 1024L * 16
private const val MAX_BUFFER = 1024L * 1024
