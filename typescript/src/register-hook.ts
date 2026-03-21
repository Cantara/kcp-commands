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
 * Registers the kcp-commands PreToolUse hook in Claude settings.
 * Idempotent: removes any existing entry whose command references kcpDir
 * before adding the new one.
 */
export function registerHook(
  settings: ClaudeSettings,
  kcpDir: string,
  hookCommand: string,
  _mode: string
): ClaudeSettings {
  settings.hooks ??= {};
  settings.hooks.PreToolUse ??= [];

  // Remove any existing kcp hook entry (idempotency + upgrade support).
  // Match on kcpDir so both legacy catch-all and Bash-matcher entries are removed.
  settings.hooks.PreToolUse = settings.hooks.PreToolUse.filter(
    group => !group.hooks?.some(h => h.command?.includes(kcpDir))
  );

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
 * Idempotent: removes any existing PostToolUse entry referencing kcpDir.
 */
export function registerPostHook(
  settings: ClaudeSettings,
  kcpDir: string,
  postHookCommand: string
): ClaudeSettings {
  settings.hooks ??= {};
  settings.hooks.PostToolUse ??= [];

  settings.hooks.PostToolUse = settings.hooks.PostToolUse.filter(
    group => !group.hooks?.some(h => h.command?.includes(kcpDir))
  );

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
