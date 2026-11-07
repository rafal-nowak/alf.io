# Code Review Report

**Date**: 2026-11-07
**Path**: `git diff main..feature/payu`, `git diff main..fix/pl-translations`, `git diff main..fix/stepper-active-color` (/Users/rafalnowak/Dev/alf.io)
**Scope**: all
**Status**: ⚠️ Issues Found

## TL;DR
- No critical issues. Signature check, completion validation, refund idempotency and the "never `failed` while an order may be payable" rule are implemented correctly.
- 4 warnings: the worst-case time under the reservation lock is now ~90 s (the spec says ~50 s); a COMPLETED notification for a non-current order is dropped silently; NOT_FOUND counts as "safe to recreate or discard"; the GET return URL can cancel an in-flight (PENDING) order with no CSRF protection.
- 12 info items: log injection, reason echo in 500 bodies, spec drift, real sleeps in tests, the AGENTS.md translation rule, and smaller points.
- AGENTS.md rules hold: no ternaries in added code, no Jackson 2 imports, Lombok limited to `@AllArgsConstructor`, GPL headers present.

## Key Decisions
- **Cancellation is confirmed by re-reading the order, not by the DELETE answer** (commits dcedbd9ad, 6475c6ecb). This is safer than spec §1, which counts a DELETE answering SUCCESS as CANCELLED. The spec text should be brought in line (I6).
- **Mismatched COMPLETED → ERROR/pending** (no auto-completion) is consistent with the accepted risk in the spec.
- **The webhook trusts the reservationId in the URL only after signature verification**, with the configuration level derived from that reservation. A key from organization A cannot sign notifications for a reservation of organization B.
- **The CSRF exemption scope is unchanged**: the existing `/api/payment/webhook/` prefix (`AbstractFormBasedWebSecurity` L338-339). No new exemption was added.

## Open Questions / Risks
- Is `DATA_NOT_FOUND` really PayU's status code for an unknown order, and does PayU answer 404 for an order of another POS? This affects W3.
- A DELETE on a PENDING order is still unverified on the sandbox (spec risk). W4 makes this path reachable from a plain GET.

## Summary
- **Critical**: 0 issues
- **Warnings**: 4 issues
- **Info**: 12 issues

## Critical Issues
None.

## Warnings

### W1. Worst-case lock duration is now ~90 s, above the spec's ~50 s and the recommended 60 s proxy timeout (concurrency)
- **Location**: `src/main/java/alfio/manager/payment/PayUManager.java:802-831` (cancel confirmation loop), `:104-105` (constants), `:210-218` (doPayment)
- **Description**: `performPayment` holds the reservation row lock (`findReservationByIdForUpdate`) and a DB connection, plus the `@Transactional` transaction, while `doPayment` runs:
  - OAuth (10 s)
  - GET (10 s)
  - DELETE (10 s)
  - up to 5 GETs (50 s) with 4 × 500 ms `Thread.sleep`
  - POST (10 s)

  That adds up to about 92 s, before any 401 retries: each retry adds another OAuth call plus the repeated call, so up to +20 s per call. The loop only continues while the re-reads succeed (FOUND NEW/PENDING), so a timeout stops it. The worst case therefore needs slow but successful answers, which is pathological but possible during a PayU degradation. Webhooks and force-checks for the same reservation wait on the lock. The same loop runs, without the lock, from the return callback and the public DELETE endpoint.
- **Recommendation**: give `cancelOrder` an overall deadline, for example 5 s in total for the re-reads (checked with `clockProvider`), instead of a fixed number of reads. Or skip the confirmation polling in `reconfirmExistingOrder`: return pending after a single re-read, and let the CANCELED notification settle it. Update the lock-duration figure in the spec's Risks section.
- **Fixable**: false (design trade-off)

### W2. A notification for a non-current order is dropped silently, even when that order is COMPLETED (money safety)
- **Location**: `src/main/java/alfio/manager/payment/PayUManager.java:497-500`
- **Description**: when `notifiedOrderId` differs from the current PENDING transaction's `paymentId`, the result is `notRelevant`, and core logs it only at TRACE. The webhook answers 200, so PayU stops retrying. If an earlier order that was "confirmed cancelled" is in fact paid later, its COMPLETED notification is lost without any trace. This can happen with the accepted risk that a cancelled PENDING order can still receive funds, or with a CANCELED status that PayU reverts. The money is then captured with no reservation and no alert. The same applies when the transaction is no longer PENDING: core logs a WARN there, but without the PayU status.
- **Recommendation**: when the notified status (`payUPayload.getOrderStatus()`) is COMPLETED or WAITING_FOR_CONFIRMATION and the orderId is not the current transaction's, log at ERROR with the orderId, extOrderId and reservationId, so that operators can refund. Keep answering 200.
- **Fixable**: true

