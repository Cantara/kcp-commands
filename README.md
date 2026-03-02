# kcp-commands

**Knowledge Context Protocol — Command Manifests**

Semantic manifests for CLI commands. Gives AI agents instant syntax knowledge and noise-filtered output for common shell commands — no man page parsing, no wall-of-text guessing.

Part of the [Knowledge Context Protocol](https://cantara.github.io/knowledge-context-protocol/) ecosystem.

---

## What it does

Registers a Claude Code `PreToolUse` hook that intercepts `Bash` tool calls.

**Phase A — Syntax context (always on):**
When Claude is about to run `ps aux`, the hook injects:
```
[kcp] ps: Report a snapshot of running processes
Key flags:
  aux: All processes, all users, with CPU/memory  → Default — find any process
  -ef: All processes, full format with PPID       → Need parent PIDs
  --sort=-%cpu: Sort by CPU descending            → Finding CPU hogs
Prefer:
  ps aux          # Find any process or check what's running
  ps aux | grep <name>  # Find a specific process
```

Claude chooses the right flags immediately, without running `ps --help`.

**Phase B — Output filtering (per-manifest, opt-in):**
For large-output commands (git log, ps, find), the command is piped through the manifest's noise filter before the output reaches Claude's context window.

```
ps aux (300 lines → 30 lines, header stripped)
git log (unbounded → 50 lines + truncation notice)
find (permission errors stripped, capped at 100 results)
```

---

## Install

```bash
git clone https://github.com/Cantara/kcp-commands
cd kcp-commands
./install.sh
```

Then restart Claude Code.

---

## Manifest lookup chain

For each Bash command, the hook resolves the manifest in order:

1. `.kcp/commands/<cmd>.yaml` — project-local override
2. `~/.kcp/commands/<cmd>.yaml` — user-level (generated manifests saved here)
3. `<package>/commands/<cmd>.yaml` — bundled (primed library)

If no manifest exists and the command is recognized, the hook runs `<cmd> --help`, parses the output, and saves a generated manifest to `~/.kcp/commands/` for future sessions.

---

## Primed manifests

Bundled manifests covering the most common AI agent commands:

| Command | Phase A | Phase B |
|---------|---------|---------|
| `ls` | ✓ | noise only |
| `ps` | ✓ | ✓ (30 line cap) |
| `git log` | ✓ | ✓ (50 line cap) |
| `git diff` | ✓ | context only |
| `git status` | ✓ | hint lines stripped |
| `find` | ✓ | ✓ (permission errors, 100 cap) |

---

## Writing your own manifest

```yaml
# .kcp/commands/mvn.yaml
command: mvn
platform: all
description: "Apache Maven build tool"

syntax:
  usage: "mvn [options] [<goal(s)>]"
  key_flags:
    - flag: "test"
      description: "Run tests"
      use_when: "Verify the build"
    - flag: "-pl <module>"
      description: "Build specific module"
    - flag: "-DskipTests"
      description: "Skip test execution"
      use_when: "Fast build when tests are known good"
    - flag: "-q"
      description: "Quiet output"

  preferred_invocations:
    - invocation: "mvn test -pl <module>"
      use_when: "Run tests for one module"
    - invocation: "./mvnw test"
      use_when: "Use Maven wrapper (preferred in eXOReaction projects)"

output_schema:
  enable_filter: true
  noise_patterns:
    - pattern: "^\\[INFO\\] Scanning for projects"
      reason: "Boilerplate startup line"
    - pattern: "^\\[INFO\\] -+$"
      reason: "Separator lines"
    - pattern: "^\\[INFO\\] Building jar"
      reason: "Packaging noise"
  max_lines: 80
  truncation_message: "... {remaining} more Maven lines. Check for BUILD SUCCESS/FAILURE."
```

Place in `.kcp/commands/mvn.yaml` (project) or `~/.kcp/commands/mvn.yaml` (user-global).

---

## Deviation detection (roadmap)

When a generated manifest's expected output schema doesn't match actual output (different column count, different format), the hook logs the deviation and flags the manifest for review. Planned for v0.2.

---

## Related

- [knowledge-context-protocol](https://github.com/Cantara/knowledge-context-protocol) — the KCP spec
- [kcp-mcp](https://github.com/Cantara/knowledge-context-protocol/tree/main/bridge/typescript) — MCP bridge for project manifests
- [Synthesis](https://github.com/exoreaction/Synthesis) — codebase intelligence indexing

**© 2026 Cantara / eXOReaction AS — Apache 2.0**
