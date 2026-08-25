# kcp-commands

A Claude Code `PreToolUse`/`PostToolUse` hook — not a CLI. It intercepts every Bash tool
call: injects compact syntax guidance before execution (Phase A, 292 bundled manifests),
strips noise from output after execution (Phase B), and logs the call for kcp-memory's
episodic index (Phase C). Java daemon (fast path) with a Node.js fallback.

**Start here:** `knowledge.yaml` is the canonical, signed, agent-navigable source of
truth for this repo, not this file. Match your question against each unit's
`triggers`/`intent`, then fetch that unit's `path`.

**Skill-authoring conventions:** this repo's skills follow the governed-skill format
defined in [kcp-skill](https://github.com/Cantara/kcp-skill) — read its `PROFILE.md`
(one skill = one procedure, `action_scope` as a firewall rule, start from nothing) before
writing or editing a skill here.

**Local skills:** [`skills/`](skills/) — procedures for *developing this repo*, not for
using the hook. Currently: adding a command manifest correctly, cutting a release.

## Gotchas

- `CONTRIBUTING.md`'s manifest YAML example is wrong — the real schema is
  `command`/`platform`/`syntax`/`output_schema` (see `commands/ps.yaml`), not the
  `name`/`use_when`/`suppress` shape it shows. Use `skills/add-command-manifest/` instead.
- Manifest resolution is three-tier, first-match-wins: `.kcp/commands/` (project) →
  `~/.kcp/commands/` (user) → bundled `commands/`. Editing `commands/` here only takes
  effect for other sessions once released/reinstalled.
- Release version is tag-driven, not file-driven: `release.yml` reads the git tag only;
  `java/pom.xml` and `typescript/package.json` versions are informational and can drift.
