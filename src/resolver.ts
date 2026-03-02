import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { homedir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { dirname } from 'node:path';
import { parseManifest } from './parser.js';
import type { CommandManifest } from './model.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

// Bundled primed manifests — packaged with the tool
const PRIMED_DIR = join(__dirname, '..', 'commands');

// User-level overrides — platform-specific tweaks, generated manifests
const USER_DIR = join(homedir(), '.kcp', 'commands');

/**
 * Resolve a command manifest using the lookup chain:
 *   1. .kcp/commands/<key>.yaml  (project-local override)
 *   2. ~/.kcp/commands/<key>.yaml (user-level override / generated)
 *   3. <package>/commands/<key>.yaml (primed/bundled)
 *
 * key examples: "ls", "ps", "git-log", "docker-ps"
 */
export function resolveManifest(key: string): CommandManifest | null {
  const filename = `${key}.yaml`;

  const candidates = [
    join(process.cwd(), '.kcp', 'commands', filename),
    join(USER_DIR, filename),
    join(PRIMED_DIR, filename),
  ];

  for (const path of candidates) {
    if (existsSync(path)) {
      try {
        return parseManifest(path);
      } catch {
        // Corrupt manifest — continue to next candidate
      }
    }
  }

  return null;
}

/**
 * Build the manifest lookup key from a parsed command.
 * "git" + "log" → "git-log"
 * "ls" + undefined → "ls"
 */
export function buildKey(cmd: string, subcommand?: string): string {
  return subcommand ? `${cmd}-${subcommand}` : cmd;
}
