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

package org.connectbot.sshlib.client.sftp

import io.kaitai.struct.ByteBufferKaitaiStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.SshSession
import org.connectbot.sshlib.protocol.SftpFrameHeader
import java.nio.ByteBuffer

/**
 * SFTP packet framing over an SSH session channel.
 *
 * SFTP packets are length-prefixed: `uint32 length + byte type + payload`.
 * The length field counts everything after itself (type + payload).
 *
 * SSH channel data arrives in arbitrary chunks that may not align with SFTP
 * packet boundaries. This class accumulates bytes until a complete packet
 * is available.
 */
internal interface SftpPacketTransport {
    suspend fun readPacket(): SftpResult<SftpRawPacket>
    suspend fun writePacket(type: Int, payload: ByteArray): SftpResult<Unit>
    suspend fun queuePacket(type: Int, payload: ByteArray): Deferred<SftpResult<Unit>> = CompletableDeferred(writePacket(type, payload))
    suspend fun queueRequest(type: Int, requestId: Int, payload: List<ByteArray>): Deferred<SftpResult<Unit>> {
        val fullPayload = ByteBuffer.allocate(payload.fold(4) { size, part -> Math.addExact(size, part.size) })
        fullPayload.putInt(requestId)
        payload.forEach { fullPayload.put(it) }
        return queuePacket(type, fullPayload.array())
    }
    fun startWriter(scope: CoroutineScope) = Unit
    fun stopWriter() = Unit
}

internal class SftpPacketIO(private val session: SshSession) : SftpPacketTransport {
    private class Write(val bytes: ByteArray) {
        val receipt = CompletableDeferred<SftpResult<Unit>>()
    }
    private val writes = Channel<Write>(16, onUndeliveredElement = {
        it.receipt.complete(SftpResult.IoError(ChannelClosedException("SFTP writer closed")))
    })
    private var writerJob: Job? = null

    override fun startWriter(scope: CoroutineScope) {
        check(writerJob == null)
        writerJob = scope.launch {
            val active = ArrayList<Write>(8)
            var failure: Throwable = CancellationException("SFTP writer closed")
            try {
                while (true) {
                    active.add(writes.receiveCatching().getOrNull() ?: break)
                    var size = active.first().bytes.size
                    while (active.size < 8 && size < 256 * 1024) {
                        val next = writes.tryReceive().getOrNull() ?: break
                        active.add(next)
                        size = Math.addExact(size, next.bytes.size)
                    }
                    val bytes = if (active.size == 1) {
                        active.single().bytes
                    } else {
                        val output = ByteBuffer.allocate(size)
                        active.forEach { output.put(it.bytes) }
                        output.array()
                    }
                    session.write(bytes)
                    active.forEach { it.receipt.complete(SftpResult.Success(Unit)) }
                    active.clear()
                }
            } catch (cause: Throwable) {
                failure = cause
            } finally {
                // A transport can fail after writing a prefix. Abandon the stream and all
                // affected receipts instead of placing another frame after that prefix.
                if (active.isNotEmpty() || failure !is CancellationException) session.close()
                writes.close(failure)
                active.forEach { it.receipt.complete(SftpResult.IoError(failure)) }
                while (true) {
                    val write = writes.tryReceive().getOrNull() ?: break
                    write.receipt.complete(SftpResult.IoError(failure))
                }
            }
        }
    }

    override fun stopWriter() {
        writes.cancel()
        writerJob?.cancel()
    }

    override suspend fun queuePacket(type: Int, payload: ByteArray): Deferred<SftpResult<Unit>> = queueFrame(frame(type, payload))

    override suspend fun queueRequest(type: Int, requestId: Int, payload: List<ByteArray>): Deferred<SftpResult<Unit>> {
        val packet = ByteBuffer.allocate(payload.fold(9) { size, part -> Math.addExact(size, part.size) })
        packet.putInt(packet.capacity() - 4)
        packet.put(type.toByte())
        packet.putInt(requestId)
        payload.forEach { packet.put(it) }
        return queueFrame(packet.array())
    }

