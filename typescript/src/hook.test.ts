import { describe, it, expect } from 'vitest';
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

describe('buildHookOutput', () => {
  it('adds context but makes no permission decision, even for a compound command', () => {
    const out = buildHookOutput('diff a b; rm -rf /tmp/x', 'diff', manifest(false), '/home/u/.kcp/kcp-filter');
    expect(out.hookSpecificOutput).not.toHaveProperty('permissionDecision');
    expect(out.hookSpecificOutput.additionalContext).toContain('diff');
    expect(out.hookSpecificOutput).not.toHaveProperty('updatedInput');
  });

  it('pipes a filtered command into kcp-filter, not curl or node', () => {
    const out = buildHookOutput('diff a b', 'diff', manifest(true), '/home/u/.kcp/kcp-filter');
    expect(out.hookSpecificOutput).not.toHaveProperty('permissionDecision');
    expect(out.hookSpecificOutput.updatedInput).toEqual({ command: 'diff a b | /home/u/.kcp/kcp-filter diff 7734' });
  });

  it('leaves the command unfiltered when the filter path would need quoting', () => {
    const out = buildHookOutput('diff a b', 'diff', manifest(true), '/home/a user/.kcp/kcp-filter');
    expect(out.hookSpecificOutput).not.toHaveProperty('updatedInput');
  });
});
