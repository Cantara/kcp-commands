# Add a command manifest

Governed skill: add a new bundled manifest (or fix an existing one) in `commands/` so
Phase A guidance and Phase B filtering are correct for that command.

**Note:** `CONTRIBUTING.md`'s "Adding a new manifest" section shows a stale schema
(`name`/`use_when`/`suppress`) that does not match any real manifest in this repo. Follow
this skill instead — it matches the schema actually parsed by the Java daemon and
TypeScript resolver, and the 292 files currently in `commands/`.

## Preconditions

- You know the exact command/subcommand name the agent will type (e.g. `git log`, not
  `git`). Subcommands get their own file: `<command>-<subcommand>.yaml` with a
  `subcommand:` field (see `commands/git-log.yaml`).
- Check it isn't already on the suppression list (README's "Suppression list" table) —
  suppressed commands never reach manifest lookup, so a new manifest for one is dead code.

## Steps

1. **(read)** Open a close sibling in `commands/` (e.g. `commands/ps.yaml`) as your
   template — don't start from CONTRIBUTING.md's example.
2. **(edit)** Create `commands/<command>.yaml` with the real top-level shape:
   - `command`, `platform` (`all`/`linux`/`macos`/`windows`), `description`
   - `syntax.usage`, `syntax.key_flags` (≤5: `flag`, `description`, optional `use_when`),
     `syntax.preferred_invocations` (≤3: `invocation`, `use_when`)
   - `output_schema` only if the command is genuinely noisy: `enable_filter: true`,
     `noise_patterns` (list of `pattern`/`reason`), `max_lines`, `truncation_message`
     (use the literal `{remaining}` placeholder — it's substituted at filter time)
3. **(bash)** Build and smoke-test: `./bin/test-build.sh` from the repo root. This builds
   both backends, starts the daemon on `:7734`, and round-trips an inject + a suppress
   case — it's the fastest way to catch a YAML shape error without hand-crafting a curl.
4. **(bash)** To check your specific manifest's injected content directly:
   ```bash
   java -jar java/target/kcp-commands-daemon.jar &
   sleep 2
   echo '{"tool":"Bash","command":"<your-command> --help"}' | \
     curl -sf -X POST http://localhost:7734/hook -H "Content-Type: application/json" --data-binary @-
   ```
   Confirm the response's `additionalContext` contains your `key_flags` and
   `preferred_invocations` text, formatted as the `[kcp] ...` block.

## Verification

`./bin/test-build.sh` reports 0 failures, and the manual `/hook` call in step 4 returns
your manifest's content in `additionalContext` (not a 204 / empty body — an empty body
means the command didn't resolve to your file, usually a name or `platform` mismatch).

## Rollback

Delete the new/edited YAML file — nothing else references it until it's committed and a
release ships. No daemon restart is needed on removal beyond killing the test instance
(`pkill -f kcp-commands-daemon`).
