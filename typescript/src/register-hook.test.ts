import { describe, it, expect } from 'vitest';
import { registerHook, registerPostHook, withoutKcpCommandsHooks, isKcpCommandsHook, type ClaudeSettings } from './register-hook.js';

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

  it('keeps another tool\'s hook that lives under the same directory (kcp-memory)', () => {
    const memoryHook = `python3 ${KCP_DIR}/memory-hook.py`;
    const settings: ClaudeSettings = {
      hooks: { PostToolUse: [{ matcher: '', hooks: [{ type: 'command', command: memoryHook }] }] }
    };
    const result = registerPostHook(settings, KCP_DIR, POST_HOOK_CMD);
    const commands = result.hooks?.PostToolUse?.flatMap(g => g.hooks.map(h => h.command));
    expect(commands).toEqual([memoryHook, POST_HOOK_CMD]);
  });

  it('keeps a hook that shares a group with ours, and drops only our own', () => {
    const settings: ClaudeSettings = {
      hooks: {
        PostToolUse: [{ matcher: 'Bash', hooks: [
          { type: 'command', command: POST_HOOK_CMD },
          { type: 'command', command: 'pr-opened.sh' }
        ] }]
      }
    };
    const result = registerPostHook(settings, KCP_DIR, POST_HOOK_CMD);
    expect(result.hooks?.PostToolUse).toHaveLength(2);
    expect(result.hooks?.PostToolUse?.[0].hooks.map(h => h.command)).toEqual(['pr-opened.sh']);
    expect(result.hooks?.PostToolUse?.[1].hooks.map(h => h.command)).toEqual([POST_HOOK_CMD]);
  });
});

describe('withoutKcpCommandsHooks', () => {
  it('drops a group only when nothing is left in it', () => {
    const groups = [
      { matcher: 'Bash', hooks: [{ type: 'command', command: HOOK_CMD }] },
      { matcher: 'Bash', hooks: [{ type: 'command', command: HOOK_CMD }, { type: 'command', command: 'other' }] }
    ];
    expect(withoutKcpCommandsHooks(groups, KCP_DIR)).toEqual([
      { matcher: 'Bash', hooks: [{ type: 'command', command: 'other' }] }
    ]);
  });

  it('matches only hook.sh and post-hook.sh, not other files in the directory', () => {
    expect(isKcpCommandsHook({ type: 'command', command: `bash "${KCP_DIR}/hook.sh"` }, KCP_DIR)).toBe(true);
    expect(isKcpCommandsHook({ type: 'command', command: `bash "${KCP_DIR}/post-hook.sh"` }, KCP_DIR)).toBe(true);
    expect(isKcpCommandsHook({ type: 'command', command: `python3 ${KCP_DIR}/hooks/prompt-router.py` }, KCP_DIR)).toBe(false);
    expect(isKcpCommandsHook({ type: 'command', command: `python3 ${KCP_DIR}/memory-hook.py` }, KCP_DIR)).toBe(false);
  });
});
