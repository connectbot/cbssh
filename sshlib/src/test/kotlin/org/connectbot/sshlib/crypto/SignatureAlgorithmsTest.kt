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

package org.connectbot.sshlib.crypto

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignatureAlgorithmsTest {
    @Test
    fun `RSA key compatibility does not authorize negotiated hash substitution`() {
        assertTrue(signatureAlgorithmMatchesKeyType("rsa-sha2-512", "ssh-rsa"))
        assertFalse(signatureMatchesNegotiatedAlgorithm("ssh-rsa", "rsa-sha2-512"))
        assertFalse(signatureMatchesNegotiatedAlgorithm("rsa-sha2-256", "rsa-sha2-512"))
        assertTrue(signatureMatchesNegotiatedAlgorithm("rsa-sha2-512", "rsa-sha2-512"))
    }

    @Test
    fun `WebAuthn aliases only authorize SK ECDSA signatures`() {
        val sk = "sk-ecdsa-sha2-nistp256@openssh.com"
        for (alias in listOf("webauthn-sk-ecdsa-sha2-nistp256@openssh.com", "webauthn-sk-ecdsa-sha2-nistp256-cert-v01@openssh.com")) {
            assertTrue(signatureMatchesNegotiatedAlgorithm(alias, sk))
            assertTrue(signatureAlgorithmMatchesKeyType(alias, sk))
            assertFalse(signatureMatchesNegotiatedAlgorithm(alias, "ecdsa-sha2-nistp256"))
            assertFalse(signatureAlgorithmMatchesKeyType(alias, "sk-ssh-ed25519@openssh.com"))
            assertFalse(signatureMatchesNegotiatedAlgorithm(alias, "sk-ecdsa-sha2-nistp256-cert-v01@openssh.com"))
        }
    }
}
