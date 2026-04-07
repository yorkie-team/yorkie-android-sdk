# Git Workflow

## Branching

- Always branch from `develop` (not `main`)
- Branch naming:
  - Feature: `feat/RTCOLLABPLATFORM-{N}-{short-desc}`
  - Bug fix: `fix/RTCOLLABPLATFORM-{N}-{short-desc}`
  - Chore / tooling: `chore/{short-desc}`
- Use `--no-track` when creating branches: `git checkout -b {branch} --no-track origin/develop`
- Never commit directly to `main` or `develop`

## Commits

Format: `{type}: {description}`

| Type | When |
|------|------|
| `feat` | New functionality |
| `fix` | Bug fix |
| `chore` | Tooling, config, dependencies |
| `docs` | Documentation only |
| `test` | Adding or updating tests |
| `refactor` | No behaviour change |
| `task` | Migration, cleanup, non-feature work |

- Lowercase after the colon, no period at the end
- Run `./gradlew formatKotlin` before every commit
- Run `./gradlew yorkie:testDebugUnitTest lintKotlin` before pushing

## Pull Requests

- All PRs target `develop`
- Title format: `[RTCOLLABPLATFORM-N] {ticket title}` (if ticket exists), otherwise `{type}: {description}`
- Use `/create-pr` skill to create PRs

## Release Flow

1. Cut `release/vX.X.X` from `develop`
2. Bump version in `gradle.properties`, stabilise (bug fixes only)
3. Run: `./gradlew clean lintKotlin yorkie:testDebugUnitTest yorkie:jacocoDebugTestReport`
4. PR `release/vX.X.X` → `main`, title: `release: vX.X.X`
5. Tag: `git tag vX.X.X && git push origin vX.X.X`
6. Merge `main` → `develop` to sync

## Hotfix Flow

1. Branch `hotfix/{desc}` from `main`
2. Fix, test, PR to `main`
3. Tag patch version: `vX.X.{N+1}`
4. PR to `develop` to keep it in sync

## Full workflow reference

See `.claude/reference/git-strategy.md` for diagrams and detailed commands.
