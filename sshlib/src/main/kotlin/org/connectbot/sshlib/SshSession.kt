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

package org.connectbot.sshlib

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * How the remote process on a session channel terminated
 * (RFC 4254 section 6.10).
 */
sealed interface SessionExit {
    /** The remote process exited normally with [code] (`exit-status`). */
    data class Status(val code: Long) : SessionExit

    /**
     * The remote process was terminated by a signal (`exit-signal`).
     *
     * @param signalName Signal name without the "SIG" prefix (e.g. "KILL")
     * @param coreDumped Whether a core dump was produced
     * @param errorMessage Additional textual explanation from the server; may be empty
     */
    data class Signal(
        val signalName: String,
        val coreDumped: Boolean,
        val errorMessage: String,
    ) : SessionExit
}

/**
 * Represents an SSH session channel (RFC 4254 section 6).
 *
 * Session channels are used for interactive shells, command execution,
 * and subsystem invocation (like SFTP).
 */
interface SshSession : AutoCloseable {
    val localChannelNumber: Int
    val remoteChannelNumber: Int
    val isOpen: Boolean

    /**
     * Standard output. Data received before remote channel close remains readable through
     * automatic connection teardown. Explicit session close or client disconnect discards it.
     */
    val stdout: ReceiveChannel<ByteArray>

    /** Standard error, with the same drain and explicit-close contract as [stdout]. */
    val stderr: ReceiveChannel<ByteArray>

    suspend fun requestPty(
        terminalType: String = "xterm",
        widthChars: Int = 80,
        heightRows: Int = 24,
        widthPixels: Int = 0,
        heightPixels: Int = 0,
        terminalModes: ByteArray = byteArrayOf(0),
    ): Boolean

    suspend fun resizeTerminal(
        widthChars: Int,
        heightRows: Int,
        widthPixels: Int,
        heightPixels: Int,
    ): Boolean

    /**
     * Request an environment variable for this session (RFC 4254 section 6.4).
     * Call before requesting a shell, command, or subsystem. Servers may restrict names
     * (OpenSSH uses AcceptEnv) and reject requests after process startup.
     * Names and values are encoded as UTF-8. Returns false for NUL-containing input,
     * a closed channel, or server rejection; rejection does not close the session.
     */
    suspend fun requestEnv(name: String, value: String): Boolean

    suspend fun requestShell(): Boolean

    /**
     * Request execution of a command on this session channel (RFC 4254 section 6.5).
     *
     * Only one of [requestShell], [requestExec], or subsystem requests
     * may succeed per session channel.
     *
     * @param command The command to execute on the remote server
     * @return true if the server accepted the request
     */
    suspend fun requestExec(command: String): Boolean

    /**
     * Request a subsystem on this session channel (RFC 4254 section 6.5).
     *
     * Subsystems are named services that run over an SSH channel, such as
     * SFTP ("sftp"). Only one of [requestShell], exec, or [requestSubsystem]
     * may succeed per session channel.
     *
     * @param name The subsystem name (e.g. "sftp")
     * @return true if the server accepted the request
     */
    suspend fun requestSubsystem(name: String): Boolean

    /**
     * Write data, suspending for channel window credit and transport backpressure.
     *
     * Successful completion means the transport writes completed, not that the remote
     * application processed the data. Await an application response when that confirmation
     * is required. Disconnecting the client can abort a suspended write.
     */
    suspend fun write(data: ByteArray)

    suspend fun read(): ByteArray?

    /**
     * Read non-stderr extended data. RFC 4254 data type 1 is exposed exclusively
     * through [stderr] so the same remote bytes are not buffered twice.
     */
    suspend fun readExtended(): Pair<Int, ByteArray>?

    suspend fun sendEof()

    /**
     * Completes with how the remote process terminated once the server
     * reports it (RFC 4254 section 6.10), or with null when the channel
     * closes without an `exit-status`/`exit-signal` notification. Servers
     * are not required to send one, so null means unknown, not failure.
     *
     * Typically awaited after [stdout] reaches end-of-stream on an
     * exec channel to collect the command's exit code.
     */
    val exitInfo: Deferred<SessionExit?>

    /** Discard unread output and release delivery resources, including after remote close. */
    override fun close()
}
