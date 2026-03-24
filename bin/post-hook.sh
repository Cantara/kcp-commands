#!/usr/bin/env bash
# kcp-memory PostToolUse hook — captures tool output preview to ~/.kcp/events.jsonl
# Registered as a Claude Code PostToolUse hook by the kcp-commands installer.
# Runs after every Bash tool call; appends a {"type":"output",...} line for kcp-memory to ingest.

EVENTS_FILE="$HOME/.kcp/events.jsonl"

# Nothing to do if kcp is not installed
[ -d "$HOME/.kcp" ] || exit 0

# Parse hook JSON from stdin and append output preview line
cat | python3 -c "
import json, sys, os, datetime, re

try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)

session_id    = data.get('session_id', '')
tool_name     = data.get('tool_name', 'Bash')
tool_input    = data.get('tool_input') or {}
tool_response = data.get('tool_response') or {}

command = tool_input.get('command', '')
output  = tool_response.get('output') or ''

if not command or not output:
    sys.exit(0)

preview = output[:200] + ('…' if len(output) > 200 else '')

# Detect error signals in the first 500 chars of output
_check = output[:500].lower()
exit_code_hint = 1 if (
    _check.startswith('error') or _check.startswith('error:')
    or any(s in _check for s in ['exception', 'traceback', 'failed', 'command not found', 'no such file'])
    or re.search(r'exit code [1-9]', _check) or 'exited with' in _check
) else 0

event = {
    'type':           'output',
    'ts':             datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
    'session_id':     session_id,
    'tool':           tool_name,
    'command':        command[:500],
    'output_preview': preview,
    'exit_code_hint': exit_code_hint,
}

events_file = os.path.expanduser('~/.kcp/events.jsonl')
with open(events_file, 'a') as f:
    f.write(json.dumps(event) + '\n')
" 2>/dev/null

exit 0
