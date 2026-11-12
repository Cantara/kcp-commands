import { describe, it, expect, beforeEach } from 'vitest';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { buildHookOutput } from './hook.js';
import type { CommandManifest } from './model.js';

// The hook must never make Claude Code's permission decision: "allow" skips the permission prompt
// for the whole command, and only the first segment is parsed, so "jq . f; rm -rf x" ran unasked.
const manifest = (enableFilter: boolean): CommandManifest =>
  ({
    command: 'diff',
    description: 'compare files line by line',
    syntax: { usage: 'diff [options] a b', key_flags: [] },
    output_schema: enableFilter ? { enable_filter: true } : undefined,
  }) as unknown as CommandManifest;

// A stand-in for an installed ~/.kcp/kcp-filter.
const installFilter = (dir: string): string => {
  mkdirSync(dir, { recursive: true });
  const f = join(dir, 'kcp-filter');
  writeFileSync(f, '#!/bin/sh\ncat\n', { mode: 0o755 });
  return f;
};

describe('buildHookOutput', () => {
  let tmp: string;
  let filter: string;
  beforeEach(() => {
    tmp = mkdtempSync(join(tmpdir(), 'kcp-hook-'));
    filter = installFilter(join(tmp, '.kcp'));
  });

  it('adds context but makes no permission decision, even for a compound command', () => {
    const out = buildHookOutput('diff a b; rm -rf /tmp/x', 'diff', manifest(false), filter);
    expect(out.hookSpecificOutput).not.toHaveProperty('permissionDecision');
    expect(out.hookSpecificOutput.additionalContext).toContain('diff');
    expect(out.hookSpecificOutput).not.toHaveProperty('updatedInput');
  });

  it('pipes a filtered command into kcp-filter, not curl or node', () => {
    const out = buildHookOutput('diff a b', 'diff', manifest(true), filter);
    expect(out.hookSpecificOutput).not.toHaveProperty('permissionDecision');
    expect(out.hookSpecificOutput.updatedInput).toEqual({ command: `diff a b | ${filter} diff 7734` });
  });

  it('leaves the command unfiltered when the filter path would need quoting', () => {
    const spaced = installFilter(join(tmp, 'a user', '.kcp'));
    const out = buildHookOutput('diff a b', 'diff', manifest(true), spaced);
    expect(out.hookSpecificOutput).not.toHaveProperty('updatedInput');
  });

  it('leaves the command unfiltered when the filter is not installed', () => {
    const out = buildHookOutput('diff a b', 'diff', manifest(true), join(tmp, 'nowhere', 'kcp-filter'));
    expect(out.hookSpecificOutput).not.toHaveProperty('updatedInput');
  });
});
