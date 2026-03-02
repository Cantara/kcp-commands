#!/usr/bin/env python3
"""
kcp-commands agent benchmark — real context window impact

Measures what actually matters for AI sessions:

  Phase A: Command syntax context injection
    - How big is the additionalContext we inject?
    - How big is `cmd --help` output that the agent would have fetched instead?
    - Net: context added vs. tool call + output avoided

  Phase B: Output noise filtering
    - Run real commands with realistic arguments
    - Measure raw vs filtered: lines, chars, estimated tokens
    - Show what actually reaches Claude's context window

  Session projection:
    - Typical agentic session: N bash calls
    - Total context saved across the session

Token estimate: 1 token ≈ 4 chars (GPT/Claude tokeniser approximation)

Usage:
  python3 benchmark_agent.py [--port PORT]
"""

import argparse
import json
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent
TOKENS_PER_CHAR = 0.25  # 1 token ≈ 4 chars

# ── Data classes ──────────────────────────────────────────────────────────────

@dataclass
class OutputStats:
    label: str
    command: str
    raw_lines: int
    raw_chars: int
    filtered_lines: int
    filtered_chars: int

    @property
    def lines_removed(self): return self.raw_lines - self.filtered_lines
    @property
    def chars_removed(self): return self.raw_chars - self.filtered_chars
    @property
    def raw_tokens(self): return int(self.raw_chars * TOKENS_PER_CHAR)
    @property
    def filtered_tokens(self): return int(self.filtered_chars * TOKENS_PER_CHAR)
    @property
    def tokens_saved(self): return self.raw_tokens - self.filtered_tokens
    @property
    def reduction_pct(self):
        if self.raw_chars == 0: return 0.0
        return (1 - self.filtered_chars / self.raw_chars) * 100

@dataclass
class ContextStats:
    command: str
    help_chars: int
    context_chars: int

    @property
    def help_tokens(self): return int(self.help_chars * TOKENS_PER_CHAR)
    @property
    def context_tokens(self): return int(self.context_chars * TOKENS_PER_CHAR)
    @property
    def tokens_avoided(self): return self.help_tokens - self.context_tokens

# ── Helpers ───────────────────────────────────────────────────────────────────

def run_command(cmd: str, timeout: int = 10) -> str:
    """Run a shell command and return stdout (best-effort)."""
    try:
        result = subprocess.run(
            cmd, shell=True, capture_output=True,
            text=True, timeout=timeout,
        )
        return result.stdout + result.stderr
    except subprocess.TimeoutExpired:
        return ""
    except Exception:
        return ""

def get_hook_response(payload: str, port: int) -> dict:
    """Send a hook request to the daemon, return parsed JSON."""
    try:
        result = subprocess.run(
            ["curl", "-s", "-X", "POST",
             f"http://localhost:{port}/hook",
             "-H", "Content-Type: application/json",
             "--data-binary", "@-"],
            input=payload.encode(),
            capture_output=True,
            timeout=5,
        )
        return json.loads(result.stdout) if result.stdout else {}
    except Exception:
        return {}

def get_node_response(payload: str) -> dict:
    cli = SCRIPT_DIR / "dist" / "cli.js"
    try:
        result = subprocess.run(
            ["node", str(cli)],
            input=payload.encode(),
            capture_output=True,
            timeout=10,
        )
        return json.loads(result.stdout) if result.stdout else {}
    except Exception:
        return {}

def filter_output(raw: str, key: str, port: int) -> str:
    """Pipe raw output through the daemon's filter endpoint."""
    # We use the Node.js filter directly (it's the same filter logic)
    cli = SCRIPT_DIR / "dist" / "cli.js"
    try:
        result = subprocess.run(
            ["node", str(cli), "filter", key],
            input=raw.encode(),
            capture_output=True,
            timeout=5,
        )
        return result.stdout.decode()
    except Exception:
        return raw

def get_help_output(cmd: str) -> str:
    """Get --help output for a command (what the agent would fetch)."""
    out = run_command(f"{cmd} --help 2>&1", timeout=5)
    if not out:
        out = run_command(f"man {cmd} 2>/dev/null | head -60", timeout=5)
    return out

def daemon_running(port: int) -> bool:
    r = subprocess.run(
        ["curl", "-sf", f"http://localhost:{port}/health"],
        capture_output=True, timeout=2,
    )
    return r.returncode == 0

