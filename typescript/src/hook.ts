import { readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { resolveManifest, buildKey } from './resolver.js';
import { generateManifest } from './generator.js';
import type { CommandManifest } from './model.js';

// Regex for a first argument that looks like a subcommand word (not a flag, path, or option)
const SUBCOMMAND_PATTERN = /^[a-z][a-z0-9-]*$/;

interface ParsedCommand {
  key: string;   // manifest lookup key, e.g. "ls", "git-log"
  cmd: string;
  subcommand?: string;
}

/**
 * Parse the base command and optional subcommand from a shell string.
 * Handles: sudo, env vars, pipes (ignored), background (&).
 * Returns null for complex expressions we shouldn't intercept.
 */
function parseCommand(shellCommand: string): ParsedCommand | null {
  // Skip very complex commands
  if (shellCommand.includes('$(') || shellCommand.includes('`')) return null;

  // Take only the first segment if there are pipes/semicolons
  const firstSegment = shellCommand.split(/[|;&&]+/)[0].trim();

  // Strip leading env var assignments and sudo
  const stripped = firstSegment.replace(/^(?:\w+=\S+\s+)*(?:sudo\s+)?/, '').trim();
  const parts = stripped.split(/\s+/);
  if (parts.length === 0 || !parts[0]) return null;

  const cmd = parts[0];
  const firstArg = parts[1];

  // If the first argument looks like a subcommand word (e.g. "get", "log", "ps"),
  // try the compound key first (e.g. "kubectl-get", "git-log", "docker-ps").
  // The hook will fall back to the simple key if no compound manifest exists.
  if (firstArg && SUBCOMMAND_PATTERN.test(firstArg)) {
    return { key: buildKey(cmd, firstArg), cmd, subcommand: firstArg };
  }

  return { key: buildKey(cmd), cmd };
}

/**
 * True if the command is safe to pipe through a filter.
 * Avoids wrapping commands with output redirection or complex constructs.
 */
function isFilterable(command: string): boolean {
  return !command.includes('>') && !command.includes('<') && !command.match(/\bexec\b/);
}

function buildAdditionalContext(manifest: CommandManifest): string {
  const name = manifest.subcommand
    ? `${manifest.command} ${manifest.subcommand}`
    : manifest.command;

  const lines: string[] = [`[kcp] ${name}: ${manifest.description}`];

  if (manifest.syntax.usage) {
    lines.push(`Usage: ${manifest.syntax.usage}`);
  }

  const flags = manifest.syntax.key_flags.slice(0, 5);
  if (flags.length > 0) {
    lines.push('Key flags:');
    for (const f of flags) {
      const hint = f.use_when ? `  → ${f.use_when}` : '';
      lines.push(`  ${f.flag}: ${f.description}${hint}`);
    }
  }

  const prefs = manifest.syntax.preferred_invocations?.slice(0, 3) ?? [];
  if (prefs.length > 0) {
    lines.push('Prefer:');
    for (const p of prefs) {
      lines.push(`  ${p.invocation}  # ${p.use_when}`);
    }
  }

  if (manifest.generated) {
    lines.push('(auto-generated manifest — improve it at ~/.kcp/commands/)');
  }

  return lines.join('\n');
}

export async function runHook(): Promise<void> {
  let raw: string;
  try {
    raw = readFileSync('/dev/stdin', 'utf-8');
  } catch {
    process.exit(0);
  }

  let input: { tool_name?: string; tool_input?: { command?: string } };
  try {
    input = JSON.parse(raw);
  } catch {
    process.exit(0);
  }

  if (input.tool_name !== 'Bash') process.exit(0);

  const command = input.tool_input?.command;
  if (!command || typeof command !== 'string') process.exit(0);

  const parsed = parseCommand(command);
  if (!parsed) process.exit(0);

  let manifest = resolveManifest(parsed.key);

  // Compound key not found — try the simple command key (e.g. "npm" when "npm-install" absent)
  if (!manifest && parsed.subcommand) {
    manifest = resolveManifest(parsed.cmd);
  }

  // Auto-generate if still missing (Phase C)
  if (!manifest) {
    manifest = generateManifest(parsed.cmd, parsed.subcommand);
  }

  if (!manifest) process.exit(0);

  process.stdout.write(JSON.stringify(buildHookOutput(command, parsed.key, manifest)));
  process.exit(0);
}

/** The filter command the installer puts in place: ~/.kcp/kcp-filter. */
export function defaultFilterCommand(): string {
  return join(homedir(), '.kcp', 'kcp-filter');
}

/**
 * The hook's reply. It never carries a permissionDecision: "allow" would skip Claude Code's
 * permission prompt for the whole command, and parseCommand only reads the first segment, so
 * "jq . f; rm -rf x" would run unasked. Claude Code checks the user's permission rules against the
 * rewritten command, so the filter is a dedicated command the installer allows by full path,
 * never a curl or node invocation. A filter path containing whitespace would need quoting and would
 * no longer match that rule, so the command is then left unfiltered.
 */
export function buildHookOutput(
  command: string,
  key: string,
  manifest: CommandManifest,
  filterCommand: string = defaultFilterCommand(),
  port = 7734,
): { hookSpecificOutput: Record<string, unknown> } {
  const out: Record<string, unknown> = {
    hookEventName: 'PreToolUse',
    additionalContext: buildAdditionalContext(manifest),
  };

  // Phase B: pipe through kcp-filter for large-output commands
  const schema = manifest.output_schema;
  if (schema?.enable_filter && isFilterable(command) && !/\s/.test(filterCommand)) {
    out['updatedInput'] = { command: `${command} | ${filterCommand} ${key} ${port}` };
  }

  return { hookSpecificOutput: out };
}
