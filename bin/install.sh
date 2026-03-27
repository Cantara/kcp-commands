#!/usr/bin/env bash
# kcp-commands installer
#
# Usage (curl | bash — no clone needed):
#   curl -fsSL https://raw.githubusercontent.com/Cantara/kcp-commands/main/bin/install.sh | bash
#   curl -fsSL https://raw.githubusercontent.com/Cantara/kcp-commands/main/bin/install.sh | bash -s -- --java
#   curl -fsSL https://raw.githubusercontent.com/Cantara/kcp-commands/main/bin/install.sh | bash -s -- --node
#
# Or from a cloned repo:
#   ./bin/install.sh [--java | --node]
#
#   --java   Java daemon backend  — ~12 ms/hook call  (recommended, requires Java 21)
#   --node   Node.js backend      — ~250 ms/hook call (requires Node.js only)
#   No flag: interactive prompt
#
# Installs to ~/.kcp/ — no source tree required after installation.
# Re-running upgrades an existing installation.

set -e

REPO="Cantara/kcp-commands"
KCP_DIR="$HOME/.kcp"
SETTINGS_FILE="$HOME/.claude/settings.json"
RELEASES_URL="https://github.com/$REPO/releases/latest/download"
RAW_URL="https://raw.githubusercontent.com/$REPO/main"

# ── Parse args ────────────────────────────────────────────────────────────────

MODE=""
for arg in "$@"; do
  case $arg in
    --java)           MODE="java" ;;
    --node|--nodejs)  MODE="node" ;;
  esac
done

# ── Interactive prompt ────────────────────────────────────────────────────────

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

# ── Create install directory ──────────────────────────────────────────────────

mkdir -p "$KCP_DIR/commands"
echo "✓ Install directory: $KCP_DIR"

# ── Download hook.sh and post-hook.sh ─────────────────────────────────────────

echo "→ Installing hook.sh..."
curl -fsSL "$RAW_URL/bin/hook.sh" -o "$KCP_DIR/hook.sh"
chmod +x "$KCP_DIR/hook.sh"
echo "✓ hook.sh installed"

echo "→ Installing post-hook.sh (output capture)..."
curl -fsSL "$RAW_URL/bin/post-hook.sh" -o "$KCP_DIR/post-hook.sh"
chmod +x "$KCP_DIR/post-hook.sh"
echo "✓ post-hook.sh installed"

# ── Java daemon ───────────────────────────────────────────────────────────────

if [ "$MODE" = "java" ]; then
  # Verify Java 21+ is available
  JAVA_BIN="java"
  if [ "$(uname)" = "Darwin" ] && command -v /usr/libexec/java_home > /dev/null 2>&1; then
    JAVA21="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    if [ -n "$JAVA21" ]; then
      JAVA_BIN="$JAVA21/bin/java"
    fi
  fi
  JAVA_VERSION=$("$JAVA_BIN" -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)
  if [ -z "$JAVA_VERSION" ] || [ "$JAVA_VERSION" -lt 21 ] 2>/dev/null; then
    echo "✗ Java 21+ required (found: ${JAVA_VERSION:-none})."
    echo "  On macOS: brew install --cask temurin@21"
    echo "  Or set JAVA_HOME to a Java 21 installation and re-run."
    echo "  Falling back to Node.js backend."
    MODE="node"
  fi
fi

if [ "$MODE" = "java" ]; then
  echo "→ Downloading Java daemon..."
  if curl -fsSL "$RELEASES_URL/kcp-commands-daemon.jar" -o "$KCP_DIR/kcp-commands-daemon.jar"; then
    echo "✓ Java daemon downloaded"
  else
    echo "✗ Download failed. Checking for local Maven build..."
    # If running from a cloned repo, fall back to building locally
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" 2>/dev/null && pwd || echo "")"
    LOCAL_JAR=""
    if [ -n "$SCRIPT_DIR" ] && [ -f "$SCRIPT_DIR/../java/pom.xml" ]; then
      LOCAL_JAR="$SCRIPT_DIR/../java/target/kcp-commands-daemon.jar"
      if [ -f "$LOCAL_JAR" ]; then
        cp "$LOCAL_JAR" "$KCP_DIR/kcp-commands-daemon.jar"
        echo "✓ Java daemon copied from local build"
      elif command -v mvn > /dev/null 2>&1; then
        mvn -f "$SCRIPT_DIR/../java/pom.xml" -q package -DskipTests
        cp "$SCRIPT_DIR/../java/target/kcp-commands-daemon.jar" "$KCP_DIR/kcp-commands-daemon.jar"
        echo "✓ Java daemon built from source"
      fi
    fi
    if [ ! -f "$KCP_DIR/kcp-commands-daemon.jar" ]; then
      echo "✗ Could not obtain Java daemon. Falling back to Node.js."
      MODE="node"
    fi
  fi
fi

# ── Node.js CLI ───────────────────────────────────────────────────────────────

