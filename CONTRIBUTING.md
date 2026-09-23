# Contributing to Clinexa

## Branches and pull requests

- Never commit to `main` or `develop` directly: branch, open a pull request, get **one review**.
- Branch names: `feature/<topic>`, `fix/<topic>`, `docs/<topic>`, `chore/<topic>`.
- A PR is mergeable only when the CI checks `build (server)` and `build (client)` are green.
  A red check is never bypassed — fix the code, not the pipeline.

## Commit messages

[Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/): `feat:`, `fix:`, `docs:`,
`refactor:`, `test:`, `chore:`, optionally scoped — `feat(identity): …`, `fix(client): …`.
Subject in the imperative, the *why* in the body.

## Before pushing

| Half | Command (from that folder) |
|---|---|
| `server/` | `mvn clean verify` — needs Docker running (Testcontainers) and JDK 25 |
| `client/` | `yarn lint && yarn format:check && yarn test --watch=false && yarn build` |

## Security rules that fail the build

Before touching a service, read the security foundation in the vault (`Security/MVP/`). In short:

- Update the matrix (`Clinexa-vault/Security/MVP/03-rbac-et-matrice-autorisation.md` §7) **and** the service's `src/test/resources/matrice.csv`
  **before** adding a route — INV-1 fails otherwise.
- Never write `anyRequest()`, never inject `Authentication`, `Jwt` or `HttpSession` outside the
  authentication package — read `CurrentUser`.
- Every entity with a `clinicId` carries `@TenantId` (only exemption: `Member`, `SEC-13`).
- No `@Disabled` test, ever: the CI refuses it.
- No secret in the repo: `.env` is git-ignored, `.env.example` holds dev-only values.
