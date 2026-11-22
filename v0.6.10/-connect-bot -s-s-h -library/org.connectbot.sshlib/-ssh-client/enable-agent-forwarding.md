//[ConnectBot SSH Library](../../../index.md)/[org.connectbot.sshlib](../index.md)/[SshClient](index.md)/[enableAgentForwarding](enable-agent-forwarding.md)

# enableAgentForwarding

[jvm]\
fun [enableAgentForwarding](enable-agent-forwarding.md)(provider: [AgentProvider](../-agent-provider/index.md))

Enable SSH agent forwarding with the provided agent.

Call after each successful [connect](connect.md) and before [openSession](open-session.md). Each session opened this way requests forwarding so the server can use your agent provider. Servers may decline forwarding. [openSftp](open-sftp.md) does not send a forwarding request.

#### Parameters

jvm

| | |
|---|---|
| provider | Agent implementation that handles signing requests |