    private suspend fun queueFrame(bytes: ByteArray): Deferred<SftpResult<Unit>> {
        if (writerJob == null) {
            return CompletableDeferred(
                try {
                    session.write(bytes)
                    SftpResult.Success(Unit)
                } catch (failure: Exception) {
                    SftpResult.IoError(failure)
                },
            )
        }
        val write = Write(bytes)
        writes.send(write)
        return write.receipt
    }

    private var bufferedBytes = ByteArray(0)
    private var bufferedOffset = 0
    private var bufferedLength = 0

    /**
     * Read a complete SFTP packet. Blocks (suspends) until enough data arrives.
     *
     * Returns a sealed [SftpResult] rather than throwing — network errors and
     * malformed packets are normal failure modes that callers should handle
     * explicitly. (Reviewed by @kruton on PR #112: Kotlin library APIs
     * shouldn't throw for things they can manage themselves.)
     */
    override suspend fun readPacket(): SftpResult<SftpRawPacket> {
        return try {
            val lengthHeader = SftpFrameHeader(ByteBufferKaitaiStream(readExact(4)))
            try {
                lengthHeader._read()
            } catch (e: io.kaitai.struct.KaitaiStream.ValidationFailedError) {
                return SftpResult.ProtocolError("Invalid SFTP packet length")
            }
            val body = readExact(lengthHeader.length().toInt())
            val header = SftpFrameHeader.BodyHeader(ByteBufferKaitaiStream(body))
            header._read()
            SftpResult.Success(SftpRawPacket(header.packetType(), body.copyOfRange(1, body.size)))
        } catch (e: ChannelClosedException) {
            SftpResult.IoError(e)
        } catch (e: Exception) {
            SftpResult.IoError(e)
        }
    }

    /**
     * Write an SFTP packet with the given type and payload.
     *
     * Returns [SftpResult.Success] on send or [SftpResult.IoError] if the
     * underlying SSH session write fails.
     */
    override suspend fun writePacket(type: Int, payload: ByteArray): SftpResult<Unit> = try {
        session.write(frame(type, payload))
        SftpResult.Success(Unit)
    } catch (e: Exception) {
        SftpResult.IoError(e)
    }

    private fun frame(type: Int, payload: ByteArray): ByteArray {
        val packet = ByteBuffer.allocate(5 + payload.size)
        packet.putInt(1 + payload.size)
        packet.put(type.toByte())
        packet.put(payload)
        return packet.array()
    }

    /**
     * Read exactly [count] bytes from the session, accumulating across
     * multiple channel data chunks as needed. Throws [ChannelClosedException]
     * if the channel closes mid-packet — callers (only [readPacket]) catch
     * and translate to [SftpResult.IoError]. Kept private so the throw
     * doesn't leak past the API surface.
     */
    private suspend fun readExact(count: Int): ByteArray {
        val result = ByteArray(count)
        var filled = 0

        // Drain any leftover buffered data first
        if (bufferedLength > 0) {
            val toCopy = minOf(count, bufferedLength)
            System.arraycopy(bufferedBytes, bufferedOffset, result, 0, toCopy)
            bufferedOffset += toCopy
            bufferedLength -= toCopy
            filled += toCopy
        }

        // Read from the session until we have enough
        while (filled < count) {
            val data = session.read()
                ?: throw ChannelClosedException("SSH channel closed before complete SFTP packet")

            val toCopy = minOf(count - filled, data.size)
            System.arraycopy(data, 0, result, filled, toCopy)
            filled += toCopy

            // Buffer any leftover bytes for the next readExact call
            if (toCopy < data.size) {
                bufferedBytes = data
                bufferedOffset = toCopy
                bufferedLength = data.size - toCopy
            }
        }

        return result
    }
}

/**
 * Internal exception used by [SftpPacketIO.readExact] to signal a closed
 * channel mid-packet. Caught by [SftpPacketIO.readPacket] and translated
 * into [SftpResult.IoError]; never escapes this file.
 */
internal class ChannelClosedException(message: String) : Exception(message)

/**
 * Raw SFTP packet with type byte and payload (without the length prefix).
 */
internal data class SftpRawPacket(val type: Int, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean = other is SftpRawPacket && type == other.type && payload.contentEquals(other.payload)
    override fun hashCode(): Int = 31 * type + payload.contentHashCode()
}

internal class SftpProtocolException(message: String) : Exception(message)
