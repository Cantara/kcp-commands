#!/usr/bin/env bash
# kcp-commands installer
# Builds the project and registers the PreToolUse hook in ~/.claude/settings.json

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOOK_SCRIPT="$SCRIPT_DIR/dist/cli.js"
SETTINGS_FILE="$HOME/.claude/settings.json"

echo "kcp-commands installer"
echo "====================="

# Build if needed
if [ ! -f "$HOOK_SCRIPT" ]; then
  echo "→ Building..."
  cd "$SCRIPT_DIR"
  npm install --silent
  npm run build --silent
  echo "✓ Build complete"
else
  echo "✓ Already built"
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
    command: \`node "\${hookScript}"\`,
    timeout: 10,
    statusMessage: 'kcp-commands: looking up manifest...'
  }]
});

writeFileSync(settingsPath, JSON.stringify(settings, null, 2) + '\n');
console.log('✓ PreToolUse hook registered in ~/.claude/settings.json');
console.log('  Restart Claude Code to activate.');
EOF
