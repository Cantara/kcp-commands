# Benchmark Results: kcp-commands Suppression and Hook Efficiency

kcp-commands v0.14.0 suppresses 72% of hook calls (24 of 33 benchmarked commands), eliminating an estimated 7,659 tokens per session of context overhead from manifests that capable agents do not need. All 33 commands complete in 22-41ms on a warm Java daemon. Zero mismatches between expected and actual suppression behavior across all test runs.

---

## Table of contents

1. [Methodology](#methodology)
2. [Suppression list design](#suppression-list-design)
3. [Results: v0.14.0 Linux](#results-v0140-linux)
4. [Evolution: how we got here](#evolution-how-we-got-here)
5. [Token economics](#token-economics)
6. [OS coverage and roadmap](#os-coverage-and-roadmap)
7. [How to run and share results](#how-to-run-and-share-results)

---

## Methodology

### What the benchmark measures

The benchmark script (`~/.kcp/benchmark.sh`, source at `bin/benchmark.sh`) fires the kcp-commands hook with synthetic JSON payloads simulating Claude Code's PreToolUse hook input. For each of 33 test commands, it measures:

1. **Hook response time** -- wall-clock milliseconds from hook invocation to response.
2. **Output size** -- character count of the hook response (the `additionalContext` block).
3. **Approximate token count** -- `chars / 4` (conservative estimate; actual tokenizer ratios vary by content).
4. **Suppression correctness** -- whether the command was suppressed or manifested, compared to the expected status.

### How suppression is detected

Each test command has an expected status: `S` (suppressed) or `M` (manifested). The benchmark determines actual status by checking the hook response length:

- **Empty response (0 chars)** = SUPPRESSED -- the daemon returned 204 with no body.
- **Non-empty response** = MANIFEST -- the daemon returned a `[kcp]` context block.

### Install detection

Each command is checked for local installation via `command -v <base_command>`. Results are marked `[*]` (installed) or `[ ]` (not installed). This is informational only -- the hook works regardless of whether the command is installed locally, because it resolves manifests from the bundled library, not the local binary.

### Mismatch detection

A mismatch occurs when a command's actual status (SUPPRESSED or MANIFEST) differs from its expected status. Mismatches indicate a bug in the suppression list or manifest resolution. The benchmark fails loudly on mismatches, reporting the count and which commands were affected.

### Limitations

- **Synthetic benchmark, not a real session.** The benchmark sends crafted JSON to the hook; it does not run actual commands or measure real agent behavior.
- **Warm daemon only.** All latency numbers assume the Java daemon is already running. Cold start latency (~537ms, one-time per session) is not included in per-call measurements.
- **Token estimates are approximate.** The `chars / 4` ratio is a rough heuristic. Actual token counts depend on the tokenizer and content structure.
- **Single-machine results.** Hardware, OS, JVM version, and system load all affect latency. Results from different machines are not directly comparable without noting the environment.

---

## Suppression list design

### What it is

The suppression list is a set of base command names (e.g., `git`, `ls`, `grep`) that skip manifest lookup entirely. When the daemon receives a hook call for a suppressed command, it returns HTTP 204 immediately -- no manifest is injected, no tokens are added to the context window.

### Why it exists

Before suppression, every hook call went through manifest lookup. For well-known commands that capable agents already understand (git, ls, grep, curl, etc.), this added 80-250 tokens per call with near-zero information value. In a typical session where 60-80% of Bash calls are to well-known commands, this amounted to 5,000-8,000 tokens of wasted context.

### Categories

The default suppression list contains commands in eight categories:

| Category | Commands | Count |
|----------|----------|------:|
| Version control | `git`, `gh` | 2 |
| Text processing | `ls`, `cat`, `head`, `tail`, `grep`, `sed`, `awk`, `find`, `echo`, `printf`, `wc`, `sort`, `uniq`, `cut`, `tr`, `xargs` | 16 |
| Filesystem | `cd`, `pwd`, `mkdir`, `rm`, `rmdir`, `mv`, `cp`, `touch`, `chmod`, `chown`, `ln` | 11 |
| Network | `curl`, `wget`, `ssh`, `scp`, `rsync` | 5 |
| System | `ps`, `kill`, `top`, `df`, `du`, `uname` | 6 |
| Runtimes | `python3`, `python`, `node`, `java` | 4 |
| Shells | `bash`, `sh`, `zsh`, `fish`, `dash` | 5 |
| Shell builtins | `which`, `type`, `command`, `env`, `export`, `source`, `eval` | 7 |
| **Total** | | **56** |

### Why suppression is unconditional

kcp-commands ships YAML manifests for many suppressed commands (e.g., `git-log.yaml`, `ls.yaml`, `grep.yaml`). These manifests exist as reference material -- they document the command's flags and output schema, and can be used by other KCP consumers. But for the hook's context injection purpose, suppression always wins. If a manifest YAML exists for a suppressed command, the hook still returns 204.

This design avoids a subtle bug that existed in the initial v0.14.0 implementation (see [Evolution](#evolution-how-we-got-here) below).

### Customization via suppress.txt

Create `~/.kcp/suppress.txt` with one command per line (`#` comments supported). This **replaces** the default list entirely. To unsuppress a command (e.g., get docker manifests back), omit it from your custom list. To add a new suppression, add it to the file.

```bash
# ~/.kcp/suppress.txt -- custom example
# Keep git/ls/grep suppressed, add docker, remove curl
git
ls
grep
docker
```

---

## Results: v0.14.0 Linux

**Environment:** Linux 6.17.0-19-generic, Java 24.0.2, warm daemon on port 7734.

### Full results table

| # | Command | Time (ms) | Chars | Tokens | Status | Category | Installed | Check |
|--:|---------|----------:|------:|-------:|--------|----------|-----------|-------|
| 1 | `git log --oneline -5` | 26 | 0 | 0 | SUPPRESSED | vcs | yes | ok |
| 2 | `git status` | 28 | 0 | 0 | SUPPRESSED | vcs | yes | ok |
| 3 | `gh issue list` | 33 | 0 | 0 | SUPPRESSED | vcs | yes | ok |
| 4 | `gh pr list` | 26 | 0 | 0 | SUPPRESSED | vcs | yes | ok |
| 5 | `ls -la` | 31 | 0 | 0 | SUPPRESSED | coreutils | yes | ok |
| 6 | `grep -rn pattern .` | 31 | 0 | 0 | SUPPRESSED | coreutils | yes | ok |
| 7 | `cat README.md` | 33 | 0 | 0 | SUPPRESSED | coreutils | yes | ok |
| 8 | `echo hello` | 30 | 0 | 0 | SUPPRESSED | coreutils | yes | ok |
| 9 | `find . -name README.md` | 29 | 0 | 0 | SUPPRESSED | coreutils | yes | ok |
| 10 | `sed --version` | 30 | 0 | 0 | SUPPRESSED | coreutils | yes | ok |
| 11 | `awk --version` | 26 | 0 | 0 | SUPPRESSED | coreutils | yes | ok |
| 12 | `head -5 README.md` | 26 | 0 | 0 | SUPPRESSED | coreutils | yes | ok |
| 13 | `tail -5 README.md` | 28 | 0 | 0 | SUPPRESSED | coreutils | yes | ok |
| 14 | `wc -l README.md` | 40 | 0 | 0 | SUPPRESSED | coreutils | yes | ok |
| 15 | `curl --version` | 35 | 0 | 0 | SUPPRESSED | network | yes | ok |
| 16 | `ssh -V` | 41 | 0 | 0 | SUPPRESSED | network | yes | ok |
| 17 | `ps aux` | 26 | 0 | 0 | SUPPRESSED | system | yes | ok |
| 18 | `cp --help` | 29 | 0 | 0 | SUPPRESSED | filesystem | yes | ok |
| 19 | `mv --help` | 36 | 0 | 0 | SUPPRESSED | filesystem | yes | ok |
| 20 | `which git` | 39 | 0 | 0 | SUPPRESSED | builtins | yes | ok |
| 21 | `env` | 38 | 0 | 0 | SUPPRESSED | builtins | yes | ok |
| 22 | `python3 --version` | 29 | 0 | 0 | SUPPRESSED | runtimes | yes | ok |
| 23 | `node --version` | 29 | 0 | 0 | SUPPRESSED | runtimes | yes | ok |
| 24 | `bash --version` | 27 | 0 | 0 | SUPPRESSED | runtimes | yes | ok |
| 25 | `aws s3 ls` | 36 | 845 | 211 | MANIFEST | cloud | no | ok |
| 26 | `kubectl get pods` | 30 | 980 | 245 | MANIFEST | cloud | no | ok |
| 27 | `terraform plan` | 32 | 911 | 227 | MANIFEST | iac | no | ok |
| 28 | `helm list` | 34 | 828 | 207 | MANIFEST | iac | no | ok |
| 29 | `docker ps` | 31 | 915 | 228 | MANIFEST | containers | yes | ok |
| 30 | `docker images` | 34 | 873 | 218 | MANIFEST | containers | yes | ok |
| 31 | `mvn test` | 35 | 837 | 209 | MANIFEST | build | yes | ok |
| 32 | `npm install` | 35 | 786 | 196 | MANIFEST | build | yes | ok |
| 33 | `cargo build` | 30 | 684 | 171 | MANIFEST | build | no | ok |

### Summary statistics

| Metric | Value |
|--------|------:|
| Commands tested | 33 |
| Suppressed | 24 (72%) |
| Manifested | 9 (28%) |
| Not installed locally | 5 |
| Mismatches | 0 |

### Suppression by category

| Category | Tested | Suppressed | Manifested |
|----------|-------:|-----------:|-----------:|
| vcs | 4 | 4 | 0 |
| coreutils | 10 | 10 | 0 |
| network | 2 | 2 | 0 |
| system | 1 | 1 | 0 |
| filesystem | 2 | 2 | 0 |
| builtins | 2 | 2 | 0 |
| runtimes | 3 | 3 | 0 |
| cloud | 2 | 0 | 2 |
| iac | 2 | 0 | 2 |
| containers | 2 | 0 | 2 |
| build | 3 | 0 | 3 |

The split is clean: all well-known/POSIX-style commands are suppressed; all cloud, infrastructure, container, and build tool commands receive manifests. This matches the design intent -- agents need help with `kubectl get pods` flags but not with `ls -la`.

### Latency distribution

| Group | Min (ms) | Max (ms) | Mean (ms) | Range |
|-------|----------:|----------:|----------:|------:|
| Suppressed (24) | 26 | 41 | 30.5 | 15ms |
| Manifested (9) | 30 | 36 | 33.0 | 6ms |
| All (33) | 26 | 41 | 31.2 | 15ms |

Suppressed and manifested calls have nearly identical latency on a warm daemon. This is expected: both paths are in-memory operations. The suppression check is an O(1) hash set lookup; the manifest lookup is also an in-memory map read. The latency is dominated by HTTP round-trip overhead between `hook.sh` (curl) and the Java daemon, not by the lookup itself.

### Manifested command token sizes

| Command | Chars | Tokens |
|---------|------:|-------:|
| `kubectl get pods` | 980 | 245 |
| `docker ps` | 915 | 228 |
| `terraform plan` | 911 | 227 |
| `docker images` | 873 | 218 |
| `aws s3 ls` | 845 | 211 |
| `mvn test` | 837 | 209 |
| `helm list` | 828 | 207 |
| `npm install` | 786 | 196 |
| `cargo build` | 684 | 171 |
| **Average** | **851** | **212** |

These are the commands where manifests provide genuine value -- flag guidance for complex CLIs that agents do not have memorized. The average manifested command adds 212 tokens of context, which is a worthwhile investment for commands like `kubectl` or `terraform` where picking the wrong flags costs a full round-trip retry.

---

## Evolution: how we got here

### v0.13.0 baseline: 0% suppression

Before the suppression list existed, every Bash hook call went through manifest lookup. For commands with shipped manifests (git, ls, grep, etc.), the hook injected 80-250 tokens of context per call. These commands accounted for the majority of Bash calls in a typical session.

The problem: capable agents (Claude, GPT-4, etc.) already know how to use `git status`, `ls -la`, and `grep -rn`. Injecting syntax guidance for these commands consumed context window space with near-zero information gain.

### v0.14.0 initial: 18% suppression (bug present)

The suppression list was introduced with the correct set of commands. However, the `isSuppressed()` method checked whether a manifest YAML file existed for the command -- and most suppressed commands had shipped manifests (e.g., `git-log.yaml`, `ls.yaml`, `grep.yaml`). The method returned `false` for any command with a manifest, effectively defeating suppression for all but `ls` and `cat` (which happened to not have manifests at the time, or had a naming mismatch).

Result: only 2 of 11 benchmarked commands were actually suppressed (18%).

### v0.14.0 fixed: 63% suppression

The fix made suppression unconditional: if the base command is in the suppression set, return 204 immediately, regardless of whether a YAML manifest exists. Shipped manifests for suppressed commands are preserved as reference material but are never injected by the hook.

Result: 7 of 11 benchmarked commands suppressed (63%). The original benchmark used a smaller test battery of 11 commands.

### v0.14.0 + shells: 72% suppression

Shell commands (`bash`, `sh`, `zsh`, `fish`, `dash`) and `python` were added to the suppression list. The benchmark was extended from 11 to 33 commands, covering a broader set of categories including cloud, IaC, containers, and build tools.

Result: 24 of 33 commands suppressed (72%). The expanded test battery includes more manifested commands (cloud, build tools), which lowers the suppression percentage compared to a session that is mostly git/grep/ls calls.

### Root cause summary

| Version | Suppressed/Tested | Rate | Issue |
|---------|------------------:|-----:|-------|
| v0.13.0 | 0/any | 0% | No suppression list |
| v0.14.0 (bug) | 2/11 | 18% | `isSuppressed()` checked for manifest file existence |
| v0.14.0 (fixed) | 7/11 | 63% | Suppression made unconditional |
| v0.14.0 (+shells) | 24/33 | 72% | Added shells; extended benchmark |

---

## Token economics

### Per-call savings

When a command is suppressed, the hook injects 0 tokens instead of the manifest's context block. Based on the benchmark's manifested commands, the average manifest is 851 characters / 212 tokens.

Each suppressed call saves approximately 212 tokens of context window space.

### Session model

A typical agentic coding session (feature implementation, debugging, code review) involves roughly 50 Bash tool calls. Based on the 72% suppression rate:

| Parameter | Value |
|-----------|------:|
| Total Bash calls per session | 50 |
| Suppression rate | 72% |
| Calls suppressed | 36 |
| Average tokens per manifest | 212 |
| **Tokens saved per session** | **7,659** |

As a proportion of Claude's 200K context window, this is approximately 3.8%. While modest as a percentage, 7,659 tokens is equivalent to roughly 4 additional tool call results fitting in the context window before it fills up.

### Comparison to Phase A + B savings

Suppression savings (7,659 tokens/session) measure a different dimension than the Phase A + B savings reported in the README (67,352 tokens/session). The two are complementary:

- **Phase A + B savings** come from replacing verbose `--help` lookups with compact manifests (Phase A) and filtering noisy command output (Phase B). These apply to commands that *receive* manifests.
- **Suppression savings** come from not injecting manifests at all for commands that do not need them. These apply to commands that are *suppressed*.

The total context window recovery from both mechanisms combined depends on the session's command mix.

### Real-world validation

From a community experience report ([Cantara/kcp-commands#51](https://github.com/Cantara/kcp-commands/issues/51), Stig Lau, March 2026):

- **Session type:** TypeScript/AWS/SolidJS project, branch merges and issue fixes.
- **Git commands observed:** `git log`, `git diff`, `git cherry-pick`, `git merge`, `git branch`, `git fetch`, `git push`, `git checkout`, `git status` -- all fired hooks, accounting for approximately 80% of session calls.
- **Token overhead per call:** 80-150 tokens of manifest context with "near-zero information gain."
- **Estimated session overhead from git/gh hooks alone:** 5,000-8,000 tokens.
- **After v0.14.0:** All of these commands are suppressed. Zero tokens injected.

This real-world observation aligns with the benchmark's 72% suppression rate. In sessions dominated by version control operations, the effective suppression rate is even higher.

---

## OS coverage and roadmap

### Current: Linux validated

All benchmark results in this document were collected on Linux (6.17.0-19-generic, Java 24.0.2). The suppression list is platform-agnostic -- the same set of commands is suppressed on all operating systems.

### macOS: expected similar results

macOS shares most of the suppressed command set (git, ls, grep, curl, ssh, etc.). Platform-specific commands that should be considered for suppression:

- `brew` -- Homebrew package manager, well-known to agents
- `open` -- macOS file/URL opener
- `pbcopy` / `pbpaste` -- clipboard utilities

Expected suppression rate: similar to Linux (70-75%), depending on how many macOS-specific tools appear in the test battery.

### Windows: partial coverage

Windows-specific commands that should be considered for suppression:

- `winget` -- Windows Package Manager
- `choco` -- Chocolatey package manager
- `tasklist` / `taskkill` -- process management
- PowerShell cmdlets (`Get-Process`, `Get-ChildItem`, etc.) -- these use a different invocation pattern and may need special handling

Expected suppression rate: lower than Linux/macOS until PowerShell cmdlet suppression is implemented.

### Issue #7: platform-scoped suppression tiers

[Issue #7](https://github.com/Cantara/kcp-commands/issues/7) tracks the design of platform-scoped suppression tiers, where:

- **Tier 1 (universal):** Commands suppressed on all platforms (git, ls/dir, grep, curl).
- **Tier 2 (platform-specific):** Commands suppressed only on their native platform (brew on macOS, winget on Windows).
- **Tier 3 (user-scoped):** Commands suppressed via `~/.kcp/suppress.txt` customization.

---

## How to run and share results

### Run the benchmark

```bash
bash ~/.kcp/benchmark.sh
```

The script outputs a human-readable table followed by a JSON summary on the last line (prefixed with `JSON:`). The JSON contains all individual command results plus aggregate statistics.

If the daemon is not running, start it first:

```bash
nohup java -jar ~/.kcp/kcp-commands-daemon.jar > /tmp/kcp-commands-daemon.log 2>&1 &
sleep 1
bash ~/.kcp/benchmark.sh
```

### JSON output format

The JSON summary has the following structure:

```json
{
  "os": "Linux",
  "os_version": "6.17.0-19-generic",
  "java": "24.0.2",
  "daemon": true,
  "backend": "java",
  "total": 33,
  "suppressed": 24,
  "manifested": 9,
  "suppressed_pct": 72,
  "mismatches": 0,
  "not_installed": 5,
  "avg_manifest_chars": 851,
  "est_chars_saved": 20424,
  "est_tokens_saved": 5106,
  "est_session_tokens_saved": 7659,
  "results": [
    {
      "command": "git log --oneline -5",
      "time_ms": 26,
      "chars": 0,
      "tokens": 0,
      "status": "SUPPRESSED",
      "expected": "S",
      "category": "vcs",
      "installed": true,
      "match": true
    }
  ]
}
```

### Submit your results

We are collecting benchmark results from different environments (macOS, Windows, different JVM versions, different hardware) to validate cross-platform suppression behavior and establish latency baselines.

To contribute your results:

1. Run the benchmark: `bash ~/.kcp/benchmark.sh`
2. Copy the JSON output line.
3. Submit it to [Cantara/kcp-commands Discussions](https://github.com/Cantara/kcp-commands/discussions) or comment on the relevant issue with your JSON output and a brief note about your environment.

When submitting, please include:
- Operating system and version
- Java version
- Whether you use a custom `~/.kcp/suppress.txt`
- Any mismatches observed

---

## Appendix: previous benchmark data (v0.13.0 era)

The following data was collected before the suppression list existed. It measures a different dimension: **Phase A context injection vs. `--help` lookups** and **Phase B output filtering**. These savings still apply to manifested commands.

### Phase A -- context injection vs. `--help` lookup

| Command | `--help` output (tokens) | kcp-commands context (tokens) | Net saved |
|---------|-------------------------:|-----------------------------:|----------:|
| `ls` | 1,882 | 159 | +1,723 |
| `ps` | 41 | 169 | -128 |
| `git log` | 567 | 169 | +398 |
| `git diff` | 567 | 175 | +392 |
| `git status` | 567 | 124 | +443 |
| `find` | 527 | 159 | +368 |
| **Total (6 commands)** | **4,151** | **955** | **+3,196** |

Average saving: 532 tokens per avoided `--help` call.

Note: `ps --help` outputs only 41 tokens (an unusually terse help message). The kcp-commands context (169 tokens) is larger in this case, but provides `use_when` hints and preferred invocations that the raw `--help` does not.

### Phase B -- output noise filtering

| Command | Raw output (tokens) | Filtered output (tokens) | Reduction |
|---------|--------------------:|------------------------:|-----------:|
| `ps aux` | 30,828 | 652 | 97.9% |
| `find . -maxdepth 3` | 1,653 | 755 | 54.3% |
| `git status` | 60 | 43 | 28.3% |
| `git log -6` | 78 | 78 | 0.0% |

### Latency benchmarks (30 iterations, 3 warmup)

| Backend | Mean | Median | p95 | Min | Max | Std dev |
|---------|-----:|-------:|----:|----:|----:|--------:|
| Baseline (cat) | 2.3ms | 2.2ms | 3.1ms | 1.9ms | 3.8ms | 0.3ms |
| Node.js (per-call) | 265ms | 258ms | 312ms | 218ms | 340ms | 45ms |
| Java daemon (warm) | 14ms | 13ms | 17ms | 11ms | 21ms | 1.2ms |

Java daemon cold start: ~537ms (one-time). Break-even: 2 calls.

### Phase C -- event logging overhead

Phase C (writing to `~/.kcp/events.jsonl`) has zero measurable impact on latency. The write runs asynchronously on a virtual thread and never blocks the hook response. Disk cost: ~150-250 bytes per event, ~100 KB for a 500-call session.

---

*Last updated: 2026-03-22. Benchmark run on Linux 6.17.0-19-generic, Java 24.0.2, kcp-commands v0.14.0.*
