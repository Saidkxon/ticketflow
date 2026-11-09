# Contributing to TicketFlow

These rules apply to every change, including the architect's.

## Workflow

1. Start from an issue that says what and why.
2. Branch from an up-to-date `main`: `git switch main && git pull && git switch -c <branch>`.
3. Make small commits. Push the branch and open a pull request (PR) using the template.
4. CI must pass and the architect must approve. **Nobody merges without explicit approval.**
5. After the merge: `git switch main && git pull`, then verify.

Never push directly to `main`.

## Branch names

`type/short-description`, with a type from the list below. Examples: `feat/booking-hold`, `fix/token-refresh`, `docs/adr-0014-caching`, `chore/ci-docs-check`.

## Commit messages (Conventional Commits)

Format: `type(scope): summary`

- **Types:** `feat`, `fix`, `docs`, `chore`, `refactor`, `test`, `ci`, `build`.
- **Scopes (optional):** `adr`, `charter`, `identity`, `catalog`, `booking`, `payment`, `notification`, `gateway`, `web`, `deploy`.
- Summary in the imperative mood, lower case, at most 72 characters, no final period.
- Add a body when the reason is not obvious: explain why, not what.
- A breaking change adds `!` after the scope, for example `feat(catalog)!: rename seat field`.

Examples:

## Rules that are never broken

- No secrets, passwords or keys in the repository (NFR-7).
- A design decision needs an ADR before the code that depends on it.
- A merged database migration is never edited; fixes are new migrations (ADR-0005).
- No service reads another service's database (NFR-2).
- A service never calls another service except as ADR-0002 allows.

## Review

The architect reviews the pushed branch against `main` before approving. Expect to be asked to explain any line.
