#!/usr/bin/env python3
"""
kcp-commands hook latency benchmark
Compares three approaches across multiple command inputs:
  1. Baseline  — pipe through cat (pure shell overhead, no processing)
  2. Node.js   — node dist/cli.js (new process per call)
  3. Java daemon (warm) — HTTP round-trip to running daemon

Usage:
  python3 benchmark.py [--iterations N] [--port PORT]
"""

import argparse
import json
import statistics
import subprocess
import sys
import time
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent

# ── Inputs ────────────────────────────────────────────────────────────────────

INPUTS = {
    "ps aux          (manifest + filter)": json.dumps({
        "tool_name": "Bash", "tool_input": {"command": "ps aux"}}),
    "git log         (manifest, no filter)": json.dumps({
        "tool_name": "Bash", "tool_input": {"command": "git log --oneline -10"}}),
    "ls -la          (manifest, pass-through filter)": json.dumps({
        "tool_name": "Bash", "tool_input": {"command": "ls -la"}}),
    "Read (pass-through — no manifest)": json.dumps({
        "tool_name": "Read", "tool_input": {"file_path": "/etc/hosts"}}),
    "unknown cmd     (auto-generate trigger)": json.dumps({
        "tool_name": "Bash", "tool_input": {"command": "df -h"}}),
}

# ── Runners ───────────────────────────────────────────────────────────────────

def run_baseline(payload: str) -> float:
    """Pipe through cat — pure OS + shell overhead, no processing."""
    start = time.perf_counter()
    proc = subprocess.run(
        ["cat"],
        input=payload.encode(),
        capture_output=True,
    )
    return (time.perf_counter() - start) * 1000

def run_nodejs(payload: str) -> float:
    cli = SCRIPT_DIR / "dist" / "cli.js"
    start = time.perf_counter()
    proc = subprocess.run(
        ["node", str(cli)],
        input=payload.encode(),
        capture_output=True,
    )
    return (time.perf_counter() - start) * 1000

def run_daemon(payload: str, port: int) -> float:
    start = time.perf_counter()
    proc = subprocess.run(
        ["curl", "-s", "-X", "POST",
         f"http://localhost:{port}/hook",
         "-H", "Content-Type: application/json",
         "--data-binary", "@-"],
        input=payload.encode(),
        capture_output=True,
    )
    return (time.perf_counter() - start) * 1000

# ── Statistics ────────────────────────────────────────────────────────────────

def compute_stats(times: list[float]) -> dict:
    s = sorted(times)
    n = len(s)
    return {
        "mean":   statistics.mean(s),
        "median": statistics.median(s),
        "p95":    s[min(int(n * 0.95), n - 1)],
        "min":    s[0],
        "max":    s[-1],
        "stdev":  statistics.stdev(s) if n > 1 else 0.0,
    }

def fmt(ms: float) -> str:
    return f"{ms:6.1f}ms"

def bar(ms: float, scale: float) -> str:
    blocks = int(ms / scale)
    return "█" * min(blocks, 40)

# ── Daemon management ─────────────────────────────────────────────────────────

def daemon_running(port: int) -> bool:
    r = subprocess.run(
        ["curl", "-sf", f"http://localhost:{port}/health"],
        capture_output=True, timeout=2,
    )
    return r.returncode == 0

def start_daemon(port: int) -> tuple[subprocess.Popen, float]:
    jar = SCRIPT_DIR / "java" / "target" / "kcp-commands-daemon.jar"
    if not jar.exists():
        return None, 0.0

    start = time.perf_counter()
    proc = subprocess.Popen(
        ["java", "-jar", str(jar), "--port", str(port)],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )

    # Wait for health check (up to 5s)
    deadline = time.perf_counter() + 5.0
    while time.perf_counter() < deadline:
        if daemon_running(port):
            cold_start_ms = (time.perf_counter() - start) * 1000
            return proc, cold_start_ms
        time.sleep(0.1)

    proc.kill()
    return None, 0.0

# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="kcp-commands benchmark")
    parser.add_argument("--iterations", "-n", type=int, default=30,
                        help="Iterations per approach per input (default: 30)")
    parser.add_argument("--port", "-p", type=int, default=7736,
                        help="Daemon port (default: 7736, avoids conflict with 7734)")
    parser.add_argument("--warmup", type=int, default=3,
                        help="Warmup iterations discarded before timing (default: 3)")
    args = parser.parse_args()

    N = args.iterations
    PORT = args.port
    WARMUP = args.warmup

    print("=" * 70)
    print("kcp-commands hook latency benchmark")
    print(f"Iterations: {N} per approach  |  Warmup: {WARMUP}  |  Port: {PORT}")
    print("=" * 70)

    # ── Verify Node.js build exists ───────────────────────────────────────────
    cli = SCRIPT_DIR / "dist" / "cli.js"
    if not cli.exists():
        print("ERROR: dist/cli.js not found. Run: npm run build")
        sys.exit(1)

    # ── Start Java daemon ─────────────────────────────────────────────────────
    daemon_proc = None
    cold_start_ms = None

    if daemon_running(PORT):
        print(f"\n✓ Java daemon already running on port {PORT}")
    else:
        jar = SCRIPT_DIR / "java" / "target" / "kcp-commands-daemon.jar"
        if jar.exists():
            print(f"\n→ Starting Java daemon on port {PORT}...", end="", flush=True)
            daemon_proc, cold_start_ms = start_daemon(PORT)
            if daemon_proc:
                print(f" ready in {cold_start_ms:.0f}ms")
            else:
                print(" FAILED to start (will skip daemon benchmarks)")
        else:
            print(f"\n⚠ Java JAR not found — skipping daemon benchmark")
            print("  Run: mvn -f java/pom.xml package -DskipTests")

    daemon_available = daemon_running(PORT)

    # ── Warmup Node.js ────────────────────────────────────────────────────────
    print("\n→ Warming up Node.js...", end="", flush=True)
    sample = list(INPUTS.values())[0]
    for _ in range(WARMUP):
        run_nodejs(sample)
    print(" done")

    if daemon_available:
        print("→ Warming up daemon...", end="", flush=True)
        for _ in range(WARMUP):
            run_daemon(sample, PORT)
        print(" done")

    # ── Run benchmarks ────────────────────────────────────────────────────────
    results = {}  # input_label -> {approach -> stats}

    for label, payload in INPUTS.items():
        print(f"\n{'─' * 70}")
        print(f"Input: {label}")
        print(f"{'─' * 70}")

        row = {}

        # Baseline
        times = [run_baseline(payload) for _ in range(N)]
        row["baseline"] = compute_stats(times)

        # Node.js
        times = [run_nodejs(payload) for _ in range(N)]
        row["nodejs"] = compute_stats(times)

        # Java daemon
        if daemon_available:
            times = [run_daemon(payload, PORT) for _ in range(N)]
            row["daemon"] = compute_stats(times)

        results[label] = row

        # Print per-input results
        scale = max(
            row["nodejs"]["mean"],
            row.get("daemon", {}).get("mean", 0),
            1,
        ) / 30  # scale bar to 30 chars max

        def print_row(name, st):
            overhead = st["mean"] - row["baseline"]["mean"]
            print(f"  {name:<12} mean={fmt(st['mean'])}  "
                  f"p95={fmt(st['p95'])}  min={fmt(st['min'])}  "
                  f"max={fmt(st['max'])}  σ={fmt(st['stdev'])}")
            print(f"               overhead +{fmt(overhead)}  {bar(st['mean'], scale)}")

        print_row("baseline", row["baseline"])
        print_row("node.js", row["nodejs"])
        if "daemon" in row:
            print_row("daemon", row["daemon"])
            speedup = row["nodejs"]["mean"] / row["daemon"]["mean"]
            print(f"               → daemon is {speedup:.1f}x faster than Node.js")

    # ── Summary table ─────────────────────────────────────────────────────────
    print(f"\n{'=' * 70}")
    print("SUMMARY — mean latency per approach (ms)")
    print(f"{'=' * 70}")
    print(f"  {'Input':<42} {'baseline':>10} {'node.js':>10}", end="")
    if daemon_available:
        print(f" {'daemon':>10} {'speedup':>8}", end="")
    print()
    print(f"  {'-'*42} {'-'*10} {'-'*10}", end="")
    if daemon_available:
        print(f" {'-'*10} {'-'*8}", end="")
    print()

    for label, row in results.items():
        short = label[:42]
        base = row["baseline"]["mean"]
        node = row["nodejs"]["mean"]
        print(f"  {short:<42} {fmt(base)} {fmt(node)}", end="")
        if "daemon" in row:
            d = row["daemon"]["mean"]
            speedup = node / d
            print(f" {fmt(d)} {speedup:>7.1f}x", end="")
        print()

    if cold_start_ms is not None:
        print(f"\n  Java daemon cold start (one-time): {cold_start_ms:.0f}ms")
        warm_mean = statistics.mean([
            row["daemon"]["mean"]
            for row in results.values()
            if "daemon" in row
        ])
        breakeven = cold_start_ms / (results[list(results.keys())[0]]["nodejs"]["mean"] - warm_mean)
        print(f"  Break-even: daemon pays off after {breakeven:.0f} hook calls in a session")

    print(f"\n  Iterations: {N}  |  Warmup discarded: {WARMUP}")

    # ── Cleanup ───────────────────────────────────────────────────────────────
    if daemon_proc:
        daemon_proc.terminate()
        print(f"  Daemon stopped.")

    print()

if __name__ == "__main__":
    main()
