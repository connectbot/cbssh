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
import org.connectbot.sshlib.sk.SkSignatureBlob
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebAuthnAuthTest {
    private val challenge = byteArrayOf(1, 2, 3)
    private val rp = "example.com"
    private fun enc(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private val der = byteArrayOf(0x30, 6, 2, 1, 1, 2, 1, 2)
    private fun auth() = MessageDigest.getInstance("SHA-256").digest(rp.toByteArray()) + byteArrayOf(0x1d, 0, 0, 0, 0)
    private fun client(origin: String) = "{\"type\":\"webauthn.get\",\"challenge\":\"${enc(challenge)}\",\"origin\":\"$origin\"}".toByteArray()
    private fun response(client: ByteArray, auth: ByteArray = auth()) = buildJsonObject {
        put("type", "public-key")
        putJsonObject("response") {
            put("signature", enc(der))
            put("authenticatorData", enc(auth))
            put("clientDataJSON", enc(client))
        }
    }.toString()

    @Test fun request() {
        val request = Json.parseToJsonElement(WebAuthnAuth.createRequestJson(rp, byteArrayOf(4), challenge)).jsonObject
        assertEquals("AQID", request["challenge"]!!.jsonPrimitive.content)
        assertEquals("required", request["userVerification"]!!.jsonPrimitive.content)
        assertEquals("BA", request["allowCredentials"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test fun responsePreservesBytes() {
        for (origin in listOf("https://example.com", "android:apk-key-hash:abc")) {
            assertContentEquals(SkSignatureBlob.packWebAuthn(der, auth(), client(origin), origin), WebAuthnAuth.signatureFromResponseJson(response(client(origin)), rp, challenge))
        }
    }

    @Test fun rejectsInvalidResponses() {
        val valid = response(client("https://example.com"))
        for (bad in listOf("{}", "not json", valid.replace("public-key", "password"), valid.replace(enc(der), "!!!"), response(client("https://example.com").reversedArray()))) {
            assertFailsWith<SshException> { WebAuthnAuth.signatureFromResponseJson(bad, rp, challenge) }
        }
        assertFailsWith<SshException> { WebAuthnAuth.signatureFromResponseJson(valid, "wrong.example", challenge) }
        assertFailsWith<SshException> { WebAuthnAuth.signatureFromResponseJson(valid, rp, byteArrayOf(9)) }
        assertFailsWith<SshException> { WebAuthnAuth.signatureFromResponseJson(response((" " + client("https://example.com").toString(Charsets.UTF_8)).toByteArray()), rp, challenge) }
    }

    @Test fun rejectsTruncatedAuthenticatorData() {
        val origin = "https://example.com"
        val valid = auth()
        for (length in valid.indices) {
            val truncated = valid.copyOf(length)
            assertFailsWith<SshException>("Adapter accepted $length bytes") {
                WebAuthnAuth.signatureFromResponseJson(response(client(origin), truncated), rp, challenge)
            }
            assertFailsWith<SshException>("Packer accepted $length bytes") {
                SkSignatureBlob.packWebAuthn(der, truncated, client(origin), origin)
            }
        }
    }

    @Test fun rejectsMalformedAuthenticatorAndSignatures() {
        for (data in listOf(ByteArray(36), auth().apply { this[32] = 0x40 }, auth().apply { this[32] = 0x80.toByte() }, auth() + byteArrayOf(1))) {
            assertFailsWith<SshException> { SkSignatureBlob.packWebAuthn(der, data, client("x"), "x") }
        }
        assertFailsWith<SshException> { SkSignatureBlob.packWebAuthn(byteArrayOf(), auth(), client("x"), "x") }
    }
}
