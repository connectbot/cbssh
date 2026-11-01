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

import org.connectbot.sshlib.SshException
import org.connectbot.sshlib.protocol.EcdsaSignatureBlob
import org.connectbot.sshlib.protocol.SkEcdsaP256PublicKeyBlob
import org.connectbot.sshlib.protocol.SkEcdsaP256SignatureBlob
import org.connectbot.sshlib.protocol.SkEd25519PublicKeyBlob
import org.connectbot.sshlib.protocol.SkEd25519SignatureBlob
import org.connectbot.sshlib.protocol.SshPublicKey
import org.connectbot.sshlib.protocol.SshSignature
import org.connectbot.sshlib.protocol.WebauthnSkSignatureBlob
import org.connectbot.sshlib.sk.SkSignatureBlob.ED25519_RAW_SIGNATURE_SIZE
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.PrivateKey
import java.util.Base64

internal object SkSignatureAlgorithm : SshSignatureAlgorithm {
    private const val ED25519_RAW_KEY_SIZE: Int = 32

    override fun sign(algorithmName: String, privateKey: PrivateKey, data: ByteArray): ByteArray = throw SshException("Security keys require an external signer")

    override fun verify(pubKey: SshPublicKey, sig: SshSignature, data: ByteArray): Boolean {
        return try {
            fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            val application: ByteArray
            val flags: Int
            val counter: Long
            val rawKey: ByteArray
            val rawSignature: ByteArray
            var extensions = byteArrayOf()
            var messageHash = hash(data)
            val ed = pubKey.keyBlob() is SkEd25519PublicKeyBlob
            if (ed) {
                val key = pubKey.keyBlob() as SkEd25519PublicKeyBlob
                val signature = sig.signatureBlob() as SkEd25519SignatureBlob
                application = key.application().data()
                rawKey = key.publicKey().data()
                rawSignature = signature.signature().data()
                if (rawKey.size != ED25519_RAW_KEY_SIZE || rawSignature.size != ED25519_RAW_SIGNATURE_SIZE) return false
                flags = signature.flags()
                counter = signature.counter()
            } else {
                val key = pubKey.keyBlob() as SkEcdsaP256PublicKeyBlob
                application = key.application().data()
                rawKey = key.publicKey().data()
                val material: EcdsaSignatureBlob
                when (val signature = sig.signatureBlob()) {
                    is SkEcdsaP256SignatureBlob -> {
                        flags = signature.flags()
                        counter = signature.counter()
                        material = signature.signature()
                    }

                    is WebauthnSkSignatureBlob -> {
                        flags = signature.flags()
                        counter = signature.counter()
                        material = signature.signature()
                        extensions = signature.extensions().data()
                        val origin = signature.origin().data()
                        if (origin.any { it == '\u0000'.code.toByte() || it == '"'.code.toByte() }) return false
                        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(data)
                        val prefix = "{\"type\":\"webauthn.get\",\"challenge\":\"$challenge\",\"origin\":\"".toByteArray() + origin + byteArrayOf('"'.code.toByte())
                        val client = signature.clientData().data()
                        if (client.size < prefix.size || !client.copyOfRange(0, prefix.size).contentEquals(prefix)) return false
                        messageHash = hash(client)
                    }

                    else -> return false
                }
                if (!material.blob()._io().isEof) return false
                val r = material.blob().r().body()
                val s = material.blob().s().body()
                if (r.isEmpty() || s.isEmpty() || r[0] < 0 || s[0] < 0) return false
                rawSignature = encodeMpint(r) + encodeMpint(s)
            }
            if (application.any { it == '\u0000'.code.toByte() } || !pubKey._io().isEof) return false
            val counterBytes = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(counter.toInt()).array()
            val signed = hash(application) + byteArrayOf(flags.toByte()) + counterBytes + extensions + messageHash
            val name = if (ed) "ssh-ed25519" else "ecdsa-sha2-nistp256"
            val keyBytes = encodeSshString(name.toByteArray()) +
                (if (ed) byteArrayOf() else encodeSshString("nistp256".toByteArray())) + encodeSshString(rawKey)
            val signatureBytes = encodeSshString(name.toByteArray()) + encodeSshString(rawSignature)
            SignatureVerifier.verify(keyBytes, signatureBytes, signed, name)
        } catch (_: Exception) {
            false
        }
    }
}
