# kcp-commands — pi coding agent integration

[pi](https://earendil.works/pi) is an AI coding agent harness that runs its own
tool pipeline independently of Claude Code. Its hook system is built around a
TypeScript **extension API** rather than `~/.claude/settings.json`, so the
kcp-commands Claude Code hooks do not fire inside pi sessions.

This document describes how to bridge kcp-commands into pi using a single
global extension file. Phase B (output filtering) and Phase C (event logging)
carry over; Phase A (proactive guidance text) does not — pi's `tool_call` event
has no injection point for it. See “Notes on Phase A context delivery” below.

## How pi's extension API maps to kcp-commands phases

| kcp-commands phase | Claude Code mechanism | pi equivalent |
|---|---|---|
| A — Proactive guidance | `PreToolUse` hook returns `additionalContext`, injected as a system note | Not delivered — no equivalent injection point (see notes below) |
| B — Output filtering | Command piped through `/filter/<key>` | `tool_call` event — `event.input` is mutated the same way |
| C — Event logging | `PostToolUse` hook writes to `events.jsonl` | `tool_result` event — read result content, append to `events.jsonl` |

The daemon's `/hook` endpoint returns both `additionalContext` (Phase A) and,
when the command is filterable, a rewritten `updatedInput.command` that pipes
output through `/filter/<key>` (Phase B). The pi extension only reads the
rewritten command — mutating `event.input.command` to it before pi executes the
command. It has no channel for `additionalContext`, so Phase A guidance text is
not delivered; see “Notes on Phase A context delivery” below.

Phase C is handled in a separate `tool_result` handler that reads the output
content blocks and appends a preview line to `~/.kcp/events.jsonl`.

## Prerequisites

- pi coding agent installed (`npm install -g @earendil-works/pi-coding-agent`)
- kcp-commands installed (`./bin/install.sh --java`)
- Java 21+ available on `PATH` (or `JAVA_HOME` set)

## Installation

Create the global extensions directory and drop in the extension file:

```bash
mkdir -p ~/.pi/agent/extensions
curl -fsSL https://raw.githubusercontent.com/Cantara/kcp-commands/main/docs/pi-extension.ts \
  -o ~/.pi/agent/extensions/kcp-commands.ts
```

Or download [`docs/pi-extension.ts`](./pi-extension.ts) manually and place it at
`~/.pi/agent/extensions/kcp-commands.ts`.

The extension is auto-discovered from that location for every pi session — no
per-project configuration required.

## Extension source

The extension lives at [`docs/pi-extension.ts`](./pi-extension.ts) in this repo —
same file the `curl` command above installs. It implements the two supported
phases described in the mapping table: Phase B via a `tool_call` handler that
mutates `event.input.command` in place (pi's documented mechanism for patching
tool arguments before execution — see the `ToolCallEvent` JSDoc in
`@earendil-works/pi-coding-agent`'s extension types), and Phase C via a
`tool_result` handler that appends to `~/.kcp/events.jsonl`.

## Verification

After placing the extension file, start pi in any project:

```bash
pi
```

You should see `kcp ✓` in the footer status bar once the daemon is confirmed
running (or started).

To verify Phase B (filtering) and Phase C (event logging):

```bash
# Clear events log for a clean test
> ~/.kcp/events.jsonl

# Run pi and ask it to execute a command
pi -p "run: git status"

# Phase C: check events were logged
cat ~/.kcp/events.jsonl
```

The daemon logs a Phase B/event-logging entry on every reachable `/hook` call,
and the extension appends a Phase C output preview to the same file. You should
see two JSONL lines per bash call on the happy path (daemon reachable, command
produced output) — see below for what happens when the daemon isn't running.
There is nothing to verify for Phase A: it is not delivered by this bridge.

## Behaviour when the daemon is not running

- `session_start`: checks for an already-running daemon first, then attempts to
  start one from `~/.kcp/kcp-commands-daemon.jar` if none is reachable
- `tool_call`: if health check fails, triggers a lazy start attempt in the background and passes the command through unchanged
- `tool_result`: Phase C logging proceeds regardless of daemon state (reads from the `inflight` map populated during `tool_call`)

Both phases degrade gracefully — a missing or crashed daemon never prevents tool
execution (bounded by the 300ms health-check and 2s hook-request timeouts).

## Notes on Phase A context delivery

Claude Code's `PreToolUse` hook can return an `additionalContext` string that
Claude Code injects into the conversation as a system note — this is how
kcp-commands delivers Phase A's proactive command guidance. Pi's `tool_call`
event has no equivalent injection point; it can only mutate `event.input`. That
mutation is what Phase B (the rewritten, filter-piped command) uses, and this
bridge reuses it for exactly that — Phase A's guidance text itself is not
delivered to the model in any form.

A future improvement could use pi's `before_agent_start` event to inject a
brief manifest summary as a system prompt addition for the current turn.
