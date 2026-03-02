import { readFileSync } from 'node:fs';
import { resolveManifest } from './resolver.js';

/**
 * Filter stdin through a command manifest's noise patterns.
 * Used as the output stage of a piped command:
 *   ps aux | node cli.js filter ps
 */
export async function runFilter(key: string): Promise<void> {
  let stdin: string;
  try {
    stdin = readFileSync('/dev/stdin', 'utf-8');
  } catch {
    process.exit(0);
  }

  const manifest = resolveManifest(key);

  if (!manifest?.output_schema) {
    process.stdout.write(stdin);
    return;
  }

  const schema = manifest.output_schema;
  const noisePatterns = (schema.noise_patterns ?? []).map(p => new RegExp(p.pattern));
  const maxLines = schema.max_lines ?? Infinity;

  const lines = stdin.split('\n');

  const filtered = lines.filter(line => {
    if (line.trim() === '') return false; // always strip blank lines
    return !noisePatterns.some(re => re.test(line));
  });

  const truncated = filtered.length > maxLines
    ? filtered.slice(0, maxLines)
    : filtered;

  const remaining = filtered.length - truncated.length;

  process.stdout.write(truncated.join('\n'));

  if (remaining > 0) {
    const msg = (schema.truncation_message ?? '... {remaining} more lines.')
      .replace('{remaining}', String(remaining));
    process.stdout.write('\n' + msg + '\n');
  } else {
    process.stdout.write('\n');
  }
}
