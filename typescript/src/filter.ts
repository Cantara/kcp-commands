import { readFileSync } from 'node:fs';
import { resolveManifest } from './resolver.js';
import { checkDeviation } from './deviation.js';
import type { FilterStats } from './deviation.js';

/**
 * Filter stdin through a command manifest's noise patterns.
 * Used as the output stage of a piped command:
 *   ps aux | node cli.js filter ps
 *
 * After filtering, runs deviation detection to auto-tune the manifest
 * when actual output diverges from the schema (truncation, stale patterns,
 * over-configured max_lines).
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
  const maxLines = schema.max_lines ?? Infinity;

  const lines = stdin.split('\n');
  const rawLines = lines.filter(l => l.trim() !== '').length;

  // Build compiled patterns with per-pattern hit counters.
  const patterns: Array<{ re: RegExp; pattern: string; hits: number }> =
    (schema.noise_patterns ?? []).map(p => ({ re: new RegExp(p.pattern), pattern: p.pattern, hits: 0 }));

  // Filter: strip blank lines and lines matched by any noise pattern.
  const filtered = lines.filter(line => {
    if (line.trim() === '') return false;
    for (const p of patterns) {
      if (p.re.test(line)) { p.hits++; return false; }
    }
    return true;
  });

  const wasTruncated = filtered.length > maxLines;
  const output = wasTruncated ? filtered.slice(0, maxLines) : filtered;
  const remaining = filtered.length - output.length;

  process.stdout.write(output.join('\n'));

  if (remaining > 0) {
    const msg = (schema.truncation_message ?? '... {remaining} more lines.')
      .replace('{remaining}', String(remaining));
    process.stdout.write('\n' + msg + '\n');
  } else {
    process.stdout.write('\n');
  }

  // Deviation detection — non-blocking, never throws.
  const stats: FilterStats = {
    key,
    rawLines,
    filteredLines: filtered.length,
    wasTruncated,
    patternHits: new Map(patterns.map(p => [p.pattern, p.hits])),
    manifest,
  };
  checkDeviation(stats);
}
