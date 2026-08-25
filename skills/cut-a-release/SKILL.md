# Cut a release

Governed skill: tag and publish a kcp-commands release without assuming the version
files drive anything they don't.

## Preconditions

- `main` is green (CI passing) at the commit you intend to release.
- You know the target version (semver, e.g. `v0.29.0`) and it is not already tagged
  (`git ls-remote --tags origin` to check).

## Steps

1. **(edit)** Optionally bump `<version>` in `java/pom.xml` and `"version"` in
   `typescript/package.json`. **These are informational only** — `release.yml` derives
   the published version solely from the pushed git tag (`GITHUB_REF_NAME#v`), and the
   Maven build uses a fixed `finalName` (`kcp-commands-daemon.jar`, no version in the
   filename). The two files are already out of sync with each other and with the last
   documented release — don't treat a mismatch as a blocker, and don't assume bumping
   them is required for the release to work.
2. **(edit)** Update the releases table in `README.md` with the new version, manifest
   count, and a one-line summary of what changed — this table is the durable record of
   what shipped when (see existing rows for the format).
3. **(bash)** `git tag vX.Y.Z && git push origin vX.Y.Z`. Do this on `main` after 1–2 are
   merged, not before — the tag push is what triggers the build.
4. **(read)** Watch the `Release` GitHub Actions run: it builds the Java daemon, the
   TypeScript `dist/cli.js`, and the KCP user CLI, then publishes a GitHub Release with
   all three attached and install-command instructions in the body. No npm publish step
   exists for this repo.

## Verification

The GitHub Release for the tag exists with `kcp-commands-daemon.jar`,
`typescript/dist/cli.js`, and `kcp-user-cli.js` attached, and
`curl -fsSL .../install.sh | bash -s -- --java` against that tag installs successfully.

## Rollback

If the tag build fails: fix forward on `main`, delete the failed tag
(`git push --delete origin vX.Y.Z` — only for a tag that never produced a release; never
delete a tag with a published release attached), and re-tag. If `knowledge.yaml` was
edited as part of the release, note it re-signs automatically via `sign-kcp.yml` on push
to `main` — no manual signing step here.