### W3. NOT_FOUND counts as "no payable order", but the lookup is scoped to the current endpoint and credentials (double-charge risk)
- **Location**: `src/main/java/alfio/manager/payment/PayUManager.java:96-97` (unverified `DATA_NOT_FOUND`), `:234-236` (re-confirm creates a new order), `:584-586` (discard returns true), `:769`
- **Description**: `retrieveOrder` uses the configuration that applies now: `PAYU_LIVE_MODE`, and the client id and secret at system or organization level. Suppose an admin switches from sandbox to live, or changes the POS or credentials (for example by adding organization-level settings over the system ones) while a PENDING order exists. PayU then answers 404 or `DATA_NOT_FOUND` for an order that still exists and may still be paid under the old POS. As a result:
  - the re-confirm creates a second payable order;
  - `discardTransaction` reports success and core resets the reservation to PENDING.

  `DATA_NOT_FOUND` itself is marked "to be verified on the sandbox".
- **Recommendation**: store the endpoint, or the POS id, in the transaction metadata next to `extOrderId`. When the current configuration differs, treat NOT_FOUND as ERROR (pending, not discarded). At minimum, verify the not-found status code on the sandbox and remove the comment.
- **Fixable**: false

### W4. A GET return URL triggers a remote cancellation, including of PENDING (in-flight) orders, with no CSRF protection
- **Location**: `src/main/java/alfio/controller/payment/PayUCallbackController.java:72-77`, `src/main/java/alfio/manager/payment/PayUManager.java:595-598`
- **Description**: anyone who knows the reservation URL can make a browser open `/{type}/{id}/reservation/{rid}/payment/payu/return?error=x`. Reservation ids appear in emails, links and browser history, and link prefetchers can open such a URL too. Opening it runs `cancelPendingPayment` → `discardTransaction`, which DELETEs NEW **and PENDING** orders. A PENDING order is one the buyer is paying right now (BLIK or bank transfer in flight). Whether PayU can still take funds after a DELETE on PENDING is the open spec risk, and this GET makes that path reachable without a CSRF token. The public `DELETE /api/v2/public/reservation/{id}/payment` goes through CSRF.
- **Recommendation**: on the return path, restrict the DELETE to NEW orders: for PENDING, rely on `forceTransactionCheck` and the notification. Alternatively, add a separate `discardTransaction` mode for the return path. If PENDING stays cancellable here, document it as an accepted risk.
- **Fixable**: false

## Informational

- **I1. Log injection** (security hygiene)
  - **Location**: `PayUCallbackController.java:73`, `PayUPaymentWebhookController.java:64`
  - **Description**: `error`/`errorCode` come from the query string and are logged as is. On the webhook, `orderId` comes from an unsigned body and is logged before any signature check. CR/LF can forge log lines.
  - **Suggestion**: strip CR/LF, or log `StringUtils.abbreviate(StringUtils.normalizeSpace(x), 64)`.
  - **Fixable**: true
- **I2. Internal reasons echoed in 500 bodies**
  - **Location**: `PayUPaymentWebhookController.java:72`
  - **Description**: an unauthenticated caller receives strings such as "payload not recognized", "signature is missing" or "PayU order X not found". This is a minor information leak and makes probing easier.
  - **Suggestion**: answer a fixed `"ERROR"` and keep the reason in the log.
  - **Fixable**: true
- **I3. Lenient extOrderId match**
  - **Location**: `PayUManager.java:281-283`
  - **Description**: when PayU's order has no `extOrderId`, the check passes. The amount, currency and the orderId (taken from our own transaction) still protect it, so the risk is low.
  - **Suggestion**: require the match when our metadata has an `extOrderId`, and log a WARN when PayU omits it.
  - **Fixable**: true
- **I4. Unchecked `orderId` in a successful create response**
  - **Location**: `PayUManager.java:324-328`
  - **Description**: the code checks `redirectUri` but not `orderId`. A response without one throws an NPE, which becomes `failed`. Also, a DB failure after the PayU order is created leaves `updateReservationStatus`, `updateValidity` and `invalidateExistingTransactions` partly applied inside a transaction where the exception is swallowed. The buyer never gets the `redirectUri`, so this matches the accepted "POST timeout" risk.
  - **Suggestion**: add `|| !responseBody.has("orderId")` to the guard, and log the orderId on any later failure, so that the order can be traced.
  - **Fixable**: true
- **I5. `getClientIp` takes the leftmost `X-Forwarded-For` entry**
  - **Location**: `src/main/java/alfio/util/RequestUtils.java:61-74`
  - **Description**: the leftmost entry can be set by the client. It is also redundant: with `server.forward-headers-strategy=native` (`application.properties:1`), `getRemoteAddr()` is already the forwarded client. This is an accepted spec risk, since PayU uses the value only for fraud scoring.
  - **Suggestion**: use `request.getRemoteAddr()` only.
  - **Fixable**: true
