#!/usr/bin/env bash
# kcp-commands efficiency benchmark
# Measures hook response time, output size, and token savings for suppressed vs manifested commands.
# Works on Linux and macOS.
#
# Usage: ~/.kcp/benchmark.sh
#    or: /path/to/kcp-commands/bin/benchmark.sh

set -euo pipefail

# ── Platform-portable millisecond timer ────────────────────────────────────────

# Linux has nanosecond date; macOS does not. Fall back to python3.
if date +%s%N > /dev/null 2>&1 && [[ "$(date +%s%N)" != *N ]]; then
    now_ms() { echo $(( $(date +%s%N) / 1000000 )); }
else
    now_ms() { python3 -c "import time; print(int(time.time()*1000))"; }
fi

# ── System info ────────────────────────────────────────────────────────────────

OS_NAME="$(uname -s)"
OS_VERSION="$(uname -r)"
JAVA_VERSION="$(java -version 2>&1 | head -1 | sed 's/.*"\(.*\)".*/\1/' 2>/dev/null || echo 'not found')"
HOOK_SCRIPT="$HOME/.kcp/hook.sh"
PORT=7734

# Detect daemon status
DAEMON_STATUS="not running"
BACKEND="none"
if curl -sf "http://localhost:$PORT/health" > /dev/null 2>&1; then
    DAEMON_STATUS="running (port $PORT)"
    BACKEND="java"
elif [ -f "$HOME/.kcp/cli.js" ]; then
    BACKEND="node (fallback)"
fi

# ── Verify hook.sh exists ──────────────────────────────────────────────────────

if [ ! -f "$HOOK_SCRIPT" ]; then
    echo "ERROR: $HOOK_SCRIPT not found. Install kcp-commands first." >&2
    exit 1
fi

# ── Test battery ───────────────────────────────────────────────────────────────

# Format: "command|expected_status|category"
# expected_status: S = should be suppressed, M = should have manifest
# category: groups for summary reporting
TEST_COMMANDS=(
    # ── Suppressed: version control ──
    "git log --oneline -5|S|vcs"
    "git status|S|vcs"
    "gh issue list|S|vcs"
    "gh pr list|S|vcs"

    # ── Suppressed: text processing / coreutils ──
    "ls -la|S|coreutils"
    "grep -rn pattern .|S|coreutils"
    "cat README.md|S|coreutils"
    "echo hello|S|coreutils"
    "find . -name README.md|S|coreutils"
    "sed --version|S|coreutils"
    "awk --version|S|coreutils"
    "head -5 README.md|S|coreutils"
    "tail -5 README.md|S|coreutils"
    "wc -l README.md|S|coreutils"

    # ── Suppressed: network ──
    "curl --version|S|network"
    "ssh -V|S|network"

    # ── Suppressed: system ──
    "ps aux|S|system"

    # ── Suppressed: filesystem ──
    "cp --help|S|filesystem"
    "mv --help|S|filesystem"

    # ── Suppressed: shell builtins ──
    "which git|S|builtins"
    "env|S|builtins"

    # ── Suppressed: runtimes and shells ──
    "python3 --version|S|runtimes"
    "node --version|S|runtimes"
    "bash --version|S|runtimes"

    # ── Manifested: cloud / IaC ──
    "aws s3 ls|M|cloud"
    "kubectl get pods|M|cloud"
    "terraform plan|M|iac"
    "helm list|M|iac"

    # ── Manifested: containers ──
    "docker ps|M|containers"
    "docker images|M|containers"

    # ── Manifested: build / package ──
    "mvn test|M|build"
    "npm install|M|build"
    "cargo build|M|build"
)

# Check if kcp-memory is available for testing
if command -v kcp-memory > /dev/null 2>&1; then
    TEST_COMMANDS+=("kcp-memory scan|M|kcp")
fi

# ── Helper: check if the base command is installed locally ────────────────────

