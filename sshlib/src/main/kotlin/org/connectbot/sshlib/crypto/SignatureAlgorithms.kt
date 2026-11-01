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

private const val SK_ECDSA = "sk-ecdsa-sha2-nistp256@openssh.com"

/** Resolve signature-format aliases to the algorithm that verifies them. */
internal fun signatureVerificationAlgorithm(signatureAlgorithm: String): String = when (signatureAlgorithm) {
    "webauthn-sk-ecdsa-sha2-nistp256@openssh.com",
    "webauthn-sk-ecdsa-sha2-nistp256-cert-v01@openssh.com",
    -> SK_ECDSA

    else -> signatureAlgorithm
}

/** Match a wire signature to the negotiated algorithm without allowing RSA hash substitution. */
internal fun signatureMatchesNegotiatedAlgorithm(signatureAlgorithm: String, negotiatedAlgorithm: String): Boolean = !negotiatedAlgorithm.endsWith(CERTIFICATE_SUFFIX) &&
    signatureVerificationAlgorithm(signatureAlgorithm) == negotiatedAlgorithm

/** Check key compatibility independently of the negotiated signature/hash choice. */
internal fun signatureAlgorithmMatchesKeyType(signatureAlgorithm: String, keyType: String): Boolean = when (val algorithm = signatureVerificationAlgorithm(signatureAlgorithm)) {
    "ssh-rsa", "rsa-sha2-256", "rsa-sha2-512" -> keyType == "ssh-rsa"
    else -> algorithm == keyType
}
