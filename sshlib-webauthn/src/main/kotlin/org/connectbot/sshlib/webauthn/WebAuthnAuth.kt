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

package org.connectbot.sshlib.webauthn

import io.kaitai.struct.ByteBufferKaitaiStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.connectbot.sshlib.SshException
import org.connectbot.sshlib.protocol.WebauthnAuthenticatorData
import org.connectbot.sshlib.sk.SkSignatureBlob
import java.security.MessageDigest
import java.util.Base64

/** Platform-independent request and response adapter for ES256 SSH passkeys. */
public object WebAuthnAuth {
    /** Creates Credential Manager request options, requiring user verification. */
    public fun createRequestJson(rpId: String, credentialId: ByteArray, dataToSign: ByteArray): String {
        if (rpId.isBlank() || credentialId.isEmpty() || dataToSign.isEmpty()) throw SshException("RP ID, credential ID and challenge must not be empty")
        return buildJsonObject {
            put("rpId", rpId)
            put("challenge", encode(dataToSign))
            put("userVerification", "required")
            putJsonArray("allowCredentials") {
                addJsonObject {
                    put("type", "public-key")
                    put("id", encode(credentialId))
                }
            }
        }.toString()
    }

    /**
     * Converts Credential Manager authentication response JSON to an SSH signature.
     * Preserves signed clientDataJSON exactly; incompatible OpenSSH formatting is rejected.
     * OpenSSH performs cryptographic signature verification.
     * @throws SshException if the response is malformed or incompatible.
     */
    public fun signatureFromResponseJson(responseJson: String, rpId: String, dataToSign: ByteArray): ByteArray = try {
        val root = Json.parseToJsonElement(responseJson).jsonObject
        if (root.string("type") != "public-key") {
            throw SshException("Expected public-key credential")
        }

        val response = root.getValue("response").jsonObject
        val clientBytes = decode(response.string("clientDataJSON"))
        val client = Json.parseToJsonElement(clientBytes.toString(Charsets.UTF_8)).jsonObject

        if (client.string("type") != "webauthn.get") {
            throw SshException("Expected webauthn.get assertion")
        }

        if (!decode(client.string("challenge")).contentEquals(dataToSign)) {
            throw SshException("WebAuthn challenge mismatch")
        }

        val origin = client.string("origin")
        val prefix = "{\"type\":\"webauthn.get\",\"challenge\":\"${encode(dataToSign)}\",\"origin\":\"$origin\"".toByteArray(Charsets.UTF_8)

        if (clientBytes.size < prefix.size || !clientBytes.copyOfRange(0, prefix.size).contentEquals(prefix)) {
            throw SshException("clientDataJSON encoding is incompatible with OpenSSH; signed JSON cannot be rewritten")
        }

        val auth = decode(response.string("authenticatorData"))
        val authenticatorData = WebauthnAuthenticatorData(ByteBufferKaitaiStream(auth)).apply { _read() }
        val rpHash = MessageDigest.getInstance("SHA-256").digest(rpId.toByteArray(Charsets.UTF_8))

        if (!authenticatorData.rpIdHash().contentEquals(rpHash)) {
            throw SshException("WebAuthn RP ID mismatch")
        }

        SkSignatureBlob.packWebAuthn(decode(response.string("signature")), auth, clientBytes, origin)
    } catch (e: SshException) {
        throw e
    } catch (e: Exception) {
        throw SshException("Malformed WebAuthn assertion response", e)
    }

    private fun JsonObject.string(name: String): String {
        val value = getValue(name).jsonPrimitive

        if (!value.isString) {
            throw SshException("Expected string for $name")
        }

        return value.content
    }

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(value: String): ByteArray {
        if (!value.matches(Regex("[A-Za-z0-9_-]*={0,2}"))) {
            throw SshException("Invalid base64url assertion field")
        }

        return Base64.getUrlDecoder().decode(value)
    }
}
