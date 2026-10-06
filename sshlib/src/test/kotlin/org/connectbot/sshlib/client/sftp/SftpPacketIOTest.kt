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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.connectbot.sshlib.SessionExit
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.SshSession
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SftpPacketIOTest {

    @Test
    fun `readPacket rejects invalid lengths`() {
        runBlocking {
            val tooSmall = FakeSshSession()
            tooSmall.enqueue(ByteBuffer.allocate(4).putInt(0).array())
            assertIs<SftpResult.ProtocolError>(SftpPacketIO(tooSmall).readPacket())

            val tooLarge = FakeSshSession()
            tooLarge.enqueue(ByteBuffer.allocate(4).putInt(256 * 1024 + 1).array())
            assertIs<SftpResult.ProtocolError>(SftpPacketIO(tooLarge).readPacket())
        }
    }

    @Test
    fun `readPacket reports closed channel before packet completes`() = runBlocking {
        val session = FakeSshSession()
        session.enqueue(ByteBuffer.allocate(4).putInt(4).array())
        session.closeReads()

        val result = SftpPacketIO(session).readPacket()

        assertIs<SftpResult.IoError>(result)
        assertEquals("ChannelClosedException", result.cause::class.simpleName)
    }

    @Test
    fun `readPacket buffers extra bytes for following packet`() = runBlocking {
        val session = FakeSshSession()
        session.enqueue(packet(10, byteArrayOf(1)) + packet(11, byteArrayOf(2, 3)))
        val packetIO = SftpPacketIO(session)

        val first = assertIs<SftpResult.Success<SftpRawPacket>>(packetIO.readPacket()).value
        val second = assertIs<SftpResult.Success<SftpRawPacket>>(packetIO.readPacket()).value

        assertEquals(10, first.type)
        assertContentEquals(byteArrayOf(1), first.payload)
        assertEquals(11, second.type)
        assertContentEquals(byteArrayOf(2, 3), second.payload)
    }

    @Test
    fun `fragmented bodies exclude adjacent frames and survive later reads`() = runBlocking {
        val data = ByteArray(65536) { it.toByte() }
        val wire = packet(103, data) + packet(101, byteArrayOf(7, 8, 9))
        for (split in listOf(1, 4, 5, 32768, 65540)) {
            val session = FakeSshSession()
            session.enqueue(wire.copyOfRange(0, split))
            session.enqueue(wire.copyOfRange(split, wire.size))
            val io = SftpPacketIO(session)
            val first = assertIs<SftpResult.Success<SftpRawPacket>>(io.readPacket()).value
            val second = assertIs<SftpResult.Success<SftpRawPacket>>(io.readPacket()).value
            assertEquals(data.size, first.payload.size)
            assertContentEquals(data, first.payload)
            assertContentEquals(byteArrayOf(7, 8, 9), second.payload)
        }
    }

    @Test
    fun `contiguous packets retain bounded read-only views with independent cursors`() = runBlocking {
        val session = FakeSshSession()
        val wire = packet(10, byteArrayOf(1, 2)) + packet(11, byteArrayOf(3))
        session.enqueue(wire)
        val io = SftpPacketIO(session)
        val first = assertIs<SftpResult.Success<SftpRawPacket>>(io.readPacket()).value
        val cursor = first.payloadBuffer
        assertTrue(cursor.isReadOnly)
        cursor.clear()
        assertEquals(2, cursor.capacity())
        cursor.get()
        assertEquals(0, first.payloadBuffer.position())
        val second = assertIs<SftpResult.Success<SftpRawPacket>>(io.readPacket()).value
        assertContentEquals(byteArrayOf(3), second.payload)
        wire[5] = 77 // Prove sharing; real input owners do not mutate retained packets.
        assertEquals(77.toByte(), first.payloadBuffer.get(0))
        val publicCopy = first.payload
        publicCopy[0] = 9
        assertEquals(77.toByte(), first.payloadBuffer.get(0))
    }

    @Test
    fun `fragmented frame uses owned assembly and empty chunks are skipped`() = runBlocking {
        val session = FakeSshSession()
        val first = packet(103, byteArrayOf(1, 2, 3)).copyOfRange(0, 6)
        val tail = byteArrayOf(2, 3)
        session.enqueue(byteArrayOf())
        session.enqueue(first)
        session.enqueue(byteArrayOf())
        session.enqueue(tail)
        val packet = assertIs<SftpResult.Success<SftpRawPacket>>(SftpPacketIO(session).readPacket()).value
        first.fill(0)
        tail.fill(0)
        val view = packet.payloadBuffer
        view.clear()
        assertTrue(view.isReadOnly)
        assertEquals(3, view.capacity())
        assertContentEquals(byteArrayOf(1, 2, 3), packet.payload)
    }

    @Test
    fun `writePacket serializes length type and payload`() = runBlocking {
        val session = FakeSshSession()

        val result = SftpPacketIO(session).writePacket(99, byteArrayOf(1, 2, 3))

        assertEquals(SftpResult.Success(Unit), result)
        assertContentEquals(byteArrayOf(0, 0, 0, 4, 99, 1, 2, 3), session.writes.single())
    }

    @Test
    fun `multipart request framing matches contiguous payload including empty parts`() = runBlocking {
        for (id in listOf(0, 7, -1, Int.MAX_VALUE)) {
            for (parts in listOf(emptyList(), listOf(byteArrayOf()), listOf(byteArrayOf(1, 2), byteArrayOf(), ByteArray(65536) { it.toByte() }))) {
                val session = FakeSshSession()
                val payload = ByteBuffer.allocate(4 + parts.sumOf { it.size }).putInt(id)
                parts.forEach { payload.put(it) }
                val io = SftpPacketIO(session)
                assertEquals(SftpResult.Success(Unit), io.queueRequest(6, id, parts).await())
                assertContentEquals(packet(6, payload.array()), session.writes.single())
            }
        }
    }

    @Test
    fun `queued multipart request snapshots every caller part before transport completion`() = runTest {
        val session = FakeSshSession()
        val gate = CompletableDeferred<Unit>()
        session.writeGate = gate
        val io = SftpPacketIO(session)
        io.startWriter(backgroundScope)
        val first = io.queuePacket(10, byteArrayOf(1))
        runCurrent()
        val header = byteArrayOf(1, 2, 3)
        val data = ByteArray(65536) { it.toByte() }
        val expected = ByteBuffer.allocate(4 + header.size + data.size).putInt(7).put(header).put(data).array()
        val request = io.queueRequest(6, 7, listOf(header, data))
        header.fill(0)
        data.fill(0)
        runCurrent()
        assertFalse(request.isCompleted)
        gate.complete(Unit)
        runCurrent()
        assertEquals(SftpResult.Success(Unit), first.await())
        assertEquals(SftpResult.Success(Unit), request.await())
        assertContentEquals(packet(6, expected), session.writes[1])
        io.stopWriter()
    }

    @Test
    fun `queued frames preserve order and wait for transport completion`() = runTest {
        val session = FakeSshSession()
        val gate = CompletableDeferred<Unit>()
        session.writeGate = gate
        val io = SftpPacketIO(session)
        io.startWriter(backgroundScope)
        val first = io.queuePacket(10, byteArrayOf(1))
        runCurrent()
        val second = io.queuePacket(11, byteArrayOf(2))
        val third = io.queuePacket(12, byteArrayOf(3))
        runCurrent()
        assertEquals(1, session.writes.size)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        assertFalse(third.isCompleted)
        gate.complete(Unit)
        runCurrent()
        assertEquals(SftpResult.Success(Unit), first.await())
        assertEquals(SftpResult.Success(Unit), second.await())
        assertEquals(SftpResult.Success(Unit), third.await())
        assertContentEquals(packet(10, byteArrayOf(1)), session.writes[0])
        assertContentEquals(packet(11, byteArrayOf(2)) + packet(12, byteArrayOf(3)), session.writes[1])
        io.stopWriter()
    }

    @Test
    fun `cancelling a receipt waiter preserves the admitted frame and following frame`() = runTest {
        val session = FakeSshSession()
        val gate = CompletableDeferred<Unit>()
        session.writeGate = gate
        val io = SftpPacketIO(session)
        io.startWriter(backgroundScope)
        val first = io.queuePacket(10, byteArrayOf(1))
        runCurrent()
        val waiter = async { first.await() }
        runCurrent()
        waiter.cancel()
        runCurrent()
        assertFalse(first.isCancelled)
        val second = io.queuePacket(11, byteArrayOf(2))
        gate.complete(Unit)
        runCurrent()
        assertEquals(SftpResult.Success(Unit), first.await())
        assertEquals(SftpResult.Success(Unit), second.await())
        assertContentEquals(packet(10, byteArrayOf(1)), session.writes[0])
        assertContentEquals(packet(11, byteArrayOf(2)), session.writes[1])
        io.stopWriter()
    }

    @Test
    fun `write failure closes stream and fails active queued and future frames`() = runTest {
        val session = FakeSshSession()
        val gate = CompletableDeferred<Unit>()
        session.writeGate = gate
        val io = SftpPacketIO(session)
        io.startWriter(backgroundScope)
        val first = io.queuePacket(10, byteArrayOf(1))
        runCurrent()
        val second = io.queuePacket(11, byteArrayOf(2))
        val failure = IllegalStateException("partial transport write")
        gate.completeExceptionally(failure)
        runCurrent()
        assertEquals(failure.message, assertIs<SftpResult.IoError>(first.await()).cause.message)
        assertEquals(failure.message, assertIs<SftpResult.IoError>(second.await()).cause.message)
        assertTrue(session.closed)
        assertEquals(1, session.writes.size)
        try {
            io.queuePacket(12, byteArrayOf(3))
            error("Closed writer accepted a frame")
        } catch (expected: IllegalStateException) {
            assertEquals(failure.message, expected.message)
        }
    }

    @Test
    fun `bounded frame queue backpressures admission and stop wakes blocked writers`() = runTest {
        val session = FakeSshSession()
        session.writeGate = CompletableDeferred()
        val io = SftpPacketIO(session)
        io.startWriter(backgroundScope)
        val first = io.queuePacket(10, byteArrayOf())
        runCurrent()
        val queued = List(16) { io.queuePacket(11, byteArrayOf()) }
        val blocked = async { runCatching { io.queuePacket(12, byteArrayOf()) } }
        runCurrent()
        assertFalse(blocked.isCompleted)
        io.stopWriter()
        runCurrent()
        assertIs<SftpResult.IoError>(first.await())
        queued.forEach { assertIs<SftpResult.IoError>(it.await()) }
        try {
            blocked.await().getOrThrow()
            error("Blocked writer survived close")
        } catch (expected: kotlinx.coroutines.CancellationException) {
            assertTrue(session.closed)
        }
    }

    @Test
    fun `large frame receipt waits for its complete write before following frame`() = runTest {
        val session = FakeSshSession()
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        session.writeGates.addAll(listOf(firstGate, secondGate))
        val io = SftpPacketIO(session)
        io.startWriter(backgroundScope)
        val payload = ByteArray(128 * 1024) { it.toByte() }
        val first = io.queuePacket(6, payload)
        runCurrent()
        assertContentEquals(packet(6, payload), session.writes.single())
        assertFalse(first.isCompleted)
        val second = io.queuePacket(7, byteArrayOf(99))
        firstGate.complete(Unit)
        runCurrent()
        assertEquals(SftpResult.Success(Unit), first.await())
        assertFalse(second.isCompleted)
        assertContentEquals(packet(7, byteArrayOf(99)), session.writes[1])
        secondGate.complete(Unit)
        runCurrent()
        assertEquals(SftpResult.Success(Unit), second.await())
        io.stopWriter()
    }

    private fun packet(type: Int, payload: ByteArray): ByteArray {
        val packet = ByteBuffer.allocate(4 + 1 + payload.size)
        packet.putInt(1 + payload.size)
        packet.put(type.toByte())
        packet.put(payload)
        return packet.array()
    }

    private class FakeSshSession : SshSession {
        private val reads = Channel<ByteArray>(Channel.UNLIMITED)
        val writes = mutableListOf<ByteArray>()
        var writeGate: CompletableDeferred<Unit>? = null
        val writeGates = ArrayDeque<CompletableDeferred<Unit>>()
        var closed = false

        override val localChannelNumber: Int = 1
        override val remoteChannelNumber: Int = 2
        override val isOpen: Boolean = true
        override val stdout: ReceiveChannel<ByteArray> = Channel()
        override val stderr: ReceiveChannel<ByteArray> = Channel()
        override val exitInfo: Deferred<SessionExit?> =
            CompletableDeferred(null)

        fun enqueue(data: ByteArray) {
            reads.trySend(data).getOrThrow()
        }

        fun closeReads() {
            reads.close()
        }

        override suspend fun requestPty(
            terminalType: String,
            widthChars: Int,
            heightRows: Int,
            widthPixels: Int,
            heightPixels: Int,
            terminalModes: ByteArray,
        ): Boolean = true

        override suspend fun resizeTerminal(
            widthChars: Int,
            heightRows: Int,
            widthPixels: Int,
            heightPixels: Int,
        ): Boolean = true

        override suspend fun requestEnv(name: String, value: String): Boolean = false

        override suspend fun requestShell(): Boolean = true

        override suspend fun requestExec(command: String): Boolean = true

        override suspend fun requestSubsystem(name: String): Boolean = true

        override suspend fun write(data: ByteArray) {
            writes += data.copyOf()
            (writeGates.removeFirstOrNull() ?: writeGate)?.await()
        }

        override suspend fun read(): ByteArray? = reads.receiveCatching().getOrNull()

        override suspend fun readExtended(): Pair<Int, ByteArray>? = null

        override suspend fun sendEof() = Unit

        override fun close() {
            closed = true
        }
    }
}
