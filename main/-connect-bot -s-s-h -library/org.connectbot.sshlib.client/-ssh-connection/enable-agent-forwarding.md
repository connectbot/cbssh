//[ConnectBot SSH Library](../../../index.md)/[org.connectbot.sshlib.client](../index.md)/[SshConnection](index.md)/[enableAgentForwarding](enable-agent-forwarding.md)

# enableAgentForwarding

[jvm]\
fun [enableAgentForwarding](enable-agent-forwarding.md)(provider: [AgentProvider](../../org.connectbot.sshlib/-agent-provider/index.md))

Enable SSH agent forwarding with the provided agent.

Call before opening session channels so they request forwarding. The provider also handles incoming agent channels on this connection.

#### Parameters

jvm

| | |
|---|---|
| provider | Agent implementation that handles signing requests |