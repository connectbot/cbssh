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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.connectbot.sshlib.protocol.DeferredIo
import org.connectbot.sshlib.transport.PacketIO
import org.connectbot.sshlib.transport.TransportException

/** Ordered outbound effects. All queue/gate operations run on [dispatcher]; only this worker writes packets. */
internal class PacketWriter(
    scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val packetIO: PacketIO,
    private val onFailure: suspend (Throwable) -> Unit,
    capacity: Int = 64,
) {
    private data class Write(
        val type: Int,
        val payload: ByteArray,
        val before: () -> Unit,
        val after: () -> Unit,
        val completion: CompletableDeferred<Unit>,
        val permits: Semaphore,
        val discard: () -> Unit,
    )
    private val pending = ArrayDeque<Write>()
    private val slots = Semaphore(capacity)

    // KEX and disconnect must still be admitted when ordinary traffic fills its queue.
    private val controlSlots = Semaphore(32)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var keyExchanges = 0
    private var closed: Throwable? = null
    private val worker = scope.launch(dispatcher) {
        var active: Write? = null
        try {
            for (ignored in wake) {
                while (true) {
                    val next = pending.firstOrNull { keyExchanges == 0 || allowedDuringKex(it.type) } ?: break
                    pending.remove(next)
                    active = next
                    next.before()
                    packetIO.writePacket(next.type, next.payload)
                    currentCoroutineContext().ensureActive()
                    next.after()
                    next.completion.complete(Unit)
                    next.permits.release()
                    active = null
                }
            }
        } catch (failure: Throwable) {
            val unexpected = closed == null
            val cause = closed ?: if (failure is CancellationException) TransportException("Packet writer stopped", failure) else failure
            closed = cause
            active?.let {
                it.discard()
                it.completion.completeExceptionally(cause)
                it.permits.release()
            }
            failPending(cause)
            if (unexpected) onFailure(cause)
        }
    }

    fun beginKex() {
        keyExchanges++
    }

    fun completeKex() {
        check(keyExchanges > 0)
        keyExchanges--
        wake.trySend(Unit)
    }

    suspend fun writePacket(type: Int, payload: ByteArray = byteArrayOf(), beforeWrite: () -> Unit = {}, afterWrite: () -> Unit = {}, discard: () -> Unit = {}): Deferred<Unit> {
        val batch = currentCoroutineContext()[DeferredIo]
        val permits = if (allowedDuringKex(type)) controlSlots else slots
        val receipt = if (batch != null) {
            withContext(dispatcher + NonCancellable) {
                if (!permits.tryAcquire()) {
                    val failure = TransportException("Outbound protocol queue exhausted")
                    discard()
                    onFailure(failure)
                    throw failure
                }
                enqueue(type, payload, beforeWrite, afterWrite, permits, discard)
            }
        } else {
            permits.acquire()
            // Acquiring the permit is admission. Cancellation after admission cannot retract
            // a packet whose peer may already have seen it.
            withContext(dispatcher + NonCancellable) {
                enqueue(type, payload, beforeWrite, afterWrite, permits, discard)
            }
        }
        if (batch != null) batch.add(receipt) else receipt.await()
        return receipt
    }

    private fun enqueue(type: Int, payload: ByteArray, before: () -> Unit, after: () -> Unit, permits: Semaphore, discard: () -> Unit): Deferred<Unit> {
        val receipt = CompletableDeferred<Unit>()
        val failure = closed
        if (failure != null) {
            receipt.completeExceptionally(failure)
            discard()
            permits.release()
        } else {
            pending.addLast(Write(type, payload.copyOf(), before, after, receipt, permits, discard))
            wake.trySend(Unit)
        }
        return receipt
    }

    /** Closing is independent of a blocked transport write. */
    suspend fun close(cause: Throwable = TransportException("Transport closed")) = withContext(dispatcher) {
        closed = cause
        failPending(cause)
        worker.cancel()
        wake.close()
    }

    private fun failPending(cause: Throwable) {
        while (pending.isNotEmpty()) {
            val write = pending.removeFirst()
            write.discard()
            write.completion.completeExceptionally(cause)
            write.permits.release()
        }
    }

    private fun allowedDuringKex(type: Int): Boolean = type in 1..4 || type in 7..49
}
