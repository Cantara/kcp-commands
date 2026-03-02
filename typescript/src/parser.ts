import { readFileSync } from 'node:fs';
import { load } from 'js-yaml';
import type { CommandManifest } from './model.js';

export function parseManifest(filePath: string): CommandManifest {
  const content = readFileSync(filePath, 'utf-8');
  const data = load(content) as Record<string, unknown>;

  if (!data || typeof data !== 'object') {
    throw new Error(`Invalid manifest: empty or non-object YAML in ${filePath}`);
  }
  if (!data['command'] || typeof data['command'] !== 'string') {
    throw new Error(`Invalid manifest: missing 'command' field in ${filePath}`);
  }

  return data as unknown as CommandManifest;
}