is_installed() {
    local cmd="$1"
    local base="${cmd%% *}"
    command -v "$base" > /dev/null 2>&1
}

# ── Header ─────────────────────────────────────────────────────────────────────

echo ""
echo "kcp-commands efficiency benchmark"
echo "=================================="
echo "System: $OS_NAME $OS_VERSION / Java $JAVA_VERSION / Daemon: $DAEMON_STATUS"
echo "Backend: $BACKEND"
echo "Commands: ${#TEST_COMMANDS[@]}"
echo ""
printf "%-40s %5s %8s %6s %8s  %-10s  %s\n" "Command" "Inst" "Time(ms)" "Chars" "~Tokens" "Status" "Check"
echo "──────────────────────────────────────────────────────────────────────────────────────────────────"

# ── Run tests ──────────────────────────────────────────────────────────────────

TOTAL=0
SUPPRESSED=0
TOTAL_SUPPRESSED_CHARS=0
TOTAL_MANIFEST_CHARS=0
MANIFEST_COUNT=0
MISMATCHES=0
NOT_INSTALLED=0
JSON_RESULTS="["

run_hook() {
    local cmd="$1"
    local expected="$2"
    local category="$3"
    local hook_input
    hook_input=$(printf '{"tool_name":"Bash","tool_input":{"command":"%s"},"session_id":"benchmark-test"}' "$cmd")

    local start end elapsed output output_len approx_tokens status installed check

    # Check local install
    if is_installed "$cmd"; then
        installed="[*]"
    else
        installed="[ ]"
        NOT_INSTALLED=$((NOT_INSTALLED + 1))
    fi

    start=$(now_ms)
    output=$(echo "$hook_input" | "$HOOK_SCRIPT" 2>/dev/null || true)
    end=$(now_ms)
    elapsed=$((end - start))

    output_len=${#output}
    approx_tokens=$((output_len / 4))

    if [ "$output_len" -eq 0 ]; then
        status="SUPPRESSED"
    else
        status="MANIFEST"
    fi

    # Verify expected vs actual
    check="ok"
    if [ "$expected" = "S" ] && [ "$status" != "SUPPRESSED" ]; then
        check="MISMATCH (expected suppressed)"
        MISMATCHES=$((MISMATCHES + 1))
    elif [ "$expected" = "M" ] && [ "$status" != "MANIFEST" ]; then
        check="MISMATCH (expected manifest)"
        MISMATCHES=$((MISMATCHES + 1))
    fi

    printf "%-40s %5s %8d %6d %8d  %-10s  %s\n" "$cmd" "$installed" "$elapsed" "$output_len" "$approx_tokens" "$status" "$check"

    # Accumulate stats
    TOTAL=$((TOTAL + 1))
    if [ "$status" = "SUPPRESSED" ]; then
        SUPPRESSED=$((SUPPRESSED + 1))
    else
        MANIFEST_COUNT=$((MANIFEST_COUNT + 1))
        TOTAL_MANIFEST_CHARS=$((TOTAL_MANIFEST_CHARS + output_len))
    fi

    # Build JSON result entry
    local json_entry
    json_entry=$(printf '{"command":"%s","time_ms":%d,"chars":%d,"tokens":%d,"status":"%s","expected":"%s","category":"%s","installed":%s,"match":%s}' \
        "$cmd" "$elapsed" "$output_len" "$approx_tokens" "$status" "$expected" "$category" \
        "$([ "$installed" = "[*]" ] && echo 'true' || echo 'false')" \
        "$([ "$check" = "ok" ] && echo 'true' || echo 'false')")

    if [ "$TOTAL" -gt 1 ]; then
        JSON_RESULTS="$JSON_RESULTS,"
    fi
    JSON_RESULTS="$JSON_RESULTS$json_entry"
}

for entry in "${TEST_COMMANDS[@]}"; do
    cmd="${entry%%|*}"
    rest="${entry#*|}"
    expected="${rest%%|*}"
    category="${rest#*|}"
    run_hook "$cmd" "$expected" "$category"
done

JSON_RESULTS="$JSON_RESULTS]"

# ── Summary ────────────────────────────────────────────────────────────────────

echo ""
echo "Summary"
echo "───────"
echo "Commands tested:  $TOTAL"

SUPPRESSED_PCT=0
if [ "$TOTAL" -gt 0 ]; then
    SUPPRESSED_PCT=$((SUPPRESSED * 100 / TOTAL))
fi
echo "Suppressed:       $SUPPRESSED ($SUPPRESSED_PCT%)"
echo "Manifested:       $MANIFEST_COUNT"
echo "Not installed:    $NOT_INSTALLED (hook works regardless -- tests daemon, not local binary)"

if [ "$MISMATCHES" -gt 0 ]; then
    echo "MISMATCHES:       $MISMATCHES (suppression behavior differs from expected)"
else
    echo "Mismatches:       0 (all commands behaved as expected)"
fi

# Estimate savings: if we had NOT suppressed, those commands would have produced
# roughly the same output as the average manifested command
AVG_MANIFEST_CHARS=0
if [ "$MANIFEST_COUNT" -gt 0 ]; then
    AVG_MANIFEST_CHARS=$((TOTAL_MANIFEST_CHARS / MANIFEST_COUNT))
fi

ESTIMATED_CHARS_SAVED=$((SUPPRESSED * AVG_MANIFEST_CHARS))
ESTIMATED_TOKENS_SAVED=$((ESTIMATED_CHARS_SAVED / 4))

# Format with comma separators (portable)
format_num() {
    printf "%'d" "$1" 2>/dev/null || printf "%d" "$1"
}

echo ""
echo "Token economics"
echo "───────────────"
echo "Avg manifest:     $(format_num $AVG_MANIFEST_CHARS) chars ($(format_num $((AVG_MANIFEST_CHARS / 4))) tokens)"
echo "Chars saved:      ~$(format_num $ESTIMATED_CHARS_SAVED) (from $SUPPRESSED suppressed commands)"
echo "Tokens saved:     ~$(format_num $ESTIMATED_TOKENS_SAVED) / session (est. 50 commands x $SUPPRESSED_PCT% suppressed)"

# Full session estimate: typical session has ~50 tool calls
SESSION_CALLS=50
SESSION_SUPPRESSED=$((SESSION_CALLS * SUPPRESSED_PCT / 100))
SESSION_TOKENS_SAVED=$((SESSION_SUPPRESSED * AVG_MANIFEST_CHARS / 4))
echo "Est. session:     ~$(format_num $SESSION_TOKENS_SAVED) tokens saved over $SESSION_CALLS calls"

# ── JSON output ────────────────────────────────────────────────────────────────

echo ""
JSON_SUMMARY=$(printf '{"os":"%s","os_version":"%s","java":"%s","daemon":%s,"backend":"%s","total":%d,"suppressed":%d,"manifested":%d,"suppressed_pct":%d,"mismatches":%d,"not_installed":%d,"avg_manifest_chars":%d,"est_chars_saved":%d,"est_tokens_saved":%d,"est_session_tokens_saved":%d,"results":%s}' \
    "$OS_NAME" "$OS_VERSION" "$JAVA_VERSION" \
    "$([ "$DAEMON_STATUS" != "not running" ] && echo 'true' || echo 'false')" \
    "$BACKEND" "$TOTAL" "$SUPPRESSED" "$MANIFEST_COUNT" "$SUPPRESSED_PCT" \
    "$MISMATCHES" "$NOT_INSTALLED" \
    "$AVG_MANIFEST_CHARS" "$ESTIMATED_CHARS_SAVED" "$ESTIMATED_TOKENS_SAVED" \
    "$SESSION_TOKENS_SAVED" "$JSON_RESULTS")

echo "JSON: $JSON_SUMMARY"
echo ""
