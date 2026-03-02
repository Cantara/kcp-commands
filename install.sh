#!/usr/bin/env bash
# kcp-commands installer
# Builds the project and registers the PreToolUse hook in ~/.claude/settings.json

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOOK_SCRIPT="$SCRIPT_DIR/hook.sh"
SETTINGS_FILE="$HOME/.claude/settings.json"

echo "kcp-commands installer"
echo "====================="

# Build Node.js filter (used by hook.sh as Phase B pipe target)
if [ ! -f "$SCRIPT_DIR/dist/cli.js" ]; then
  echo "→ Building Node.js filter..."
  cd "$SCRIPT_DIR"
  npm install --silent
  npm run build --silent
  echo "✓ Node.js build complete"
else
  echo "✓ Node.js filter already built"
fi

# Build Java daemon (fast path — optional but recommended)
DAEMON_JAR="$SCRIPT_DIR/java/target/kcp-commands-daemon-0.1.0.jar"
if [ ! -f "$DAEMON_JAR" ]; then
  if command -v mvn > /dev/null 2>&1; then
    echo "→ Building Java daemon..."
    mvn -f "$SCRIPT_DIR/java/pom.xml" -q package -DskipTests
    echo "✓ Java daemon built"
  else
    echo "⚠ mvn not found — skipping Java daemon (hook.sh will fall back to Node.js)"
  fi
else
  echo "✓ Java daemon already built"
fi

# Create settings file if missing
if [ ! -f "$SETTINGS_FILE" ]; then
  mkdir -p "$(dirname "$SETTINGS_FILE")"
  echo '{}' > "$SETTINGS_FILE"
  echo "✓ Created $SETTINGS_FILE"
fi

# Register hook via Node (handles JSON manipulation safely)
node --input-type=module << EOF
import { readFileSync, writeFileSync } from 'fs';

const settingsPath = '$SETTINGS_FILE';
const hookScript = '$HOOK_SCRIPT';

let settings;
try {
  settings = JSON.parse(readFileSync(settingsPath, 'utf-8'));
} catch {
  settings = {};
}

settings.hooks ??= {};
settings.hooks.PreToolUse ??= [];

const alreadyInstalled = settings.hooks.PreToolUse.some(group =>
  group.hooks?.some(h => h.command?.includes('kcp-commands'))
);

if (alreadyInstalled) {
  console.log('✓ Hook already registered');
  process.exit(0);
}

settings.hooks.PreToolUse.push({
  matcher: 'Bash',
  hooks: [{
    type: 'command',
    command: \`bash "\${hookScript}"\`,
    timeout: 10,
    statusMessage: 'kcp-commands: looking up manifest...'
  }]
});

writeFileSync(settingsPath, JSON.stringify(settings, null, 2) + '\n');
console.log('✓ PreToolUse hook registered in ~/.claude/settings.json');
console.log('  Restart Claude Code to activate.');
EOF
