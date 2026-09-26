# Flash MCP runner

`src/flash-mcp.mjs` connects the existing Harness runtime to the Flash role listener. It reads the existing `provider.json` and `provider-secret.txt`; it never places the key in process arguments or evidence logs.

```powershell
& '<installed Harness runtime>\node.exe' src/flash-mcp.mjs '<game>\config\geomantia' 'http://127.0.0.1:5002/mcp' --once
```

Use the installed Harness Node runtime, not an older system Node. Build `dist/main.mjs` with `npm run build` when updating the Harness kernel. Remove `--once` to keep waiting for Flash tasks. Set `GEOMANTIA_FLASH_HOME` to choose the evidence/session directory (default `.flash-harness`). Advanced tasks remain at the other MCP port, normally 5001.

The endpoint supplies scoped schemas and actual images. The runner relays tool calls through `planning_action`, maintains activity while the model thinks, and stops only after host-confirmed completion or escalation. `planning_escalate` saves the reason and hands ownership to the advanced role. Both endpoints and the in-game Harness share the same world lease; do not run multiple Flash workers expecting parallel writes.

The game settings support `port` and `flashPort` in `config/geomantia/mcp_server.json`. The configured provider model determines the model actually called; the Flash role is a task routing rule, not a model identity check.
