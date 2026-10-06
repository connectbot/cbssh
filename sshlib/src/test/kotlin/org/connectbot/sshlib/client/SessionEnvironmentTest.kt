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
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.HostKeyVerifier
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.SshClient
import org.connectbot.sshlib.SshClientConfig
import org.connectbot.sshlib.SshSession
import org.connectbot.sshlib.protocol.ChannelRequestEnv
import org.connectbot.sshlib.transport.PipedTransport
import org.connectbot.sshlib.transport.TransportFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionEnvironmentTest {
    private val verifier = object : HostKeyVerifier {
        override suspend fun verify(key: PublicKey): Boolean = true
    }

    private fun config(environment: Map<String, String> = emptyMap()): SshClientConfig = SshClientConfig {
        host = "example.com"
        hostKeyVerifier = verifier
        this.environment = environment
        autoDisconnectOnLastChannelClose = false
    }

    @Test
    fun `configuration snapshots environment in iteration order`() {
        val input = linkedMapOf("SECOND" to "二", "FIRST" to "")
        val config = config(input)
        input.clear()
        assertEquals(listOf("SECOND", "FIRST"), config.environment.keys.toList())
        assertEquals("二", config.environment["SECOND"])
        assertFailsWith<UnsupportedOperationException> {
            (config.environment as MutableMap<String, String>)["OTHER"] = "changed"
        }
        assertTrue(config().environment.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `NUL configuration fails before connecting`(inName: Boolean) {
        assertFailsWith<IllegalArgumentException> {
            config(mapOf((if (inName) "A\u0000B" else "A") to (if (inName) "B" else "A\u0000B")))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["shell", "exec", "subsystem"])
    fun `defaults precede process requests on every session`(process: String): Unit = runBlocking {
        fixture(linkedMapOf("名前" to "値🌍", "EMPTY" to "")) { client, server ->
            repeat(2) { index ->
                val opening = async { client.openSession() }
                val open = server.awaitChannelOpen()
                val local = open.senderChannel().toInt()
                val remote = 100 + index
                server.sendChannelOpenConfirmation(local, senderChannel = remote)
                val session = assertNotNull(opening.await())
                for ((name, value) in linkedMapOf("名前" to "値🌍", "EMPTY" to "")) {
                    val request = server.awaitChannelRequest()
                    assertEquals(remote.toLong(), request.recipientChannel())
                    assertEquals("env", request.requestType().value())
                    assertEquals(0, request.wantReply())
                    val env = assertIs<ChannelRequestEnv>(request.requestSpecificFields())
                    assertEquals(name, env.variableName().data().toString(Charsets.UTF_8))
                    assertEquals(value, env.variableValue().data().toString(Charsets.UTF_8))
                    assertEquals(value.toByteArray(Charsets.UTF_8).size.toLong(), env.variableValue().lenData())
                }
                val start = async {
                    when (process) {
                        "shell" -> session.requestShell()
                        "exec" -> session.requestExec("true")
                        else -> session.requestSubsystem("sftp")
                    }
                }
                assertEquals(process, server.awaitChannelRequest().requestType().value())
                server.sendChannelSuccess(local)
                assertTrue(start.await())
                session.close()
            }
        }
    }

    @Test
    fun `empty defaults and invalid explicit input emit no env requests`(): Unit = runBlocking {
        fixture { client, server ->
            val session = open(client, server)
            assertFalse(session.requestEnv("A\u0000B", "value"))
            assertFalse(session.requestEnv("A", "v\u0000alue"))
            val shell = async { session.requestShell() }
            assertEquals("shell", server.awaitChannelRequest().requestType().value())
            server.sendChannelSuccess(session.localChannelNumber)
            assertTrue(shell.await())
            session.close()
            assertFalse(session.requestEnv("A", "value"))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `explicit request reports acceptance and leaves the session usable`(accepted: Boolean): Unit = runBlocking {
        fixture { client, server ->
            val session = open(client, server)
            val setting = async { session.requestEnv("LANG", "C.UTF-8") }
            val request = server.awaitChannelRequest()
            assertEquals("env", request.requestType().value())
            assertEquals(1, request.wantReply())
            if (accepted) {
                server.sendChannelSuccess(session.localChannelNumber)
            } else {
                server.sendChannelFailure(session.localChannelNumber)
            }
            assertEquals(accepted, setting.await())
            val shell = async { session.requestShell() }
            assertEquals("shell", server.awaitChannelRequest().requestType().value())
            server.sendChannelSuccess(session.localChannelNumber)
            assertTrue(shell.await())
            session.close()
        }
    }

    @Test
    fun `cancelled env request retains its reply slot until the reply arrives`(): Unit = runBlocking {
        fixture { client, server ->
            val session = open(client, server)
            val setting = async { session.requestEnv("LANG", "C") }
            assertEquals("env", server.awaitChannelRequest().requestType().value())
            setting.cancel()
            setting.join()
            server.sendChannelFailure(session.localChannelNumber)
            // A subsequent open confirmation proves the reader processed the prior reply.
            val barrier = open(client, server, remote = 101)
            barrier.close()
            val next = async { session.requestEnv("LANG", "C.UTF-8") }
            assertEquals("env", server.awaitChannelRequest().requestType().value())
            server.sendChannelSuccess(session.localChannelNumber)
            assertTrue(next.await())
            session.close()
        }
    }

    @Test
    fun `failed defaults close newly opened session and SFTP channels`(): Unit = runBlocking {
        for (sftp in listOf(false, true)) {
            val connection = mockk<SshConnection>(relaxed = true)
            val session = mockk<SessionChannel>(relaxed = true)
            coEvery { connection.openSessionChannel() } returns session
            coEvery { connection.openBufferedSessionChannel(any()) } returns session
            coEvery { session.sendEnv(any(), any(), false) } throws IllegalStateException("write failed")
            val client = SshClient.createForTesting(config(mapOf("A" to "B")), initialConnection = connection, initialAuthenticated = true)
            if (sftp) client.openSftp() else assertNull(client.openSession())
            coVerify(exactly = 1) { session.close() }
        }
    }

    @Test
    fun `SFTP defaults precede subsystem startup without awaiting env replies`(): Unit = runBlocking {
        fixture(mapOf("LANG" to "C")) { client, server ->
            val opening = async { client.openSftp() }
            val open = server.awaitChannelOpen()
            server.sendChannelOpenConfirmation(open.senderChannel().toInt(), senderChannel = 100)
            val env = server.awaitChannelRequest()
            assertEquals("env", env.requestType().value())
            assertEquals(0, env.wantReply())
            assertEquals("subsystem", server.awaitChannelRequest().requestType().value())
            server.sendChannelFailure(open.senderChannel().toInt())
            assertIs<org.connectbot.sshlib.SftpResult.ProtocolError>(opening.await())
        }
    }

    @Test
    fun `cancelled default setup closes the new channel`(): Unit = runBlocking {
        val connection = mockk<SshConnection>(relaxed = true)
        val session = mockk<SessionChannel>(relaxed = true)
        val sending = CompletableDeferred<Unit>()
        coEvery { connection.openSessionChannel() } returns session
        coEvery { session.sendEnv(any(), any(), false) } coAnswers {
            sending.complete(Unit)
            awaitCancellation()
        }
        val client = SshClient.createForTesting(config(mapOf("A" to "B")), initialConnection = connection, initialAuthenticated = true)
        val opening = async { client.openSession() }
        sending.await()
        opening.cancel()
        opening.join()
        coVerify(exactly = 1) { session.close() }
    }

    private suspend fun open(client: SshClient, server: FakeSshServer, remote: Int = 100): SshSession = kotlinx.coroutines.coroutineScope {
        val opening = async { client.openSession() }
        val request = server.awaitChannelOpen()
        server.sendChannelOpenConfirmation(request.senderChannel().toInt(), senderChannel = remote)
        assertNotNull(opening.await())
    }

    private suspend fun fixture(
        environment: Map<String, String> = emptyMap(),
        action: suspend CoroutineScope.(SshClient, FakeSshServer) -> Unit,
    ) = kotlinx.coroutines.coroutineScope {
        val (clientTransport, serverTransport) = PipedTransport.create()
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = FakeSshServer(serverTransport, serverScope)
        server.start()
        val client = SshClient(
            SshClientConfig {
                transportFactory = TransportFactory { clientTransport }
                hostKeyVerifier = verifier
                this.environment = environment
                autoDisconnectOnLastChannelClose = false
            },
        )
        try {
            withTimeout(15_000) {
                assertIs<ConnectResult.Success>(client.connect())
                val auth = async { client.authenticatePassword("user", "pass") }
                server.awaitUserauthRequest()
                server.sendUserauthSuccess()
                assertEquals(AuthResult.Success, auth.await())
                action(client, server)
            }
        } finally {
            client.disconnect()
            serverScope.cancel()
        }
    }
}
