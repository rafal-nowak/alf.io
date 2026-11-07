# Code Review Re-verification: PayU fix batch 1

## TL;DR
- 10 of 12 findings are resolved. CR-W2 and PR-W3 are only partly resolved (see N1).
- New warning N1: the manager's ERROR for a paid notification on a non-PENDING transaction can never run. Core filters those notifications before calling `processWebhook`. A paid order whose transaction was invalidated (core answers "transaction not found") is still dropped without a log line.
- AGENTS.md: compliant. There are no ternaries, no Lombok, no `com.fasterxml`, and LogCapture has the GPL header.
- The targeted tests pass at 938d5ca3d: 99 tests, 0 failures. PayUManagerTest now runs in 0.34 s (it used to sleep for several seconds).

## Open Questions / Risks
- Does PayU's `GET /orders/{id}` always return `extOrderId` when it was set at creation? The stricter CR-I3 check depends on it: if PayU omits it, a paid order is held as a mismatch and is never completed automatically. PayU's documentation says it does, but the sandbox log shows no evidence either way.
- The unknown-reservation ERROR (PR-W3) is driven by an unsigned body. Anyone can trigger a "money may be captured" ERROR line (sanitized). Alerting on it is fine, but operators must check the PayU panel before acting, as the message already says.

**Date**: 2026-11-07
**Path**: `git diff 6475c6ecb..938d5ca3d` (11 files) + `src/test/java/alfio/test/util/LogCapture.java`
**Scope**: all (focused re-review)
**Status**: Issues Found

## Summary
- **Critical**: 0
- **Warnings**: 1 (new)
- **Info**: 5 (new)

## Original findings

| ID | Verdict | Evidence |
|----|---------|----------|
| CR-W1 lock duration | Resolved (small residual) | `cancelOrder` stops polling when `elapsed + pause >= budget` (3 s, `PayUManager.java` ~L862-868). Spec PI6 updates the lock figure and the ≥120 s proxy advice. Residual: see N3. |
| CR-W2 silent drop, non-current order | Partly resolved | A PENDING transaction with a different orderId and a COMPLETED/WAITING_FOR_CONFIRMATION status now logs ERROR (`processWebhook` ~L517-533). Two cases never reach the manager: a transaction INVALID after a confirmed cancel (core: "transaction not found", no log) and a COMPLETE transaction (core WARN). See N1. |
| PR-W3 unknown reservation only WARN | Partly resolved | `logUnknownReservation` logs ERROR for paid statuses and WARN otherwise, with a parameterized test. The "transaction no longer PENDING" half of PR-W3 is still core's WARN `discarding transaction webhook payu-COMPLETED`. See N1. |
| PR-W1 override in live mode | Resolved | `endpoint()` returns `LIVE_ENDPOINT` in live mode and logs ERROR once (`AtomicBoolean`). The token cache key includes `endpoint()`, so a token from another host is never reused. Test `endpointOverrideIsIgnoredInLiveMode` checks that every request goes to `https://secure.payu.com/`. |
| PG-W3 real sleeps in tests | Resolved | `setCancelConfirmationTiming(Duration.ZERO, 3 s)` in both test setups, plus two budget tests. PayUManagerTest runs 68 tests in 0.34 s. |
| CR-I1 log injection | Resolved | `RequestUtils.sanitizeForLog` replaces `\p{Cc}`, U+2028 and U+2029, and abbreviates to 64 characters. It is applied to `error`/`errorCode` and to the unsigned `orderId`/`status`, and tested in 3 places. `reservationId` (a path variable) is still logged as is (N5). |
| CR-I2 500 echoes reason | Resolved | The body is now `"NOK"` and the reason is logged at WARN (`PayUPaymentWebhookController` ~L73-76). |
| CR-I3 missing extOrderId passes | Resolved | `extOrderIdMatches = expected == null \|\| expected.equals(actual)`, with test `completedOrderWithoutExtOrderIdIsAMismatch`. See the open question above. |
| CR-I4 NPE without orderId | Resolved | `stringOrNull` is used for both `orderId` and `redirectUri`. When either is missing, the code logs ERROR and returns `failed` with no DB writes (tested). The secondary note (DB failure after PayU created the order) is unchanged and is an accepted risk. |
| CR-I8 toast on reload | Resolved | `router.navigate([], {queryParams:{paymentCancelled:null}, queryParamsHandling:'merge', replaceUrl:true})` runs after the toast is shown. |
| CR-I10 stale comments | Resolved | `DATA_NOT_FOUND` is marked "confirmed on the sandbox". The IPv6 and cancel Javadocs are updated. No TODO or "to be verified" is left in the PayU sources. |
| PG-W1 refund Javadoc | Resolved | The `refund` Javadoc now says what the id protects against (an in-call timeout re-read, concurrent duplicates) and what it does not (an operator retry after a refund that landed), and asks to check the PayU panel. |

