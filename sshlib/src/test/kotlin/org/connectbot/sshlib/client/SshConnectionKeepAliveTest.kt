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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.HostKeyVerifier
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.transport.PipedTransport
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class SshConnectionKeepAliveTest {
    @Test
    fun `heartbeat starts after authentication and contains an empty SSH string`() = runTest {
        val (connection, server) = fixture(100)
        try {
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(0, server.receivedIgnores.size)
            authenticate(connection, server)
            advanceTimeBy(101)
            runCurrent()
            assertEquals(1, server.receivedIgnores.size)
            assertContentEquals(byteArrayOf(), server.receivedIgnores.single().data().data())
            connection.close()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1, server.receivedIgnores.size)
        } finally {
            connection.close()
        }
    }

    @Test
    fun `disabled heartbeat sends no packets after authentication`() = runTest {
        val (connection, server) = fixture(0)
        try {
            authenticate(connection, server)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(0, server.receivedIgnores.size)
        } finally {
            connection.close()
        }
    }

    @Test
    fun `remote disconnect retires heartbeat and replacement connection sends its own`() = runTest {
        val (first, oldServer) = fixture(100)
        try {
            authenticate(first, oldServer)
            advanceTimeBy(101)
            runCurrent()
            assertEquals(1, oldServer.receivedIgnores.size)
            val disconnected = async { first.disconnectedFlow.first() }
            runCurrent()
            oldServer.sendDisconnect()
            withTimeout(5_000) { disconnected.await() }
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1, oldServer.receivedIgnores.size)

            val (second, newServer) = fixture(100)
            try {
                authenticate(second, newServer)
                advanceTimeBy(101)
                runCurrent()
                assertEquals(1, newServer.receivedIgnores.size)
                assertEquals(1, oldServer.receivedIgnores.size)
            } finally {
                second.close()
            }
        } finally {
            first.close()
        }
    }

    private suspend fun TestScope.fixture(intervalMs: Long): Pair<SshConnection, FakeSshServer> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (clientTransport, serverTransport) = PipedTransport.create()
        val server = FakeSshServer(serverTransport, backgroundScope, dispatcher)
        server.start(ignoreTransportErrors = true)
        val connection = SshConnection(
            transport = clientTransport,
            hostKeyVerifier = object : HostKeyVerifier {
                override suspend fun verify(key: PublicKey): Boolean = true
            },
            rekeyIntervalMs = Long.MAX_VALUE,
            coroutineDispatcher = dispatcher,
        )
        connection.keepAliveIntervalMs = intervalMs
        assertIs<ConnectResult.Success>(connection.connect())
        return connection to server
    }

    private suspend fun TestScope.authenticate(connection: SshConnection, server: FakeSshServer) {
        val result = async { connection.authenticatePassword("user", "pass") }
        withTimeout(5_000) { server.awaitUserauthRequest() }
        server.sendUserauthSuccess()
        assertEquals(AuthResult.Success, withTimeout(5_000) { result.await() })
    }
}
