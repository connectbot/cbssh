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
import org.connectbot.sshlib.transport.PacketIO
import org.connectbot.sshlib.transport.TransportException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class PacketWriterTest {
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
