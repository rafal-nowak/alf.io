# Implementation Verification: PayU payment provider

## TL;DR
**Verdict: ⚠️ Passed with Issues (cycle 2, post-fix).** There are no code-critical issues. All 15 fixable code and doc findings from cycle 1 are fixed, plus the 6 findings from the re-review (6 fix commits on feature/payu and 1 README commit on plo-pl). The full build after batch 1 ran 1064 tests with 0 failures; after batch 2 the targeted PayU tests pass 117/117.
**Still open:** 3 critical operational go-live checks (PR-B1 notification on a public URL, PR-B2 live/sandbox hygiene, PR-B3 alerting). They are written up in the README runbook. Design and core warnings are accepted (12 warnings, 5 info).
**Deferred to Phase 14:** pushes, the .maister commit, worktree removal, the plo-pl full build and the image rebuild.

## Open Questions / Risks
- No real PayU notification has reached the server yet. The webhook is proven only with WireMock. This is go-live check PR-B1.
- Core gap: no job re-checks WAITING_EXTERNAL_CONFIRMATION (PR-W4). Mitigation is the runbook's daily SQL check plus a manual force-check.
- Live mode has never been exercised. Do a 1–2 PLN BLIK purchase and refund it before go-live.
- CR-I3 depends on PayU's GET orders/{id} always returning extOrderId. The sandbox E2E responses did include it. If a response ever omits it, a paid order is held as a mismatch and logged at ERROR.
- The cancel budget (3 s) limits only the pauses between re-reads; one slow re-read can still add up to the 10 s request timeout. Set the reverse-proxy read timeout to at least 120 s.

## Executive Summary
The PayU provider is complete end to end, and every reviewer considers the money paths safe. Two fix cycles closed all the code-level hardening findings: bounded cancel polling, ERROR alerts for paid notifications that cannot be matched to a reservation, the live-mode override guard, log sanitizing, a generic 500 body, strict validation of the order response, and a one-time cancellation toast. What remains is operational (go-live verification and monitoring) or core behaviour already present in alf.io.

## Checks
| Check | Cycle 1 | Cycle 2 | Report |
|---|---|---|---|
| Completeness | ⚠️ 98.2%, spec drift | ✅ spec amended (PI1–PI7), 19.9 marked partial; 19.6 and 19.8 deferred | [completeness-report.md](completeness-report.md) |
| Test suite | ⏭ passed in implementation (1047) | ✅ full build 1064/0 failures at 938d5ca3d; targeted 117/117 at 1affdc531 | [test-suite-results.md](test-suite-results.md) |
| Code review | ⚠️ 4 W, 12 I | ✅ re-review: 10 resolved, 2 partial, then fixed in batch 2 (N1–N6) | [code-review-report.md](code-review-report.md), [code-review-reverify.md](code-review-reverify.md) |
| Pragmatic review | ✅ appropriate (3 W) | ✅ PG-W1 and PG-W3 fixed; PG-W2 accepted (fork-only branch) | [pragmatic-review.md](pragmatic-review.md) |
| Production readiness | ❌ NO-GO pending go-live checks | ⚠️ code-side fixes done (PR-W1, W3, W9); operational B1–B3 remain for the owner | [production-readiness-report.md](production-readiness-report.md) |
| Reality check | ⚠️ works on sandbox | unchanged; action plan A1–A7 is the go-live list | [reality-check.md](reality-check.md) |

Visual fidelity: Phase 12 (pending).

## Remaining Findings (open)
| ID | Sev | Issue | Disposition |
|---|---|---|---|
| PR-B1 | critical | Real notification never received | Owner: runbook "Before go-live" (public BASE_URL, closed-tab sandbox purchase) |
| PR-B2 | critical | Live/sandbox switch has no guard | Owner: runbook "PayU configuration" (one level only, live POS) |
| PR-B3 | critical | No alerting | Owner: runbook "Daily operations" (ERROR log alert + SQL check) |
| CR-W3 | warning | NOT_FOUND after a credential change | Accepted (design); runbook says not to change credentials while orders are open |
| CR-W4 | warning | `?error` GET cancels a PENDING order | Accepted: the reservation id is an unguessable UUID; the sandbox showed no funds after DELETE on PENDING |
| PG-W2 | warning | PL edited outside POEditor | Accepted: fix/pl-translations is fork-only |
| CP-W3, PR-W8 | warning | Pushes and .maister commit pending | Phase 14 |
| PR-W2, W4, W5, W6, W7, W10, W11 | warning | Plain-text secrets; core WAITING gap; proxy timeout; refund semantics; sandbox data cleanup; upstream image label; no metrics or rate limits | Accepted or operational; covered by the runbook where applicable |
| CR-I5, CR-I11, RC-I7, RC-I1, RV-Q1 | info | XFF spoofable; NEW without error waits; PL wording review; image never started; extOrderId presence | Accepted; RC-I1 smoke run in Phase 14 |

## Fix & Re-Verification History
| Issue | Fix | Re-check |
|---|---|---|
| CR-W1, PG-W3 | 3 s polling budget, injectable pause (b2fae5dea) | resolved; residual: one slow read can exceed the budget (documented) |
| CR-W2, PR-W3 | ERROR alerts in manager and controller (ed7ab46e8) | partial: the manager branch is unreachable (N1) |
| RV-N1 | Alert moved to webhook controller with a transaction lookup; dead branches removed (1affdc531) | resolved: 22 controller tests, integration flow logs 0 false alerts |
| PR-W1 | Override ignored in live mode (938d5ca3d) | resolved (test: all requests go to secure.payu.com) |
| CR-I1, RV-N5 | `RequestUtils.sanitizeForLog` (53f3a9142, 1affdc531) | resolved |
| CR-I2 | Generic NOK body (53f3a9142) | resolved |
| CR-I3 | Missing extOrderId counts as mismatch (53f3a9142) | resolved (open question RV-Q1) |
| CR-I4 | Create response without orderId/redirectUri: failed, no DB writes (53f3a9142) | resolved |
| CR-I8 | Query param removed after the toast (b0fd49be8) | resolved |
| CR-I10, RV-N3 | Comments updated with sandbox facts | resolved |
| PG-W1 | Refund Javadoc and spec PI5 | resolved |
| RV-N2, RV-N4, RV-N6 | UUID check before ERROR, shared constant, import | resolved (1affdc531) |
| CP-W1, CP-W2, PR-W9 | Spec PI1–PI7, plan 19.9 partial, README runbook (5384c2a9f) | resolved |

Test evidence: full build after batch 1 ran 1064 tests (0 failures, 1 skipped, MigrationValidatorTest as on baseline). After batch 2 the targeted PayU suite passes 117/117. A final full build on plo-pl runs in Phase 14 before the push.

## Recommendations
1. Phase 14: run the full build on plo-pl, rebuild the image, run a smoke start, push, commit .maister and remove the worktrees.
2. Owner, before the reunion goes live: work through the README runbook (PR-B1–B3, live smoke payment and refund), and have a Polish speaker review the PL wording.

## Verification Checklist
- [x] Cycle 1: all 5 reviewers ran and all reports are on disk
- [x] Fixes applied in 2 batches; the full test suite was re-run after batch 1
- [x] Focused re-review of the fixes (code-review-reverify.md)
- [x] Canonical report and HTML rewritten for cycle 2
