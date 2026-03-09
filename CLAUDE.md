# kcp-commands

## Purpose
A Claude Code hook that intercepts every Bash tool call and applies three phases: syntax injection (before execution), output filtering (after execution), and event logging. Saves approximately 33% of Claude Code's context window by giving it instant command knowledge and noise-filtered output. Ships with 283 bundled command manifests.

## Tech Stack
- Language: Shell (hooks), YAML (manifests), TypeScript + Java (bridges)
- Framework: Claude Code Hooks API
- Build: npm (TypeScript bridge), Maven (Java bridge)
- Key dependencies: Knowledge Context Protocol (KCP)

## Architecture
Three-phase hook system:
- **Phase A (Pre-execution):** Injects compact syntax/flag guidance so the agent picks correct flags immediately
- **Phase B (Post-execution):** Strips noise (boilerplate, permission errors, irrelevant lines) before output reaches context window
- **Phase C (Event logging):** Writes every Bash call to `~/.kcp/events.jsonl` for kcp-memory episodic indexing

Command knowledge is stored as YAML manifests in `commands/` directory. TypeScript and Java bridges provide MCP integration.

## Key Entry Points
- `commands/` - 283 YAML command manifests
- `bin/` - Hook installation scripts
- `typescript/` - TypeScript MCP bridge
- `java/` - Java MCP bridge
- `knowledge.yaml` - KCP manifest for self-description

## Development
```bash
# Install hooks
./bin/install.sh

# View manifests
ls commands/

# TypeScript bridge
cd typescript && npm install && npm test

# Java bridge
cd java && mvn clean install
```

## Domain Context
AI agent productivity infrastructure. Part of the Knowledge Context Protocol (KCP) ecosystem. Optimizes Claude Code sessions by reducing wasted context window space from help lookups and noisy command output.
