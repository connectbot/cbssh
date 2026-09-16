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

package org.connectbot.sshlib.protocol

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Write receipts collected during admission, awaited only after transition locks are released. */
internal class DeferredIo : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DeferredIo>
    private val receipts = mutableListOf<Deferred<Unit>>()

    fun add(receipt: Deferred<Unit>) {
        receipts += receipt
    }
    fun transferTo(other: DeferredIo) {
        receipts.forEach(other::add)
    }
    suspend fun await() {
        receipts.forEach { it.await() }
    }
}

internal suspend fun <T> deferIo(action: suspend () -> T): T {
    if (currentCoroutineContext()[DeferredIo] != null) return action()
    val batch = DeferredIo()
    val result = withContext(batch) { action() }
    batch.await()
    return result
}
