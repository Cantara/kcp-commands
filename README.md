# kcp-commands

**Save 33% of Claude Code's context window by giving it instant command knowledge and noise-filtered output.**

kcp-commands is a [Claude Code hook](https://docs.anthropic.com/en/docs/claude-code/hooks) that intercepts Bash tool calls at two critical points: *before* execution (injecting concise flag/syntax guidance so the agent never wastes tokens on `--help`) and *after* execution (stripping noise from large outputs before they reach the model's context window).

Measured across a typical agentic coding session: **67,352 tokens saved -- 33.7% of a 200K context window recovered**, equivalent to 33 additional tool call results fitting in the same context.

Part of the [Knowledge Context Protocol](https://cantara.github.io/knowledge-context-protocol/) ecosystem.
Read the [release post](https://wiki.totto.org/blog/2026/03/02/kcp-commands/) for the full benchmark methodology and design rationale.

---

## How it works

### Phase A -- Command syntax context (before execution)

When Claude is about to run `ps aux`, the hook injects a compact `additionalContext` block:

```
[kcp] ps: Report a snapshot of running processes
Key flags:
  aux: All processes, all users, with CPU/memory  -> Default
  -ef: All processes, full format with PPID       -> Need parent PIDs
  --sort=-%cpu: Sort by CPU descending            -> Finding CPU hogs
Prefer:
  ps aux          # Find any process or check what's running
  ps aux | grep <name>  # Find a specific process
```

The agent picks the right flags immediately. No `--help` lookup, no man page parsing, no wasted round trip. Average saving: **532 tokens per avoided `--help` call**.

### Phase B -- Output noise filtering (after execution)

Large command outputs are piped through the manifest's noise filter before reaching the context window. Only the signal gets through:

| Command | Raw output | After filter | Reduction |
|---------|-----------|--------------|-----------|
| `ps aux` | 30,828 tokens | 652 tokens | **98%** |
| `find . -maxdepth 3` | 1,653 tokens | 755 tokens | 54% |
| `git status` | 60 tokens | 43 tokens | 28% |
| `git log -6` | 78 tokens | 78 tokens | 0% (already small) |

The filter adds zero overhead on commands whose output is already concise. It only activates when there is noise to remove.

---

## Install

### One-liner (Java daemon, recommended)

```bash
curl -fsSL https://raw.githubusercontent.com/Cantara/kcp-commands/main/bin/install.sh | bash -s -- --java
```

Requires Java 21. Hook latency: ~12ms per call.

### One-liner (Node.js only)

```bash
curl -fsSL https://raw.githubusercontent.com/Cantara/kcp-commands/main/bin/install.sh | bash -s -- --node
```

Requires Node.js 18+. Hook latency: ~250ms per call.

### Clone and install interactively

```bash
git clone https://github.com/Cantara/kcp-commands.git
cd kcp-commands
./bin/install.sh
```

The installer registers a `PreToolUse` hook in `~/.claude/settings.json` and downloads the daemon JAR from GitHub Releases (or builds from source if Maven is available). Restart Claude Code to activate.

---

## Performance

| Backend | Mean latency | p95 | Notes |
|---------|-------------|-----|-------|
| Java daemon (warm) | 14ms | 17ms | 19x faster than Node.js |
| Node.js (per-call) | 265ms | 312ms | No JVM required |
| Baseline (cat) | 2.3ms | 3.1ms | Pure OS overhead |

Java daemon cold start is ~537ms (one-time per session). Break-even: **2 hook calls** -- the daemon pays for itself within the first `git status` + `ls`.

Full methodology and raw numbers: [docs/benchmark-results.md](docs/benchmark-results.md).

---

## Supported commands

### Bundled manifests (62 primed)

**Git** — `git log` · `git diff` · `git status` · `git add` · `git commit` · `git push` · `git pull` · `git fetch` · `git branch` · `git checkout` · `git stash` · `git merge` · `git rebase` · `git clone` · `git reset` · `git tag` · `git remote` · `git show`

**Linux / macOS** — `ls` · `ps` · `find` · `cp` · `mv` · `rm` · `mkdir` · `cat` · `head` · `tail` · `grep` · `chmod` · `df` · `du` · `tar` · `ln` · `rsync` · `top` · `kill` · `systemctl` · `journalctl` · `lsof` · `netstat` · `ss` · `ping`

**Cross-platform** — `curl` · `npm` · `node` · `ssh` · `docker ps` · `docker images` · `docker logs` · `kubectl get` · `kubectl logs` · `kubectl describe`

**Windows** — `dir` · `tasklist` · `taskkill` · `ipconfig` · `netstat` · `where` · `robocopy` · `type` · `xcopy` (all include PowerShell equivalents)

Phase B output filtering is enabled on the high-noise commands: `ps`, `find`, `top`, `df`, `du`, `grep`, `journalctl`, `systemctl`, `lsof`, `netstat`, `ss`, `rsync`, `npm`, `docker ps`, `docker images`, `docker logs`, `kubectl get`, `kubectl logs`, `kubectl describe`, `dir`, `tasklist`.

### Auto-generated manifests

When the hook encounters an unknown command, it runs `<cmd> --help`, parses the output, and saves a generated manifest to `~/.kcp/commands/` for future sessions. The agent gets syntax context on the very next invocation — no manual authoring needed.

---

## Manifest format

Manifests are YAML files, one per command or subcommand. Three sections:

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
  preferred_invocations:
    - invocation: "mvn test -pl <module>"
      use_when: "Run tests for one module"

output_schema:
  enable_filter: true
  noise_patterns:
    - pattern: "^\\[INFO\\] Scanning for projects"
      reason: "Boilerplate startup line"
    - pattern: "^\\[INFO\\] -+$"
      reason: "Separator lines"
  max_lines: 80
  truncation_message: "... {remaining} more Maven lines. Check for BUILD SUCCESS/FAILURE."
```

**`syntax`** drives Phase A. The hook formats `key_flags` and `preferred_invocations` into a compact context block injected before execution.

**`output_schema`** drives Phase B. When `enable_filter: true`, the command's stdout is piped through a filter that removes lines matching `noise_patterns` and truncates beyond `max_lines`.

---

## Manifest lookup chain

For each Bash command, the hook resolves the manifest in order:

1. **`.kcp/commands/<key>.yaml`** -- project-local override (checked into your repo)
2. **`~/.kcp/commands/<key>.yaml`** -- user-level (generated manifests land here)
3. **`<package>/commands/<key>.yaml`** -- bundled primed library

First match wins. This lets you override bundled defaults per-project or per-user without modifying the package.

---

## Architecture

```
hook.sh (thin client)
  |
  +--> Java daemon (localhost:7734) -- 12ms, warm
  |      |
  |      +--> /hook endpoint: resolve manifest, build additionalContext
  |      +--> /health endpoint: liveness check
  |
  +--> Node.js fallback (dist/cli.js) -- 250ms, cold
         |
         +--> hook.ts: parse command, resolve manifest, build context
         +--> filter.ts: pipe stdout through noise patterns + truncation
```

**`hook.sh`** is the registered hook script. It tries the Java daemon first (HTTP POST to `localhost:7734`). If the daemon is not running, it starts it from the JAR. If no JAR exists, it falls back to Node.js.

**Manifest resolution** follows the three-tier lookup chain described above. Unknown commands trigger auto-generation via `--help` parsing.

**Phase B filtering** wraps the original command with a pipe: `ps aux` becomes `ps aux | node cli.js filter ps`. The filter reads stdin, strips noise patterns, truncates to `max_lines`, and appends a truncation message with the count of omitted lines.

---

## Repository structure

```
kcp-commands/
  bin/
    hook.sh              # thin client: daemon -> Node.js fallback
    install.sh           # registers hook in ~/.claude/settings.json
  typescript/            # Node.js hook + filter implementation
    src/
      hook.ts            # Phase A: parse command, inject context
      filter.ts          # Phase B: noise filtering + truncation
      resolver.ts        # three-tier manifest lookup
      generator.ts       # auto-generate from --help
      parser.ts          # YAML manifest parser
      model.ts           # TypeScript interfaces
      cli.ts             # CLI entry point
    dist/                # compiled output
    package.json
  java/                  # Fast daemon (12ms/call warm)
    pom.xml
    src/
    target/
  commands/              # bundled primed manifests
    ls.yaml
    ps.yaml
    find.yaml
    git-log.yaml
    git-diff.yaml
    git-status.yaml
    git-add.yaml
    git-branch.yaml
    git-checkout.yaml
  docs/
    benchmark-results.md # full benchmark methodology and data
  .github/
    workflows/
      release.yml        # builds JAR + Node.js, publishes GitHub release on tag
  benchmark.py           # latency benchmark script
  benchmark_agent.py     # token savings benchmark script
```

---

## Writing your own manifests

1. Create a YAML file following the [manifest format](#manifest-format) above.
2. Place it in `.kcp/commands/` (project-local) or `~/.kcp/commands/` (user-global).
3. The hook picks it up on the next Bash call -- no restart needed.

Good candidates for custom manifests:
- Build tools your team uses daily (`mvn`, `gradle`, `cargo`, `go build`)
- Cloud CLIs with verbose output (`aws`, `gcloud`, `az`, `kubectl`)
- Project-specific scripts where you want the agent to know the right flags

---

## Related projects

- [Release post](https://wiki.totto.org/blog/2026/03/02/kcp-commands/) -- benchmark methodology, design rationale, and infographic
- [Knowledge Context Protocol](https://github.com/Cantara/knowledge-context-protocol) -- the KCP specification
- [KCP MCP Bridge](https://github.com/Cantara/knowledge-context-protocol/tree/main/bridge/typescript) -- MCP bridge for project manifests
- [Synthesis](https://github.com/exoreaction/Synthesis) -- codebase intelligence and indexing

---

## License

Apache 2.0 -- see [LICENSE](LICENSE).

Copyright 2026 Cantara / eXOReaction AS.

