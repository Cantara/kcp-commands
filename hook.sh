#!/usr/bin/env bash
# kcp-commands hook client
# Thin shell script registered as the Claude Code PreToolUse hook.
# Talks to the Java daemon (fast path) or falls back to Node.js (slow path).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT=7734
DAEMON_JAR="$SCRIPT_DIR/java/target/kcp-commands-daemon.jar"
NODE_HOOK="$SCRIPT_DIR/dist/cli.js"
DAEMON_LOG="/tmp/kcp-commands-daemon.log"

# Read stdin (Claude Code hook JSON) once
HOOK_INPUT="$(cat)"

# ── Fast path: daemon already running ────────────────────────────────────────
if curl -sf "http://localhost:$PORT/health" > /dev/null 2>&1; then
    response=$(echo "$HOOK_INPUT" | curl -sf -X POST "http://localhost:$PORT/hook" \
        -H "Content-Type: application/json" \
        --data-binary @-)
    if [ -n "$response" ]; then
        echo "$response"
    fi
    exit 0
fi

# ── Daemon not running: try to start it ──────────────────────────────────────
if [ -f "$DAEMON_JAR" ]; then
    nohup java -jar "$DAEMON_JAR" > "$DAEMON_LOG" 2>&1 &

    # Wait up to 3s for startup (JVM cold start is ~200-500ms)
    for _ in 1 2 3 4 5 6; do
        sleep 0.5
        if curl -sf "http://localhost:$PORT/health" > /dev/null 2>&1; then
            # Daemon is up — serve this request
            response=$(echo "$HOOK_INPUT" | curl -sf -X POST "http://localhost:$PORT/hook" \
                -H "Content-Type: application/json" \
                --data-binary @-)
            if [ -n "$response" ]; then
                echo "$response"
            fi
            exit 0
        fi
    done
    # Daemon didn't start in time — fall through to Node.js
fi

# ── Slow path: Node.js hook (no daemon, no JAR) ───────────────────────────────
if [ -f "$NODE_HOOK" ]; then
    echo "$HOOK_INPUT" | node "$NODE_HOOK"
    exit $?
fi

# Nothing available — pass through silently
exit 0
