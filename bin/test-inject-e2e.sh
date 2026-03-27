#!/usr/bin/env bash
# test-inject-e2e.sh — End-to-end test for kcp-commands inject event logging.
#
# Starts the daemon with a temp usage DB, sends a hook request for a command
# with a known manifest (docker run), waits briefly, and asserts that an
# inject event row appears in the DB.
#
# Requirements: java 21+, python3 (with sqlite3 module), curl
# Exit: 0 on success, 1 on failure

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/../java/target/kcp-commands-daemon.jar"
PORT=17734  # Use non-standard port to avoid conflicts with running daemon
TEMP_DIR="$(mktemp -d)"
DAEMON_PID=""

cleanup() {
    if [ -n "$DAEMON_PID" ] && kill -0 "$DAEMON_PID" 2>/dev/null; then
        kill "$DAEMON_PID" 2>/dev/null || true
        wait "$DAEMON_PID" 2>/dev/null || true
    fi
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

# Helper: query SQLite using Python (avoids sqlite3 CLI dependency)
sql_query() {
    local db_path="$1"
    local query="$2"
    python3 - "$db_path" "$query" <<'PYEOF'
import sqlite3, sys
db_path, query = sys.argv[1], sys.argv[2]
try:
    conn = sqlite3.connect(db_path)
    cursor = conn.execute(query)
    rows = cursor.fetchall()
    for row in rows:
        print('|'.join(str(c) if c is not None else '' for c in row))
    conn.close()
except Exception as e:
    print(f'ERROR: {e}', file=sys.stderr)
    sys.exit(1)
PYEOF
}

sql_scalar() {
    local db_path="$1"
    local query="$2"
    python3 - "$db_path" "$query" <<'PYEOF'
import sqlite3, sys
db_path, query = sys.argv[1], sys.argv[2]
try:
    conn = sqlite3.connect(db_path)
    cursor = conn.execute(query)
    row = cursor.fetchone()
    if row:
        print(row[0] if row[0] is not None else '')
    conn.close()
except Exception as e:
    print(f'ERROR: {e}', file=sys.stderr)
    sys.exit(1)
PYEOF
}

# ── Pre-check ────────────────────────────────────────────────────────────────
if [ ! -f "$JAR" ]; then
    echo "SKIP: JAR not found at $JAR — run 'mvn package -DskipTests' in java/ first"
    exit 0
fi

if ! python3 -c "import sqlite3" 2>/dev/null; then
    echo "SKIP: python3 sqlite3 module not available"
    exit 0
fi

echo "=== kcp-commands inject e2e test ==="
echo "Temp dir: $TEMP_DIR"
echo "Port: $PORT"

# ── Override the usage DB path via system property ────────────────────────────
# UsageLogger.dbPath reads from Path.of(System.getProperty("user.home"), ".kcp", "usage.db")
# We override user.home so the daemon writes to our temp directory.
mkdir -p "$TEMP_DIR/.kcp"

java -Duser.home="$TEMP_DIR" --enable-native-access=ALL-UNNAMED \
    -jar "$JAR" --port "$PORT" > "$TEMP_DIR/daemon.log" 2>&1 &
DAEMON_PID=$!
echo "Daemon PID: $DAEMON_PID"

# ── Wait for daemon startup ──────────────────────────────────────────────────
echo -n "Waiting for daemon..."
for i in $(seq 1 20); do
    if curl -sf "http://localhost:$PORT/health" > /dev/null 2>&1; then
        echo " ready (${i}00ms)"
        break
    fi
    if [ "$i" -eq 20 ]; then
        echo " FAILED"
        echo "--- daemon.log ---"
        cat "$TEMP_DIR/daemon.log"
        exit 1
    fi
    sleep 0.1
done

# ── Send a hook request for "docker run hello-world" ─────────────────────────
HOOK_JSON='{
  "tool_name": "Bash",
  "tool_input": { "command": "docker run hello-world" },
  "session_id": "e2e-test-session",
  "cwd": "/home/user/my-project"
}'