def start_daemon(port: int):
    jar = SCRIPT_DIR / "java" / "target" / "kcp-commands-daemon.jar"
    if not jar.exists():
        return None
    proc = subprocess.Popen(
        ["java", "-jar", str(jar), "--port", str(port)],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    deadline = time.perf_counter() + 8.0
    while time.perf_counter() < deadline:
        if daemon_running(port):
            return proc
        time.sleep(0.15)
    proc.kill()
    return None

def tok(n: int) -> str:
    return f"{n:,} tok"

def pct(p: float) -> str:
    return f"{p:.0f}%"

def bar(n: int, max_n: int, width: int = 30) -> str:
    if max_n == 0: return ""
    filled = int(n / max_n * width)
    return "█" * filled + "░" * (width - filled)

# ── Phase B: Output filtering ─────────────────────────────────────────────────

# Commands to test with realistic arguments used by AI agents
PHASE_B_CASES = [
    ("ps",        "ps aux",                   "ps"),
    ("ps grep",   "ps aux | grep -v grep",    "ps"),
    ("git-log",   "git log --oneline -100",   "git-log"),
    ("git-log 20","git log --oneline -20",    "git-log"),
    ("git-status","git status",               "git-status"),
    ("git-diff",  "git diff --stat",          "git-diff"),
    ("find src",  "find . -type f -name '*.java' 2>/dev/null | head -200", "find"),
    ("find all",  "find . -maxdepth 3 -type f 2>/dev/null | head -200",    "find"),
    ("ls root",   "ls -la /",                 "ls"),
    ("ls src",    "ls -la /src/cantara/kcp-commands/", "ls"),
]

# ── Phase A: Context injection vs --help ──────────────────────────────────────

PHASE_A_CASES = [
    ("ls",          "ls",          "Bash", "ls -la"),
    ("ps",          "ps",          "Bash", "ps aux"),
    ("git log",     "git",         "Bash", "git log --oneline -10"),
    ("git diff",    "git",         "Bash", "git diff --stat"),
    ("git status",  "git",         "Bash", "git status"),
    ("find",        "find",        "Bash", "find . -name '*.yaml'"),
]

# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="kcp-commands agent benchmark")
    parser.add_argument("--port", "-p", type=int, default=7736)
    args = parser.parse_args()
    PORT = args.port

    print("=" * 70)
    print("kcp-commands — agent context window benchmark")
    print("=" * 70)

    # Start daemon
    daemon_proc = None
    if not daemon_running(PORT):
        jar = SCRIPT_DIR / "java" / "target" / "kcp-commands-daemon.jar"
        if jar.exists():
            print(f"\n→ Starting daemon on port {PORT}...", end="", flush=True)
            daemon_proc = start_daemon(PORT)
            print(" ready" if daemon_proc else " FAILED")
        else:
            print("\n⚠ No daemon JAR — Phase A uses Node.js fallback")

    # ═══════════════════════════════════════════════════════════════════════════
    print(f"\n{'═' * 70}")
    print("PHASE A — Command syntax context vs. --help lookup")
    print("When the agent doesn't know which flags to use, it fetches --help.")
    print("kcp-commands injects a concise additionalContext instead.")
    print(f"{'═' * 70}\n")

    phase_a_results = []
    for label, base_cmd, tool, cmd_str in PHASE_A_CASES:
        payload = json.dumps({"tool_name": tool, "tool_input": {"command": cmd_str}})

        # Get additionalContext from daemon (or Node.js)
        if daemon_running(PORT):
            resp = get_hook_response(payload, PORT)
        else:
            resp = get_node_response(payload)

        ctx = resp.get("hookSpecificOutput", {}).get("additionalContext", "")
        help_text = get_help_output(base_cmd)

        stats = ContextStats(
            command=label,
            help_chars=len(help_text),
            context_chars=len(ctx),
        )
        phase_a_results.append(stats)

        saved_sign = "+" if stats.tokens_avoided > 0 else ""
        print(f"  {label:<12}  --help: {tok(stats.help_tokens):>12}  "
              f"context: {tok(stats.context_tokens):>10}  "
              f"net saved: {saved_sign}{tok(stats.tokens_avoided):>10}")

    total_help = sum(s.help_tokens for s in phase_a_results)
    total_ctx  = sum(s.context_tokens for s in phase_a_results)
    total_saved_a = total_help - total_ctx
    print(f"\n  {'TOTAL':<12}  --help: {tok(total_help):>12}  "
          f"context: {tok(total_ctx):>10}  "
          f"net saved: +{tok(total_saved_a):>10}")
    print(f"\n  → Each --help fetch costs {int(total_help/len(phase_a_results)):,} tokens on average.")
    print(f"    kcp-commands replaces it with {int(total_ctx/len(phase_a_results)):,} tokens of targeted context.")
    print(f"    Net saving per avoided --help: +{int(total_saved_a/len(phase_a_results)):,} tokens")

    # ═══════════════════════════════════════════════════════════════════════════
    print(f"\n{'═' * 70}")
    print("PHASE B — Output noise filtering")
    print("Large command outputs are truncated/filtered before reaching Claude.")
    print(f"{'═' * 70}\n")

    phase_b_results = []
    max_raw = 0

    for label, cmd, manifest_key in PHASE_B_CASES:
        raw = run_command(cmd)
        filtered = filter_output(raw, manifest_key, PORT)

        stats = OutputStats(
            label=label,
            command=cmd,
            raw_lines=len(raw.splitlines()),
            raw_chars=len(raw),
            filtered_lines=len(filtered.splitlines()),
            filtered_chars=len(filtered),
        )
        phase_b_results.append(stats)
        max_raw = max(max_raw, stats.raw_tokens)

    # Print table
    print(f"  {'Command':<14} {'raw lines':>10} {'raw tok':>9} {'filt tok':>9} "
          f"{'saved':>9} {'reduc':>7}  bar (raw → filtered)")
    print(f"  {'-'*14} {'-'*10} {'-'*9} {'-'*9} {'-'*9} {'-'*7}  {'-'*32}")

    for s in phase_b_results:
        raw_bar  = bar(s.raw_tokens, max_raw, 16)
        filt_bar = bar(s.filtered_tokens, max_raw, 16)
        print(f"  {s.label:<14} {s.raw_lines:>10,} {tok(s.raw_tokens):>9} "
              f"{tok(s.filtered_tokens):>9} {tok(s.tokens_saved):>9} "
              f"{pct(s.reduction_pct):>7}  {raw_bar}→{filt_bar}")

    total_raw_tok  = sum(s.raw_tokens for s in phase_b_results)
    total_filt_tok = sum(s.filtered_tokens for s in phase_b_results)
    total_saved_b  = total_raw_tok - total_filt_tok
    avg_reduction  = (1 - total_filt_tok / total_raw_tok) * 100 if total_raw_tok else 0

    print(f"\n  {'TOTAL':<14} {sum(s.raw_lines for s in phase_b_results):>10,} "
          f"{tok(total_raw_tok):>9} {tok(total_filt_tok):>9} "
          f"{tok(total_saved_b):>9} {pct(avg_reduction):>7}")

    # ═══════════════════════════════════════════════════════════════════════════
    print(f"\n{'═' * 70}")
    print("SESSION PROJECTION — typical agentic coding session")
    print(f"{'═' * 70}\n")

    # Typical session: developer asking Claude to investigate a codebase
    SESSION = [
        ("git status",   1, "phase_a_and_b"),
        ("git log",      2, "phase_a_and_b"),
        ("git diff",     1, "phase_a_and_b"),
        ("ls (various)", 8, "phase_a"),
        ("ps aux",       2, "phase_a_and_b"),
        ("find",         3, "phase_a_and_b"),
        ("--help fetches avoided", 6, "phase_a"),
    ]

    avg_help_saved  = int(total_saved_a / len(phase_a_results))
    avg_output_saved = int(total_saved_b / len(phase_b_results))

    print(f"  {'Task':<35} {'calls':>6} {'tokens saved':>14}")
    print(f"  {'-'*35} {'-'*6} {'-'*14}")

    total_session_saved = 0
    for task, calls, kind in SESSION:
        if kind == "phase_a":
            saved = calls * avg_help_saved
        elif kind == "phase_a_and_b":
            saved = calls * (avg_help_saved + avg_output_saved)
        else:
            saved = 0
        total_session_saved += saved
        print(f"  {task:<35} {calls:>6}   +{tok(saved):>12}")

    print(f"\n  {'TOTAL saved per session':<35}        +{tok(total_session_saved):>12}")

    # Context window impact
    context_200k = 200_000
    pct_saved = total_session_saved / context_200k * 100
    extra_turns = total_session_saved // 2000  # rough: 2K tokens ≈ 1 extra turn of headroom
    print(f"\n  Claude context window:   200,000 tokens")
    print(f"  Tokens saved per session: {tok(total_session_saved)} ({pct_saved:.1f}% of window)")
    print(f"  Headroom gained:          ~{extra_turns} additional tool call results fit in context")

    # Cleanup
    if daemon_proc:
        daemon_proc.terminate()

    print()

if __name__ == "__main__":
    main()
