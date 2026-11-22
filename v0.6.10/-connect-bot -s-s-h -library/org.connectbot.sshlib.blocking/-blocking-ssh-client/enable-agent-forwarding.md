//[ConnectBot SSH Library](../../../index.md)/[org.connectbot.sshlib.blocking](../index.md)/[BlockingSshClient](index.md)/[enableAgentForwarding](enable-agent-forwarding.md)

# enableAgentForwarding

[jvm]\
fun [enableAgentForwarding](enable-agent-forwarding.md)(provider: [AgentProvider](../../org.connectbot.sshlib/-agent-provider/index.md))

Enable SSH agent forwarding with the provided agent.

Call after each successful [connect](connect.md) and before [openSession](open-session.md).

#### Parameters

jvm

| | |
|---|---|
| provider | Agent implementation that handles signing requests |

#### See also

| |
|---|
| [SshClient.enableAgentForwarding](../../org.connectbot.sshlib/-ssh-client/enable-agent-forwarding.md) |