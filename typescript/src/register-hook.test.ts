import { describe, it, expect } from 'vitest';
import { registerHook, registerPostHook, type ClaudeSettings } from './register-hook.js';

const KCP_DIR = '/home/user/.kcp';
const HOOK_CMD = `bash "${KCP_DIR}/hook.sh"`;

describe('registerHook', () => {
  it('adds a PreToolUse entry to empty settings', () => {
    const result = registerHook({}, KCP_DIR, HOOK_CMD, 'java');
    expect(result.hooks?.PreToolUse).toHaveLength(1);
    expect(result.hooks?.PreToolUse?.[0].matcher).toBe('Bash');
    expect(result.hooks?.PreToolUse?.[0].hooks[0].command).toBe(HOOK_CMD);
  });

  it('is idempotent — running twice produces exactly one entry', () => {
    let settings: ClaudeSettings = {};
    settings = registerHook(settings, KCP_DIR, HOOK_CMD, 'java');
    settings = registerHook(settings, KCP_DIR, HOOK_CMD, 'java');
    expect(settings.hooks?.PreToolUse).toHaveLength(1);
  });

  it('replaces a stale entry on re-install', () => {
    const settings: ClaudeSettings = {
      hooks: {
        PreToolUse: [{
          matcher: 'Bash',
          hooks: [{ type: 'command', command: HOOK_CMD, timeout: 10 }]
        }]
      }
    };
    const result = registerHook(settings, KCP_DIR, HOOK_CMD, 'java');
    expect(result.hooks?.PreToolUse).toHaveLength(1);
  });

  it('preserves unrelated PreToolUse entries', () => {
    const settings: ClaudeSettings = {
      hooks: {
        PreToolUse: [{
          matcher: 'Bash',
          hooks: [{ type: 'command', command: 'some-other-tool' }]
        }]
      }
    };
    const result = registerHook(settings, KCP_DIR, HOOK_CMD, 'java');
    expect(result.hooks?.PreToolUse).toHaveLength(2);
  });

  it('upgrades a legacy catch-all matcher entry', () => {

    const settings: ClaudeSettings = {
      hooks: {
        PreToolUse: [
          { matcher: '', hooks: [{ type: 'command', command: `${KCP_DIR}/hook.sh` }] },
          { matcher: 'Bash', hooks: [{ type: 'command', command: HOOK_CMD, timeout: 10 }] }
        ]
      }
    };
    const result = registerHook(settings, KCP_DIR, HOOK_CMD, 'java');
    expect(result.hooks?.PreToolUse).toHaveLength(1);
    expect(result.hooks?.PreToolUse?.[0].matcher).toBe('Bash');
  });
});

const POST_HOOK_CMD = `bash "${KCP_DIR}/post-hook.sh"`;

describe('registerPostHook', () => {
  it('adds a PostToolUse entry to empty settings', () => {
    const result = registerPostHook({}, KCP_DIR, POST_HOOK_CMD);
    expect(result.hooks?.PostToolUse).toHaveLength(1);
    expect(result.hooks?.PostToolUse?.[0].matcher).toBe('Bash');
    expect(result.hooks?.PostToolUse?.[0].hooks[0].command).toBe(POST_HOOK_CMD);
  });

  it('is idempotent — running twice produces exactly one PostToolUse entry', () => {
    let settings: ClaudeSettings = {};
    settings = registerPostHook(settings, KCP_DIR, POST_HOOK_CMD);
    settings = registerPostHook(settings, KCP_DIR, POST_HOOK_CMD);
    expect(settings.hooks?.PostToolUse).toHaveLength(1);
  });

  it('does not affect PreToolUse entries', () => {
    let settings: ClaudeSettings = {};
    settings = registerHook(settings, KCP_DIR, HOOK_CMD, 'java');
    settings = registerPostHook(settings, KCP_DIR, POST_HOOK_CMD);
    expect(settings.hooks?.PreToolUse).toHaveLength(1);
    expect(settings.hooks?.PostToolUse).toHaveLength(1);
  });

  it('preserves unrelated PostToolUse entries', () => {
    const settings: ClaudeSettings = {
      hooks: {
        PostToolUse: [{ matcher: 'Bash', hooks: [{ type: 'command', command: 'other-tool' }] }]
      }
    };
    const result = registerPostHook(settings, KCP_DIR, POST_HOOK_CMD);
    expect(result.hooks?.PostToolUse).toHaveLength(2);
  });
});
