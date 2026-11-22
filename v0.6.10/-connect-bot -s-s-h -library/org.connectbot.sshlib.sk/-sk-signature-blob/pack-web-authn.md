//[ConnectBot SSH Library](../../../index.md)/[org.connectbot.sshlib.sk](../index.md)/[SkSignatureBlob](index.md)/[packWebAuthn](pack-web-authn.md)

# packWebAuthn

[jvm]\
fun [packWebAuthn](pack-web-authn.md)(signature: [ByteArray](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-byte-array/index.html), authenticatorData: [ByteArray](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-byte-array/index.html), clientDataJson: [ByteArray](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-byte-array/index.html), origin: [String](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-string/index.html)): [ByteArray](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-byte-array/index.html)

Packs an ES256 WebAuthn assertion for OpenSSH. Signed client JSON and extension bytes are preserved verbatim. The caller supplies the origin from clientDataJSON. Use the original SSH signing bytes as the WebAuthn challenge, without hashing.

#### Throws

| | |
|---|---|
| [SshException](../../org.connectbot.sshlib/-ssh-exception/index.md) | for malformed authenticator data, origin or DER signature. |