echo "Sending hook request..."
HTTP_STATUS=$(curl -s -o "$TEMP_DIR/response.json" -w "%{http_code}" \
    -X POST "http://localhost:$PORT/hook" \
    -H "Content-Type: application/json" \
    -d "$HOOK_JSON")

echo "HTTP status: $HTTP_STATUS"

if [ "$HTTP_STATUS" -ne 200 ]; then
    echo "FAIL: Expected HTTP 200 (manifest hit), got $HTTP_STATUS"
    echo "Response:"
    cat "$TEMP_DIR/response.json" 2>/dev/null
    echo "--- daemon.log ---"
    cat "$TEMP_DIR/daemon.log"
    exit 1
fi

echo "Response (manifest hit confirmed):"
cat "$TEMP_DIR/response.json"
echo ""

# ── Wait for async inject event to be written ────────────────────────────────
echo -n "Waiting for inject event..."
USAGE_DB_PATH="$TEMP_DIR/.kcp/usage.db"
for i in $(seq 1 20); do
    if [ -f "$USAGE_DB_PATH" ]; then
        COUNT=$(sql_scalar "$USAGE_DB_PATH" "SELECT COUNT(*) FROM usage_events WHERE event_type='inject'" 2>/dev/null || echo "0")
        if [ "$COUNT" -gt 0 ] 2>/dev/null; then
            echo " found (${i}00ms)"
            break
        fi
    fi
    if [ "$i" -eq 20 ]; then
        echo " TIMEOUT"
        echo "FAIL: No inject event found in $USAGE_DB_PATH after 2s"
        if [ -f "$USAGE_DB_PATH" ]; then
            echo "DB exists, all rows:"
            sql_query "$USAGE_DB_PATH" "SELECT * FROM usage_events" 2>/dev/null || echo "(query failed)"
        else
            echo "DB file does not exist"
        fi
        echo "--- daemon.log ---"
        cat "$TEMP_DIR/daemon.log"
        exit 1
    fi
    sleep 0.1
done

# ── Verify inject event content ──────────────────────────────────────────────
echo ""
echo "=== Verifying inject event ==="

echo "Row:"
sql_query "$USAGE_DB_PATH" "SELECT event_type, unit_id, project, session_id, token_estimate FROM usage_events WHERE event_type='inject' LIMIT 1"

EVENT_TYPE=$(sql_scalar "$USAGE_DB_PATH" "SELECT event_type FROM usage_events WHERE event_type='inject' LIMIT 1")
UNIT_ID=$(sql_scalar "$USAGE_DB_PATH" "SELECT unit_id FROM usage_events WHERE event_type='inject' LIMIT 1")
PROJECT=$(sql_scalar "$USAGE_DB_PATH" "SELECT project FROM usage_events WHERE event_type='inject' LIMIT 1")
SESSION_ID=$(sql_scalar "$USAGE_DB_PATH" "SELECT session_id FROM usage_events WHERE event_type='inject' LIMIT 1")

PASS=true

if [ "$EVENT_TYPE" != "inject" ]; then
    echo "FAIL: event_type = '$EVENT_TYPE', expected 'inject'"
    PASS=false
fi

if [ "$UNIT_ID" != "docker-run" ]; then
    echo "FAIL: unit_id = '$UNIT_ID', expected 'docker-run'"
    PASS=false
fi

if [ "$PROJECT" != "my-project" ]; then
    echo "FAIL: project = '$PROJECT', expected 'my-project'"
    PASS=false
fi

if [ "$SESSION_ID" != "e2e-test-session" ]; then
    echo "FAIL: session_id = '$SESSION_ID', expected 'e2e-test-session'"
    PASS=false
fi

echo ""
if [ "$PASS" = true ]; then
    echo "=== ALL CHECKS PASSED ==="
    exit 0
else
    echo "=== SOME CHECKS FAILED ==="
    exit 1
fi
