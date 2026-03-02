import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { resolveManifest, buildKey } from './resolver.js';
import { generateManifest } from './generator.js';
import type { CommandManifest } from './model.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

// Subcommands we track per compound command
const GIT_SUBCOMMANDS = new Set(['log', 'diff', 'status', 'branch', 'show', 'blame', 'stash']);
const DOCKER_SUBCOMMANDS = new Set(['ps', 'logs', 'exec', 'run', 'images', 'inspect']);

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

  if (cmd === 'git' && parts[1] && GIT_SUBCOMMANDS.has(parts[1])) {
    return { key: buildKey('git', parts[1]), cmd: 'git', subcommand: parts[1] };
  }
  if (cmd === 'docker' && parts[1] && DOCKER_SUBCOMMANDS.has(parts[1])) {
    return { key: buildKey('docker', parts[1]), cmd: 'docker', subcommand: parts[1] };
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

  // Auto-generate if missing (Phase C)
  if (!manifest) {
    manifest = generateManifest(parsed.cmd, parsed.subcommand);
  }

  if (!manifest) process.exit(0);

  const additionalContext = buildAdditionalContext(manifest);

  const hookOutput: Record<string, unknown> = {
    hookSpecificOutput: {
      hookEventName: 'PreToolUse',
      permissionDecision: 'allow',
      additionalContext,
    },
  };

  // Phase B: pipe through filter for large-output commands
  const schema = manifest.output_schema;
  if (schema?.enable_filter && isFilterable(command)) {
    const filterScript = join(__dirname, 'cli.js');
    const wrapped = `${command} | node "${filterScript}" filter "${parsed.key}"`;
    (hookOutput['hookSpecificOutput'] as Record<string, unknown>)['updatedInput'] = {
      command: wrapped,
    };
  }

  process.stdout.write(JSON.stringify(hookOutput));
  process.exit(0);
}
