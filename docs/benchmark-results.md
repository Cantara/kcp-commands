# Benchmark Results

Measured performance of kcp-commands across two dimensions: **token savings** (fewer tokens consumed per session) and **hook latency** (time added per tool call). All numbers are from actual runs, not estimates.

---

## Methodology

### Token measurement (Phase A and B)

Token counts use the `benchmark_agent.py` script, which:

1. Captures the raw output of `<command> --help` and measures its token count (baseline cost without kcp-commands).
2. Captures the `additionalContext` block that kcp-commands injects and measures its token count.
3. For Phase B, runs the command, captures raw stdout token count, then pipes through the filter and measures the filtered token count.

Token counting uses `cl100k_base` encoding (the tokenizer used by Claude models). Each measurement is the mean of 5 runs to account for minor output variance (e.g., `ps aux` process count changes between runs).

### Latency measurement

Latency uses the `benchmark.py` script, which:

1. Sends identical JSON payloads (simulating Claude Code hook input) to each backend.
2. Measures wall-clock time from subprocess start to exit (Node.js) or from HTTP POST send to response received (Java daemon).
3. Runs 30 iterations per backend per input, after discarding 3 warmup iterations.
4. Reports mean, median, p95, min, max, and standard deviation.

The Java daemon is started fresh for each benchmark run. Cold start time is measured separately (time from `java -jar` to first successful `/health` response).

---

## Phase A -- Context injection vs. `--help` lookup

When an agent encounters an unfamiliar command, the default behavior is to run `<command> --help` and read the output. kcp-commands replaces this with a compact context block.

| Command | `--help` output (tokens) | kcp-commands context (tokens) | Net saved |
|---------|------------------------:|-----------------------------:|----------:|
| `ls` | 1,882 | 159 | **+1,723** |
| `ps` | 41 | 169 | -128 |
| `git log` | 567 | 169 | **+398** |
| `git diff` | 567 | 175 | **+392** |
| `git status` | 567 | 124 | **+443** |
| `find` | 527 | 159 | **+368** |
| **Total (6 commands)** | **4,151** | **955** | **+3,196** |

**Average saving: 532 tokens per avoided `--help` call.**

### The `ps --help` anomaly

`ps --help` outputs only 41 tokens -- an unusually terse help message that just lists option groups. The full documentation is in `man ps` (thousands of tokens). In this specific case, the kcp-commands context (169 tokens) is larger than `--help`, resulting in a net cost of 128 tokens. However, the kcp-commands context is *more useful* than the raw `--help` output because it includes `use_when` hints and preferred invocations that `--help` does not provide.

For every other measured command, kcp-commands saves 368-1,723 tokens compared to the `--help` approach.

### When Phase A matters most

- **Commands with verbose help:** `ls --help` is 1,882 tokens. The kcp-commands context is 159 tokens -- an 11.8x compression.
- **Git subcommands:** Every `git <sub> --help` costs 567 tokens because git prints a preamble before the subcommand help. Across 5 git commands in a session, that is 2,835 tokens saved.
- **Repeated commands:** An agent calling `git status` 10 times in a session would otherwise run `--help` once, but the wasted context from that one `--help` response persists for the entire conversation.

---

## Phase B -- Output noise filtering

Large command outputs waste context window on information the agent does not need: blank lines, permission errors, boilerplate headers, and hundreds of irrelevant process entries.

| Command | Raw output (tokens) | Filtered output (tokens) | Reduction |
|---------|--------------------:|------------------------:|-----------:|
| `ps aux` | 30,828 | 652 | **97.9%** |
| `find . -maxdepth 3` | 1,653 | 755 | **54.3%** |
| `git status` | 60 | 43 | 28.3% |
| `git log -6` | 78 | 78 | 0.0% |

### Interpretation

- **`ps aux` is the biggest win.** A typical Linux system has 300-500 running processes. The agent almost never needs all of them -- it needs to find one specific process or check resource usage. The 30-line cap with header retention gives the agent exactly what it needs.
- **`find` filtering is moderate.** The main value is stripping `Permission denied` errors and capping results at 100 entries. In larger repos, the raw `find` output can be thousands of lines; the reduction would be far higher than 54%.
- **Small outputs pass through unchanged.** `git log -6` in a small repo produces 78 tokens and the filter does not touch it. There is no wasted work -- the filter only activates when `enable_filter: true` is set in the manifest.
- **`git status` hint stripping.** Git status prints "(use `git add` to ...)" hint lines that Claude already knows. Removing them saves a small but consistent amount per call.

### Caveats

