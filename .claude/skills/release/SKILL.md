---
name: release
description: |
  Cuts and publishes a new version of the yorkie-android-sdk to Maven Central.
  Runs the full release flow: branch release/vX.X.X from develop, bump VERSION_NAME
  in gradle.properties, PR to main, create the GitHub Release that triggers
  publish.yml, then sync main back into develop (and SNAPSHOT / manual publishes
  via publish-manual.yml). ALWAYS use this skill for ANY request to release,
  publish, ship, tag, or cut a version of the SDK — including short ones like
  "release v0.7.5", "ship 0.6.36", "publish to maven central", "cut a release",
  "re-cut the release", "bump the version and tag it", "get develop out on
  central sonatype", or "put out a 0.8.0-SNAPSHOT". Don't hand-roll any of this
  with raw git/gh: the publish is an irreversible Maven Central step and needs a
  GitHub Release (not a bare tag) to fire publish.yml. NOT for: a normal PR to
  develop, bumping third-party dependency versions in libs.versions.toml,
  git-tagging an arbitrary checkpoint, hotfix PRs, or just reading a version number.
argument-hint: "[version e.g. 0.7.5] [--from develop] [--snapshot] [--dry-run]"
---

## What this skill does

Releasing the SDK is a multi-step flow with **one irreversible action** (publishing to
Maven Central — a version, once published, can never be replaced or unpublished). So this
skill runs as a guided runbook with two hard confirmation gates: before merging to `main`,
and before the publish. Everything else flows automatically.

The flow follows `.claude/reference/git-strategy.md` (Release Flow) and the
`.github/workflows/publish.yml` trigger. Read those if anything here is ambiguous.

### The one thing the docs get subtly wrong — read this

`git-strategy.md` step 5 says "Tag `vX.X.X` on main after merge". **A bare pushed tag does
NOT publish anything.** `publish.yml` is triggered by `on: release: [created]` — i.e.
*creating a GitHub Release*, not pushing a tag. And it publishes **whatever
`VERSION_NAME` is committed at the released ref** — it does not bump the version itself.

Two consequences this skill always enforces:
1. `VERSION_NAME` in `gradle.properties` **must already equal the release version on `main`**
   before the Release is created — otherwise the wrong (or duplicate) version gets published.
2. Use `gh release create` (which creates the tag on `main` **and** fires the event) rather
   than `git tag && git push`. One step both tags and publishes.

---

## Preflight (always run first)

1. **Parse the version.** Target version comes from the argument (e.g. `0.7.5`), else ask.
   Validate it is semver `X.Y.Z`. The tag is `v` + version (`v0.7.5`); the
   `VERSION_NAME` is the bare version (`0.7.5`). For a pre-release publish, accept a
   `-SNAPSHOT` suffix and route to the **Manual / SNAPSHOT publish** section instead.

2. **Confirm GitHub host.** `origin` is `github.com:yorkie-team/yorkie-android-sdk` (SSH).
   On this repo `gh` may default to a different host (e.g. an internal GHE). Run every `gh`
   command against github.com explicitly:
   ```bash
   export GH_HOST=github.com
   gh auth status -h github.com   # if the token is invalid/missing, stop and tell the user:
   #   run:  ! gh auth login -h github.com -w
   ```
   Do not proceed until `gh auth status -h github.com` shows a valid login.

3. **Collision check** — refuse to clobber an existing release:
   ```bash
   git fetch origin --prune --tags
   git ls-remote --tags origin "refs/tags/v<version>"        # tag must NOT exist
   gh release view "v<version>" -h github.com --repo yorkie-team/yorkie-android-sdk 2>/dev/null  # must 404
   git ls-remote --heads origin "refs/heads/release/v<version>"  # branch may exist (resume) — note it
   ```
   If the tag or Release already exists, **stop** — the version was already (partly) released;
   ask the user how to proceed rather than guessing.

4. **Working tree clean.** `git status --porcelain` must be empty. Stash or stop otherwise.

5. **Show the plan** — current latest tag (`git ls-remote --tags origin | sort` / `gh release list`),
   the proposed new version, and the base branch (`--from`, default `develop`). Get a go-ahead
   before mutating anything.

If `--dry-run`, print every command the skill *would* run, then stop.

---

## Phase 1 — Cut the release branch from develop

```bash
git fetch origin
git switch -c "release/v<version>" "origin/<from>"   # default origin/develop
```
Never branch from a stale local `develop` — always from `origin/<from>`. If the branch
already exists remotely (resume case), check it out and reconcile instead of recreating.

## Phase 2 — Bump the version

1. Edit `gradle.properties`: set `VERSION_NAME=<version>` (currently the only line to change;
   leave `GROUP`, `POM_*` untouched).
