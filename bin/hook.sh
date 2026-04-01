#!/usr/bin/env bash
# kcp-commands hook client
# Registered as the Claude Code PreToolUse hook via ~/.claude/settings.json.
# Installed to ~/.kcp/hook.sh — tries backends in priority order:
#   1. Java daemon   (~12 ms/call, requires Java 21)
#   2. kcp-dashboard serve  (~2 ms/call, no JVM, requires kcp-dashboard binary)
#   3. Node.js cli.js      (~250 ms/call, stateless, no daemon)

KCP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT=7734
DAEMON_JAR="$KCP_DIR/kcp-commands-daemon.jar"
NODE_HOOK="$KCP_DIR/cli.js"
DAEMON_LOG="/tmp/kcp-commands-daemon.log"
DASHBOARD_BIN="$(command -v kcp-dashboard 2>/dev/null || echo "")"

# Read stdin (Claude Code hook JSON) once
HOOK_INPUT="$(cat)"

# ── Fast path: a daemon is already running on port 7734 ──────────────────────
if curl -sf "http://localhost:$PORT/health" > /dev/null 2>&1; then
    response=$(echo "$HOOK_INPUT" | curl -sf -X POST "http://localhost:$PORT/hook" \
        -H "Content-Type: application/json" \
        --data-binary @-)
    if [ -n "$response" ]; then
        echo "$response"
    fi
    exit 0
fi

# ── Helper: wait for daemon startup then call /hook ──────────────────────────
_await_and_call() {
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        sleep 0.5
        if curl -sf "http://localhost:$PORT/health" > /dev/null 2>&1; then
            response=$(echo "$HOOK_INPUT" | curl -sf -X POST "http://localhost:$PORT/hook" \
                -H "Content-Type: application/json" \
                --data-binary @-)
            if [ -n "$response" ]; then
                echo "$response"
            fi
            return 0
        fi
    done
    return 1
}

# ── Option 1: Java daemon ─────────────────────────────────────────────────────
if [ -f "$DAEMON_JAR" ]; then
    JAVA_BIN="java"
    if [ "$(uname)" = "Darwin" ] && command -v /usr/libexec/java_home > /dev/null 2>&1; then
        JAVA21="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
        if [ -n "$JAVA21" ]; then
            JAVA_BIN="$JAVA21/bin/java"
        fi
    fi
    if "$JAVA_BIN" -version > /dev/null 2>&1; then
        nohup "$JAVA_BIN" --enable-native-access=ALL-UNNAMED -jar "$DAEMON_JAR" > "$DAEMON_LOG" 2>&1 &
        if _await_and_call; then
            exit 0
        fi
        echo "[kcp] Java daemon startup timeout — trying next backend. Check $DAEMON_LOG" >&2
    fi
fi

# ── Option 2: kcp-dashboard serve (Go, no JVM) ───────────────────────────────
if [ -n "$DASHBOARD_BIN" ]; then
    nohup "$DASHBOARD_BIN" serve > /tmp/kcp-dashboard-serve.log 2>&1 &
    if _await_and_call; then
        exit 0
    fi
    echo "[kcp] kcp-dashboard serve startup timeout — trying Node.js fallback." >&2
fi

# ── Option 3: Node.js CLI (stateless, no daemon) ─────────────────────────────
if [ -f "$NODE_HOOK" ]; then
    echo "$HOOK_INPUT" | node "$NODE_HOOK"
    exit $?
fi

# Nothing available — pass through silently
exit 0
