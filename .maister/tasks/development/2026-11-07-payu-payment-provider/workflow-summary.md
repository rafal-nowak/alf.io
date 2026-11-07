# Workflow Summary: PayU payment provider (alf.io fork)

## TL;DR
PayU (BLIK, pay-by-link, cards) is implemented end to end and verified. The full build is green: feature/payu 1064 tests (+ batch 2 targeted 117/117), plo-pl 1073 tests, 0 failures. On the public PayU sandbox the E2E passes 2/2 (Selenium) and 6/6 (browser verification).
Branches: `feature/payu` (PR-able to upstream), `fix/pl-translations` (fork-only), `fix/stepper-active-color` (PR candidate), `plo-pl` (integration + README + .maister). Docker image: `alfio-plo-pl:payu-1118e88d2` / `payu-20261107`.
Go-live still needs 3 operational checks (README runbook): a real PayU notification on a public HTTPS URL, live/sandbox configuration hygiene, and alerting.

## Key Decisions
- Mode: autonomous with recommended options (owner instruction at 2026-11-07T13:19Z). Every gate decision is recorded in orchestrator-state.yml.
- feature/payu was rebased onto main without the Mollie commit. The WIP commit was reworked into meaningful commits. The pre-rebase WIP is kept as `backup/feature-payu-wip` (f05742e44).
- Order lifecycle: a re-confirm never creates a second payable order. A cancel counts only when a re-read confirms it: on the sandbox, DELETE answered SUCCESS on a COMPLETED order, and the cancellation is applied asynchronously.
- Confirmation goes through the return callback + force-check plus the signed notification. A NEW order is NOT_RELEVANT in webhooks and an ERROR (meaning "not yet") in force-check, so it does not trigger the STUCK alert.
- customerIp comes from a new optional PaymentSpecification field (owner decision). IPv6 is normalized.
- PL translations are on a fork-only branch, because AGENTS.md sends non-English edits through POEditor. The stepper badge uses `var(--bs-primary)`.
- Commits carry no AI trailers (AGENTS.md overrides the default attribution).

## Open Questions / Risks
- PR-B1: no real PayU notification has reached the server yet (localhost in E2E).
- PR-B2/B3: live/sandbox hygiene and alerting are operational (see the README runbook).
- Core gap: no job re-checks WAITING_EXTERNAL_CONFIRMATION. Use the runbook's daily SQL check plus force-check.
- An operator retrying a refund after one that actually landed causes a double refund. Check the PayU panel first.
- Have a Polish speaker review the PL wording.
- Outside the dev profile alf.io requires HTTPS headers. The webhook answers 500 on plain HTTP to the container port (documented in the README).

## Phases
| # | Phase | Result |
|---|---|---|
| 1 | Codebase analysis + clarifications | 4 owner decisions (PL branch, stepper colour, E2E via force-check, customerIp field) |
| 2 | Gap analysis | 8 decisions auto-approved (C1, C2, I1–I6) |
| 3 | TDD red | 6 defect tests failing on the draft |
| 4 | Mockups (ASCII) | 4 stable IDs |
| 5 | Spec | FR1–FR15 |
| 6 | Spec audit | pass with concerns (3 high), all applied |
| 7 | Plan | 19 groups, 113 steps |
| 8 | Implementation | 19 groups + G15b, 14 waves |
| 9 | TDD green | 7/7 |
| 10 | Verification options | all enabled |
| 11 | Verification | cycle 2: passed with issues; 21 findings fixed in 2 batches |
| 12 | E2E browser | 6/6 (2 with expected branch-scope caveats) |
| 13 | User docs | Polish guide, 14 screenshots |
| 14 | Finalization | plo-pl build 1073/0, image rebuilt and smoke-tested, pushes |

## Branches and commits
- `feature/payu` (19 commits on main): backend, EN i18n, frontend, admin, tests, descriptor, sandbox fixes (IPv6, cancel re-read, async cancel), verification fixes.
- `fix/mollie-ignore-unsupported-methods`: ea770e35f (unchanged).
- `fix/pl-translations`: 91b33c11c, 739683de0.
- `fix/stepper-active-color`: e80c0f0c4.
- `plo-pl`: main + all of the above (merge commits), README "Building the Docker image (plo-pl fork)" + PayU go-live runbook (1dca809d0, 5384c2a9f, f40f294e9), and the `.maister/` task records.

## Build the image
```
git checkout plo-pl
./gradlew build                    # must be green for this commit
./gradlew distribution -x test
docker build -t alfio-plo-pl:payu-$(date +%Y%m%d) -t alfio-plo-pl:payu-$(git rev-parse --short HEAD) ./build/dockerize
```

## Notes
- The screenshot `verification/screenshots/01-admin-system-payu-panel.png` shows the public sandbox keys. It is kept only locally and is not committed.
- The test DB container `alfio-payu-e2e-pg` and `custom.jvmargs` were removed at the end.
- No pull requests were opened, and nothing was pushed to `upstream`.
