//[ConnectBot SSH Library](index.md)

# ConnectBot SSH Library

[jvm]\
[[Continuous Integration](https://img.shields.io/github/actions/workflow/status/connectbot/cbssh/ci.yml?branch=main&label=CI)](https://github.com/connectbot/cbssh/actions/workflows/ci.yml?query=branch%3Amain)[[Maven Central](https://img.shields.io/maven-central/v/org.connectbot.sshlib/sshlib?label=Maven%20Central&color=blue)](https://central.sonatype.com/artifact/org.connectbot.sshlib/sshlib)[[License](https://img.shields.io/github/license/connectbot/cbssh?label=License&color=blue)](https://github.com/connectbot/cbssh/blob/e84332aa870611f9f230d1908811fe4c71addc30/LICENSE)[[Quality Gate Status](https://img.shields.io/sonar/quality_gate/connectbot_cbssh?server=https%3A%2F%2Fsonarcloud.io&label=Quality%20Gate)](https://sonarcloud.io/summary/overall?id=connectbot_cbssh)[[Coverage](https://img.shields.io/sonar/coverage/connectbot_cbssh?server=https%3A%2F%2Fsonarcloud.io&label=Coverage)](https://sonarcloud.io/summary/overall?id=connectbot_cbssh)

This is ConnectBot SSH library built with Kotlin. Internally it uses coroutines, protocol definition files, and a state machine to run the SSH protocol. It currently connects to SSH servers, authenticates, and provide interactive shell sessions.

The protocol parsing uses declarative Kaitai Struct specifications that auto-generate code from `.ksy` definitions. The internal state machine is defined in KStateMachine for clear separation of protocol states from the code that runs in reaction to state changes.

## Features

- 
   **SSH Client**: Connect, authenticate, open shell sessions, read/write data
- 
   **Protocol Parsing**: Complete SSH wire protocol coverage (RFCs 4250-4256,   4419, 5656, 8308, 8709, 8731, 9142)
- 
   **Channel I/O**: Interactive shells with PTY, stdout/stderr streams, flow   control
- 
   **SFTP**: File transfer with full read/write/stat/directory operations   ([draft-ietf-secsh-filexfer](https://datatracker.ietf.org/doc/html/draft-ietf-secsh-filexfer))
- 
   **Port Forwarding**: Local, remote, and dynamic (SOCKS5) port forwarding
- 
   **Agent Forwarding**: Forward SSH agent requests with session binding support
- 
   **Transport**: Pluggable transport layer (TCP via Ktor, or custom)

## Algorithm Support

The library supports a wide range of modern SSH algorithms, including:

- 
   **Authentication**: `publickey` (including FIDO2/SK), `password`, `keyboard-interactive`
- 
   **Host Keys**: Ed25519, Ed448, ECDSA, RSA (SHA-2)
- 
   **Key Exchange**: ML-KEM hybrid, Curve25519, ECDH, DH group-exchange
- 
   **Encryption**: ChaCha20-Poly1305, AES-GCM, AES-CTR
- 
   **MACs**: HMAC-SHA2 (including ETM variants)

For a complete list of supported algorithms and their respective RFCs, see [docs/ALGORITHMS.md](https://github.com/connectbot/cbssh/blob/e84332aa870611f9f230d1908811fe4c71addc30/docs/ALGORITHMS.md).

The defaults intentionally exclude SHA-1 key exchange and MACs, CBC/3DES ciphers, and `ssh-rsa` host-key signatures. These legacy algorithms remain available only through the explicit `kexAlgorithms`, `hostKeyAlgorithms`, `encryptionAlgorithms`, and `macAlgorithms` settings in `SshClientConfig`. RSA user authentication normally requires the server to advertise `rsa-sha2-256` or `rsa-sha2-512` through `server-sig-algs`. Explicitly including `ssh-rsa` in the configured host-key algorithm wishlist also permits the legacy RSA/SHA-1 signature when advertised, or as the base-key algorithm when that extension is absent.

## Quick Start

### Build

```bash
./gradlew build
```

### Use the Test CLI Client

There is a &quot;testapp&quot; that allows you to try the library from a test client app. You can use it by running the following commands:

```bash
./gradlew :testapp:installDist
./testapp/build/install/testapp/bin/testapp user@host
./testapp/build/install/testapp/bin/testapp user@host -p 2222

# Enable more debug logging:
./testapp/build/install/testapp/bin/testapp -d user@host
```

### Library API

```kotlin
val client = SshClient("example.com", port = 22, hostKeyVerifier = myVerifier)
check(client.connect() is ConnectResult.Success) { "SSH connection failed" }
check(client.authenticatePassword("user", "pass") is AuthResult.Success) {
    "SSH authentication failed"
}

val session = checkNotNull(client.openSession()) { "Failed to open SSH session" }
check(session.requestPty()) { "Server rejected PTY request" }
check(session.requestShell()) { "Server rejected shell request" }

// Read/write
session.write("ls\n".toByteArray())
val output = session.read()  // ByteArray? (null on EOF)

// Or use coroutine channels directly
session.stdout  // ReceiveChannel<ByteArray>
session.stderr  // ReceiveChannel<ByteArray>

// Clean up
session.close()
client.disconnect()
```

### SFTP File Transfer

```kotlin
val sftp = when (val result = client.openSftp()) {
    is SftpResult.Success -> result.value
    else -> error("Failed to open SFTP: $result")
}

try {
    // List a directory
    when (val result = sftp.listdir("/home/user")) {
        is SftpResult.Success -> result.value.forEach { println(it.filename) }
        is SftpResult.ServerError -> println("Server error: ${result.message}")
        else -> println("Error: $result")
    }

    // Read a file
    val handle = sftp.open("/home/user/file.txt", setOf(SftpOpenFlag.READ)).getOrThrow()
    try {
        val data = sftp.read(handle, 0L, 4096).getOrThrow() // ByteArray? (null on EOF)
    } finally {
        sftp.close(handle).getOrThrow()
    }
} finally {
    sftp.close()
}
```

### FIDO2 / Security Key Authentication

The library supports authentication with `sk-ssh-ed25519@openssh.com` and `sk-ecdsa-sha2-nistp256@openssh.com` keys. Callers provide their own FIDO2 stack and surface the resulting assertion through the library's helpers.

See [docs/SK_AUTH.md](https://github.com/connectbot/cbssh/blob/e84332aa870611f9f230d1908811fe4c71addc30/docs/SK_AUTH.md) for detailed implementation details and examples.

### SSH Agent Forwarding

Enable SSH agent forwarding to allow remote servers to use your keys:

```kotlin
// Implement an agent provider
class MyAgentProvider : AgentProvider {
    override suspend fun getIdentities(): AgentResult<List<AgentIdentity>> {
        val keyBlob = loadPublicKeyBlob()
        return AgentResult.Success(listOf(AgentIdentity(keyBlob, "my-key")))
    }

    override suspend fun signData(context: AgentSigningContext): AgentResult<ByteArray?> {
        // Show approval UI to user with session context
        val approved = showSigningPrompt(
            "Remote server ${context.serverHostKey.joinToString("") { "%02x".format(it) }} wants to use your key",
            "Session bound: ${context.isBound}"
        )

        return AgentResult.Success(if (approved) {
            signWithPrivateKey(context.publicKeyBlob, context.dataToSign)
        } else {
            null  // Deny the request
        })
    }
}

// Enable agent forwarding
val client = SshClient("bastion.example.com", hostKeyVerifier = myVerifier)
check(client.connect() is ConnectResult.Success) { "SSH connection failed" }
check(client.authenticatePassword("user", "pass") is AuthResult.Success) {
    "SSH authentication failed"
}
client.enableAgentForwarding(MyAgentProvider())

// Now remote servers can use your agent through forwarding
val session = checkNotNull(client.openSession()) { "Failed to open SSH session" }
check(session.requestShell()) { "Server rejected shell request" }
// When you SSH from bastion to another server, it can request signatures
```

## Compatibility Testing

The library is tested against multiple SSH server implementations using Docker (via Testcontainers):

- 
   **OpenSSH** 9.9p2 — full integration tests including port forwarding
- 
   **AsyncSSH** (Python) — compatibility tests for ciphers, key exchange, MACs,   and public key auth
- 
   **Dropbear** — compatibility tests including ML-KEM post-quantum key exchange

Run integration tests with: `./gradlew :sshlib:test` (requires Docker).

## Current Limitations

- 
   Client-only (no server implementation)

## License

Apache License 2.0 - See LICENSE file

## Copyright

Copyright 2019-2026, [Kenny Root](https://github.com/kruton/)

## Packages

| Name |
|---|
| [org.connectbot.sshlib](-connect-bot -s-s-h -library/org.connectbot.sshlib/index.md) |
| [org.connectbot.sshlib.blocking](-connect-bot -s-s-h -library/org.connectbot.sshlib.blocking/index.md) |
| [org.connectbot.sshlib.client](-connect-bot -s-s-h -library/org.connectbot.sshlib.client/index.md) |
| [org.connectbot.sshlib.sk](-connect-bot -s-s-h -library/org.connectbot.sshlib.sk/index.md) |
| [org.connectbot.sshlib.transport](-connect-bot -s-s-h -library/org.connectbot.sshlib.transport/index.md) |

<!-- BEGIN DOCS API CHANGES -->
## New and changed APIs

Changes since v0.4.2.

[Compare source versions](https://github.com/connectbot/cbssh/compare/v0.4.2...v0.5.0)

### SSH

#### org.connectbot.sshlib.SshClientConfig

- Added: [val sessionWindowSize: Int](-connect-bot%20-s-s-h%20-library/org.connectbot.sshlib/-ssh-client-config/session-window-size.html)

- Added: [val sftpWindowSize: Int](-connect-bot%20-s-s-h%20-library/org.connectbot.sshlib/-ssh-client-config/sftp-window-size.html)

#### org.connectbot.sshlib.SshClientConfig.Builder

- Added: [var sessionWindowSize: Int](-connect-bot%20-s-s-h%20-library/org.connectbot.sshlib/-ssh-client-config/-builder/session-window-size.html)

- Added: [var sftpWindowSize: Int](-connect-bot%20-s-s-h%20-library/org.connectbot.sshlib/-ssh-client-config/-builder/sftp-window-size.html)

### Release notes

Changes for library users since `0.4.2`.

### Added

- Added support for reading OpenSSH private keys encrypted with
  `aes128-gcm@openssh.com` and `aes256-gcm@openssh.com`.
- Added `SshClientConfig.sessionWindowSize` and `SshClientConfig.sftpWindowSize`
  to configure receive windows for session channels and channels opened by
  `SshClient.openSftp` independently.

### Changed

- Increased default receive windows from 64 KiB to 2 MiB for session channels
  and 8 MiB for SFTP channels to improve throughput on higher-latency connections.
- Improved SSH and SFTP transfer performance by batching channel writes and
  receive-window updates, pipelining SFTP frames, and reducing buffer copies
  and allocations.
- Serialized protocol decisions independently of transport writes, keeping
  incoming packet processing and shutdown responsive under backpressure.

### Fixed

- Prevented valid packets arriving during suspended writes from causing
  spurious disconnects by serializing complete connection state transitions.
- Preserved reply ordering when requests are cancelled after being admitted
  for transmission.
- Released SFTP lifecycle locks before waiting for writes to prevent deadlocks
  under transport or channel-window backpressure.
- Cancelled pending remote forwarding handlers and released sockets and
  selector resources when forwarding ends or fails.
- Improved Android key compatibility by falling back to JVM Base64 operations
  when Android calls fail and accepting Ed25519 private keys with PKCS#8
  encodings regardless of their implementation class name.

[0.5.0]: https://github.com/connectbot/cbssh/compare/v0.4.2...v0.5.0
[0.4.2]: https://github.com/connectbot/cbssh/compare/v0.4.1...v0.4.2
[0.4.1]: https://github.com/connectbot/cbssh/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/connectbot/cbssh/compare/v0.3.1...v0.4.0
[0.3.1]: https://github.com/connectbot/cbssh/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/connectbot/cbssh/compare/v0.2.1...v0.3.0

[View changelog](https://github.com/connectbot/cbssh/blob/e84332aa870611f9f230d1908811fe4c71addc30/CHANGELOG.md)
<!-- END DOCS API CHANGES -->
