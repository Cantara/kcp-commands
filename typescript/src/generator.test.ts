import { describe, it, expect, beforeEach } from 'vitest';
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readdirSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { generateManifest, looksLikeHelp, runHelp } from './generator.js';

// The --help probe runs a command the agent has not run yet, so it must never run anything
// that would not treat --help as a request for help. The scripts below write a marker file
// when they run: "not run" is asserted on the marker, not inferred from the return value.
const posix = process.platform !== 'win32';

describe.runIf(posix)('generateManifest', () => {
  let tmp: string;
  let userDir: string;
  beforeEach(() => {
    tmp = mkdtempSync(join(tmpdir(), 'kcp-gen-'));
    userDir = join(tmp, 'manifests');
  });

  const script = (dir: string, name: string, marker: string) => {
    mkdirSync(dir, { recursive: true });
    const s = join(dir, name);
    writeFileSync(s, `#!/bin/sh\ntouch '${marker}'\necho "usage: did real work with: $*"\n`);
    chmodSync(s, 0o755);
    return s;
  };
  const saved = () => (existsSync(userDir) ? readdirSync(userDir) : []);

  it('never runs a script found on PATH', () => {
    const bin = join(tmp, 'bin'), marker = join(tmp, 'ran');
    script(bin, 'deploy-everything', marker);
    expect(generateManifest('deploy-everything', undefined, { userDir, path: bin })).toBeNull();
    expect(generateManifest('deploy-everything', 'prod', { userDir, path: bin })).toBeNull();
    expect(existsSync(marker)).toBe(false);
    expect(saved()).toEqual([]);
  });

  it('never runs a script behind a symlink', () => {
    const bin = join(tmp, 'bin'), marker = join(tmp, 'ran');
    const target = script(join(tmp, 'real'), 'tool.sh', marker);
    mkdirSync(bin, { recursive: true });
    symlinkSync(target, join(bin, 'tool'));
    expect(generateManifest('tool', undefined, { userDir, path: bin })).toBeNull();
    expect(existsSync(marker)).toBe(false);
  });

  it('never hands text from the command line to a shell', () => {
    const marker = join(tmp, 'ran');
    const path = process.env.PATH ?? '';
    for (const cmd of ['S=/tmp/x', 'for', `true;touch ${marker}`, `true$(touch ${marker})`, '../bin/true', '/usr/bin/true', 'until']) {
      expect(generateManifest(cmd, undefined, { userDir, path }), cmd).toBeNull();
    }
    expect(generateManifest('true', `x;touch ${marker}`, { userDir, path })).toBeNull();
    expect(existsSync(marker)).toBe(false);
    expect(saved()).toEqual([]);
  });

  it('saves nothing for a command that is not on PATH', () => {
    expect(generateManifest('no-such-command-for-kcp-tests', undefined, { userDir, path: process.env.PATH ?? '' })).toBeNull();
    expect(saved()).toEqual([]);
  });

  it('still produces a manifest for a real program', () => {
    const m = generateManifest('ls', undefined, { userDir, path: process.env.PATH ?? '' });
    expect(m).not.toBeNull();
    expect(m!.description.length).toBeGreaterThan(0);
    expect(saved()).toEqual(['ls.yaml']);
  });
});

describe('looksLikeHelp', () => {
  it('rejects outputs the old probe cached as manifests', () => {
    expect(looksLikeHelp('bash: line 1: --help: command not found\n')).toBe(false);
    expect(looksLikeHelp("bash: -c: line 1: syntax error near unexpected token `--help'\n")).toBe(false);
    expect(looksLikeHelp('sunstone-ti\nexoreaction-canvas\n')).toBe(false);
  });
  it('accepts help text', () => {
    expect(looksLikeHelp('Usage: ls [OPTION]... [FILE]...\n')).toBe(true);
    expect(looksLikeHelp('Print stuff.\n  -n, --dry-run   show what would be done\n')).toBe(true);
  });
});

describe.runIf(posix)('runHelp', () => {
  it('kills a program that does not finish', () => {
    const start = Date.now();
    expect(runHelp(['sleep', '30'], 300)).toBeNull();
    expect(Date.now() - start).toBeLessThan(5000);
  });
  it('closes stdin, so a program reading it does not wait', () => {
    expect(runHelp(['cat'], 3000)).toBe('');
  });
});
