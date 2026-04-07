# Git Strategy

## Branch Model

```
main
  └── develop          ← integration branch; all feature PRs target here
        ├── feat/RTCOLLABPLATFORM-N-short-description
        ├── fix/RTCOLLABPLATFORM-N-short-description
        └── chore/short-description

release/vX.X.X         ← cut from develop when preparing a release
  └── hotfix/short-description   ← critical fixes on a live release
```

## Branch Naming

| Type | Pattern | Example |
|------|---------|---------|
| Feature | `feat/RTCOLLABPLATFORM-{N}-{short-desc}` | `feat/RTCOLLABPLATFORM-42-json-tree-move` |
| Bug fix | `fix/RTCOLLABPLATFORM-{N}-{short-desc}` | `fix/RTCOLLABPLATFORM-55-gc-tombstone-drop` |
| Chore / tooling | `chore/{short-desc}` | `chore/update-connect-rpc-dependency` |
| Release | `release/v{X.X.X}` | `release/v0.6.36` |
| Hotfix | `hotfix/{short-desc}` | `hotfix/null-crash-on-detach` |

- Use lowercase and hyphens only — no underscores, no camelCase.
- Keep descriptions short (2–4 words).
- When a JIRA ticket exists, include the ticket number.

## Commit Messages

Format: `{type}: {description}`

| Type | When to use |
|------|-------------|
| `feat` | New functionality |
| `fix` | Bug fix |
| `chore` | Tooling, config, dependency updates |
| `docs` | Documentation only |
| `test` | Adding or updating tests |
| `refactor` | Code change with no behaviour change |
| `task` | Non-feature work items (migration, cleanup) |

Rules:
- Lowercase after the colon, no period at the end
- Imperative mood: "add json tree move" not "added json tree move"
- Reference JIRA ticket in the body or PR title, not in every commit subject
- Run `./gradlew formatKotlin` before committing

## Pull Request Flow

```
feat/RTCOLLABPLATFORM-42  →  PR  →  develop  →  (release cut)  →  main
```

1. Branch off `develop`
2. Implement, test, lint (`./gradlew formatKotlin yorkie:testDebugUnitTest lintKotlin`)
3. Open PR targeting `develop`
4. Merge after review approval

## Release Flow

1. Cut `release/vX.X.X` from `develop`
2. Bump `VERSION_NAME` in `gradle.properties`, stabilise on release branch
3. Run: `./gradlew clean lintKotlin yorkie:testDebugUnitTest yorkie:jacocoDebugTestReport`
4. PR `release/vX.X.X` → `main`
5. Tag `vX.X.X` on `main` after merge
6. Merge `main` back into `develop`

## Hotfix Flow

1. Branch `hotfix/{desc}` from `main`
2. Fix and test
3. PR to `main`, then separately to `develop`

## Rules

- **Never commit directly to `main` or `develop`** — always via PR
- **Never force-push** to `develop`, `main`, or `release/**`
- **One logical change per PR** — split unrelated changes into separate branches
- **Delete branches after merge** — keep the remote clean
- **`./gradlew formatKotlin` before every push** — CI will reject unformatted code
