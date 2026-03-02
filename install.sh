#!/usr/bin/env bash
# kcp-commands installer
#
# Usage:
#   ./install.sh [--java | --node]
#
#   --java   Java daemon backend  — ~12 ms/hook call  (recommended, requires Java 21)
#            Downloads the pre-built JAR from GitHub releases.
#            Falls back to local Maven build if the download fails.
#   --node   Node.js backend      — ~250 ms/hook call (requires Node.js only)
#
#   No flag: interactive prompt.
#
# Re-running install.sh upgrades an existing installation (replaces hook, re-downloads JAR).

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SETTINGS_FILE="$HOME/.claude/settings.json"
REPO="Cantara/kcp-commands"
JAR_NAME="kcp-commands-daemon.jar"
JAR_PATH="$SCRIPT_DIR/java/target/$JAR_NAME"
RELEASE_URL="https://github.com/$REPO/releases/latest/download/$JAR_NAME"

# ── Parse args ────────────────────────────────────────────────────────────────

MODE=""
for arg in "$@"; do
  case $arg in
    --java)           MODE="java" ;;
    --node|--nodejs)  MODE="node" ;;
  esac
done

# ── Interactive prompt (if no flag given) ─────────────────────────────────────

if [ -z "$MODE" ]; then
  echo "kcp-commands installer"
  echo "======================"
  echo ""
  echo "Choose backend:"
  echo "  1) Java daemon  — ~12 ms/hook call, requires Java 21  [recommended]"
  echo "  2) Node.js      — ~250 ms/hook call, requires Node.js only"
  echo ""
  read -rp "Choice [1/2, default: 1]: " choice
  case "${choice:-1}" in
    2|n|N|node|nodejs) MODE="node" ;;
    *)                 MODE="java" ;;
  esac
fi

echo ""
echo "kcp-commands installer — backend: $MODE"
echo "========================================"

# ── Node.js filter (always needed — Phase B pipe target) ─────────────────────

if [ ! -f "$SCRIPT_DIR/dist/cli.js" ]; then
  echo "→ Building Node.js filter..."
  cd "$SCRIPT_DIR"
  npm install --silent
  npm run build --silent
  echo "✓ Node.js filter built"
else
  echo "✓ Node.js filter already built"
fi

# ── Java daemon (download from GitHub releases, fall back to mvn) ─────────────

if [ "$MODE" = "java" ]; then
  if [ -f "$JAR_PATH" ]; then
    echo "✓ Java daemon already present ($JAR_NAME)"
  else
    echo "→ Downloading Java daemon from GitHub releases..."
    mkdir -p "$SCRIPT_DIR/java/target"

    if curl -fsSL "$RELEASE_URL" -o "$JAR_PATH" 2>/dev/null; then
      echo "✓ Java daemon downloaded"
    elif command -v mvn > /dev/null 2>&1; then
      echo "  (download failed — building from source with Maven)"
      mvn -f "$SCRIPT_DIR/java/pom.xml" -q package -DskipTests
      echo "✓ Java daemon built from source"
    else
      echo "✗ Could not obtain Java daemon (download failed; mvn not found)"
      echo "  Falling back to Node.js backend."
      MODE="node"
    fi
  fi
fi

# ── Hook command ──────────────────────────────────────────────────────────────

if [ "$MODE" = "java" ]; then
  HOOK_COMMAND="bash \"$SCRIPT_DIR/hook.sh\""
  BACKEND_LABEL="Java daemon"
else
  HOOK_COMMAND="node \"$SCRIPT_DIR/dist/cli.js\""
  BACKEND_LABEL="Node.js"
fi

# ── Register hook in ~/.claude/settings.json ─────────────────────────────────

if [ ! -f "$SETTINGS_FILE" ]; then
  mkdir -p "$(dirname "$SETTINGS_FILE")"
  echo '{}' > "$SETTINGS_FILE"
  echo "✓ Created $SETTINGS_FILE"
fi

# Pass values via env vars; heredoc is single-quoted to prevent shell interpolation.
SETTINGS_FILE="$SETTINGS_FILE" \
HOOK_COMMAND="$HOOK_COMMAND" \
BACKEND_LABEL="$BACKEND_LABEL" \
node --input-type=module << 'JSEOF'
import { readFileSync, writeFileSync } from 'fs';

const settingsPath  = process.env.SETTINGS_FILE;
const hookCommand   = process.env.HOOK_COMMAND;
const backendLabel  = process.env.BACKEND_LABEL;

let settings;
try {
  settings = JSON.parse(readFileSync(settingsPath, 'utf-8'));
} catch {
  settings = {};
}

settings.hooks        ??= {};
settings.hooks.PreToolUse ??= [];

// Remove any existing kcp-commands entry (upgrade support).
settings.hooks.PreToolUse = settings.hooks.PreToolUse.filter(
  group => !group.hooks?.some(h => h.command?.includes('kcp-commands'))
);

settings.hooks.PreToolUse.push({
  matcher: 'Bash',
  hooks: [{
    type: 'command',
    command: hookCommand,
    timeout: 10,
    statusMessage: 'kcp-commands: looking up manifest...'
  }]
});

writeFileSync(settingsPath, JSON.stringify(settings, null, 2) + '\n');
console.log(`✓ PreToolUse hook registered (${backendLabel})`);
console.log('  Restart Claude Code to activate.');
JSEOF