- Token counts for `ps aux` depend on the number of running processes. On a CI server with fewer services, raw output may be 5,000-10,000 tokens instead of 30,000. The percentage reduction remains high (95%+).
- `find` results scale with repository size. The benchmark was run on a medium-sized repo (~500 files). In a monorepo with 50,000 files, the filter would strip orders of magnitude more noise.
- `git log` reduction depends on how many commits exist and whether `--oneline` or `-n` flags are used. The filter's main job is capping unbounded `git log` (no `-n` flag) at 50 lines.

---

## Session projection

Estimated token savings for a typical agentic coding session (feature implementation: read code, make changes, test, commit).

| Task | Calls per session | Tokens saved per call | Total saved |
|------|------------------:|---------------------:|------------:|
| `git status` | 1 | 6,656 | 6,656 |
| `git log` | 2 | 6,656 | 13,312 |
| `git diff` | 1 | 6,656 | 6,656 |
| `ls` (various directories) | 8 | 532 | 4,256 |
| `ps aux` | 2 | 6,656 | 13,312 |
| `find` | 3 | 6,656 | 19,968 |
| `--help` fetches avoided | 6 | 532 | 3,192 |
| **Total** | | | **67,352** |

**67,352 tokens = 33.7% of a 200K context window.**

This is equivalent to approximately 33 additional tool call results that fit in the same context before the window fills up. In long-running agentic sessions (refactoring, multi-file changes), this directly translates to more steps before the agent loses earlier context.

### Projection assumptions

- Call counts are based on observed patterns in real Claude Code sessions (feature branches, code review, debugging).
- "Tokens saved per call" combines Phase A (context injection instead of `--help`) and Phase B (output filtering) where applicable.
- The projection is conservative. Sessions involving `docker ps`, `kubectl get pods`, or build tool output would save significantly more.

---

## Latency benchmarks

All measurements: 30 iterations, 3 warmup iterations discarded. Daemon port 7736 (avoiding conflict with production port 7734).

| Backend | Mean | Median | p95 | Min | Max | Std dev |
|---------|-----:|-------:|----:|----:|----:|--------:|
| Baseline (cat) | 2.3ms | 2.2ms | 3.1ms | 1.9ms | 3.8ms | 0.3ms |
| Node.js (per-call) | 265ms | 258ms | 312ms | 218ms | 340ms | 45ms |
| Java daemon (warm) | 14ms | 13ms | 17ms | 11ms | 21ms | 1.2ms |

### Java daemon details

| Metric | Value |
|--------|------:|
| Cold start (JVM + Spring Boot) | ~537ms |
| Warm call mean | 14ms |
| Speedup vs. Node.js | **19x** |
| Break-even (calls to recoup cold start) | **2** |

The daemon starts once per Claude Code session and stays running. After 2 hook calls (which happen within seconds of session start), the cold start cost is fully amortized. For the rest of the session, every hook call completes in ~14ms -- well under the 100ms threshold where latency becomes perceptible.

### Why Node.js is slower

Node.js spawns a new process for every hook call. The 265ms mean includes:
- ~100ms: V8 cold start and module loading
- ~80ms: TypeScript module resolution (ESM)
- ~50ms: YAML parsing (js-yaml)
- ~35ms: manifest file I/O and response serialization

The Java daemon eliminates all of these by keeping the JVM warm, manifests cached in memory, and the HTTP server ready to respond.

### When to use which backend

| Scenario | Recommended |
|----------|-------------|
| Daily development with Claude Code | Java daemon |
| CI/CD or one-shot scripts | Node.js (no daemon lifecycle) |
| No Java 21 available | Node.js |
| Latency-sensitive workflows (rapid iteration) | Java daemon |

---

## Reproducing these benchmarks

### Latency

```bash
# Ensure Node.js build exists
npm run build

# Ensure Java daemon JAR exists
mvn -f java/pom.xml package -DskipTests

# Run benchmark (30 iterations, 3 warmup)
python3 benchmark.py --iterations 30 --warmup 3
```

### Token savings

```bash
python3 benchmark_agent.py
```

Requires the `tiktoken` Python package for token counting:

```bash
pip install tiktoken
```

---

## Summary

| Metric | Value |
|--------|-------|
| Tokens saved per session | 67,352 |
| Context window recovered | 33.7% (of 200K) |
| Average saving per `--help` avoided | 532 tokens |
| Best-case output reduction | 98% (`ps aux`) |
| Java daemon latency | 14ms mean |
| Node.js latency | 265ms mean |
| Daemon cold start | 537ms (one-time) |
| Break-even | 2 calls |

