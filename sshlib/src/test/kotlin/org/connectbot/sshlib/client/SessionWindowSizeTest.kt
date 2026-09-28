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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.HostKeyVerifier
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.SshClient
import org.connectbot.sshlib.SshClientConfig
import org.connectbot.sshlib.transport.PipedTransport
import org.connectbot.sshlib.transport.TransportFactory
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class SessionWindowSizeTest {

    private val acceptAllVerifier = object : HostKeyVerifier {
        override suspend fun verify(key: PublicKey): Boolean = true
    }

    @Test
    fun `session channels advertise two mebibytes by default`() = runTest {
        assertEquals(2L * 1024 * 1024, advertisedWindow(sessionWindowSize = null))
    }

    @Test
    fun `session channels advertise the configured window`() = runTest {
        assertEquals(64L * 1024, advertisedWindow(sessionWindowSize = 64 * 1024))
    }

    @Test
    fun `SshClient passes the configured window to session channels`(): Unit = runBlocking {
        val (clientTransport, serverTransport) = PipedTransport.create()
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = FakeSshServer(serverTransport, serverScope)
        server.start()
        val client = SshClient(
            SshClientConfig {
                transportFactory = TransportFactory { clientTransport }
                hostKeyVerifier = acceptAllVerifier
                sessionWindowSize = 2 * 1024 * 1024
            },
        )

        try {
            withTimeout(10_000) {
                assertIs<ConnectResult.Success>(client.connect())
                val auth = async { client.authenticatePassword("user", "pass") }
                server.awaitUserauthRequest()
                server.sendUserauthSuccess()
                assertEquals(AuthResult.Success, auth.await())

                val session = async { client.openSession() }
                val openRequest = server.awaitChannelOpen()
                assertEquals(2L * 1024 * 1024, openRequest.initialWindowSize())
                server.sendChannelOpenConfirmation(openRequest.senderChannel().toInt(), senderChannel = 100)
                assertNotNull(session.await())
            }
        } finally {
            client.disconnect()
            serverScope.cancel()
        }
    }

    /** Opens a session channel on a fresh connection and returns the window it asked for. */
    private suspend fun TestScope.advertisedWindow(sessionWindowSize: Int?): Long {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (clientTransport, serverTransport) = PipedTransport.create()
        val server = FakeSshServer(serverTransport, backgroundScope, dispatcher)
        server.start()

        val connection = SshConnection(
            transport = clientTransport,
            hostKeyVerifier = acceptAllVerifier,
            coroutineDispatcher = dispatcher,
        )
        if (sessionWindowSize != null) connection.sessionWindowSize = sessionWindowSize

        try {
            val connected = CompletableDeferred<ConnectResult>()
            backgroundScope.launch(dispatcher) { connected.complete(connection.connect()) }
            yield()
            assertIs<ConnectResult.Success>(connected.await())

            val auth = async(dispatcher) { connection.authenticatePassword("user", "pass") }
            withTimeout(5_000) { server.awaitUserauthRequest() }
            server.sendUserauthSuccess()
            assertEquals(AuthResult.Success, withTimeout(5_000) { auth.await() })

            val open = async(dispatcher) { connection.openSessionChannel() }
            val openRequest = withTimeout(5_000) { server.awaitChannelOpen() }
            server.sendChannelOpenConfirmation(openRequest.senderChannel().toInt(), senderChannel = 100)
            assertNotNull(withTimeout(5_000) { open.await() })
            return openRequest.initialWindowSize()
        } finally {
            connection.close()
        }
    }
}