if [ "$MODE" = "node" ]; then
  echo "→ Downloading Node.js CLI..."
  if curl -fsSL "$RELEASES_URL/cli.js" -o "$KCP_DIR/cli.js"; then
    echo "✓ Node.js CLI downloaded"
  else
    echo "✗ Download failed. Checking for local build..."
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" 2>/dev/null && pwd || echo "")"
    if [ -n "$SCRIPT_DIR" ] && [ -f "$SCRIPT_DIR/../typescript/package.json" ]; then
      cd "$SCRIPT_DIR/../typescript"
      npm install --silent
      npm run build --silent
      cp dist/cli.js "$KCP_DIR/cli.js"
      echo "✓ Node.js CLI built from source"
    else
      echo "✗ Could not obtain Node.js CLI."
      echo "  Install will register the hook but it will pass through silently until"
      echo "  a backend is available."
    fi
  fi
fi

# ── KCP user CLI (kcp stats / validate / init) — always installed ─────────────

echo "→ Installing KCP user CLI (kcp stats, kcp validate, kcp init)..."
if curl -fsSL "$RELEASES_URL/kcp-user-cli.js" -o "$KCP_DIR/kcp-user-cli.js" 2>/dev/null; then
  echo "✓ KCP user CLI downloaded"
else
  echo "  (KCP user CLI not available in this release — skipping)"
fi

# Create ~/.local/bin/kcp wrapper so `kcp stats` works from any terminal
if [ -f "$KCP_DIR/kcp-user-cli.js" ]; then
  mkdir -p "$HOME/.local/bin"
  cat > "$HOME/.local/bin/kcp" << WRAPPER
#!/usr/bin/env bash
node "$KCP_DIR/kcp-user-cli.js" "\$@"
WRAPPER
  chmod +x "$HOME/.local/bin/kcp"
  echo "✓ kcp wrapper installed → ~/.local/bin/kcp"
fi

# ── Register hook in ~/.claude/settings.json ─────────────────────────────────

if [ ! -f "$SETTINGS_FILE" ]; then
  mkdir -p "$(dirname "$SETTINGS_FILE")"
  echo '{}' > "$SETTINGS_FILE"
fi

HOOK_COMMAND="bash \"$KCP_DIR/hook.sh\""
POST_HOOK_COMMAND="bash \"$KCP_DIR/post-hook.sh\""

KCP_DIR="$KCP_DIR" \
SETTINGS_FILE="$SETTINGS_FILE" \
HOOK_COMMAND="$HOOK_COMMAND" \
POST_HOOK_COMMAND="$POST_HOOK_COMMAND" \
MODE="$MODE" \
node --input-type=module << 'JSEOF'
import { readFileSync, writeFileSync } from 'fs';

const kcpDir          = process.env.KCP_DIR;
const settingsPath    = process.env.SETTINGS_FILE;
const hookCommand     = process.env.HOOK_COMMAND;
const postHookCommand = process.env.POST_HOOK_COMMAND;
const mode            = process.env.MODE;

let settings;
try {
  settings = JSON.parse(readFileSync(settingsPath, 'utf-8'));
} catch {
  settings = {};
}

settings.hooks             ??= {};
settings.hooks.PreToolUse  ??= [];
settings.hooks.PostToolUse ??= [];

// Remove any existing kcp hook entries (idempotency + upgrade support).
// Match on kcpDir so both legacy catch-all and Bash-matcher entries are removed.
settings.hooks.PreToolUse = settings.hooks.PreToolUse.filter(
  group => !group.hooks?.some(h => h.command?.includes(kcpDir))
);
settings.hooks.PostToolUse = settings.hooks.PostToolUse.filter(
  group => !group.hooks?.some(h => h.command?.includes(kcpDir))
);

settings.hooks.PreToolUse.push({
  matcher: 'Bash',
  hooks: [{
    type:          'command',
    command:       hookCommand,
    timeout:       10,
    statusMessage: 'kcp-commands: looking up manifest...'
  }]
});

settings.hooks.PostToolUse.push({
  matcher: 'Bash',
  hooks: [{
    type:    'command',
    command: postHookCommand,
    timeout: 10
  }]
});

writeFileSync(settingsPath, JSON.stringify(settings, null, 2) + '\n');
console.log(`✓ PreToolUse  hook registered → ${kcpDir}/hook.sh (${mode})`);
console.log(`✓ PostToolUse hook registered → ${kcpDir}/post-hook.sh (output capture)`);
JSEOF

echo ""
echo "Installation complete!"
echo "  Location : $KCP_DIR"
echo "  Hook     : $KCP_DIR/hook.sh"
if [ "$MODE" = "java" ]; then
echo "  Daemon   : $KCP_DIR/kcp-commands-daemon.jar"
else
echo "  CLI      : $KCP_DIR/cli.js"
fi
echo ""
echo "  ➜ Restart Claude Code to activate the hook."
if [ -f "$HOME/.local/bin/kcp" ]; then
echo ""
echo "  Run 'kcp stats' to see token savings and usage data."
echo "  (Make sure ~/.local/bin is in your PATH)"
fi
