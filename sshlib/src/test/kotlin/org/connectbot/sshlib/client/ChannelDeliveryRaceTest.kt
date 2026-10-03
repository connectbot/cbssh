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

package org.connectbot.sshlib.client

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.connectbot.sshlib.protocol.DeferredIo
import org.connectbot.sshlib.transport.TransportException
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChannelDeliveryRaceTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `failed admitted window credit after remote close preserves the session tail`(buffered: Boolean) = runTest {
        val connection = mockk<SshConnection>(relaxed = true)
        val resources = ChannelDeliveryResources()
        every { connection.deliveryResources } returns resources
        val receipt = CompletableDeferred<Unit>()
        val admitted = CompletableDeferred<Unit>()
        coEvery { connection.sendWindowAdjust(any(), any()) } coAnswers {
            currentCoroutineContext()[DeferredIo]!!.add(receipt)
            admitted.complete(Unit)
        }
        val session = SessionChannel(
            connection,
            backgroundScope,
            0,
            1,
            32768,
            initialWindowSize = 128,
            bufferedStdout = buffered,
        )
        try {
            val stdout = session.stdout
            session.onData(ByteArray(100))
            session.onData(byteArrayOf(7, 8))
            assertEquals(100, stdout.receive().size)
            withTimeout(5_000) { admitted.await() }
            // The lifecycle lock has been released, but delivery is awaiting a write receipt.
            session.onClose()
            receipt.completeExceptionally(TransportException("Writer closed after remote CLOSE"))
            assertContentEquals(byteArrayOf(7, 8), withTimeout(5_000) { stdout.receive() })
            assertTrue(stdout.receiveCatching().isClosed)
            withTimeout(5_000) { session.delivery.awaitReleased() }
            assertEquals(0, resources.size)
            coVerify(exactly = 1) { connection.sendWindowAdjust(1, 100) }
            coVerify(exactly = 0) { connection.transportFailed(any()) }
        } finally {
            receipt.complete(Unit)
            session.close()
            resources.close()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `forwarding credit failure after remote close drains but local close discards`(localClose: Boolean) = runTest {
        val connection = mockk<SshConnection>(relaxed = true)
        val resources = ChannelDeliveryResources()
        every { connection.deliveryResources } returns resources
        val receipt = CompletableDeferred<Unit>()
        val admitted = CompletableDeferred<Unit>()
        coEvery { connection.sendWindowAdjust(any(), any()) } coAnswers {
            currentCoroutineContext()[DeferredIo]!!.add(receipt)
            admitted.complete(Unit)
        }
        val channel = ForwardingChannel(connection, backgroundScope, 0, 1, 32768, 128, initialWindowSize = 128)
        try {
            channel.onData(ByteArray(100))
            channel.onData(byteArrayOf(7, 8))
            assertEquals(100, channel.incomingData.receive().size)
            withTimeout(5_000) { admitted.await() }
            if (localClose) channel.close() else channel.onClose()
            receipt.completeExceptionally(TransportException("Writer closed"))
            if (!localClose) assertContentEquals(byteArrayOf(7, 8), withTimeout(5_000) { channel.incomingData.receive() })
            assertTrue(channel.incomingData.receiveCatching().isClosed)
            withTimeout(5_000) { channel.delivery.awaitReleased() }
            assertEquals(0, resources.size)
            coVerify(exactly = 0) { connection.transportFailed(any()) }
        } finally {
            receipt.complete(Unit)
            channel.close()
            resources.close()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `resource shutdown rejects late registration and releases completed workers`(finishBeforeRegistration: Boolean) = runTest {
        val resources = ChannelDeliveryResources()
        val delivery = ChannelDelivery(backgroundScope.coroutineContext)
        val worker = CompletableDeferred<Unit>()
        delivery.launch { worker.await() }
        if (finishBeforeRegistration) {
            worker.complete(Unit)
            delivery.finish()
            runCurrent()
        } else {
            resources.close()
        }
        var abortCalls = 0
        delivery.initialize(abortStreams = { abortCalls++ }, resources = resources)
        withTimeout(5_000) { delivery.awaitReleased() }
        assertEquals(0, resources.size)
        assertEquals(1, abortCalls)
        assertEquals(null, delivery.launch { error("Retired delivery cannot start a lazy adapter") })
        resources.close()
    }
}
