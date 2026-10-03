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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Owns inbound delivery independently of the network job. The connection's resource ledger keeps
 * it reachable after CHANNEL_CLOSE removes packet routing, until consumption or explicit close.
 * This is resource bookkeeping only: the channel state machine decides finish versus abort.
 */
internal class ChannelDelivery(context: CoroutineContext) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(context.minusKey(Job) + supervisor)
    private val lock = Any()
    private var workers = 0
    private var initialized = false
    private var finished = false
    private var retired = false
    private var cleanupInProgress = false
    private val released = CompletableDeferred<Unit>()
    private var abortStreams: () -> Unit = {}
    private var onRetired: () -> Unit = {}

    @Volatile var isAborted: Boolean = false
        private set

    internal val job: Job get() = supervisor

    /** Starting the lazy stdout adapter and retiring the last pump must be atomic. */
    fun launch(block: suspend CoroutineScope.() -> Unit): Job? {
        val job = synchronized(lock) {
            if (retired || isAborted) return null
            workers++
            scope.launch(start = CoroutineStart.LAZY, block = block).also {
                it.invokeOnCompletion { workerFinished() }
            }
        }
        job.start()
        return job
    }

    fun initialize(abortStreams: () -> Unit, resources: ChannelDeliveryResources) {
        synchronized(lock) {
            this.abortStreams = abortStreams
            this.onRetired = { resources.unregister(this) }
        }
        // A closed ledger can reject registration and abort immediately; install cleanup first,
        // but do not permit retirement to race ahead of registration.
        resources.register(this)
        synchronized(lock) { initialized = true }
        retireIfIdle()
    }

    /** Called only after an accepted EOF/CLOSE has sealed channel ingress. */
    fun finish() {
        synchronized(lock) { finished = true }
        retireIfIdle()
    }

    fun abort() {
        val cleanup = synchronized(lock) {
            if (isAborted || retired) return
            isAborted = true
            cleanupInProgress = true
            abortStreams
        }
        // No owner/lifecycle lock is needed to release streams after protocol teardown.
        supervisor.cancel()
        try {
            cleanup()
        } finally {
            synchronized(lock) { cleanupInProgress = false }
            retireIfIdle()
        }
    }

    suspend fun awaitReleased() {
        released.await()
        supervisor.join()
    }

    private fun workerFinished() {
        synchronized(lock) { workers-- }
        retireIfIdle()
    }

    private fun retireIfIdle() {
        val retiredCallback = synchronized(lock) {
            if (!initialized || (!finished && !isAborted) || cleanupInProgress || workers != 0 || retired) return
            retired = true
            onRetired
        }
        // A receiver may have cancelled a stream and left unread values in its ingress queue.
        // Once every worker has ended and ingress is sealed, those values are abandoned.
        if (!isAborted) abortStreams()
        supervisor.complete()
        retiredCallback()
        released.complete(Unit)
    }
}

/** Resource ledger, not a second channel-ID registry. Never wait while holding its lock. */
internal class ChannelDeliveryResources {
    private val lock = Any()
    private val deliveries = mutableSetOf<ChannelDelivery>()
    private var closed = false
    internal val size: Int get() = synchronized(lock) { deliveries.size }

    fun register(delivery: ChannelDelivery) {
        val reject = synchronized(lock) {
            if (closed) {
                true
            } else {
                deliveries.add(delivery)
                false
            }
        }
        if (reject) delivery.abort()
    }

    fun unregister(delivery: ChannelDelivery) {
        synchronized(lock) { deliveries.remove(delivery) }
    }

    suspend fun close() {
        val retained = synchronized(lock) {
            closed = true
            deliveries.toList()
        }
        retained.forEach { it.abort() }
        retained.forEach { it.awaitReleased() }
    }
}
