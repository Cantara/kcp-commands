export interface HookEntry {
  type: string;
  command: string;
  timeout?: number;
  statusMessage?: string;
}

export interface HookGroup {
  matcher: string;
  hooks: HookEntry[];
}

export interface ClaudeSettings {
  hooks?: {
    PreToolUse?:  HookGroup[];
    PostToolUse?: HookGroup[];
    [key: string]: HookGroup[] | undefined;
  };
  [key: string]: unknown;
}

/**
 * Is this one of kcp-commands' own hooks (hook.sh or post-hook.sh in kcpDir)?
 * Legacy catch-all and Bash-matcher entries both run these scripts.
 */
export function isKcpCommandsHook(h: HookEntry | undefined, kcpDir: string): boolean {
  return typeof h?.command === 'string' &&
    (h.command.includes(`${kcpDir}/hook.sh`) || h.command.includes(`${kcpDir}/post-hook.sh`));
}

/**
 * Removes kcp-commands' own hooks and nothing else. A group is dropped only when no hook is left
 * in it: dropping every group that mentions kcpDir also deleted other tools' hooks kept under the
 * same directory (kcp-memory's) and any hook that shared a group with ours.
 */
export function withoutKcpCommandsHooks(groups: HookGroup[], kcpDir: string): HookGroup[] {
  return groups
    .map(g => Array.isArray(g?.hooks) ? { ...g, hooks: g.hooks.filter(h => !isKcpCommandsHook(h, kcpDir)) } : g)
    .filter(g => !Array.isArray(g?.hooks) || g.hooks.length > 0);
}

/**
 * Registers the kcp-commands PreToolUse hook in Claude settings.
 * Idempotent: removes kcp-commands' own earlier hooks (only those) before adding the new one.
 */
export function registerHook(
  settings: ClaudeSettings,
  kcpDir: string,
  hookCommand: string,
  _mode: string
): ClaudeSettings {
  settings.hooks ??= {};
  settings.hooks.PreToolUse ??= [];

  // Remove kcp-commands' own earlier hooks (idempotency + upgrade support), and nothing else.
  settings.hooks.PreToolUse = withoutKcpCommandsHooks(settings.hooks.PreToolUse, kcpDir);

  settings.hooks.PreToolUse.push({
    matcher: 'Bash',
    hooks: [{
      type:          'command',
      command:       hookCommand,
      timeout:       10,
      statusMessage: 'kcp-commands: looking up manifest...'
    }]
  });

  return settings;
}

/**
 * Registers the kcp-memory PostToolUse hook (output capture) in Claude settings.
 * Idempotent: removes kcp-commands' own earlier PostToolUse hooks (only those).
 */
export function registerPostHook(
  settings: ClaudeSettings,
  kcpDir: string,
  postHookCommand: string
): ClaudeSettings {
  settings.hooks ??= {};
  settings.hooks.PostToolUse ??= [];

  settings.hooks.PostToolUse = withoutKcpCommandsHooks(settings.hooks.PostToolUse, kcpDir);

  settings.hooks.PostToolUse.push({
    matcher: 'Bash',
    hooks: [{
      type:    'command',
      command: postHookCommand,
      timeout: 10
    }]
  });

  return settings;
}
