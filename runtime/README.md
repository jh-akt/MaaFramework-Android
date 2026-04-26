Place prebuilt Android runtime artifacts here before packaging:

- `agent/go-service` - Android arm64 Maa Agent service used by Go-agent projects such as MaaEnd
- `agent/main.py` plus `python/` - Project Interface Python agent entry plus a bundled Android Python runtime
- `agent/cpp-algo` or `agent/agent-service` - other Maa AgentServer-compatible native executables
- `maafw/` - MaaFramework Android runtime files required by the agent

This directory is intentionally text-only in git. Large runtime binaries should be staged locally.
