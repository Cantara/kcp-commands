/**
 * kcp-commands pi extension
 *
 * Bridges the kcp-commands Java daemon into pi's tool pipeline:
 *
 *   Phase B — Output filtering: daemon rewrites bash commands to pipe their
 *             output through /filter/<cmd>, stripping noise before it reaches
 *             the context window. (Phase A's separate manifest-guidance text
 *             is NOT delivered — pi's tool_call event has no channel for it;
 *             see docs/pi-integration.md.)
 *   Phase C — Event logging: writes output previews to ~/.kcp/events.jsonl
 *             for kcp-memory episodic indexing.
 *
 * Installed globally at ~/.pi/agent/extensions/kcp-commands.ts
 */

import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { spawn } from "node:child_process";
import { appendFileSync, existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

// ── Config ────────────────────────────────────────────────────────────────────

const KCP_DIR     = join(homedir(), ".kcp");
const DAEMON_JAR  = join(KCP_DIR, "kcp-commands-daemon.jar");
const EVENTS_FILE = join(KCP_DIR, "events.jsonl");
const PORT        = 7734;
const HEALTH_URL  = `http://localhost:${PORT}/health`;
const HOOK_URL    = `http://localhost:${PORT}/hook`;

// ── Daemon helpers ────────────────────────────────────────────────────────────

async function isDaemonRunning(): Promise<boolean> {
  try {
    const res = await fetch(HEALTH_URL, { signal: AbortSignal.timeout(300) });
    return res.ok;
  } catch {
    return false;
  }
}

async function startDaemon(): Promise<void> {
  if (!existsSync(DAEMON_JAR)) return;

  const javaBin = process.env.JAVA_HOME
    ? join(process.env.JAVA_HOME, "bin", "java")
    : "java";

  const child = spawn(
    javaBin,
    ["--enable-native-access=ALL-UNNAMED", "-jar", DAEMON_JAR],
    { detached: true, stdio: ["ignore", "ignore", "ignore"] },
  );
  child.on("error", () => {
    // e.g. java binary not found — startDaemon's health-check retry loop
    // below will simply time out and the extension degrades gracefully.
  });
  child.unref();

  for (let i = 0; i < 10; i++) {
    await new Promise((r) => setTimeout(r, 500));
    if (await isDaemonRunning()) return;
  }
}

async function ensureDaemon(): Promise<void> {
  if (await isDaemonRunning()) return;
  await startDaemon();
}

// ── Extension ─────────────────────────────────────────────────────────────────

export default async function (pi: ExtensionAPI) {

  // Track original commands by toolCallId for Phase C logging.
  const inflight = new Map<string, string>();

  // Ensure daemon is running at session start.
  pi.on("session_start", async (_event, ctx) => {
    if (await isDaemonRunning()) {
      ctx.ui.setStatus("kcp", "kcp ✓");
      return;
    }
    if (!existsSync(DAEMON_JAR)) {
      ctx.ui.setStatus("kcp", "kcp: daemon jar missing");
      return;
    }
    await ensureDaemon();
    ctx.ui.setStatus("kcp", (await isDaemonRunning()) ? "kcp ✓" : "kcp: daemon unavailable");
  });

  // Phase B: Intercept bash tool calls, rewrite command via the daemon.
  pi.on("tool_call", async (event, ctx) => {
    if (event.toolName !== "bash") return;

    const input = event.input as { command: string; timeout?: number };
    if (!input.command) return;

    // Store original command for Phase C.
    inflight.set(event.toolCallId, input.command);

    // Skip if daemon is down — attempt lazy start but don't block.
    if (!(await isDaemonRunning())) {
      ensureDaemon().catch(() => {});
      return;
    }

    try {
      const res = await fetch(HOOK_URL, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          session_id: ctx.sessionManager.getSessionId(),
          cwd: ctx.cwd,
          tool_name: "Bash",
          tool_input: { command: input.command },
        }),
        signal: AbortSignal.timeout(2000),
      });

      if (!res.ok) return;

      const data = (await res.json()) as {
        hookSpecificOutput?: {
          updatedInput?: { command?: string };
        };
      };

      const rewritten = data?.hookSpecificOutput?.updatedInput?.command;
      if (rewritten && rewritten !== input.command) {
        input.command = rewritten;
      }
    } catch {
      // Daemon unavailable, timed out, or returned no body (e.g. 204 for an
      // unmatched command) — pass through unchanged either way.
    }
  });

  // Phase C: Log output previews to events.jsonl for kcp-memory.
  pi.on("tool_result", async (event, ctx) => {
    if (event.toolName !== "bash") return;

    const command = inflight.get(event.toolCallId) ?? "";
    inflight.delete(event.toolCallId);
    if (!command) return;

    // Extract text from content blocks.
    let output = "";
    if (Array.isArray(event.content)) {
      output = (event.content as Array<{ type: string; text?: string }>)
        .filter((b) => b.type === "text")
        .map((b) => b.text ?? "")
        .join("\n");
    } else if (typeof event.content === "string") {
      output = event.content;
    }

    if (!output) return;

    // Heuristic exit code from output — mirrors post-hook.sh's pattern set.
    const check = output.slice(0, 500).toLowerCase();
    const exitCodeHint =
      event.isError ||
      check.startsWith("error") ||
      /exception|traceback|failed|command not found|no such file|exited with/.test(check) ||
      /exit code [1-9]/.test(check)
        ? 1
        : 0;

    const entry = {
      type: "output",
      ts: new Date().toISOString().replace(/\.\d+Z$/, "Z"),
      session_id: ctx.sessionManager.getSessionId(),
      tool: "Bash",
      command: command.slice(0, 500),
      output_preview: output.slice(0, 200) + (output.length > 200 ? "..." : ""),
      exit_code_hint: exitCodeHint,
    };

    try {
      appendFileSync(EVENTS_FILE, JSON.stringify(entry) + "\n");
    } catch {
      // Non-critical — don't break the tool pipeline.
    }
  });
}
