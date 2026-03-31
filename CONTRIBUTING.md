# Contributing to kcp-commands

## Repo structure

```
java/           Java daemon (Maven, fat JAR, serves HTTP on :7734)
typescript/     Node.js CLI fallback (TypeScript, builds to dist/cli.js)
bin/            Shell scripts: hook.sh, post-hook.sh, install.sh, test-build.sh
commands/       KCP manifest YAML files (291 bundled manifests)
docs/           Design docs and specs
tools/          Benchmark scripts
knowledge.yaml  Top-level KCP knowledge entry for this repo
```

## Prerequisites

- Java 21+ (`java -version` must show 21 or higher)
- Maven 3.6+ (`mvn -version`)
- Node.js 18+ (`node -version`) — required even if using Java backend (settings.json registration uses Node)
- Optional: Python 3 + C++ compiler if you want `kcp stats` / `better-sqlite3` to work

## Build from source

### Java daemon

```bash
cd java
mvn clean package -DskipTests
# Output: java/target/kcp-commands-daemon.jar (~17 MB fat JAR)
```

### TypeScript CLI

```bash
cd typescript
npm install
npm run build
# Output: typescript/dist/cli.js
```

### Run the smoke test

```bash
./bin/test-build.sh
# Builds both, starts daemon, verifies inject + suppress round-trips
```

## Running tests

```bash
# Java unit tests
cd java && mvn test

# TypeScript tests
cd typescript && npx vitest run
```

## Adding a new manifest

Manifests live in `commands/`. Each is a YAML file following the KCP manifest schema.

**1. Create the file**

```bash
# Name it after the primary command (lowercase, hyphens for multi-word)
touch commands/my-tool.yaml
```

**2. Minimal manifest structure**

```yaml
name: my-tool
version: "1"
description: "One-line description of what this tool does"

use_when:
  - condition: "When you need to <do X>"
    command: "my-tool --flag value"

preferred_invocations:
  - command: "my-tool --recommended-flag"
    description: "Most common use case"

common_errors:
  - symptom: "Error: permission denied"
    fix: "Run with sudo or check file permissions"

suppress: false   # set true if Claude already knows this tool well (e.g. git, ls)
```

**3. Verify it appears in the build**

The pre-build step in `java/pom.xml` auto-generates a manifest index from all YAML files in `commands/`. After `mvn clean package`, check:

```bash
jar tf java/target/kcp-commands-daemon.jar | grep "manifest-index"
```

**4. Test your manifest**

```bash
# Start the daemon from the new build
java -jar java/target/kcp-commands-daemon.jar &
sleep 2

# Send a hook request for your command
echo "{\"tool\":\"Bash\",\"command\":\"my-tool --help\"}" | \
  curl -sf -X POST http://localhost:7734/hook \
    -H "Content-Type: application/json" \
    --data-binary @-

# Should return JSON with additionalContext containing your manifest content
```

## PR guidelines

- **Tests:** New manifests don't require tests, but changes to Java/TypeScript code do. Aim for a test per new behaviour path.
- **Manifest quality bar:** `use_when` entries should describe *when Claude should use the command*, not just what it does. `preferred_invocations` should include the 1–3 most common real-world patterns. `common_errors` should cover errors that trip up automated agents (not obvious human errors).
- **Suppress carefully:** Only add a command to the suppression list if it's well-known enough that injecting guidance would be net-negative. Currently suppressed: git, grep, ls, cat, curl, ssh, and similar ubiquitous tools.
- **Commit messages:** `feat(manifest): add my-tool manifest` / `fix(daemon): describe what changed`

## Release process (maintainers only)

1. Bump version in `java/pom.xml` and `typescript/package.json`
2. Update `README.md` releases table
3. `git tag v0.X.Y && git push origin v0.X.Y`
4. GitHub Actions `release.yml` builds and uploads JAR + CLI to GitHub Releases automatically
