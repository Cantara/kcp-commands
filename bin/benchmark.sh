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

# Format: "command|expected_status"
# expected_status: S = should be suppressed, M = should have manifest, ? = either
TEST_COMMANDS=(
    "git log --oneline -5|S"
    "git status|S"
    "gh issue list|S"
    "ls -la|S"
    "grep -rn pattern .|S"
    "cat README.md|S"
    "echo hello|S"
    "aws s3 ls|M"
    "kubectl get pods|M"
    "docker ps|M"
    "mvn test|M"
)

# Check if kcp-memory is available for testing
if command -v kcp-memory > /dev/null 2>&1; then
    TEST_COMMANDS+=("kcp-memory scan|M")
fi

# ── Header ─────────────────────────────────────────────────────────────────────

echo ""
echo "kcp-commands efficiency benchmark"
echo "=================================="
echo "System: $OS_NAME $OS_VERSION / Java $JAVA_VERSION / Daemon: $DAEMON_STATUS"
echo "Backend: $BACKEND"
echo ""
printf "%-40s %8s %6s %8s  %-10s\n" "Command" "Time(ms)" "Chars" "~Tokens" "Status"
echo "────────────────────────────────────────────────────────────────────────────────"

# ── Run tests ──────────────────────────────────────────────────────────────────

TOTAL=0
SUPPRESSED=0
TOTAL_SUPPRESSED_CHARS=0
TOTAL_MANIFEST_CHARS=0
MANIFEST_COUNT=0
JSON_RESULTS="["

run_hook() {
    local cmd="$1"
    local hook_input
    hook_input=$(printf '{"tool_name":"Bash","tool_input":{"command":"%s"},"session_id":"benchmark-test"}' "$cmd")

    local start end elapsed output output_len approx_tokens status

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

    printf "%-40s %8d %6d %8d  %-10s\n" "$cmd" "$elapsed" "$output_len" "$approx_tokens" "$status"

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
    json_entry=$(printf '{"command":"%s","time_ms":%d,"chars":%d,"tokens":%d,"status":"%s"}' \
        "$cmd" "$elapsed" "$output_len" "$approx_tokens" "$status")

    if [ "$TOTAL" -gt 1 ]; then
        JSON_RESULTS="$JSON_RESULTS,"
    fi
    JSON_RESULTS="$JSON_RESULTS$json_entry"
}

for entry in "${TEST_COMMANDS[@]}"; do
    cmd="${entry%%|*}"
    run_hook "$cmd"
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

echo "Avg manifest:     $(format_num $AVG_MANIFEST_CHARS) chars ($(format_num $((AVG_MANIFEST_CHARS / 4))) tokens)"
echo "Chars saved:      ~$(format_num $ESTIMATED_CHARS_SAVED) (from $SUPPRESSED suppressed commands)"
echo "Tokens saved:     ~$(format_num $ESTIMATED_TOKENS_SAVED) / session (est. 50 commands × $SUPPRESSED_PCT% suppressed)"

# Full session estimate: typical session has ~50 tool calls
SESSION_CALLS=50
SESSION_SUPPRESSED=$((SESSION_CALLS * SUPPRESSED_PCT / 100))
SESSION_TOKENS_SAVED=$((SESSION_SUPPRESSED * AVG_MANIFEST_CHARS / 4))
echo "Est. session:     ~$(format_num $SESSION_TOKENS_SAVED) tokens saved over $SESSION_CALLS calls"

# ── JSON output ────────────────────────────────────────────────────────────────

echo ""
JSON_SUMMARY=$(printf '{"os":"%s","os_version":"%s","java":"%s","daemon":%s,"backend":"%s","total":%d,"suppressed":%d,"suppressed_pct":%d,"avg_manifest_chars":%d,"est_chars_saved":%d,"est_tokens_saved":%d,"est_session_tokens_saved":%d,"results":%s}' \
    "$OS_NAME" "$OS_VERSION" "$JAVA_VERSION" \
    "$([ "$DAEMON_STATUS" != "not running" ] && echo 'true' || echo 'false')" \
    "$BACKEND" "$TOTAL" "$SUPPRESSED" "$SUPPRESSED_PCT" \
    "$AVG_MANIFEST_CHARS" "$ESTIMATED_CHARS_SAVED" "$ESTIMATED_TOKENS_SAVED" \
    "$SESSION_TOKENS_SAVED" "$JSON_RESULTS")

echo "JSON: $JSON_SUMMARY"
echo ""
