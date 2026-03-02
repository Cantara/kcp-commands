import { execSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { homedir } from 'node:os';
import { dump } from 'js-yaml';
import type { CommandManifest } from './model.js';

const USER_DIR = join(homedir(), '.kcp', 'commands');

/**
 * Auto-generate a minimal manifest for a command by running --help.
 * Saves to ~/.kcp/commands/<key>.yaml for future use.
 *
 * Generated manifests are minimal — they capture the help text description
 * and mark themselves as generated so humans can improve them.
 */
export function generateManifest(cmd: string, subcommand?: string): CommandManifest | null {
  const invocation = subcommand ? `${cmd} ${subcommand} --help` : `${cmd} --help`;

  let helpText: string;
  try {
    helpText = execSync(invocation, { encoding: 'utf-8', timeout: 5000, stdio: ['pipe', 'pipe', 'pipe'] });
  } catch (err: unknown) {
    // Some commands write help to stderr
    if (err && typeof err === 'object' && 'stderr' in err && typeof (err as { stderr: unknown }).stderr === 'string') {
      helpText = (err as { stderr: string }).stderr;
    } else {
      return null;
    }
  }

  if (!helpText || helpText.trim().length < 10) return null;

  // Extract the first non-empty description line
  const lines = helpText.split('\n').map(l => l.trim()).filter(Boolean);
  const description = lines[0] ?? `${cmd}${subcommand ? ' ' + subcommand : ''} command`;

  // Extract flag lines (heuristic: lines starting with -, --, or short option-like patterns)
  const flagLines = lines.filter(l => /^-{1,2}\w/.test(l)).slice(0, 8);
  const key_flags = flagLines.map(line => {
    const match = line.match(/^(-{1,2}[\w-]+(?:,\s*-{1,2}[\w-]+)?)\s+(.*)/);
    if (match) {
      return { flag: match[1].trim(), description: match[2].trim().slice(0, 80) };
    }
    return { flag: line.slice(0, 20), description: line.slice(20, 100).trim() };
  });

  const manifest: CommandManifest = {
    command: cmd,
    ...(subcommand ? { subcommand } : {}),
    platform: 'all',
    description,
    syntax: {
      key_flags,
    },
    generated: true,
    generated_at: new Date().toISOString(),
  };

  // Save to user dir for future sessions
  try {
    mkdirSync(USER_DIR, { recursive: true });
    const key = subcommand ? `${cmd}-${subcommand}` : cmd;
    const outPath = join(USER_DIR, `${key}.yaml`);
    writeFileSync(outPath, dump(manifest, { lineWidth: 100 }));
  } catch {
    // Non-fatal — manifest still returned for this session
  }

  return manifest;
}
