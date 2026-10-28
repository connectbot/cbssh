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

package org.connectbot.sshlib.protocol

import io.kaitai.struct.ByteBufferKaitaiStream
import io.kaitai.struct.KaitaiStream.ValidationExprError
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.assertFailsWith

class WebauthnValidationTest {
    private fun sshString(bytes: ByteArray): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES + bytes.size).putInt(bytes.size).put(bytes).array()

    private fun authenticator(flags: Int, extensions: ByteArray): WebauthnAuthenticatorData {
        val bytes = ByteArray(32) + byteArrayOf(flags.toByte()) + ByteArray(Int.SIZE_BYTES) + extensions
        return WebauthnAuthenticatorData(ByteBufferKaitaiStream(bytes)).apply { _read() }
    }

    private fun signature(flags: Int, extensions: ByteArray): WebauthnSkSignatureBlob {
        val material = sshString(sshString(byteArrayOf(1)) + sshString(byteArrayOf(2)))
        val bytes = material + byteArrayOf(flags.toByte()) + ByteArray(Int.SIZE_BYTES) +
            sshString("https://example.com".toByteArray()) + sshString("{}".toByteArray()) + sshString(extensions)
        return WebauthnSkSignatureBlob(ByteBufferKaitaiStream(bytes)).apply { _read() }
    }

    @Test fun rejectsInvalidAssertionFlagsAndExtensionsDuringParsing() {
        for (flags in listOf(0x01, 0x41, 0x81, 0xc1)) {
            for (extensions in listOf(byteArrayOf(), byteArrayOf(0xa0.toByte()))) {
                val valid = (flags == 0x01 && extensions.isEmpty()) || (flags == 0x81 && extensions.isNotEmpty())
                if (valid) {
                    authenticator(flags, extensions)._check()
                    signature(flags, extensions)._check()
                } else {
                    assertFailsWith<ValidationExprError> { authenticator(flags, extensions) }
                    assertFailsWith<ValidationExprError> { signature(flags, extensions) }
                }
            }
        }
    }

    @Test fun rejectsInvalidAssertionFlagsAndExtensionsDuringSerialization() {
        for (flags in listOf(0x41, 0x81)) {
            val auth = authenticator(0x01, byteArrayOf()).apply { setFlags(flags) }
            val sig = signature(0x01, byteArrayOf()).apply { setFlags(flags) }
            assertFailsWith<ValidationExprError> { auth.toByteArray() }
            assertFailsWith<ValidationExprError> { sig.toByteArray() }
        }
        val extensions = byteArrayOf(0xa0.toByte())
        val auth = authenticator(0x01, byteArrayOf()).apply { setExtensions(extensions) }
        val sig = signature(0x01, byteArrayOf()).apply {
            setExtensions(ByteString(ByteBufferKaitaiStream(sshString(extensions))).apply { _read() })
        }
        assertFailsWith<ValidationExprError> { auth.toByteArray() }
        assertFailsWith<ValidationExprError> { sig.toByteArray() }
    }
}