- **I6. Spec drift**
  - **Location**: `implementation/spec.md` §1 "Shared cancel routine" and Risks "Lock duration"
  - **Description**: the implementation changed the cancel semantics: always re-read, with up to 5 reads. The spec still describes "DELETE SUCCESS = CANCELLED" and a ~50 s lock.
  - **Suggestion**: update the spec to the implemented behaviour, which is the safer one.
  - **Fixable**: true
- **I7. Real sleeps in unit tests**
  - **Location**: `PayUManager.java:105`; `src/test/java/alfio/manager/payment/PayUManagerTest.java:674-692` (and every test reaching the loop)
  - **Description**: `CANCEL_CONFIRMATION_PAUSE` is a constant, so the tests that reach the loop really sleep, up to ~2 s each.
  - **Suggestion**: make the pause, or a sleeper, injectable through the package-private constructor.
  - **Fixable**: true
- **I8. Cancellation toast repeats**
  - **Location**: `frontend/public/src/app/reservation/overview/overview.component.ts:72-74`
  - **Description**: the toast comes back on every reload or back-navigation while `?paymentCancelled=true` stays in the URL.
  - **Suggestion**: after showing it, remove the parameter with `router.navigate([], {queryParams: {paymentCancelled: null}, queryParamsHandling: 'merge', replaceUrl: true})`.
  - **Fixable**: true
- **I9. PL translations edited directly** (AGENTS.md rule)
  - **Location**: `fix/pl-translations`: `src/main/resources/alfio/i18n/public_pl.properties` (+67 keys)
  - **Description**: AGENTS.md says "leave the other languages to POEditor". This is likely deliberate for the `plo-pl` fork (spec FR12); keep it out of any upstream PR. The 4 PayU keys are missing from that branch's EN file only because `feature/payu` is not merged yet. A check found no placeholder mismatches in the added keys.
  - **Fixable**: false
- **I10. Leftover "to be verified" comment**
  - **Location**: `PayUManager.java:96`
  - **Description**: the comment "to be verified on the sandbox" is an open TODO (see W3).
  - **Fixable**: false
- **I11. Return without `error` on a still-NEW order leaves the attendee on the processing page**
  - **Location**: `PayUCallbackController.java:78-85`
  - **Description**: the force-check maps NEW to error → pending → `/book` → processing page. That page has no cancel action, so the tickets stay held until PayU expires the order. This is partly an accepted risk ("NEW past validity").
  - **Suggestion**: on return, treat a NEW order like the `error` path.
  - **Fixable**: false
- **I12. Legacy admin and plain-text secrets**
  - **Location**: `src/main/webapp/alfio-admin-v1/.../configuration/{system,organization}.html`, `ConfigurationKeys.java` (PAYU_CLIENT_SECRET, PAYU_SECOND_KEY as `ComponentType.TEXT`)
  - **Description**: AGENTS.md says to build new admin screens in `frontend/admin`; here the existing legacy pages are extended, consistent with Stripe, Saferpay and Mollie. The secret fields render as plain text like the other providers' (spec I6). Both are intentional.
  - **Fixable**: false

### Verified OK (no finding)
- **Signature**: `MessageDigest.isEqual` (constant-time), a blank second key is rejected, invalid hex is rejected, only MD5 and SHA-256 are accepted (`PayUSignature.java:37-65`).
- **No open redirect**: the redirects are built from the DB purchase context and an existing reservation id (`PayUCallbackController.java:58-91`).
- **No secret leakage**: the client secret, token and second key are never logged. The OAuth failure message carries only the HTTP status, and the token cache key hashes the secret.
- **Refund idempotency**: deterministic `extRefundId`, re-read on I/O error, no POST when the refund list cannot be read.
- **Money safety in `doPayment`**: `failed` is never returned once a previous order may be payable. COMPLETED is validated before completion. WAITING_FOR_CONFIRMATION is captured.
- **Endless retries**: refund, unrecognized and non-current-order notifications answer 200. Only invalid signatures and unreadable or mismatched orders answer 500 (by design).
- **Lock order** (reservation, then transaction) follows core. No new deadlock path.
- **fix/stepper-active-color**: a one-line `var(--bs-primary)` change, fine.

## Metrics
- Max function length: ~48 lines (`cancelOrder`, PayUManager.java:802-850)
- Max nesting depth: 3 levels
- Potential vulnerabilities: 3 (W4 GET-triggered cancel, I1 log injection, I2 reason echo)
- N+1 query risks: 0
- Blocking calls under the DB lock: up to ~9 HTTP calls plus 2 s of sleep (W1)

## Prioritized Recommendations
1. W2: log at ERROR a COMPLETED notification for a non-current order (cheap, closes a silent money-loss path).
2. W4: restrict the DELETE on the return path to NEW orders.
3. W3: record the POS or endpoint in the transaction metadata, and treat NOT_FOUND under a changed configuration as ERROR. Verify `DATA_NOT_FOUND` on the sandbox.
4. W1: put a time budget on the cancel confirmation, and update the lock-duration figure in the spec.
5. I1, I2, I4, I8: small hygiene fixes.