2. If `CHANGELOG.md` exists at the repo root, move `[Unreleased]` entries under a new
   `## [<version>]` heading per `.claude/rules/changelog-rule.md`. (As of writing there is no
   CHANGELOG.md — skip this step unless one has been added.)
3. Format and commit:
   ```bash
   ./gradlew formatKotlin
   git commit -am "chore: bump version to <version>"
   ```
   Commit style: lowercase after the colon, no trailing period (`.claude/rules/git-workflow.md`).

## Phase 3 — Stabilize and verify locally

Run the release verification the repo prescribes:
```bash
./gradlew clean lintKotlin yorkie:testDebugUnitTest yorkie:jacocoDebugTestReport
```
On Apple Silicon this needs `protoc_platform=osx-x86_64` in `local.properties` (see CLAUDE.md).
If the heavy gradle run is impractical locally, say so explicitly — the PR's
`check_build_and_test.yml` re-runs lint + tests on `release/**` PRs, so CI is the backstop.
Only **bug fixes** belong on a release branch; no new features.

## Phase 4 — PR to main  ⛔ GATE 1 (merge needs confirmation)

```bash
git push -u origin "release/v<version>"
gh pr create -h github.com --repo yorkie-team/yorkie-android-sdk \
  --base main --head "release/v<version>" \
  --title "release: v<version>" \
  --body "<release notes summary — what's in this version vs the last tag>"
```
- Base is **`main`** — this is the one flow where PRs do not target `develop`.
- Build the body from `git log <last-tag>..HEAD --oneline` (the user-facing changes).
- **Stop here.** Report the PR URL and wait. Merge only after CI is green and the user
  approves the merge. Do not self-merge without that explicit go-ahead.

After merge, sync local main:
```bash
git fetch origin && git switch main && git pull --ff-only
```
Re-confirm `grep '^VERSION_NAME=' gradle.properties` on `main` equals `<version>` — this is
the exact ref that will be published.

## Phase 5 — Publish  ⛔ GATE 2 (IRREVERSIBLE — Maven Central)

This creates the tag on `main` **and** fires `publish.yml` → `publishToCentralPortal`.
A published Maven Central version is permanent. Get an explicit "yes, publish" first.

```bash
gh release create "v<version>" -h github.com --repo yorkie-team/yorkie-android-sdk \
  --target main --title "v<version>" --generate-notes --latest
```
Then watch the publish run:
```bash
gh run list -h github.com --repo yorkie-team/yorkie-android-sdk \
  --workflow publish.yml --limit 1
gh run watch <run-id> -h github.com --repo yorkie-team/yorkie-android-sdk
```
Report success/failure. If it fails, the version is **not** on Maven Central; the GitHub
Release/tag still exists, so fix the cause and re-trigger via the **Manual publish** path
(do not delete and recreate the Release just to retry).

## Phase 6 — Sync main back into develop, clean up

Keep `develop` ahead of the released `main` (so the bump + tag history is consistent):
```bash
gh pr create -h github.com --repo yorkie-team/yorkie-android-sdk \
  --base develop --head main --title "chore: sync v<version> back to develop"
```
Use a PR (the repo forbids direct commits/force-push to `develop` and `main`). After it
merges, delete the release branch:
```bash
git push origin --delete "release/v<version>"
```

## Final verification

- `gh release list -h github.com` shows `v<version>` as **Latest**.
- The publish.yml run is green.
- Artifact visible on Maven Central: `https://central.sonatype.com/artifact/dev.yorkie/yorkie-android/<version>`
  (propagation can take a few minutes to a couple hours).

Report a short summary: version, PR links, Release URL, publish run result.

---

## Manual / SNAPSHOT publish (escape hatch)

For pre-release `-SNAPSHOT` builds, re-publishing a fixed version, or publishing without a
GitHub Release, use `publish-manual.yml` (`workflow_dispatch`). It sed-bumps `VERSION_NAME`
in the runner only (not committed) and picks the task by suffix:
```bash
gh workflow run publish-manual.yml -h github.com --repo yorkie-team/yorkie-android-sdk \
  -f version=<version[-SNAPSHOT]>
gh run watch <run-id> -h github.com --repo yorkie-team/yorkie-android-sdk
```
Caveat: because it doesn't commit the bump, the published artifact's version won't match
`gradle.properties` in git — fine for SNAPSHOTs, not a substitute for a real release.

---

## Guardrails (non-negotiable)

- **Never force-push** to `develop`, `main`, or `release/**`, and never commit directly to
  `main`/`develop` — always via PR (`.claude/reference/git-strategy.md` Rules).
- **Never** create the GitHub Release before `VERSION_NAME` on `main` equals the target
  version — that is what gets published.
- Treat Phase 5 as a one-way door. When unsure, stop and ask rather than publish.
- A SNAPSHOT version is the only case that skips the GitHub-Release path.
