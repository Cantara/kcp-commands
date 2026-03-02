#!/usr/bin/env node
import { runHook } from './hook.js';
import { runFilter } from './filter.js';

const [, , mode, ...args] = process.argv;

if (mode === 'filter') {
  const key = args[0];
  if (!key) {
    process.stderr.write('Usage: kcp-commands filter <command-key>\n');
    process.exit(1);
  }
  await runFilter(key);
} else {
  // Default: PreToolUse hook mode — reads hook JSON from stdin
  await runHook();
}
