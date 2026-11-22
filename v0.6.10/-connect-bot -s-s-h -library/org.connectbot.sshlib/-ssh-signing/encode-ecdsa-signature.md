//[ConnectBot SSH Library](../../../index.md)/[org.connectbot.sshlib](../index.md)/[SshSigning](index.md)/[encodeEcdsaSignature](encode-ecdsa-signature.md)

# encodeEcdsaSignature

[jvm]\
fun [encodeEcdsaSignature](encode-ecdsa-signature.md)(algorithmName: [String](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-string/index.html), derSignature: [ByteArray](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-byte-array/index.html)): [ByteArray](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-byte-array/index.html)

Wrap an externally generated DER ECDSA signature in the SSH signature format.

Use for hardware signers from [AuthHandler.onSignatureRequest](../-auth-handler/on-signature-request.md). The input must be a DER SEQUENCE of two positive INTEGERs, not raw concatenated r and s. Supports ecdsa-sha2-nistp256, ecdsa-sha2-nistp384, and ecdsa-sha2-nistp521. This encodes the signature; it does not hash data or verify the signature.

#### Return

Complete SSH signature blob, including the algorithm name

#### Parameters

jvm

| | |
|---|---|
| algorithmName | SSH ECDSA signature algorithm matching the signing key |
| derSignature | DER signature returned by the external signer |

#### Throws

| | |
|---|---|
| [SshException](../-ssh-exception/index.md) | if the algorithm or signature encoding is invalid |