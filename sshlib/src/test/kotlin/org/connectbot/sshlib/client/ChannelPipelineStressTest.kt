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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.HostKeyVerifier
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.protocol.ChannelRequestWindowChange
import org.connectbot.sshlib.protocol.SshEnums
import org.connectbot.sshlib.transport.PipedTransport
import org.connectbot.sshlib.transport.Transport
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ChannelPipelineStressTest {
    @ParameterizedTest(name = "multiplexed pipeline stress seed={0}")
    @ValueSource(ints = [7, 4254, 8675309])
    fun `random bidirectional traffic preserves every channel stream`(seed: Int) = runTest(timeout = 60.seconds) {
        // Use real worker threads: a single virtual-time dispatcher hides serialization races.
        withContext(Dispatchers.Default) {
            coroutineScope {
                val (clientTransport, peer) = PipedTransport.create()
                val serverReady = CompletableDeferred<FakeSshServer>()
                val serverJob = launch {
                    coroutineScope {
                        val server = FakeSshServer(peer, this)
                        serverReady.complete(server)
                        server.start()
                    }
                }
                val server = serverReady.await()
                val connection = SshConnection(
                    transport = FragmentedTransport(clientTransport, seed),
                    hostKeyVerifier = object : HostKeyVerifier {
                        override suspend fun verify(key: PublicKey): Boolean = true
                    },
                    coroutineDispatcher = Dispatchers.Default,
                    rekeyIntervalMs = Long.MAX_VALUE,
                    rekeyBytesLimit = Long.MAX_VALUE,
                ).apply {
                    autoDisconnectOnLastChannelClose = false
                    sessionWindowSize = 8 * 1024
                }
                try {
                    assertIs<ConnectResult.Success>(connection.connect())
                    val authentication = async { connection.authenticatePassword("user", "pass") }
                    server.awaitUserauthRequest()
                    server.sendUserauthSuccess()
                    assertEquals(AuthResult.Success, authentication.await())

                    val openings = List(CHANNEL_COUNT) { async { connection.openSessionChannel() } }
                    val requests = List(CHANNEL_COUNT) { server.awaitChannelOpen() }
                    // Different local/remote IDs and reverse confirmations expose routing assumptions.
                    val remoteIds = requests.mapIndexed { index, request ->
                        request.senderChannel().toInt() to (1000 + index * 17)
                    }.toMap()
                    for (request in requests.reversed()) {
                        server.sendChannelOpenConfirmation(
                            request.senderChannel().toInt(),
                            remoteIds.getValue(request.senderChannel().toInt()),
                            initialWindowSize = REMOTE_WINDOW,
                            maximumPacketSize = REMOTE_PACKET,
                        )
                    }
                    val traffic = openings.awaitAll().mapIndexed { index, session ->
                        val channel = assertNotNull(session)
                        Traffic(channel, remoteIds.getValue(channel.localChannelNumber), Random(seed + index * 31))
                    }
                    exerciseTraffic(server, traffic, seed)

                    // All channels have closed; opening another verifies the connection and registry survive.
                    val reopening = async { connection.openSessionChannel() }
                    val request = server.awaitChannelOpen()
                    server.sendChannelOpenConfirmation(request.senderChannel().toInt(), 9000)
                    val probe = assertNotNull(reopening.await())
                    val marker = "still connected after stress seed=$seed".encodeToByteArray()
                    probe.write(marker)
                    val data = server.awaitChannelData()
                    assertEquals(9000L, data.recipientChannel())
                    assertContentEquals(marker, data.data().data())
                    server.sendChannelData(probe.localChannelNumber, marker)
                    assertContentEquals(marker, probe.read())
                    probe.close()
                } finally {
                    connection.close()
                    serverJob.cancelAndJoin()
                    peer.close()
                }
            }
        }
    }

    private suspend fun exerciseTraffic(server: FakeSshServer, traffic: List<Traffic>, seed: Int) = coroutineScope {
        val byRemote = traffic.associateBy { it.remoteId }
        assertEquals(CHANNEL_COUNT, traffic.map { it.session.localChannelNumber }.toSet().size)
        val adjustments = launch {
            while (true) {
                val adjust = server.awaitChannelWindowAdjust()
                val target = assertNotNull(byRemote[adjust.recipientChannel().toInt()], "window adjust recipient")
                assertTrue(adjust.bytesToAdd() > 0)
                target.credit.send(adjust.bytesToAdd().toInt())
            }
        }
        val controls = async {
            val resizeCounts = mutableMapOf<Int, Int>()
            val shells = mutableSetOf<Int>()
            repeat(CHANNEL_COUNT * (RESIZES + 1)) {
                val request = server.awaitChannelRequest()
                val target = assertNotNull(byRemote[request.recipientChannel().toInt()], "request recipient")
                when (request.requestType().value()) {
                    "shell" -> {
                        assertTrue(shells.add(target.remoteId), "duplicate shell request")
                        assertEquals(1, request.wantReply())
                        server.sendChannelSuccess(target.session.localChannelNumber)
                    }

                    "window-change" -> {
                        assertEquals(0, request.wantReply())
                        val index = resizeCounts.getOrDefault(target.remoteId, 0)
                        assertTrue(index < RESIZES, "excess resize requests")
                        val dimensions = target.dimensions[index]
                        val fields = assertIs<ChannelRequestWindowChange>(request.requestSpecificFields())
                        assertEquals(dimensions.first.toLong(), fields.terminalWidth())
                        assertEquals(dimensions.second.toLong(), fields.terminalHeight())
                        assertEquals(0L, fields.terminalWidthPixels())
                        assertEquals(0L, fields.terminalHeightPixels())
                        resizeCounts[target.remoteId] = index + 1
                    }

                    else -> error("Unexpected channel request ${request.requestType().value()}")
                }
            }
            assertEquals(CHANNEL_COUNT, shells.size)
            traffic.forEach { assertEquals(RESIZES, resizeCounts[it.remoteId]) }
        }
        val outbound = async {
            val offsets = mutableMapOf<Int, Int>()
            val windows = traffic.associate { it.remoteId to REMOTE_WINDOW }.toMutableMap()
            val consumed = mutableMapOf<Int, Int>()
            val random = Random(seed xor 0x1234)
            var remaining = traffic.sumOf { it.outbound.size }
            while (remaining > 0) {
                val packet = server.awaitChannelData()
                val target = assertNotNull(byRemote[packet.recipientChannel().toInt()], "data recipient")
                val bytes = packet.data().data()
                assertTrue(bytes.isNotEmpty() && bytes.size <= REMOTE_PACKET, "negotiated packet size")
                val window = windows.getValue(target.remoteId) - bytes.size
                assertTrue(window >= 0, "sender exceeded window on ${target.remoteId}")
                windows[target.remoteId] = window
                val offset = offsets.getOrDefault(target.remoteId, 0)
                assertTrue(offset + bytes.size <= target.outbound.size, "excess data on ${target.remoteId}")
                assertContentEquals(target.outbound.copyOfRange(offset, offset + bytes.size), bytes, "seed=$seed outbound channel=${target.remoteId} offset=$offset")
                offsets[target.remoteId] = offset + bytes.size
                remaining -= bytes.size
                perturb(random)
                // Refill in batches: refilling each tiny packet can collapse the window
                // into a feedback loop of one-byte packets and obscure useful coverage.
                val pending = consumed.getOrDefault(target.remoteId, 0) + bytes.size
                consumed[target.remoteId] = pending
                if (pending >= REMOTE_WINDOW / 2) {
                    val first = random.nextInt(1, pending)
                    server.sendChannelWindowAdjust(target.session.localChannelNumber, first.toLong())
                    server.sendChannelWindowAdjust(target.session.localChannelNumber, (pending - first).toLong())
                    windows[target.remoteId] = window + pending
                    consumed[target.remoteId] = 0
                }
            }
        }
        val writers = traffic.map { target ->
            async {
                val random = Random(seed + target.remoteId)
                assertTrue(target.session.requestShell())
                var offset = 0
                while (offset < target.outbound.size) {
                    val size = minOf(target.outbound.size - offset, writeSize(random))
                    target.session.write(target.outbound.copyOfRange(offset, offset + size))
                    offset += size
                    perturb(random)
                }
                target.session.sendEof()
            }
        }
        val resizes = traffic.map { target ->
            async {
                val random = Random(seed xor target.remoteId)
                for ((width, height) in target.dimensions) {
                    assertTrue(target.session.resizeTerminal(width, height, 0, 0))
                    perturb(random)
                }
            }
        }
        val readers = traffic.flatMap { target ->
            listOf(
                async { verifyStream(target.stdout, seed, target.session.localChannelNumber, "stdout") { target.session.read() } },
                async { verifyStream(target.stderr, seed, target.session.localChannelNumber, "stderr") { target.session.stderr.receiveCatching().getOrNull() } },
            )
        }
        val senders = traffic.map { target ->
            async {
                val random = Random(seed - target.remoteId)
                val offsets = IntArray(2)
                val streams = listOf(target.stdout, target.stderr)
                var credit = 8 * 1024
                while (offsets.indices.any { offsets[it] < streams[it].size }) {
                    if (credit == 0) credit += target.credit.receive()
                    while (true) credit += target.credit.tryReceive().getOrNull() ?: break
                    val stream = offsets.indices.filter { offsets[it] < streams[it].size }.random(random)
                    val size = minOf(credit, streams[stream].size - offsets[stream], random.nextInt(1, 4097))
                    val data = streams[stream].copyOfRange(offsets[stream], offsets[stream] + size)
                    if (stream == 0) server.sendChannelData(target.session.localChannelNumber, data) else server.sendChannelExtendedData(target.session.localChannelNumber, 1, data)
                    offsets[stream] += size
                    credit -= size
                    perturb(random)
                }
                server.sendChannelEof(target.session.localChannelNumber)
            }
        }
        writers.awaitAll()
        resizes.awaitAll()
        controls.await()
        outbound.await()
        senders.awaitAll()
        // Close while readers may still have buffered output; the final bytes must remain readable.
        for (target in traffic.shuffled(Random(seed))) server.sendChannelClose(target.session.localChannelNumber)
        readers.awaitAll()
        repeat(CHANNEL_COUNT) { assertEquals(SshEnums.MessageType.SSH_MSG_CHANNEL_CLOSE, server.awaitClosingPacket()) }
        traffic.forEach { assertFalse(it.session.isOpen) }
        assertNull(withTimeoutOrNull(50) { server.awaitChannelData() }, "unexpected trailing data")
        adjustments.cancelAndJoin()
    }

    private suspend fun verifyStream(expected: ByteArray, seed: Int, channel: Int, stream: String, read: suspend () -> ByteArray?) {
        val random = Random(seed + channel + stream.hashCode())
        var offset = 0
        while (true) {
            perturb(random)
            val bytes = read() ?: break
            assertTrue(bytes.isNotEmpty() && offset + bytes.size <= expected.size, "seed=$seed $stream channel=$channel excess bytes")
            assertContentEquals(expected.copyOfRange(offset, offset + bytes.size), bytes, "seed=$seed $stream channel=$channel offset=$offset")
            offset += bytes.size
        }
        assertEquals(expected.size, offset, "seed=$seed $stream channel=$channel truncated")
    }

    private class Traffic(val session: SessionChannel, val remoteId: Int, random: Random) {
        val outbound = random.nextBytes(128 * 1024 + random.nextInt(1024))
        val stdout = random.nextBytes(96 * 1024 + random.nextInt(1024))
        val stderr = random.nextBytes(96 * 1024 + random.nextInt(1024))
        val dimensions = List(RESIZES) { random.nextInt(40, 200) to random.nextInt(10, 80) }
        val credit = Channel<Int>(Channel.UNLIMITED)
    }

    /** Exact reads assembled from random fragments; writes split across yields/delays. */
    private class FragmentedTransport(private val delegate: Transport, seed: Int) : Transport by delegate {
        private val readRandom = Random(seed)
        private val writeRandom = Random(seed xor 0x5678)

        override suspend fun read(count: Int): ByteArray {
            val result = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val size = minOf(count - offset, readRandom.nextInt(1, 2049))
                delegate.read(size).copyInto(result, offset)
                offset += size
                perturb(readRandom)
            }
            return result
        }

        override suspend fun write(data: ByteArray) {
            var offset = 0
            while (offset < data.size) {
                val size = minOf(data.size - offset, writeRandom.nextInt(1, 2049))
                delegate.write(data.copyOfRange(offset, offset + size))
                offset += size
                perturb(writeRandom)
            }
        }
    }

    companion object {
        private const val CHANNEL_COUNT = 8
        private const val REMOTE_WINDOW = 4096
        private const val REMOTE_PACKET = 1024
        private const val RESIZES = 12

        private fun writeSize(random: Random): Int = when (random.nextInt(4)) {
            0 -> 1
            1 -> random.nextInt(2, 128)
            2 -> random.nextInt(128, 4097)
            else -> random.nextInt(32768, 65537)
        }

        private suspend fun perturb(random: Random) {
            when (random.nextInt(16)) {
                0 -> delay(random.nextLong(1, 4))
                in 1..5 -> yield()
            }
        }
    }
}
