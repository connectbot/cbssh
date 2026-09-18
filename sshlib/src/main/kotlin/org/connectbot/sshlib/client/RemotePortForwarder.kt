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

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.connectbot.sshlib.PortForwarder
import org.connectbot.sshlib.protocol.SshChannelState
import org.slf4j.LoggerFactory

internal class RemotePortForwarder(
    private val scope: CoroutineScope,
    private val connection: SshConnection,
    private val localHost: String,
    private val localPort: Int,
    private val remoteBindAddress: String,
    private val remoteBindPort: Int,
    override val boundHost: String,
    override val boundPort: Int,
) : PortForwarder {
    companion object {
        private val logger = LoggerFactory.getLogger(RemotePortForwarder::class.java)

        suspend fun create(
            scope: CoroutineScope,
            connection: SshConnection,
            remoteBindAddress: String,
            remoteBindPort: Int,
            localHost: String,
            localPort: Int,
        ): RemotePortForwarder? {
            val actualPort = connection.sendTcpipForwardRequest(remoteBindAddress, remoteBindPort)
                ?: return null

            val forwarder = RemotePortForwarder(
                scope,
                connection,
                localHost,
                localPort,
                remoteBindAddress,
                actualPort,
                remoteBindAddress,
                actualPort,
            )

            val key = "$remoteBindAddress:$actualPort"
            connection.registerRemoteForwarder(key) { connectedAddr, connectedPort, originAddr, originPort, senderChannel, initialWindow, maxPacketSize ->
                forwarder.handleIncomingChannel(connectedAddr, connectedPort, originAddr, originPort, senderChannel, initialWindow, maxPacketSize)
            }

            return forwarder
        }
    }

    private val dataForwarders = mutableListOf<DataForwarder>()
    private var _isActive = true

    override val isActive: Boolean get() = _isActive

    private suspend fun handleIncomingChannel(
        connectedAddr: String,
        connectedPort: Int,
        originAddr: String,
        originPort: Int,
        senderChannel: Int,
        initialWindow: Long,
        maxPacketSize: Int,
    ) {
        logger.debug("Incoming forwarded-tcpip from $originAddr:$originPort, connecting to $localHost:$localPort")

        var registeredChannel: ForwardingChannel? = null
        var confirmationSent = false
        val selectorManager = SelectorManager(Dispatchers.IO)
        var socket: Socket? = null
        try {
            val localChannelNumber = connection.allocateChannelNumber()

            val connectedSocket = aSocket(selectorManager).tcp().connect(localHost, localPort)
            socket = connectedSocket

            val fwdChannel = ForwardingChannel(
                connection,
                scope,
                localChannelNumber,
                senderChannel,
                maxPacketSize,
                remoteWindowSizeInitial = initialWindow,
                initialWindowSize = 256 * 1024,
                lifecycle = connection.newChannelStateMachine(SshChannelState.OPEN),
            )
            connection.registerForwardingChannel(fwdChannel)
            registeredChannel = fwdChannel

            connection.protocolExecutor.run {
                connection.sendChannelOpenConfirmationPublic(
                    recipientChannel = senderChannel,
                    senderChannel = localChannelNumber,
                    initialWindowSize = 256 * 1024,
                    maximumPacketSize = 32 * 1024,
                )
                confirmationSent = true
            }

            val readChannel = connectedSocket.openReadChannel()
            val writeChannel = connectedSocket.openWriteChannel(autoFlush = false)

            val forwarder = DataForwarder(scope, fwdChannel, readChannel, writeChannel) {
                connectedSocket.close()
                selectorManager.close()
            }
            synchronized(dataForwarders) {
                check(_isActive) { "Remote forwarder stopped" }
                dataForwarders.add(forwarder)
                forwarder.start()
            }
        } catch (e: Exception) {
            socket?.close()
            selectorManager.close()
            logger.warn("Failed to handle incoming forwarded-tcpip: ${e.message}")
            withContext(NonCancellable) {
                if (!connection.protocolExecutor.isClosed) {
                    connection.protocolExecutor.run(awaitWrites = false) {
                        registeredChannel?.let { channel ->
                            if (confirmationSent) {
                                channel.close()
                            } else {
                                connection.unregisterForwardingChannel(channel)
                                channel.onDisconnected()
                            }
                        }
                        if (!confirmationSent) {
                            connection.sendChannelOpenFailurePublic(
                                recipientChannel = senderChannel,
                                reasonCode = 2, // SSH_OPEN_CONNECT_FAILED
                                description = "Failed to connect to local target: ${e.message}",
                                languageTag = "",
                            )
                        }
                    }
                }
            }
            if (e is CancellationException) throw e
        }
    }

    override suspend fun stop() {
        val forwarders = synchronized(dataForwarders) {
            if (!_isActive) return
            _isActive = false
            dataForwarders.toList()
        }

        val key = "$remoteBindAddress:$remoteBindPort"
        connection.unregisterRemoteForwarder(key)
        connection.sendCancelTcpipForward(remoteBindAddress, remoteBindPort)

        forwarders.forEach { it.stop() }
    }
}
