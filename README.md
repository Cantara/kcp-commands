# kcp-commands

<img src="https://totto.goatcounter.com/count?p=/kcp-commands-readme" alt="" style="display:none">

**Not a CLI — typed knowledge infrastructure for 291 CLIs.** Saves 33% of Claude Code's context window by injecting syntax context before execution and filtering noise after.

kcp-commands is a [Claude Code hook](https://docs.anthropic.com/en/docs/claude-code/hooks) — it runs invisibly *around* CLI tools, not as one. It intercepts every Bash tool call and applies three phases:

| Phase | When | What it does |
|-------|------|--------------|
| **A -- Syntax injection** | Before execution | Injects compact flag/syntax guidance so the agent picks the right flags immediately, never wastes a round trip on `--help` |
| **B -- Output filtering** | After execution | Strips noise (boilerplate, permission errors, hundreds of irrelevant lines) before the output reaches the context window |
| **C -- Event logging** | After execution | Writes every Bash call to `~/.kcp/events.jsonl` for [kcp-memory](https://github.com/Cantara/kcp-memory) to index as episodic memory |

Measured across a typical agentic coding session: **67,352 tokens saved -- 33.7% of a 200K context window recovered**, equivalent to 33 additional tool call results fitting in the same context.

291 bundled manifests. Part of the [Knowledge Context Protocol](https://cantara.github.io/knowledge-context-protocol/) ecosystem.
Read the [release post](https://wiki.totto.org/blog/2026/03/02/kcp-commands/) for the full benchmark methodology and design rationale.

---

## Why this works

Claude Code is designed to prefer shell commands (`grep`, `find`, `ls`) over reading full files — it reaches for the terminal first to narrow scope before loading content. This is the right instinct for token efficiency.

The gap is that Claude arrives at each Bash call with no pre-loaded knowledge of the command it is about to run:

- **Phase A** injects syntax context at the moment Claude is already planning the command — it lands in exactly the right place in the reasoning chain, before the call is issued.
- **Phase B** removes the noise that Claude cannot filter itself — raw command output arrives in the context window as-is, and there is no built-in mechanism to strip it before it consumes space.
- **Phase C** fills the cross-session gap — Claude Code starts each session with an empty context window. The event log gives kcp-memory the raw material to reconstruct what happened across sessions.

kcp-commands does not change how Claude reasons. It gives Claude better inputs at the Bash tool boundary and cleaner outputs on the way back.

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

### Phase C -- Event logging (after execution)

Every Bash hook call is appended to `~/.kcp/events.jsonl` as a single JSONL line:

```json
{"ts":"2026-03-03T16:04:24Z","session_id":"ad732c58-...","project_dir":"/src/myproject","tool":"Bash","command":"cat /tmp/daemon.log","manifest_key":"cat","manifest_version":"cabf7009","exit_code_hint":0}
```

Fields: `ts` (ISO-8601), `session_id` (Claude Code session UUID), `project_dir` (working directory), `tool` (always `"Bash"`), `command` (raw command, truncated to 500 chars), `manifest_key` (resolved manifest or `null`), `manifest_version` (SHA-256 first 8 hex chars of the active manifest YAML, or `null` — v0.16.0), `exit_code_hint` (`0` clean / `1` error detected, v0.15.0).

The `post-hook.sh` PostToolUse hook enriches each event with an `exit_code_hint` field (v0.15.0): `0` if the output looks clean, `1` if error signals were detected (exception, traceback, "command not found", non-zero exit patterns). This powers `kcp-memory analyze` — the manifest quality feedback loop that surfaces which manifests correlate with failures and retries.

The write is asynchronous (virtual thread) and never blocks the hook response or raises an error. [kcp-memory](https://github.com/Cantara/kcp-memory) v0.2.0+ indexes these events to provide tool-level episodic memory across sessions.

### Suppression list -- Skip manifests for well-known commands (v0.14.0)

Well-known commands skip manifest lookup entirely. The daemon returns 204 immediately — no manifest injected, no tokens spent. This eliminates 5-8K tokens/session of context overhead for commands capable agents already know.

Default suppressed commands:

| Category | Commands |
|----------|----------|
| Version control | `git`, `gh` |
| Text processing | `ls`, `cat`, `head`, `tail`, `grep`, `sed`, `awk`, `find`, `echo`, `printf`, `wc`, `sort`, `uniq`, `cut`, `tr`, `xargs` |
| Filesystem | `cd`, `pwd`, `mkdir`, `rm`, `rmdir`, `mv`, `cp`, `touch`, `chmod`, `chown`, `ln` |
| Network | `curl`, `wget`, `ssh`, `scp`, `rsync` |
| System | `ps`, `kill`, `top`, `df`, `du`, `uname` |
| Runtimes | `python3`, `node`, `java` |
| Shell builtins | `which`, `type`, `command`, `env`, `export`, `source`, `eval` |

Suppression is **unconditional** — even if a manifest yaml exists for the command, suppression wins. Shipped manifests for these commands exist as reference only.

**Benchmark (typical session):** 63% of hook calls suppressed. Cloud/DevOps tools (aws, kubectl, docker, mvn) still receive manifests.

**Customize:** create `~/.kcp/suppress.txt` (one command per line, `#` comments). This replaces the default list entirely. To unsuppress a command, omit it from your custom list.

```bash
# ~/.kcp/suppress.txt — custom example
# Keep git/ls/grep suppressed but add docker
docker
git
ls
grep
```

**Measure your setup:** `bash ~/.kcp/benchmark.sh` — shows suppression rate, hook latency, and estimated tokens saved, with JSON output for sharing.

---

## Install

Both options download pre-built artifacts from GitHub Releases and install to `~/.kcp/`. No source clone required.

### Java daemon (recommended)

```bash
curl -fsSL https://raw.githubusercontent.com/Cantara/kcp-commands/main/bin/install.sh | bash -s -- --java
```

Requires Java 21+. Hook latency: ~12ms per call.

> **macOS:** Install Java 21 with `brew install --cask temurin@21`. The installer and `hook.sh` detect Temurin 21 automatically via `/usr/libexec/java_home -v 21` — even if your system default is an older Java version.

### Node.js only

```bash
curl -fsSL https://raw.githubusercontent.com/Cantara/kcp-commands/main/bin/install.sh | bash -s -- --node
```

Requires Node.js 18+. Hook latency: ~250ms per call.

The installer places `hook.sh`, the daemon JAR, and `cli.js` in `~/.kcp/`, then registers `bash "$HOME/.kcp/hook.sh"` as a `PreToolUse` hook in `~/.claude/settings.json`. Restart Claude Code to activate.

### Upgrade

```bash
# Built-in updater (v0.18.0+):
java -jar ~/.kcp/kcp-commands-daemon.jar --check-update   # check only (exit 1 if update available)
java -jar ~/.kcp/kcp-commands-daemon.jar --update          # interactive download + install

# Or re-run the installer to pull the latest release:
curl -fsSL https://raw.githubusercontent.com/Cantara/kcp-commands/main/bin/install.sh | bash -s -- --java
pkill -f kcp-commands-daemon; nohup java -jar ~/.kcp/kcp-commands-daemon.jar > /tmp/kcp-commands-daemon.log 2>&1 &
```

### For contributors / local development

```bash
git clone https://github.com/Cantara/kcp-commands.git
cd kcp-commands
./bin/install.sh   # interactive prompt: --java or --node
```

When running from a clone, the installer falls back to a local Maven build if the release download fails.

### Verify it's working

After restarting Claude Code, run any Bash command. You should see a `[kcp]` context block injected before it executes (visible in the hook output). To confirm all three phases:

```bash
# Phase A: daemon responds to health check
curl -sf http://localhost:7734/health && echo "daemon running"

# Phase C: events are being logged
tail -1 ~/.kcp/events.jsonl
```

Phase B is transparent -- you'll notice it when a normally noisy command like `ps aux` returns a concise, filtered result.

---

## Full KCP setup (kcp-commands + kcp-memory)

kcp-commands handles the Bash tool boundary. [kcp-memory](https://github.com/Cantara/kcp-memory) adds persistent episodic memory across sessions by indexing the event log (Phase C) and session transcripts. Install both for the complete stack:

**Step 1 — Install kcp-commands** (Java daemon backend):

```bash
curl -fsSL https://raw.githubusercontent.com/Cantara/kcp-commands/main/bin/install.sh | bash -s -- --java
```

**Step 2 — Install kcp-memory** (MCP server for episodic memory):

```bash
curl -fsSL https://raw.githubusercontent.com/Cantara/kcp-memory/main/bin/install.sh | bash
```

The kcp-memory installer scans your existing `~/.kcp/events.jsonl` (written by kcp-commands Phase C) and registers `kcp-memory` as an MCP server in `~/.claude.json`.

**Step 3 — Restart Claude Code** to activate both hooks and the MCP server.

After restart:
- Every Bash call gets syntax context injected (Phase A) and noise filtered (Phase B)
- Every Bash call is logged to `~/.kcp/events.jsonl` (Phase C)
- kcp-memory indexes events and session transcripts; Claude can query past sessions and tool-call history via the MCP tools

---

## Troubleshooting

### `[kcp]` context not appearing in Claude

1. Check the hook is registered:
   ```bash
   cat ~/.claude/settings.json | grep kcp
   ```
   You should see `bash "$HOME/.kcp/hook.sh"` in the `PreToolUse` hooks array.
2. If missing, re-run the installer.
3. Restart Claude Code after any settings change — hooks only activate on startup.
4. Confirm the daemon responds: `curl -sf http://localhost:7734/health && echo "ok"`

### Java daemon fails to start — `UnsupportedClassVersionError`

```
Error: LinkageError ... UnsupportedClassVersionError:
  (class file version 65.0, this Java supports 52.0)
```

The daemon is compiled for Java 21 (class file version 65.0). Your system `java` is older — Java 8 = version 52.0, Java 11 = version 55.0.

**Fix on macOS:**
```bash
brew install --cask temurin@21
# Verify:
/usr/libexec/java_home -v 21
# Restart the daemon:
pkill -f kcp-commands-daemon || true
curl -fsSL https://raw.githubusercontent.com/Cantara/kcp-commands/main/bin/install.sh | bash -s -- --java
```

The installer and `hook.sh` pick up Temurin 21 automatically via `/usr/libexec/java_home -v 21` — no changes to `JAVA_HOME` needed.

**Fix on Linux:** Install OpenJDK 21 and ensure it is the active version:
```bash
# Debian/Ubuntu
sudo apt install openjdk-21-jdk
sudo update-alternatives --config java   # select Java 21

# Fedora/RHEL
sudo dnf install java-21-openjdk
sudo alternatives --config java
```

**Fallback:** If you cannot upgrade Java right now, use the Node.js backend instead:
```bash
curl -fsSL https://raw.githubusercontent.com/Cantara/kcp-commands/main/bin/install.sh | bash -s -- --node
```

### Node.js install fails with HTTP 404

This was a bug in releases before v0.14.0 where the release asset was named `kcp-commands-cli.js` instead of `cli.js`. Fixed in v0.14.0+. Re-run the installer to pull the correct artifact.

### Some commands get `[kcp]` blocks, others don't

Expected behaviour. Commands on the suppression list (`git`, `ls`, `grep`, `curl`, `ssh`, and ~30 others) return 204 immediately — no manifest is injected. This is intentional: capable agents already know these commands, so skipping saves 5–8K tokens per session. See the [Suppression list](#suppression-list----skip-manifests-for-well-known-commands-v0140) section. Customise the list with `~/.kcp/suppress.txt`.

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

### Bundled manifests (291 primed)

**Git** — `git log` · `git diff` · `git status` · `git add` · `git commit` · `git push` · `git pull` · `git fetch` · `git branch` · `git checkout` · `git stash` · `git merge` · `git rebase` · `git clone` · `git reset` · `git tag` · `git remote` · `git show` · `git cherry-pick` · `git bisect` · `git worktree` · `git submodule`

**Linux / macOS** — `ls` · `ps` · `find` · `cp` · `mv` · `rm` · `mkdir` · `cat` · `head` · `tail` · `grep` · `chmod` · `df` · `du` · `tar` · `ln` · `rsync` · `top` · `kill` · `systemctl` · `journalctl` · `lsof` · `netstat` · `ss` · `ping` · `free` · `watch` · `wget` · `dig` · `openssl` · `scp`

**Text processing** — `jq` · `sed` · `awk` · `sort` · `uniq` · `wc` · `cut` · `xargs` · `tee` · `tr` · `diff` · `make` · `yq` · `base64` · `sha256sum` · `envsubst` · `nl` · `xxd` · `strings` · `xmllint` · `column`

**Build tools** — `mvn` · `gradle` · `gradlew` · `cargo` · `go build` · `go test` · `go mod` · `ant` · `sbt` · `dotnet`

**Package managers** — `npm` · `yarn` · `pnpm` · `bun` · `pip` · `brew` · `apt` · `yum` · `gem` · `conda` · `snap` · `pacman` · `composer` · `poetry` · `bundle`

**Runtimes** — `node` · `python3` · `ruby` · `java` · `npx` · `mix`

**GitHub CLI** — `gh pr` · `gh issue` · `gh repo` · `gh workflow` · `gh run` · `gh release` · `gh auth` · `gh api` · `gh gist`

**Docker** — `docker ps` · `docker images` · `docker logs` · `docker build` · `docker run` · `docker exec` · `docker compose` · `docker network` · `docker volume` · `docker system` · `docker inspect` · `docker pull` · `docker push` · `docker tag`

**Kubernetes** — `kubectl get` · `kubectl logs` · `kubectl describe` · `kubectl apply` · `kubectl exec` · `kubectl port-forward` · `kubectl delete` · `kubectl rollout` · `kubectl scale` · `kubectl top` · `kubectl config` · `kubectl create`

**Cloud / IaC** — `aws` · `gcloud` · `az` · `terraform` · `helm` · `ansible` · `ansible-playbook` · `vagrant` · `pulumi` · `serverless` · `minikube` · `kind` · `packer` · `eksctl`

**Database CLIs** — `psql` · `mysql` · `redis-cli` · `sqlite3` · `mongosh` · `influx` · `pg_dump` · `pg_restore` · `mysqldump` · `duckdb`

**Security** — `gpg` · `ssh-keygen` · `ssh-add` · `certbot` · `keytool` · `age` · `vault` · `consul`

**System diagnostics** — `top` · `htop` · `vmstat` · `dstat` · `iotop` · `strace` · `dmesg` · `lsblk` · `iostat` · `uptime` · `id` · `who` · `crontab` · `tmux`

**Networking** — `nmap` · `nc` · `traceroute` · `ip` · `mtr` · `nslookup` · `whois`

**Modern CLI** — `fzf` · `rg` · `fd` · `bat` · `delta` · `eza` · `hyperfine` · `tldr` · `jless` · `parallel` · `lazygit`

**Linters / CI** — `shellcheck` · `hadolint` · `act` · `k9s`

**GitOps / K8s extras** — `kustomize` · `argocd` · `flux`

**Deployment platforms** — `fly` · `vercel` · `wrangler` · `heroku` · `doctl`

**Version managers** — `asdf` · `mise` · `nvm` · `pyenv` · `rustup`

**Build & test** — `cmake` · `ffmpeg` · `pytest` · `mkdocs` · `rclone`

**Developer HTTP** — `http` (HTTPie)

**AI / LLM** — `ollama`

**System tools** — `zip` · `unzip` · `gzip` · `date` · `env` · `chown`

**Windows** — `dir` · `tasklist` · `taskkill` · `ipconfig` · `netstat` · `where` · `robocopy` · `type` · `xcopy` · `winget` (all include PowerShell equivalents)

**Linters / formatters** — `ruff` · `eslint` · `prettier` · `mypy` · `golangci-lint` · `yamllint` · `markdownlint`

**Testing** — `jest` · `vitest` · `playwright` · `cypress` · `k6` · `grpcurl`

**Containers+** — `podman` · `trivy` · `cosign`

**Monorepo / task runners** — `nx` · `turbo` · `just` · `bazel` · `task`

**Secrets / config** — `sops` · `op` · `direnv`

**Modern CLI+** — `zoxide` · `btm` · `dust` · `procs`

**Package managers+** — `uv` · `apk` · `dnf` · `pipx`

**Runtimes+** — `deno` · `go run` · `php` · `swift`

**Dev workflow** — `pre-commit` · `gh codespace`

Phase B output filtering is enabled on the high-noise commands: `ps`, `find`, `top`, `df`, `du`, `grep`, `journalctl`, `systemctl`, `lsof`, `netstat`, `ss`, `rsync`, `npm`, `yarn`, `pnpm`, `pip`, `brew`, `apt`, `yum`, `mvn`, `gradle`, `cargo`, `go test`, `make`, `docker ps`, `docker images`, `docker logs`, `docker build`, `docker compose`, `kubectl get`, `kubectl logs`, `kubectl describe`, `aws`, `gcloud`, `az`, `terraform`, `dig`, `openssl`, `dir`, `tasklist`, `nmap`, `ansible-playbook`, `conda`, `sbt`, `vmstat`, `dstat`, `iotop`, `strace`, `iostat`, `psql`, `mysql`, `ffmpeg`, `pytest`, `cmake`, `rclone`, `eksctl`, `packer`, `dbt`, `duckdb`.

### Auto-generated manifests

When the hook encounters an unknown command, it runs `<cmd> --help`, parses the output, and saves a generated manifest to `~/.kcp/commands/` for future sessions. The agent gets syntax context on the very next invocation — no manual authoring needed.

---

## Manifest format

Manifests are YAML files, one per command or subcommand:

```yaml
# .kcp/commands/mvn.yaml
command: mvn                          # command name (must match what the agent runs)
platform: all                         # "all", "linux", "macos", or "windows"
description: "Apache Maven build tool"  # one-line summary shown in [kcp] context block

syntax:                               # ── Phase A: injected before execution ──
  usage: "mvn [options] [<goal(s)>]"
  key_flags:
    - flag: "test"
      description: "Run tests"
      use_when: "Verify the build"    # optional: helps the agent choose the right flag
    - flag: "-pl <module>"
      description: "Build specific module"
    - flag: "-DskipTests"
      description: "Skip test execution"
      use_when: "Fast build when tests are known good"
  preferred_invocations:
    - invocation: "mvn test -pl <module>"
      use_when: "Run tests for one module"

output_schema:                        # ── Phase B: applied after execution ──
  enable_filter: true
  noise_patterns:
    - pattern: "^\\[INFO\\] Scanning for projects"
      reason: "Boilerplate startup line"
    - pattern: "^\\[INFO\\] -+$"
      reason: "Separator lines"
  max_lines: 80
  truncation_message: "... {remaining} more Maven lines. Check for BUILD SUCCESS/FAILURE."
```

**Top-level fields:** `command` is the executable name. For subcommands, use a separate file named `<command>-<subcommand>.yaml` (e.g., `git-log.yaml`) and add a `subcommand: log` field. `platform` controls which OS the manifest applies on; `"all"` matches everywhere. `description` is shown in the `[kcp]` context block the agent sees.

**`syntax`** drives Phase A. The hook formats `key_flags` (up to 5) and `preferred_invocations` (up to 3) into a compact context block injected before execution.

**`output_schema`** drives Phase B. When `enable_filter: true`, the command's stdout is piped through a filter that removes lines matching `noise_patterns` (regexes) and truncates beyond `max_lines`. The `{remaining}` placeholder in `truncation_message` is replaced with the count of omitted lines.

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
  |      +--> /hook endpoint: resolve manifest, build additionalContext (Phase A)
  |      +--> /filter/{key} endpoint: noise filtering + truncation (Phase B)
  |      +--> EventLogger: async write to ~/.kcp/events.jsonl (Phase C)
  |      +--> /health endpoint: liveness check
  |
  +--> Node.js fallback (dist/cli.js) -- 250ms, cold
         |
         +--> hook.ts: parse command, resolve manifest, build context (Phase A)
         +--> filter.ts: pipe stdout through noise patterns + truncation (Phase B)
```

**`hook.sh`** is the registered hook script. It tries the Java daemon first (HTTP POST to `localhost:7734`). If the daemon is not running, it starts it from the JAR. If no JAR exists, it falls back to Node.js.

**Manifest resolution** follows the three-tier lookup chain described above. Unknown commands trigger auto-generation via `--help` parsing.

**Phase B filtering** wraps the original command with a pipe: `ps aux` becomes `ps aux | curl -s -X POST http://localhost:7734/filter/ps --data-binary @-` (Java daemon) or `ps aux | node cli.js filter ps` (Node.js fallback). The filter reads stdin, strips noise patterns, truncates to `max_lines`, and appends a truncation message with the count of omitted lines.

**Phase C event logging** runs on every hook call, regardless of whether a manifest was found. The Java daemon writes asynchronously on a virtual thread; it never blocks the hook response. Phase C is currently Java-daemon only.

**Suppression list** is checked before manifest lookup. Commands in the list (git, ls, grep, curl, etc.) return 204 immediately. This is the lowest-cost path — no file I/O, no manifest parsing.

---

## Repository structure

```
kcp-commands/
  bin/
    hook.sh              # thin client: daemon -> Node.js fallback
    post-hook.sh         # PostToolUse: captures output preview -> events.jsonl
    install.sh           # registers PreToolUse + PostToolUse hooks
    benchmark.sh         # measures suppression rate + token savings
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
      .../HookHandler.java      # Phase A + B: manifest resolution, context injection, filter piping
      .../SuppressionList.java  # suppression list — fast path before manifest lookup
      .../EventLogger.java      # Phase C: async JSONL event writer
      .../ManifestResolver.java
      .../ManifestGenerator.java
    target/
  commands/              # bundled primed manifests (291)
    ls.yaml
    ps.yaml
    find.yaml
    git-log.yaml
    git-diff.yaml
    ...
  tools/
    benchmark.py         # latency benchmark script
    benchmark_agent.py   # token savings benchmark script
  docs/
    benchmark-results.md # full benchmark methodology and data
  .github/
    workflows/
      release.yml        # builds JAR + Node.js, publishes GitHub release on tag
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

## Releases

| Version | Manifests | Notes |
|---------|-----------|-------|
| v0.1.0 | 18 | Initial: git, Linux/macOS basics, curl, ssh, docker, kubectl |
| v0.2.0 | 32 | Windows, extended git, networking |
| v0.3.0 | 62 | Full initial library |
| v0.4.0 | 114 | Text processing, build tools, package managers, cloud/IaC |
| v0.5.0 | 214 | System tools, DB CLIs, security, modern CLI, monitoring |
| v0.6.0 | 244 | ollama, HTTPie, ffmpeg, pytest, cmake, mkdocs, rclone, pg_dump/restore, mysqldump, glab, fly/vercel/wrangler/heroku/doctl/eksctl, vault/consul/packer, kustomize/argocd/flux, asdf/mise/nvm/pyenv/rustup, dbt, lazygit |
| v0.6.1 | 244 | **Fix**: `index.txt` now auto-generated by Maven — v0.4.0–v0.6.0 shipped with only 62 manifests in the daemon due to a stale index. Install path changed to `~/.kcp/` (no source clone needed). `cli.js` now released as a downloadable artifact. |
| v0.7.0 | 244 | README install section clarifications; Releases changelog table; v0.6.1 patch documented in blog post. |
| v0.8.0 | 283 | uv, apk, dnf, pipx, winget, deno, go-run, php, swift, ruff, eslint, prettier, mypy, golangci-lint, yamllint, markdownlint, podman, trivy, cosign, nx, turbo, just, bazel, task, sops, op, direnv, jest, vitest, playwright, cypress, k6, grpcurl, zoxide, btm, dust, procs, pre-commit, gh-codespace |
| v0.9.0 | 283 | **Phase C: EventLogger** — writes every Bash hook call to `~/.kcp/events.jsonl` (async, virtual thread, ReentrantLock); consumed by kcp-memory v0.2.0+ for tool-level episodic memory |
| v0.12.0 | 284 | **KCP 0.9 Federation Release.** `knowledge.yaml` bumped to `kcp_version: "0.9"`, added `manifests` block (federation link to KCP spec). New manifest: `duckdb` (in-process analytical SQL engine). Aligned with kcp-mcp@0.12.0. |
| v0.13.0 | 289 | +5 manifests: `gws`, `gws-auth`, `gws-gmail`, `gws-calendar`, `gws-drive` — Google Workspace CLI (gmail.modify, calendar, drive scopes) |
| v0.14.0 | 289 | **KCP 0.10 Discovery & Versioning Release.** `knowledge.yaml` bumped to `kcp_version: "0.10"`. KCP 0.10 adds RFC-0007 Query Vocabulary (normative pre-invocation discovery), federation version pinning (`version_pin` + `version_policy`), instruction file bridge guide, and `kcp init` spec. |
| v0.15.0 | 289 | **Manifest quality feedback loop.** `post-hook.sh` adds `exit_code_hint` (0/1) to PostToolUse events — error signals detected from output preview. Powers `kcp-memory analyze` for self-improving manifest quality. |
| v0.16.0 | 289 | **Manifest version tracking.** Stamps every Phase A event with `manifest_version` — the SHA-256 first 8 hex chars of the YAML file active at invocation time. Enables `kcp-memory analyze --by-version` to compare quality metrics before and after a manifest is improved. |
| v0.16.1 | 289 | **Manifest quality improvements.** First round of data-driven manifest improvements based on `kcp-memory analyze` quality signals (60-day dataset): `ssh` (69% retry), `gh-api` (71% retry), `curl` (46% retry + 26% help), `find` (62% retry, 949 calls), `head` (79% retry), `sed` (66% retry). Added `common_errors` sections, EC2/macOS patterns, `-exec`, `--fail-with-body`, `BatchMode`, and more. |
| v0.17.0 | 289 | **Documentation and release alignment.** Aligned version with kcp-memory v0.17.0. |
| v0.17.1 | 291 | **Patch.** Fix: `ssh -i` variable assignments no longer parsed as command `-i` (`SuppressionList` guards `cmd.startsWith("-")`). Fix: `./gradlew` now resolves the primed `gradlew` manifest (normalize `./` prefix in `ManifestResolver`). New manifest: `gradlew` (Gradle wrapper — build, test, bootRun). Data-driven improvements to `az`, `pgrep`, `nohup-mvn`, `javap` based on quality feedback loop. |
| v0.18.0 | 291 | **Auto-update.** `--update` / `--check-update` pre-daemon flags for interactive and scriptable update checks. `UpdateChecker`: 24h-rate-limited GitHub API check, `.tmp`+`.bak` download with JAR validation, shared `~/.kcp/last-update-check` cache (shared with kcp-memory). New `/version` endpoint: `GET http://localhost:7734/version` → current + latest + updateAvailable. Startup update notification on first run each day. |

---

## Related projects

- [Release post](https://wiki.totto.org/blog/2026/03/02/kcp-commands/) -- benchmark methodology, design rationale, and infographic
- [Knowledge Context Protocol](https://github.com/Cantara/knowledge-context-protocol) -- the KCP specification
- [KCP MCP Bridge](https://github.com/Cantara/knowledge-context-protocol/tree/main/bridge/typescript) -- MCP bridge for project manifests
- [Synthesis](https://github.com/exoreaction/Synthesis) -- codebase intelligence and indexing
- [kcp-memory](https://github.com/Cantara/kcp-memory) -- episodic memory daemon; indexes session transcripts + tool-call events written by kcp-commands v0.9.0 Phase C
- [kcp-memory release post](https://wiki.totto.org/blog/2026/03/03/kcp-memory/) -- three-layer memory model and MCP integration

## Knowledge manifest

This repository ships a [`knowledge.yaml`](knowledge.yaml) and [`llms.txt`](llms.txt) for AI agent navigation.

---

## License

Apache 2.0 -- see [LICENSE](LICENSE).

Copyright 2026 Cantara / eXOReaction AS.

