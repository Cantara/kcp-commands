import { spawnSync } from 'node:child_process';
import { accessSync, constants, closeSync, mkdirSync, openSync, readSync, statSync, writeFileSync } from 'node:fs';
import { delimiter, join } from 'node:path';
import { homedir } from 'node:os';
import { dump } from 'js-yaml';
import type { CommandManifest } from './model.js';

const USER_DIR = join(homedir(), '.kcp', 'commands');
const DEFAULT_TIMEOUT_MS = 3000;

/** A plain program or subcommand name: no path, no '=', no shell syntax. */
const PLAIN_NAME = /^[A-Za-z0-9][A-Za-z0-9._+-]*$/;
const FLAG_LINE = /^\s{0,4}(-{1,2}[\w-]+(?:,\s*-{1,2}[\w-]+)?)\s+(.*)/;
const USAGE = /\busage\b/i;

export interface GenerateOptions {
  /** Where generated manifests are saved (default ~/.kcp/commands). */
  userDir?: string;
  /** The PATH searched for the program (default process.env.PATH). */
  path?: string;
  /** How long the --help probe may run (default 3 s). */
  timeoutMs?: number;
}

/** The executable regular file named `cmd` in the first PATH directory that has one. */
export function findOnPath(cmd: string, path: string): string | null {
  for (const dir of path.split(delimiter)) {
    if (!dir) continue;
    const p = join(dir, cmd);
    try {
      if (!statSync(p).isFile()) continue;
      accessSync(p, constants.X_OK);
      return p;
    } catch {
      // not here
    }
  }
  return null;
}

/** True when the file (after following symlinks) starts with "#!". Unreadable counts as a script. */
export function isScript(file: string): boolean {
  try {
    const fd = openSync(file, 'r');
    try {
      const head = Buffer.alloc(2);
      return readSync(fd, head, 0, 2, 0) === 2 && head.toString('latin1') === '#!';
    } finally {
      closeSync(fd);
    }
  } catch {
    return true;
  }
}

/**
 * Help text names its usage or lists flags. Anything else (an error such as "command not
 * found", a command's real output) is not a manifest and must not be cached as one.
 */
export function looksLikeHelp(text: string): boolean {
  return USAGE.test(text) || text.split('\n').some(l => FLAG_LINE.test(l));
}

/**
 * Run `argv` without a shell, with stdin closed, at most `timeoutMs`. stdout and stderr are
 * joined (some tools print help on stderr). Returns null if it could not run or did not finish.
 */
export function runHelp(argv: string[], timeoutMs: number): string | null {
  const r = spawnSync(argv[0], argv.slice(1), {
    shell: false,
    stdio: ['ignore', 'pipe', 'pipe'],
    timeout: timeoutMs,
    killSignal: 'SIGKILL',
    maxBuffer: 1024 * 1024,
    encoding: 'utf-8',
  });
  if (r.error || r.signal) return null; // failed to start, timed out, or killed
  return `${r.stdout ?? ''}${r.stderr ?? ''}`;
}

/**
 * Auto-generate a minimal manifest for a command by running `<cmd> [<subcommand>] --help`.
 * Saves to ~/.kcp/commands/<key>.yaml for future use.
 *
 * Running a command the agent has not run yet is only safe if it treats --help as a request
 * for help, so the probe is deliberately narrow: no shell (text from the agent's command line
 * is never interpreted), only a program found on PATH and never a script (a file starting with
 * "#!" might just do its real work), stdin closed and a timeout, and output that does not look
 * like help is not saved.
 *
 * Generated manifests are minimal — they capture the help text description
 * and mark themselves as generated so humans can improve them.
 */
export function generateManifest(cmd: string, subcommand?: string, opts: GenerateOptions = {}): CommandManifest | null {
  if (!PLAIN_NAME.test(cmd)) return null;
  if (subcommand !== undefined && !PLAIN_NAME.test(subcommand)) return null;

  const program = findOnPath(cmd, opts.path ?? process.env.PATH ?? '');
  if (!program || isScript(program)) return null;

  const helpText = runHelp([program, ...(subcommand ? [subcommand] : []), '--help'], opts.timeoutMs ?? DEFAULT_TIMEOUT_MS);
  if (!helpText || helpText.trim().length < 10 || !looksLikeHelp(helpText)) return null;

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
  const userDir = opts.userDir ?? USER_DIR;
  try {
    mkdirSync(userDir, { recursive: true });
    const key = subcommand ? `${cmd}-${subcommand}` : cmd;
    const outPath = join(userDir, `${key}.yaml`);
    writeFileSync(outPath, dump(manifest, { lineWidth: 100 }));
  } catch {
    // Non-fatal — manifest still returned for this session
  }

  return manifest;
}
