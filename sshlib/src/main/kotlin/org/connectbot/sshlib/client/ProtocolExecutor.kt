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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.connectbot.sshlib.protocol.DeferredIo
import org.connectbot.sshlib.transport.TransportException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One owner for connection protocol decisions. I/O receipts are awaited by callers, never by the owner. */
internal class ProtocolExecutor(scope: CoroutineScope, dispatcher: CoroutineDispatcher) {
    private class Owner(val executor: ProtocolExecutor) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Owner>
    }
    private class Operation<T>(val caller: Job?, val ready: () -> Boolean, val action: suspend () -> T) {
        val result = CompletableDeferred<Pair<T, DeferredIo>>()
        suspend fun execute() {
            try {
                caller?.ensureActive()
                val effects = DeferredIo()
                result.complete(withContext(effects) { action() } to effects)
            } catch (failure: Throwable) {
                result.completeExceptionally(failure)
            }
        }
    }
    private val commands = Channel<Operation<*>>(64)
    private val waiting = ArrayDeque<Operation<*>>()

    @Volatile private var closed: Throwable? = null
    val isClosed: Boolean get() = closed != null

    // Eight local operations may each admit four data packets, leaving half
    // the ordinary writer queue available for protocol-generated effects.
    private val inFlight = Semaphore(8)
    private val worker = scope.launch(dispatcher + Owner(this)) {
        var failure: Throwable = IllegalStateException("Protocol processor closed")
        try {
            for (command in commands) {
                if (command.ready() || command.caller?.isActive == false) command.execute() else waiting.addLast(command)
                while (true) {
                    val next = waiting.firstOrNull { it.caller?.isActive == false || it.ready() } ?: break
                    waiting.remove(next)
                    next.execute()
                }
            }
        } catch (cause: Throwable) {
            failure = cause
        } finally {
            val cause = closed ?: failure
            closed = cause
            commands.close(cause)
            waiting.forEach { it.result.completeExceptionally(cause) }
            waiting.clear()
            while (true) (commands.tryReceive().getOrNull() ?: break).result.completeExceptionally(cause)
        }
    }

    suspend fun <T> run(awaitWrites: Boolean = true, ready: () -> Boolean = { true }, action: suspend () -> T): T {
        closed?.let { throw it }
        val context = currentCoroutineContext()
        if (context[Owner]?.executor === this) return action()
        suspend fun submit(): T {
            val command = Operation(context[Job], ready, action)
            commands.send(command)
            val (value, receipts) = command.result.await()
            val outer = context[DeferredIo]
            if (outer != null) {
                receipts.transferTo(outer)
            } else if (awaitWrites) {
                receipts.await()
            }
            return value
        }
        // Inbound packets and completions must progress even when local callers fill the writer.
        return if (awaitWrites) inFlight.withPermit { submit() } else submit()
    }

    fun cancel() {
        closed = TransportException("Connection closed")
        commands.close(closed)
        worker.cancel()
    }
}
