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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.HostKeyVerifier
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.protocol.SshEnums
import org.connectbot.sshlib.transport.PipedTransport
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class MlKemConnectionConformanceTest {
    @ParameterizedTest
    @ValueSource(strings = ["short", "long", "zero", "low-order", "ciphertext", "signature", "truncated"])
    fun `invalid hybrid exchange sends key exchange failed disconnect before closing`(damage: String) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (clientTransport, serverTransport) = PipedTransport.create()
        val server = FakeSshServer(serverTransport, backgroundScope, dispatcher).apply {
            kexAlgorithms = "mlkem768x25519-sha256"
            corruptKexSignature = damage == "signature"
            truncateKexReply = damage == "truncated"
            transformKexServerPublic = { reply ->
                when (damage) {
                    "short" -> reply.copyOf(1119)

                    "long" -> reply.copyOf(1121)

                    "zero", "low-order" -> reply.copyOf().also {
                        it.fill(0, 1088, 1120)
                        if (damage == "low-order") it[1088] = 1
                    }

                    "ciphertext" -> reply.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }

                    else -> reply
                }
            }
        }
        server.start(ignoreTransportErrors = true)
        var verifierCalled = false
        val connection = SshConnection(
            transport = clientTransport,
            hostKeyVerifier = object : HostKeyVerifier {
                override suspend fun verify(key: PublicKey): Boolean {
                    verifierCalled = true
                    return true
                }
            },
            kexAlgorithms = "mlkem768x25519-sha256",
            coroutineDispatcher = dispatcher,
        )
        try {
            val connect = backgroundScope.async(dispatcher) { connection.connect() }
            assertIs<ConnectResult.ProtocolError>(withTimeout(5000) { connect.await() })
            val disconnect = withTimeout(5000) { server.awaitDisconnect() }
            assertEquals(SshEnums.DisconnectReason.SSH_DISCONNECT_KEY_EXCHANGE_FAILED, disconnect.reasonCode())
            assertFalse(verifierCalled)
        } finally {
            connection.close()
        }
    }

    @Test
    fun `hybrid exchange authenticates and rekeys with an independent server combiner`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (clientTransport, serverTransport) = PipedTransport.create()
        val server = FakeSshServer(serverTransport, backgroundScope, dispatcher).apply {
            kexAlgorithms = "mlkem768x25519-sha256"
        }
        server.start()
        val connection = SshConnection(
            transport = clientTransport,
            hostKeyVerifier = object : HostKeyVerifier {
                override suspend fun verify(key: PublicKey): Boolean = true
            },
            kexAlgorithms = "mlkem768x25519-sha256",
            coroutineDispatcher = dispatcher,
        )
        try {
            val connect = backgroundScope.async(dispatcher) { connection.connect() }
            assertIs<ConnectResult.Success>(withTimeout(5000) { connect.await() })
            assertEquals("mlkem768x25519-sha256", connection.connectionInfo?.kexAlgorithm)
            val auth = backgroundScope.async(dispatcher) { connection.authenticatePassword("user", "pass") }
            withTimeout(5000) { server.awaitUserauthRequest() }
            server.sendUserauthSuccess()
            assertIs<AuthResult.Success>(withTimeout(5000) { auth.await() })
            server.initiateRekey()
            withTimeout(5000) { server.rekeyCount.first { it > 0 } }
            // An encrypted request after rekey proves both directions installed the same keys.
            val session = backgroundScope.async(dispatcher) { connection.openSessionChannel() }
            val open = withTimeout(5000) { server.awaitChannelOpen() }
            server.sendChannelOpenConfirmation(open.senderChannel().toInt(), 42)
            assertNotNull(withTimeout(5000) { session.await() }).close()
        } finally {
            connection.close()
        }
    }

    @Test
    fun `failed hybrid rekey disconnect bypasses saturated local command admission`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (clientTransport, serverTransport) = PipedTransport.create()
        val server = FakeSshServer(serverTransport, backgroundScope, dispatcher).apply {
            kexAlgorithms = "mlkem768x25519-sha256"
        }
        server.start(ignoreTransportErrors = true)
        val connection = SshConnection(
            transport = clientTransport,
            hostKeyVerifier = object : HostKeyVerifier {
                override suspend fun verify(key: PublicKey): Boolean = true
            },
            kexAlgorithms = "mlkem768x25519-sha256",
            coroutineDispatcher = dispatcher,
        )
        try {
            val connect = backgroundScope.async(dispatcher) { connection.connect() }
            assertIs<ConnectResult.Success>(withTimeout(5000) { connect.await() })
            // Eight local commands consume all admission permits while waiting for a
            // protocol condition. Fatal inbound KEX handling must still enter the owner.
            repeat(8) {
                backgroundScope.async(dispatcher) {
                    runCatching { connection.protocolExecutor.run(ready = { false }) {} }
                }
            }
            runCurrent()
            server.transformKexServerPublic = { it.copyOf(1119) }
            server.initiateRekey()
            val disconnect = withTimeout(1000) { server.awaitDisconnect() }
            assertEquals(SshEnums.DisconnectReason.SSH_DISCONNECT_KEY_EXCHANGE_FAILED, disconnect.reasonCode())
        } finally {
            connection.close()
        }
    }
}
