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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.HostKeyVerifier
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.SessionExit
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.client.sftp.SftpDispatcher
import org.connectbot.sshlib.client.sftp.SftpPacketIO
import org.connectbot.sshlib.client.sftp.SftpPacketTransport
import org.connectbot.sshlib.client.sftp.SftpRawPacket
import org.connectbot.sshlib.protocol.SftpStateMachine
import org.connectbot.sshlib.protocol.SshEnums
import org.connectbot.sshlib.transport.ForwardingChannelTransport
import org.connectbot.sshlib.transport.PipedTransport
import org.connectbot.sshlib.transport.Transport
import org.connectbot.sshlib.transport.TransportException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SshChannelDrainTest {
    companion object {
        @JvmStatic
        fun lateReaders(): List<Arguments> = buildList {
            for (autoDisconnect in listOf(false, true)) {
                for (exec in listOf(false, true)) {
                    for (eof in listOf(false, true)) {
                        for (type in listOf(0, 1, 7)) add(Arguments.of(autoDisconnect, exec, eof, type))
                    }
                }
            }
        }
    }

    @ParameterizedTest(name = "autoDisconnect={0}, exec={1}, EOF={2}, stream={3}")
    @MethodSource("lateReaders")
    fun `late reader retains output after remote close`(autoDisconnect: Boolean, exec: Boolean, eof: Boolean, type: Int) = runTest {
        fixture { connection, server ->
            connection.autoDisconnectOnLastChannelClose = autoDisconnect
            val session = openSession(connection, server)
            val request = backgroundScope.async { if (exec) session.requestExec("fail") else session.requestShell() }
            val wireRequest = withTimeout(5_000) { server.awaitChannelRequest() }
            assertEquals(if (exec) "exec" else "shell", wireRequest.requestType().value())
            server.sendChannelSuccess(session.localChannelNumber)
            assertTrue(withTimeout(5_000) { request.await() })
            val expected = "oops\n".encodeToByteArray()
            if (type == 0) {
                server.sendChannelData(session.localChannelNumber, expected)
            } else {
                server.sendChannelExtendedData(session.localChannelNumber, type, expected)
            }
            if (exec) server.sendChannelExitStatus(session.localChannelNumber, 3)
            closeRemotely(connection, server, session, eof, autoDisconnect)
            assertEquals(if (exec) SessionExit.Status(3) else null, session.exitInfo.await())

            val output = ByteArrayOutputStream()
            withTimeout(5_000) {
                when (type) {
                    0 -> for (chunk in session.stdout) output.write(chunk)
                    1 -> for (chunk in session.stderr) output.write(chunk)
                    else -> while (true) output.write(session.readExtended()?.second ?: break)
                }
            }
            assertContentEquals(expected, output.toByteArray())
            withTimeout(5_000) { session.delivery.awaitReleased() }
            assertEquals(0, connection.deliveryResources.size)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `buffered stdout first reader can attach after network teardown`(arrayAdapter: Boolean) = runTest {
        fixture { connection, server ->
            val session = openSession(connection, server, buffered = true)
            server.sendChannelData(session.localChannelNumber, byteArrayOf(1, 2))
            server.sendChannelData(session.localChannelNumber, byteArrayOf(3))
            closeRemotely(connection, server, session)
            assertEquals(1, connection.deliveryResources.size)
            val output = ByteArrayOutputStream()
            withTimeout(5_000) {
                if (arrayAdapter) {
                    for (chunk in session.stdout) output.write(chunk)
                } else {
                    while (true) {
                        val buffer = session.readBuffer() ?: break
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        output.write(bytes)
                    }
                }
            }
            assertContentEquals(byteArrayOf(1, 2, 3), output.toByteArray())
            withTimeout(5_000) { session.delivery.awaitReleased() }
            assertEquals(0, connection.deliveryResources.size)
            // Starting the unused array adapter after buffer delivery retired is harmless.
            assertTrue(session.stdout.receiveCatching().isClosed)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `empty output retires delivery and a late buffered adapter sees EOF`(buffered: Boolean) = runTest {
        fixture { connection, server ->
            val session = openSession(connection, server, buffered)
            closeRemotely(connection, server, session)
            withTimeout(5_000) { session.delivery.awaitReleased() }
            assertEquals(0, connection.deliveryResources.size)
            assertTrue(withTimeout(5_000) { session.stdout.receiveCatching() }.isClosed)
            assertTrue(session.stderr.receiveCatching().isClosed)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `explicit close after network teardown discards retained streams`(disconnectClient: Boolean) = runTest {
        fixture { connection, server ->
            val session = openSession(connection, server, buffered = true)
            server.sendChannelData(session.localChannelNumber, byteArrayOf(1))
            server.sendChannelExtendedData(session.localChannelNumber, 1, byteArrayOf(2))
            server.sendChannelExtendedData(session.localChannelNumber, 7, byteArrayOf(3))
            closeRemotely(connection, server, session)
            assertEquals(1, connection.deliveryResources.size)
            if (disconnectClient) {
                connection.close()
                connection.close()
            } else {
                session.close()
                session.close()
            }
            withTimeout(5_000) { session.delivery.awaitReleased() }
            assertEquals(0, connection.deliveryResources.size)
            assertNull(session.read())
            assertTrue(session.stdout.receiveCatching().isClosed)
            assertTrue(session.stderr.receiveCatching().isClosed)
            assertNull(session.readExtended())
        }
    }

    @Test
    fun `partial consumption leaves stderr retained until explicit cleanup`() = runTest {
        fixture { connection, server ->
            val session = openSession(connection, server)
            server.sendChannelData(session.localChannelNumber, byteArrayOf(1))
            assertContentEquals(byteArrayOf(1), session.read())
            server.sendChannelData(session.localChannelNumber, byteArrayOf(2))
            server.sendChannelExtendedData(session.localChannelNumber, 1, byteArrayOf(3))
            closeRemotely(connection, server, session)
            assertContentEquals(byteArrayOf(2), session.read())
            assertNull(session.read())
            assertEquals(1, connection.deliveryResources.size)
            session.close()
            withTimeout(5_000) { session.delivery.awaitReleased() }
            assertTrue(session.stderr.receiveCatching().isClosed)
            assertEquals(0, connection.deliveryResources.size)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `transport failure before remote close aborts unread data even after EOF`(eof: Boolean) = runTest {
        fixture { connection, server ->
            val session = openSession(connection, server)
            server.sendChannelData(session.localChannelNumber, byteArrayOf(1))
            if (eof) server.sendChannelEof(session.localChannelNumber)
            server.sendDisconnect()
            // The socket-like fixture wakes the packet loop on close; no channel CLOSE occurred.
            withTimeout(5_000) { connection.connectionScope.coroutineContext[Job]!!.join() }
            assertTrue(session.stdout.receiveCatching().isClosed)
            withTimeout(5_000) { session.delivery.awaitReleased() }
            assertEquals(0, connection.deliveryResources.size)
        }
    }

    @Test
    fun `closed forwarding tail survives last session automatic teardown`() = runTest {
        fixture { connection, server ->
            val session = openSession(connection, server)
            val open = backgroundScope.async { connection.openDirectTcpipChannel("localhost", 22, "localhost", 1234) }
            val request = withTimeout(5_000) { server.awaitChannelOpen() }
            server.sendChannelOpenConfirmation(request.senderChannel().toInt(), senderChannel = 200)
            val forwarding = assertNotNull(withTimeout(5_000) { open.await() })
            val transport = ForwardingChannelTransport(forwarding)
            server.sendChannelData(forwarding.localChannelNumber, byteArrayOf(1, 2))
            server.sendChannelData(forwarding.localChannelNumber, byteArrayOf(3, 4))
            server.sendChannelClose(forwarding.localChannelNumber)
            assertEquals(SshEnums.MessageType.SSH_MSG_CHANNEL_CLOSE, server.awaitClosingPacket())
            closeRemotely(connection, server, session)
            assertEquals(1, connection.deliveryResources.size)
            assertContentEquals(byteArrayOf(1, 2, 3), withTimeout(5_000) { transport.read(3) })
            assertContentEquals(byteArrayOf(4), transport.read(1))
            assertFailsWith<TransportException> { transport.read(1) }
            withTimeout(5_000) { forwarding.delivery.awaitReleased() }
            transport.close()
            assertEquals(0, connection.deliveryResources.size)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `cancelled stdout consumer does not poison stderr or another session`(buffered: Boolean) = runTest {
        fixture { connection, server ->
            connection.autoDisconnectOnLastChannelClose = false
            val cancelled = openSession(connection, server, buffered)
            val other = openSession(connection, server)
            cancelled.stdout.cancel()
            server.sendChannelData(cancelled.localChannelNumber, byteArrayOf(1))
            server.sendChannelData(cancelled.localChannelNumber, byteArrayOf(9))
            server.sendChannelExtendedData(cancelled.localChannelNumber, 1, byteArrayOf(2))
            server.sendChannelData(other.localChannelNumber, byteArrayOf(3))
            assertContentEquals(byteArrayOf(2), withTimeout(5_000) { cancelled.stderr.receive() })
            assertContentEquals(byteArrayOf(3), withTimeout(5_000) { other.read() })
            assertTrue(other.isOpen)
            closeRemotely(connection, server, cancelled, autoDisconnect = false)
            withTimeout(5_000) { cancelled.delivery.awaitReleased() }
            assertEquals(1, connection.deliveryResources.size)
        }
    }

    @Test
    fun `cancelled forwarding delivery stays tracked until explicit cleanup`() = runTest {
        fixture { connection, server ->
            val open = backgroundScope.async { connection.openDirectTcpipChannel("localhost", 22, "localhost", 1234) }
            val request = withTimeout(5_000) { server.awaitChannelOpen() }
            server.sendChannelOpenConfirmation(request.senderChannel().toInt(), senderChannel = 200)
            val channel = assertNotNull(withTimeout(5_000) { open.await() })
            channel.incomingData.cancel()
            server.sendChannelData(channel.localChannelNumber, byteArrayOf(1))
            server.sendChannelData(channel.localChannelNumber, byteArrayOf(2))
            runCurrent()
            assertTrue(channel.delivery.job.children.none())
            // No pump remains, but the peer can still send and ingress may hold queued data.
            assertEquals(1, connection.deliveryResources.size)
            connection.close()
            withTimeout(5_000) { channel.delivery.awaitReleased() }
            assertEquals(0, connection.deliveryResources.size)
        }
    }

    @Test
    fun `previously closed session drains after peer disconnect while an open session aborts`() = runTest {
        fixture { connection, server ->
            connection.autoDisconnectOnLastChannelClose = false
            val closed = openSession(connection, server)
            val active = openSession(connection, server)
            server.sendChannelData(closed.localChannelNumber, byteArrayOf(1))
            closeRemotely(connection, server, closed, autoDisconnect = false)
            server.sendChannelData(active.localChannelNumber, byteArrayOf(2))
            server.sendDisconnect()
            withTimeout(5_000) { connection.connectionScope.coroutineContext[Job]!!.join() }
            assertTrue(active.stdout.receiveCatching().isClosed)
            assertContentEquals(byteArrayOf(1), closed.read())
            assertNull(closed.read())
            withTimeout(5_000) { closed.delivery.awaitReleased() }
            withTimeout(5_000) { active.delivery.awaitReleased() }
            assertEquals(0, connection.deliveryResources.size)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `SFTP responses drain after network teardown and truncated frames fail pending requests`(truncated: Boolean) = runTest {
        fixture { connection, server ->
            val session = openSession(connection, server, buffered = true)
            val io = SftpPacketIO(session)
            val allowRead = CompletableDeferred<Unit>()
            val machine = SftpStateMachine()
            machine.sendInit { }
            machine.receiveVersion { }
            val dispatcher = SftpDispatcher(
                object : SftpPacketTransport by io {
                    override suspend fun readPacket(): SftpResult<SftpRawPacket> {
                        allowRead.await()
                        return io.readPacket()
                    }
                },
                machine,
            )
            val reader = dispatcher.startReadLoop(backgroundScope)
            try {
                val pending = backgroundScope.async {
                    dispatcher.request(5, byteArrayOf()) { action -> machine.readFile { action() } }
                }
                // The first request has ID 1. Observing its SSH data proves admission and write.
                withTimeout(5_000) { server.awaitChannelData() }
                val data = byteArrayOf(7, 8, 9)
                val payload = ByteBuffer.allocate(4 + data.size).putInt(data.size).put(data).array()
                val frame = ByteBuffer.allocate(9 + payload.size)
                    .putInt(5 + payload.size).put(103.toByte()).putInt(1).put(payload).array()
                val wire = if (truncated) frame.copyOf(frame.size - 2) else frame
                // Split both the length prefix and body across independently owned SSH buffers.
                server.sendChannelData(session.localChannelNumber, wire.copyOfRange(0, 2))
                server.sendChannelData(session.localChannelNumber, wire.copyOfRange(2, 7))
                server.sendChannelData(session.localChannelNumber, wire.copyOfRange(7, wire.size))
                closeRemotely(connection, server, session)
                assertFalse(pending.isCompleted)
                allowRead.complete(Unit)
                val result = withTimeout(5_000) { pending.await() }
                if (truncated) {
                    assertIs<SftpResult.IoError>(result)
                } else {
                    assertContentEquals(payload, assertIs<SftpResult.Success<SftpRawPacket>>(result).value.payload)
                }
                withTimeout(5_000) { reader.join() }
                withTimeout(5_000) { session.delivery.awaitReleased() }
                assertEquals(0, connection.deliveryResources.size)
            } finally {
                allowRead.complete(Unit)
                dispatcher.stop()
            }
        }
    }

    private suspend fun TestScope.openSession(connection: SshConnection, server: FakeSshServer, buffered: Boolean = false): SessionChannel {
        val open = backgroundScope.async {
            if (buffered) connection.openBufferedSessionChannel(128) else connection.openSessionChannel(initialWindowSize = 128)
        }
        val request = withTimeout(5_000) { server.awaitChannelOpen() }
        server.sendChannelOpenConfirmation(request.senderChannel().toInt(), senderChannel = 100 + request.senderChannel().toInt())
        return assertNotNull(withTimeout(5_000) { open.await() })
    }

    private suspend fun TestScope.closeRemotely(
        connection: SshConnection,
        server: FakeSshServer,
        session: SessionChannel,
        eof: Boolean = true,
        autoDisconnect: Boolean = true,
    ) {
        if (eof) server.sendChannelEof(session.localChannelNumber)
        server.sendChannelClose(session.localChannelNumber)
        assertEquals(SshEnums.MessageType.SSH_MSG_CHANNEL_CLOSE, withTimeout(5_000) { server.awaitClosingPacket() })
        if (autoDisconnect) {
            assertEquals(SshEnums.MessageType.SSH_MSG_DISCONNECT, withTimeout(5_000) { server.awaitClosingPacket() })
            // Network teardown, including cancellation of the old delivery scope, MUST finish
            // before readers attach. Neither a sleep nor isOpen=false establishes this barrier.
            withTimeout(5_000) { connection.connectionScope.coroutineContext[Job]!!.join() }
            assertTrue(connection.protocolExecutor.isClosed)
        } else {
            runCurrent()
        }
        assertFalse(session.isOpen)
    }

    private suspend fun TestScope.fixture(block: suspend (SshConnection, FakeSshServer) -> Unit) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (clientTransport, serverTransport) = PipedTransport.create()
        val server = FakeSshServer(serverTransport, backgroundScope, dispatcher)
        server.start()
        val connection = SshConnection(
            // PipedTransport.close only closes its outbound pipe. Real sockets also wake their
            // local reader; close both ends here to reproduce that network teardown faithfully.
            transport = object : Transport by clientTransport {
                override suspend fun close() {
                    clientTransport.close()
                    serverTransport.close()
                }
            },
            hostKeyVerifier = object : HostKeyVerifier {
                override suspend fun verify(key: PublicKey): Boolean = true
            },
            rekeyIntervalMs = Long.MAX_VALUE,
            rekeyBytesLimit = Long.MAX_VALUE,
            coroutineDispatcher = dispatcher,
        )
        try {
            val connect = backgroundScope.async { connection.connect() }
            assertIs<ConnectResult.Success>(withTimeout(5_000) { connect.await() })
            val auth = backgroundScope.async { connection.authenticatePassword("user", "pass") }
            withTimeout(5_000) { server.awaitUserauthRequest() }
            server.sendUserauthSuccess()
            assertEquals(AuthResult.Success, withTimeout(5_000) { auth.await() })
            block(connection, server)
        } finally {
            connection.close()
            assertEquals(0, connection.deliveryResources.size)
            serverTransport.close()
        }
    }
}
