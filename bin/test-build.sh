#!/usr/bin/env bash
# kcp-commands build + smoke test
# Run from the repo root: ./bin/test-build.sh
# Validates: Java daemon build, TypeScript CLI build, daemon startup, hook round-trip

set -e

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT=7734
DAEMON_PID=""
PASS=0
FAIL=0

# ── Helpers ───────────────────────────────────────────────────────────────────

ok()   { echo "  ✓ $*"; PASS=$((PASS + 1)); }
fail() { echo "  ✗ $*"; FAIL=$((FAIL + 1)); }

cleanup() {
  if [ -n "$DAEMON_PID" ]; then
    kill "$DAEMON_PID" 2>/dev/null || true
    wait "$DAEMON_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

# ── 1. Java daemon build ──────────────────────────────────────────────────────

echo ""
echo "=== 1. Java daemon build ==="
if cd "$REPO_DIR/java" && mvn clean package -q -DskipTests 2>/dev/null; then
  JAR="$REPO_DIR/java/target/kcp-commands-daemon.jar"
  if [ -f "$JAR" ]; then
    SIZE=$(du -sh "$JAR" | cut -f1)
    ok "kcp-commands-daemon.jar built ($SIZE)"
  else
    fail "JAR missing after mvn package"
  fi
else
  fail "mvn clean package failed"
fi
cd "$REPO_DIR"

# ── 2. TypeScript CLI build ───────────────────────────────────────────────────

echo ""
echo "=== 2. TypeScript CLI build ==="
if cd "$REPO_DIR/typescript" && npm install --silent 2>/dev/null && npm run build --silent 2>/dev/null; then
  if [ -f "$REPO_DIR/typescript/dist/cli.js" ]; then
    ok "dist/cli.js built"
  else
    fail "dist/cli.js missing after build"
  fi
else
  fail "TypeScript build failed"
fi
cd "$REPO_DIR"

# ── 3. Daemon startup ─────────────────────────────────────────────────────────

echo ""
echo "=== 3. Java daemon startup ==="

# Kill any existing daemon on this port
pkill -f "kcp-commands-daemon" 2>/dev/null || true
sleep 0.2

JAR="$REPO_DIR/java/target/kcp-commands-daemon.jar"
if [ ! -f "$JAR" ]; then
  fail "JAR not found — skipping startup test"
else
  java --enable-native-access=ALL-UNNAMED -jar "$JAR" > /tmp/kcp-test-daemon.log 2>&1 &
  DAEMON_PID=$!

  STARTED=0
  for i in $(seq 1 10); do
    sleep 0.5
    if curl -sf "http://localhost:$PORT/health" > /dev/null 2>&1; then
      STARTED=1
      ok "daemon healthy at http://localhost:$PORT/health (${i} × 0.5s)"
      break
    fi
  done

  if [ "$STARTED" -eq 0 ]; then
    fail "daemon did not respond within 5s — check /tmp/kcp-test-daemon.log"
  fi
fi

# ── 4. Hook round-trip: inject path ──────────────────────────────────────────

echo ""
echo "=== 4. Hook round-trip ==="

if curl -sf "http://localhost:$PORT/health" > /dev/null 2>&1; then
  # Test manifest hit (mvn should have a manifest)
  RESPONSE=$(echo '{"tool":"Bash","command":"mvn help:describe"}' | \
    curl -sf -X POST "http://localhost:$PORT/hook" \
      -H "Content-Type: application/json" \
      --data-binary @- 2>/dev/null || echo "")

  if echo "$RESPONSE" | grep -q "additionalContext"; then
    ok "inject path: mvn → additionalContext returned"
  else
    fail "inject path: no additionalContext in response (got: ${RESPONSE:0:200})"
  fi

  # Test suppression path (git should return 204)
  HTTP_CODE=$(echo '{"tool":"Bash","command":"git status"}' | \
    curl -s -o /dev/null -w "%{http_code}" -X POST "http://localhost:$PORT/hook" \
      -H "Content-Type: application/json" \
      --data-binary @- 2>/dev/null || echo "000")

  if [ "$HTTP_CODE" = "204" ]; then
    ok "suppress path: git → 204 (suppressed, no tokens spent)"
  else
    fail "suppress path: git → $HTTP_CODE (expected 204)"
  fi
else
  fail "daemon not running — skipping round-trip tests"
fi

# ── 5. Node.js CLI smoke test ─────────────────────────────────────────────────

echo ""
echo "=== 5. Node.js CLI ==="

CLI="$REPO_DIR/typescript/dist/cli.js"
if [ -f "$CLI" ]; then
  NODE_RESPONSE=$(echo '{"tool":"Bash","command":"mvn help:describe"}' | \
    node "$CLI" 2>/dev/null || echo "")
  if echo "$NODE_RESPONSE" | grep -q "additionalContext\|{}"; then
    ok "Node.js CLI responds to hook input"
  else
    fail "Node.js CLI returned unexpected output: ${NODE_RESPONSE:0:200}"
  fi
else
  fail "dist/cli.js not found — skipping Node.js test"
fi

# ── Summary ───────────────────────────────────────────────────────────────────

echo ""
echo "========================================"
echo "  Results: $PASS passed, $FAIL failed"
echo "========================================"
echo ""

[ "$FAIL" -eq 0 ]