## Warnings

### N1. The manager's non-PENDING alert can never run, and a paid order with an invalidated transaction is still dropped silently
- **Location**: `src/main/java/alfio/manager/payment/PayUManager.java` ~L518-533. Core: `TicketReservationManager.java:1807-1816`.
- **Description**: core calls `processWebhook` only when the latest *valid* transaction is PENDING and the reservation status is compatible. Otherwise it returns before the manager runs:
  - "transaction not found" (TRACE only), when the transaction is `INVALID`. `lockLatestForUpdate` uses `SELECT_VALID_BY_RESERVATION_ID`, which excludes INVALID.
  - a WARN, when the transaction is not PENDING or the reservation is in an incompatible state.

  As a result, the `alreadyCompleted` / "transaction is not pending" branches are dead in production. The test case `OLDORDER, COMPLETED, CANCELLED, true` in `unmatchedPaidNotificationIsLoggedAsError` therefore suggests coverage that does not exist. The main CR-W2 scenario is a cancelled PENDING order paid later, after the buyer went back and did *not* start a new order. Its transaction is INVALID, so the COMPLETED notification is still answered 200 with no log line.
- **Recommendation**: raise the alert where every path passes. In `PayUPaymentWebhookController`, when `result.getType() == NOT_RELEVANT` and the body's `order.status` is COMPLETED or WAITING_FOR_CONFIRMATION, log at ERROR with reservationId and orderId. The signature was already checked by `parseTransactionPayload` before core's lookups. Then either remove the unreachable manager branches or keep them as defensive code with a comment, and drop the misleading test rows.
- **Fixable**: true

## Informational

- **N2. The unknown-reservation ERROR comes from an unsigned body.** `PayUPaymentWebhookController.logUnknownReservation`: an unauthenticated POST with `"status":"COMPLETED"` produces an ERROR line. It is sanitized and documented in the Javadoc. If the alert is noisy, check that `reservationId` looks like a UUID before logging at ERROR.
- **N3. The cancel budget does not cover a slow re-read.** `PayUManager.cancelOrder`: the budget is checked only before each pause, so one re-read that starts inside the budget can still take up to 10 s, or about 20 s with a 401 retry. Worst-case polling is about 3 s + one request timeout, not 3 s. The comment "bounded as a whole" overstates this a little; spec PI6 describes it correctly.
- **N4. `PAID_ORDER_STATUSES` is duplicated** in `PayUManager` and `PayUPaymentWebhookController`. The controller already static-imports constants from `PayUManager`, so a single package-visible constant would do.
- **N5. `reservationId` is logged without sanitizing** in the webhook controller (3 lines) and the callback controller. Spring Security's `StrictHttpFirewall` rejects encoded CR/LF by default, and no custom firewall is configured, so the risk is negligible. `sanitizeForLog` would make it explicit.
- **N6. Test-only mutable state and style.** `cancelConfirmationPause` and `cancelConfirmationBudget` are non-final, non-volatile fields on a singleton bean, changed only by a package-private test setter. This is acceptable. `PayUManagerLifecycleTest` uses fully qualified `java.time.Duration` inline instead of an import (nit). `LogCapture` uses `addAppender` and `setLevel`, which leave a LoggerConfig and a pinned level behind after `close()`. That is harmless, because tests run in a single fork with no parallel execution.

## AGENTS.md compliance
- No ternary operator in any added line, Java or TypeScript.
- No Lombok in the changed files. No `com.fasterxml` imports: JSON goes through Gson, as in the rest of PayU.
- `LogCapture.java` has the GPL header from `config/HEADER`.

## Metrics
- Longest changed method: `processWebhook` (about 30 lines), `cancelOrder` (about 45 lines).
- Max nesting depth: 3.
- Potential vulnerabilities: 0 new (N2 is alert noise, N5 is mitigated by the firewall).
- Tests run: 99 (PayUManagerTest 68, Lifecycle 7, CallbackController 8, WebhookController 11, RequestUtils 5), all passing.

## Prioritized Recommendations
1. N1: move or add the paid-notification ERROR in the webhook controller, keyed on a NOT_RELEVANT result, so that it also covers transactions that are INVALID or not PENDING. Fix the misleading test rows.
2. Confirm on the sandbox that `GET /orders/{id}` returns `extOrderId` (the CR-I3 dependency).
3. N3/N4: optional wording and deduplication cleanups.
