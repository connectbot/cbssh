/*
 * ConnectBot SSH Library
 * Copyright 2026 Kenny Root
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

package org.connectbot.sshlib.client

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.connectbot.sshlib.crypto.AesGcmCipher
import org.connectbot.sshlib.protocol.DeferredIo
import org.connectbot.sshlib.transport.ByteArrayTransport
import org.connectbot.sshlib.transport.PacketIO
import org.connectbot.sshlib.transport.Transport
import org.connectbot.sshlib.transport.TransportException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PacketWriterTest {
    @Test
    fun `confined and cross dispatcher effects preserve queue order and actual receipts`() = runTest {
        for (confined in listOf(true, false)) {
            val bytes = ByteArrayTransport()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val callerDispatcher = if (confined) dispatcher else StandardTestDispatcher(testScheduler)
            val writer = PacketWriter(backgroundScope, dispatcher, PacketIO(bytes), { throw it })
            writer.beginKex()
            val effects = DeferredIo()
            val admission = backgroundScope.async(callerDispatcher + effects) {
                listOf(writer.writeOwnedPacket(94, byteArrayOf(1)), writer.writeOwnedPacket(94, byteArrayOf(2)))
            }
            runCurrent()
            val receipts = admission.await()
            assertTrue(receipts.none { it.isCompleted })
            val completion = backgroundScope.async { effects.await() }
            runCurrent()
            assertFalse(completion.isCompleted)
            writer.completeKex()
            completion.await()
            val reader = PacketIO(ByteArrayTransport(bytes.getWrittenData()))
            assertContentEquals(byteArrayOf(1), reader.readPacket()._raw_body())
            assertContentEquals(byteArrayOf(2), reader.readPacket()._raw_body())
            writer.close()
        }
    }

    @Test
    fun `confined exhausted admission finishes failure handling after caller cancellation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var handled = false
        val writer = PacketWriter(backgroundScope, dispatcher, PacketIO(ByteArrayTransport()), {
            entered.complete(Unit)
            release.await()
            handled = true
        }, capacity = 1)
        writer.beginKex()
        val caller = backgroundScope.async(dispatcher + DeferredIo()) {
            assertFailsWith<TransportException> {
                writer.writeOwnedPacket(94, byteArrayOf(1))
                writer.writeOwnedPacket(94, byteArrayOf(2))
            }
        }
        entered.await()
        caller.cancel()
        release.complete(Unit)
        caller.join()
        assertTrue(handled)
        writer.close()
    }

    @Test
    fun `owned serialization shares ordering and receipts while ordinary input is snapshotted`() = runTest {
        val bytes = ByteArrayTransport()
        val io = PacketIO(bytes)
        val writer = PacketWriter(backgroundScope, StandardTestDispatcher(testScheduler), io, { throw it })
        writer.beginKex()
        val source = byteArrayOf(1, 2, 3)
        val first = backgroundScope.async { writer.writePacket(94, source) }
        val second = backgroundScope.async { writer.writeOwnedPacket(94, ByteArray(32_768) { 42 }) }
        runCurrent()
        source.fill(99)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        writer.completeKex()
        first.await()
        second.await()
        val reader = PacketIO(ByteArrayTransport(bytes.getWrittenData()))
        assertContentEquals(byteArrayOf(1, 2, 3), reader.readPacket()._raw_body())
        assertContentEquals(ByteArray(32_768) { 42 }, reader.readPacket()._raw_body())
        writer.close()
    }

    @Test
    fun `batched AEAD packets preserve nonces and wait for actual write`() = runTest {
        val bytes = ByteArrayTransport()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var size = 0
        val transport = object : Transport by bytes {
            override suspend fun write(data: ByteArray) {
                size = data.size
                entered.complete(Unit)
                release.await()
                bytes.write(data)
            }
        }
        val io = PacketIO(transport)
        io.enableSendAead(AesGcmCipher(ByteArray(16), ByteArray(12)))
        val writer = PacketWriter(backgroundScope, StandardTestDispatcher(testScheduler), io, { throw it })
        writer.beginKex()
        val first = backgroundScope.async { writer.writePacket(94, byteArrayOf(1)) }
        val second = backgroundScope.async { writer.writePacket(94, byteArrayOf(2)) }
        runCurrent()
        writer.completeKex()
        entered.await()
        assertEquals(72, size)
        assertEquals(0L, io.bytesSentOnWire)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        release.complete(Unit)
        first.await()
        second.await()
        assertEquals(72L, io.bytesSentOnWire)
        val reader = PacketIO(ByteArrayTransport(bytes.getWrittenData()))
        reader.enableReceiveAead(AesGcmCipher(ByteArray(16), ByteArray(12)))
        for (value in 1..2) {
            val packet = reader.readPacketWithSequence()
            assertEquals(value - 1L, packet.sequenceNumber)
            assertContentEquals(byteArrayOf(value.toByte()), packet.payload._raw_body())
        }
        writer.close()
    }

    @Test
    fun `batching preserves NEWKEYS protection boundary`() = runTest {
        val bytes = ByteArrayTransport()
        var transportWrites = 0
        val transport = object : Transport by bytes {
            override suspend fun write(data: ByteArray) {
                transportWrites++
                bytes.write(data)
            }
        }
        val io = PacketIO(transport)
        val writer = PacketWriter(backgroundScope, StandardTestDispatcher(testScheduler), io, { throw it })
        val key = ByteArray(16)
        val iv = ByteArray(12)
        val receipts = DeferredIo()
        val operations = listOf(
            backgroundScope.async(receipts) { writer.writePacket(94, byteArrayOf(1)) },
            backgroundScope.async(receipts) { writer.writePacket(21, afterWrite = { io.enableSendAead(AesGcmCipher(key, iv)) }) },
            backgroundScope.async(receipts) { writer.writePacket(94, byteArrayOf(2)) },
            backgroundScope.async(receipts) { writer.writePacket(94, byteArrayOf(3)) },
        )
        operations.forEach { it.await() }
        receipts.await()
        assertEquals(3, transportWrites)
        val reader = PacketIO(ByteArrayTransport(bytes.getWrittenData()))
        assertContentEquals(byteArrayOf(1), reader.readPacket()._raw_body())
        assertEquals(21, reader.readPacketWithSequence().messageNumber)
        reader.enableReceiveAead(AesGcmCipher(key, iv))
        assertContentEquals(byteArrayOf(2), reader.readPacket()._raw_body())
        assertContentEquals(byteArrayOf(3), reader.readPacket()._raw_body())
        writer.close()
    }

    @Test
    fun `batched data receipts wait for transport and preserve packet sequence`() = runTest {
        val bytes = ByteArrayTransport()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        val transport = object : Transport by bytes {
            override suspend fun write(data: ByteArray) {
                writes++
                entered.complete(Unit)
                release.await()
                bytes.write(data)
            }
        }
        val writer = PacketWriter(backgroundScope, StandardTestDispatcher(testScheduler), PacketIO(transport), { throw it })
        writer.beginKex()
        val first = backgroundScope.async { writer.writePacket(94, byteArrayOf(1)) }
        val second = backgroundScope.async { writer.writePacket(94, byteArrayOf(2)) }
        runCurrent()
        writer.completeKex()
        entered.await()
        assertEquals(1, writes)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        release.complete(Unit)
        first.await()
        second.await()
        val reader = PacketIO(ByteArrayTransport(bytes.getWrittenData()))
        val one = reader.readPacketWithSequence()
        val two = reader.readPacketWithSequence()
        assertEquals(0L, one.sequenceNumber)
        assertEquals(1L, two.sequenceNumber)
        assertContentEquals(byteArrayOf(1), one.payload._raw_body())
        assertContentEquals(byteArrayOf(2), two.payload._raw_body())
        writer.close()
    }

    @Test
    fun `closing a blocked batch fails every receipt without waiting for transport`() = runTest {
        val bytes = ByteArrayTransport()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport = object : Transport by bytes {
            override suspend fun write(data: ByteArray) {
                entered.complete(Unit)
                release.await()
            }
        }
        val writer = PacketWriter(backgroundScope, StandardTestDispatcher(testScheduler), PacketIO(transport), { throw it })
        writer.beginKex()
        val writes = List(2) { backgroundScope.async { runCatching { writer.writePacket(94) } } }
        runCurrent()
        writer.completeKex()
        entered.await()
        writer.close()
        writes.forEach { assertIs<TransportException>(it.await().exceptionOrNull()) }
        assertFalse(release.isCompleted)
    }

    @Test
    fun `full data queue reserves room for key exchange and preserves data order`() = runTest {
        val io = mockk<PacketIO>()
        val sent = Channel<Int>(Channel.UNLIMITED)
        coEvery { io.writePacket(any(), any()) } coAnswers { sent.send(firstArg()) }
        val writer = PacketWriter(backgroundScope, StandardTestDispatcher(testScheduler), io, { throw it }, capacity = 2)
        writer.beginKex()
        val first = backgroundScope.async { writer.writePacket(94) }
        val second = backgroundScope.async { writer.writePacket(95) }
        val third = backgroundScope.async { writer.writePacket(96) }
        runCurrent()
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        assertFalse(third.isCompleted)
        writer.writePacket(20)
        assertEquals(20, sent.receive())
        writer.writePacket(21, afterWrite = writer::completeKex)
        assertEquals(21, sent.receive())
        assertEquals(94, sent.receive())
        assertEquals(95, sent.receive())
        assertEquals(96, sent.receive())
        first.await()
        second.await()
        third.await()
        writer.close()
    }

    @Test
    fun `writer failure resolves active queued and backpressured callers`() = runTest {
        val io = mockk<PacketIO>()
        val fail = CompletableDeferred<Unit>()
        val reported = CompletableDeferred<Throwable>()
        coEvery { io.writePacket(any(), any()) } coAnswers {
            fail.await()
            throw TransportException("write failed")
        }
        coEvery { io.writePackets(any()) } coAnswers {
            fail.await()
            throw TransportException("write failed")
        }
        val writer = PacketWriter(backgroundScope, StandardTestDispatcher(testScheduler), io, { reported.complete(it) }, capacity = 2)
        val writes = List(3) { backgroundScope.async { runCatching { writer.writePacket(94) } } }
        runCurrent()
        fail.complete(Unit)
        withTimeout(1_000) {
            writes.forEach { assertIs<TransportException>(it.await().exceptionOrNull()) }
            assertIs<TransportException>(reported.await())
        }
        writer.close()
    }
}
