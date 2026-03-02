import { appendFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { homedir } from 'node:os';
import { dump } from 'js-yaml';
import type { CommandManifest } from './model.js';

const KCP_DIR       = join(homedir(), '.kcp');
const DEVIATION_LOG = join(KCP_DIR, 'deviations.log');
const USER_DIR      = join(KCP_DIR, 'commands');

export interface FilterStats {
  key: string;
  rawLines: number;           // lines before filtering
  filteredLines: number;      // lines after noise removal, before max_lines cap
  wasTruncated: boolean;      // filtered count exceeded max_lines
  patternHits: Map<string, number>; // regex string → number of lines matched
  manifest: CommandManifest;
}

/**
 * Inspect filter stats against manifest schema and record any deviations.
 *
 * Deviation types:
 *   truncation      — raw output well above the max_lines cap
 *                     → auto-tunes a user-level manifest with headroom
 *   stale_pattern   — a noise_pattern matched zero lines (possibly outdated)
 *                     → logged only; human should review
 *   over_configured — max_lines is set but output is tiny (< 10% of cap)
 *                     → logged only; suggests loosening the schema
 */
export function checkDeviation(stats: FilterStats): void {
  const { manifest, rawLines, wasTruncated, patternHits } = stats;
  const schema = manifest.output_schema;
  if (!schema?.enable_filter) return;

  const findings: string[] = [];

  // 1. Significant truncation: actual output well above the configured cap.
  //    Threshold: 1.5× to avoid noise from occasional outlier runs.
  if (wasTruncated && schema.max_lines && rawLines > schema.max_lines * 1.5) {
    findings.push(`truncation: raw_lines=${rawLines}, max_lines=${schema.max_lines}`);
  }

  // 2. Stale noise patterns: patterns that never matched.
  //    Only flag when output is substantial (≥ 10 lines) to avoid false
  //    positives on commands that happened to produce little output.
  if (rawLines >= 10) {
    for (const [pattern, hits] of patternHits) {
      if (hits === 0) {
        findings.push(`stale_pattern: "${pattern}" matched 0/${rawLines} lines`);
      }
    }
  }

  // 3. Over-configured: max_lines set but output is tiny.
  //    Suggests the manifest was written for a noisier context than reality.
  if (!wasTruncated && schema.max_lines && rawLines > 0 && rawLines < schema.max_lines / 10) {
    findings.push(`over_configured: max_lines=${schema.max_lines} but only ${rawLines} raw lines`);
  }

  if (findings.length === 0) return;

  logDeviation(stats.key, findings);

  // Auto-tune only on significant truncation — the only case where we know
  // the right direction (up) with high confidence.
  if (wasTruncated && schema.max_lines && rawLines > schema.max_lines * 1.5) {
    tuneLocalManifest(stats);
  }
}

function logDeviation(key: string, findings: string[]): void {
  try {
    mkdirSync(KCP_DIR, { recursive: true });
    const ts = new Date().toISOString();
    appendFileSync(DEVIATION_LOG, `[${ts}] ${key}: ${findings.join(' | ')}\n`);
  } catch {
    // Non-fatal — deviation logging must never break the filter output
  }
}

/**
 * Write an adjusted manifest to ~/.kcp/commands/<key>.yaml.
 * Sets max_lines to rawLines × 1.3 rounded up to the nearest 25,
 * giving 30% headroom without excessive over-allocation.
 *
 * This is a user-level override — it takes precedence over the primed
 * manifest and is picked up on the next hook invocation without restart.
 */
function tuneLocalManifest(stats: FilterStats): void {
  const { key, rawLines, manifest } = stats;
  const tuned = Math.ceil((rawLines * 1.3) / 25) * 25;

  const updated: CommandManifest = {
    ...manifest,
    output_schema: {
      ...manifest.output_schema!,
      max_lines: tuned,
    },
  };

  try {
    mkdirSync(USER_DIR, { recursive: true });
    writeFileSync(join(USER_DIR, `${key}.yaml`), dump(updated, { lineWidth: 100 }));
  } catch {
    // Non-fatal
  }
}
