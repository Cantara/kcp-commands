#!/usr/bin/env bash
# kcp-memory PostToolUse hook — captures tool output preview to ~/.kcp/events.jsonl
# Registered as a Claude Code PostToolUse hook by the kcp-commands installer.
# Runs after every Bash tool call; appends a {"type":"output",...} line for kcp-memory to ingest.
#
# Claude Code PostToolUse passes JSON on stdin with tool_response whose schema
# varies by tool.  For the Bash tool the output text may live in any of:
#   tool_response.stdout, tool_response.output, tool_response.content,
#   tool_response.result, or tool_response itself (if it is a plain string).
# We probe all known locations so this hook survives across Claude Code versions.

EVENTS_FILE="$HOME/.kcp/events.jsonl"
DEBUG_LOG="$HOME/.kcp/post-hook-debug.log"

# Nothing to do if kcp is not installed
[ -d "$HOME/.kcp" ] || exit 0

# Read stdin once — Claude Code pipes hook JSON here
HOOK_INPUT="$(cat)"

# Parse hook JSON from stdin and append output preview line
echo "$HOOK_INPUT" | python3 -c "
import json, sys, os, datetime, re

debug_log = os.path.expanduser('~/.kcp/post-hook-debug.log')

def debug(msg):
    try:
        with open(debug_log, 'a') as f:
            f.write(datetime.datetime.now().strftime('%H:%M:%S') + ' ' + msg + '\n')
    except Exception:
        pass

try:
    raw = sys.stdin.read()
    data = json.loads(raw)
except Exception as e:
    debug('JSON parse error: ' + str(e))
    debug('raw (first 500): ' + repr(raw[:500]) if raw else '(empty)')
    sys.exit(0)

session_id    = data.get('session_id', '')
tool_name     = data.get('tool_name', '') or data.get('tool', 'Bash')
tool_input    = data.get('tool_input') or {}
tool_response = data.get('tool_response')

command = tool_input.get('command', '')
if not command:
    debug('no command in tool_input; keys=' + str(list(tool_input.keys())))
    sys.exit(0)

# --- Extract output from tool_response (schema varies by tool & version) ---
output = ''

if isinstance(tool_response, str):
    # tool_response is a plain string (some tool versions)
    output = tool_response
elif isinstance(tool_response, dict):
    # Try known field names in priority order
    for field in ('stdout', 'output', 'content', 'result'):
        val = tool_response.get(field)
        if val and isinstance(val, str):
            output = val
            break
    # Fallback: if tool_response has a single string value, use it
    if not output:
        str_vals = [v for v in tool_response.values() if isinstance(v, str) and len(v) > 0]
        if len(str_vals) == 1:
            output = str_vals[0]
elif isinstance(tool_response, list):
    # Some tools return a list of content blocks [{type:'text', text:'...'}]
    texts = []
    for item in tool_response:
        if isinstance(item, dict):
            t = item.get('text') or item.get('content') or ''
            if t:
                texts.append(str(t))
        elif isinstance(item, str):
            texts.append(item)
    output = '\n'.join(texts)

if not output:
    # Log what we received so we can adapt to new schemas
    tr_repr = repr(tool_response)[:300] if tool_response is not None else 'None'
    debug('no output extracted; tool_response type=' + type(tool_response).__name__
          + ' repr=' + tr_repr)
    sys.exit(0)

debug('OK cmd=' + command[:80] + ' out_len=' + str(len(output)))

preview = output[:200] + ('...' if len(output) > 200 else '')

# Detect error signals in the first 500 chars of output
_check = output[:500].lower()
exit_code_hint = 1 if (
    _check.startswith('error') or _check.startswith('error:')
    or any(s in _check for s in ['exception', 'traceback', 'failed', 'command not found', 'no such file'])
    or re.search(r'exit code [1-9]', _check) or 'exited with' in _check
) else 0

# Also capture exit_code from tool_response if present
if isinstance(tool_response, dict):
    ec = tool_response.get('exit_code')
    if ec is not None and ec != 0:
        exit_code_hint = int(ec) if isinstance(ec, (int, float)) else 1

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
" 2>>"$DEBUG_LOG"

exit 0
