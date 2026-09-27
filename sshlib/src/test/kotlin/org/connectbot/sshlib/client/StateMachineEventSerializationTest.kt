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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.HostKeyVerifier
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.transport.PipedTransport
import org.connectbot.sshlib.transport.Transport
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A transition callback can suspend in its packet write after the packet has reached the server.
 * These tests hold that write open while the server's reply arrives, as a slow socket or a transport
 * that hops dispatchers would, and check that the reply waits for the transition instead of being
 * treated as an unexpected packet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StateMachineEventSerializationTest {

    private val acceptAllVerifier = object : HostKeyVerifier {
        override suspend fun verify(key: PublicKey): Boolean = true
    }

    @Test
    fun `channel open confirmation that arrives while the open is still being written is accepted`() = runTest {
        connectedFixture { connection, server, transport, dispatcher ->
            authenticate(connection, server, dispatcher)

            val release = transport.holdNextWrite()
            val open = async(dispatcher) { connection.openSessionChannel() }
            val openRequest = withTimeout(5_000) { server.awaitChannelOpen() }
            server.sendChannelOpenConfirmation(openRequest.senderChannel().toInt(), senderChannel = 100)
            testScheduler.advanceUntilIdle()
            release.complete(Unit)

            val session = assertNotNull(withTimeout(5_000) { open.await() })
            assertConnectionUsable(connection, server, session, dispatcher)
        }
    }

    @Test
    fun `channel success that arrives while the request is still being written is accepted`() = runTest {
        connectedFixture { connection, server, transport, dispatcher ->
            authenticate(connection, server, dispatcher)
            val session = openSession(connection, server, dispatcher)

            val release = transport.holdNextWrite()
            val pty = async(dispatcher) { session.requestPty("xterm", 80, 24, 0, 0, byteArrayOf(0)) }
            val request = withTimeout(5_000) { server.awaitChannelRequest() }
            assertEquals("pty-req", request.requestType().value())
            server.sendChannelSuccess(session.localChannelNumber)
            testScheduler.advanceUntilIdle()
            release.complete(Unit)

            assertTrue(withTimeout(5_000) { pty.await() })
            assertConnectionUsable(connection, server, session, dispatcher)
        }
    }

    private suspend fun TestScope.assertConnectionUsable(
        connection: SshConnection,
        server: FakeSshServer,
        session: SessionChannel,
        dispatcher: CoroutineDispatcher,
    ) {
        val exec = async(dispatcher) { session.requestExec("true") }
        withTimeout(5_000) { server.awaitChannelRequest() }
        server.sendChannelSuccess(session.localChannelNumber)
        assertTrue(withTimeout(5_000) { exec.await() })
        assertNull(withTimeoutOrNull(250) { connection.disconnectedFlow.first() })
    }

    /** Passes every write through, but can keep one write call suspended after its bytes are sent. */
    private class HoldingTransport(private val delegate: Transport) : Transport by delegate {
        @Volatile
        private var hold: CompletableDeferred<Unit>? = null

        fun holdNextWrite(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { hold = it }

        override suspend fun write(data: ByteArray) {
            delegate.write(data)
            hold?.let {
                hold = null
                it.await()
            }
        }
    }

    private suspend fun authenticate(
        connection: SshConnection,
        server: FakeSshServer,
        dispatcher: CoroutineDispatcher,
    ) {
        val auth = CoroutineScope(dispatcher).async { connection.authenticatePassword("user", "pass") }
        withTimeout(5_000) { server.awaitUserauthRequest() }
        server.sendUserauthSuccess()
        assertEquals(AuthResult.Success, withTimeout(5_000) { auth.await() })
    }

    private suspend fun openSession(
        connection: SshConnection,
        server: FakeSshServer,
        dispatcher: CoroutineDispatcher,
    ): SessionChannel {
        val open = CoroutineScope(dispatcher).async { connection.openSessionChannel() }
        val openRequest = withTimeout(5_000) { server.awaitChannelOpen() }
        server.sendChannelOpenConfirmation(openRequest.senderChannel().toInt(), senderChannel = 100)
        return assertNotNull(withTimeout(5_000) { open.await() })
    }

    private suspend fun TestScope.connectedFixture(
        block: suspend (SshConnection, FakeSshServer, HoldingTransport, CoroutineDispatcher) -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (clientTransport, serverTransport) = PipedTransport.create()
        val server = FakeSshServer(serverTransport, backgroundScope, dispatcher)
        server.start()

        val transport = HoldingTransport(clientTransport)
        val connection = SshConnection(
            transport = transport,
            hostKeyVerifier = acceptAllVerifier,
            rekeyIntervalMs = Long.MAX_VALUE,
            rekeyBytesLimit = Long.MAX_VALUE,
            coroutineDispatcher = dispatcher,
        )

        try {
            val result = CompletableDeferred<ConnectResult>()
            backgroundScope.launch(dispatcher) { result.complete(connection.connect()) }
            yield()
            assertIs<ConnectResult.Success>(result.await())
            block(connection, server, transport, dispatcher)
        } finally {
            connection.close()
        }
    }
}
