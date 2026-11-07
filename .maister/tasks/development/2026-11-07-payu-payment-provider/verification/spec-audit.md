# Spec Audit: PayU payment provider (pre-implementation)

## TL;DR
Verdict: **pass-with-concerns** (⚠️ Mostly Compliant). The spec covers FR1–FR15, can be built against the current core APIs without touching `TicketReservationManager`, and all 6 red-gate tests turn green if it is followed as written.
Three **High** money-safety holes sit in the §2 re-confirm matrix: a read error creates a second order; COMPLETED does not update the transaction, and a mismatch deletes it; a failed DELETE is not specified. Each one needs a few lines in the spec before coding.
Counts: Critical 0, High 3, Medium 7, Low 9.

## Key Decisions
- Rated the §2 holes **High, not Critical**: they need a re-confirm while the reservation is EXTERNAL_PROCESSING_PAYMENT (double-click, an API call, or a race), and the Angular guard normally routes the attendee away from the overview. The impact (double charge, or captured money with no reservation) is the exact thing the spec's goal rules out.
- The wrong cleanup and expiry analysis (M1) is rated **Medium**: it does not break code, but the Open Questions describe a failure mode that cannot happen and leave out the one that can.
- The owner's choices C1 (DELETE a NEW order) and I1–I6 are accepted as given. They are not re-litigated, and only their implementability and side effects are checked.
- The intended deviation from requirements Q&A 1 (a NEW order with no `error` parameter goes to the processing page, not the overview) is accepted, because it is reasoned in the spec's Key Decisions.

## Open Questions / Risks
- PayU semantics for `DELETE /api/v2_1/orders/{id}` on a **PENDING** order (a BLIK or bank transfer in flight) are unverified. `discardTransaction` sends it from the return `error` path and from `DELETE /api/v2/public/reservation/{id}/payment` (`ReservationApiV2Controller.java:693-697`).
- A reservation in WAITING_EXTERNAL_CONFIRMATION (PayU PENDING or WAITING) never expires. No scheduler selects that status, so a lost final notification holds the tickets until someone runs a force-check. This is inherited core behaviour.
- `customerIp` from `remoteAddr` can be IPv6 (`0:0:0:0:0:0:0:1` locally). Check that PayU accepts IPv6.
- The sandbox POS 300746 may accept only PLN, and the e2e harness hard-codes CHF (see M2).

---

## 1. Scope of verification
Read: `implementation/spec.md`, `analysis/requirements.md`, `analysis/scope-clarifications.md`, `analysis/clarifications.md`, `analysis/gap-analysis.md` (IDs), `implementation/tdd-red-gate.md`.
Code checked on `feature/payu` (HEAD `2259c9313`, already rebased onto `main`; `origin/feature/payu` = `f05742e44` still contains the Mollie commit):
- `PayUManager.java` (draft)
- `PayUSignature`, `PayUWebhookPayload`, `PayUToken`
- `PaymentWebhookResult`, `PaymentResult`, `PaymentSpecification`, `ServerInitiatedTransaction`, `TransactionInitializationToken`, `PaymentProvider`
- `TicketReservationManager`: performPayment L609, initPaymentProcess L753, cancelPendingPayment L687, reTransitionToPending L953, cleanupExpiredReservations L1014, findStuckPaymentsToBeNotified L1110, processTransactionWebhook L1771, handlePaymentWebhookResult L1831, forceTransactionCheck L1906, initTransaction L1983
- `ReservationFinalizer.registerAlfioTransaction` L470
- `TicketReservationRepository` L173–183, `TransactionRepository`, `PaymentManagerUtils`
- `MollieWebhookPaymentManager`, `SaferpayManager.doPayment`, `SaferpayCallbackController`, `MolliePaymentWebhookController`, `ReservationApiV2Controller.confirmOverview`, `IndexController`, `RequestUtils`
- `PayUManagerLifecycleTest` (untracked), `CheckRestApiStability`, the e2e `BaseE2ETest` and `AdminConsole`
- Public frontend: model, Saferpay proxy, processing page, overview
- Admin: new-admin model and icon helper; legacy `util.js` and configuration partials

## 2. How core reacts to each result (verified, basis for the findings)
| Provider returns | Path | Core effect | Evidence |
|---|---|---|---|
| `PaymentResult.successful` | performPayment | `transitionToComplete`. **The transaction is not touched** for PAYU (the finalizer only registers OFFLINE/CUSTOM_OFFLINE) | TRM L656-658; ReservationFinalizer L481-494 |
| `PaymentResult.failed` | performPayment | `reTransitionToPending(id)`, which **deletes all PENDING transactions** | TRM L659-661, L953-959 |
| `PaymentResult.pending` | performPayment → confirmOverview | Nothing in core; the controller answers 422 | TRM L656-663; RAV2C L375-381 |
| `pending()` = TRANSACTION_INITIATED | webhook / force-check | EXTERNAL_PROCESSING_PAYMENT → WAITING_EXTERNAL_CONFIRMATION; force-check → `PaymentResult.pending` | PWR L68-70; TRM L1844-1852, L1929-1930 |
| NOT_RELEVANT | webhook / force-check / stuck-check | Nothing; force-check → pending; **stuck-check marks the reservation STUCK** | TRM L1839, L1926, L1139 |
| CANCELLED | webhook / force-check | `reTransitionToPending(id, false)` (keeps the tx row); force-check → failed | TRM L1893-1896, L1925 |
| ERROR | webhook | Nothing; controller → 500 | TRM L1897-1899 |
| `discardTransaction` false | cancelPendingPayment | Returns false; no state change | TRM L698-708 |
| (any) | `cleanupExpiredReservations` | Only **PENDING** reservations are selected | TicketReservationRepository L173, L176 |
| (any) | `markExpiredInPaymentReservationAsStuck` | IN_PAYMENT / EXTERNAL_PROCESSING_PAYMENT, events only | TicketReservationRepository L182 |

`initPaymentProcess` moves to IN_PAYMENT only for STRIPE (TRM L757). A PayU re-confirm therefore keeps EXTERNAL_PROCESSING_PAYMENT, and notifications stay compatible with it (TRM L1950-1953). This is correct.

## 3. Requirements coverage (FR1–FR15)
| FR | Covered | Note |
|---|---|---|
| FR1 order creation | ✅ §1 doPayment, buildOrderRequest | validityTime fallback for a null reservation is correctly specified for the red-gate |
| FR2 re-confirm matrix | ⚠️ §2 | H1, H2, H3 |
| FR3 notification endpoint | ✅ §4 | M4 (400 is retried) |
| FR4 status mapping | ✅ §3 | – |
| FR5 remote cancel | ✅ §1 discardTransaction | The requirement wrongly says `reTransitionToPending` uses it (L1). The spec narrows it correctly but does not say so |
| FR6 return callback | ✅ §5 | Deliberate deviation for NEW without `error` (Key Decision) |
| FR7 refunds + getInfo | ✅ | M6 (idempotency) |
| FR8 currency gating | ✅ | – |
| FR9 timeouts | ✅ | L7 (cumulative lock time) |
| FR10 public frontend | ✅ §7 | – |
| FR11 admin | ✅ §8 | L2 (paths) |
| FR12 i18n | ✅ §9 (+1 extra key, justified) | – |
| FR13 stepper | ✅ verified `stepper.component.scss:44-46` | – |
| FR14 tests | ⚠️ | M2, M3 |
| FR15 Docker README | ✅ §11 | – |

---

## 4. High findings

### H1. A read error during re-confirm creates a second order (double charge)
- **Spec reference**: §2, row "CANCELED / not found / read error → create a new order and redirect". The Key Decision says this is "the only option that cannot double-charge".
- **Evidence**: the draft's `retrieveOrder` collapses every failure (timeout, 5xx, 401 after the retry, a non-SUCCESS body, unparseable JSON) into `Optional.empty()` (`PayUManager.java:342-363`). An existing PENDING order (BLIK or transfer in progress) whose status read fails transiently would get a second payable order. `invalidateExistingTransactions` then drops the old transaction (`PaymentManagerUtils.java:37-41`). A later COMPLETED notification for the old order is NOT_RELEVANT (`processWebhook` orderId mismatch, spec §1; draft L257-259), so money is captured with no reservation.
- **Category**: Incorrect / unsafe specification. **Severity**: High. This is the spec's core invariant.
- **Fix**: create a new order **only after a successful read that returns CANCELED** (and optionally after HTTP 404 with an explicit "order not found" body). On any other read failure, return `PaymentResult.pending(transaction.getPaymentId())`: the transaction is kept, confirmOverview answers 422, and the attendee retries later. This needs `retrieveOrder` to return a tri-state (found / not found / error) instead of `Optional`. Add a unit test for this row.

### H2. The COMPLETED row leaves the transaction PENDING, and a mismatch deletes it
- **Spec reference**: §2, row COMPLETED: "none (validate as in §3; on a mismatch, fall through to the error result) → `PaymentResult.successful(orderId)`". Key Decision: "The status is read without side effects".
- **Evidence**:
  - `performPayment` on success only calls `transitionToComplete` (TRM L656-658). `ReservationFinalizer.registerAlfioTransaction` updates the transaction only for OFFLINE/CUSTOM_OFFLINE (L489-494). For PAYU it logs and ignores. Unless PayUManager itself updates the transaction to COMPLETE (as §3 does, and as Saferpay's `doPayment` does through `internalProcessWebhook`, `SaferpayManager.java:128-133`), the reservation becomes COMPLETE with a PENDING transaction. That breaks refunds, getInfo and the reporting of confirmed payments.
  - "Fall through to the error result" is undefined. If it means `PaymentResult.failed`, `performPayment` calls `reTransitionToPending`, which deletes the PENDING transaction (TRM L659-661, L955-958). The record of a captured order is lost, the reservation is back to PENDING, and the attendee can pay again (a double charge).
- **Severity**: High.
- **Fix**: in §2, COMPLETED on a validation match means "update the transaction to COMPLETE (the same code path as the §3 COMPLETED branch) → `successful(orderId)`". On a mismatch: log at ERROR → `PaymentResult.pending(orderId)`, keeping the transaction and the reservation status so an operator can act. Never `failed` once an order exists. Correct the Key Decision wording: the read itself has no side effects, but the COMPLETED branch updates the transaction and the WAITING branch captures.

### H3. NEW row: a failed DELETE is unspecified, and a new order would orphan the old one
- **Spec reference**: §2, row NEW: "DELETE `/api/v2_1/orders/{id}`, then create a new order".
- **Evidence**: there is no rule for a DELETE that fails (timeout, the order moved to PENDING or COMPLETED between the GET and the DELETE, a 5xx). Creating the new order anyway leaves order 1 payable. Its COMPLETED notification then becomes NOT_RELEVANT (orderId mismatch, or "transaction not found" once it is invalidated: TRM L1810-1813), so the money is captured with no reservation. Note also that the red-gate stub answers the DELETE with HTTP **302** and a `SUCCESS` body (`PayUManagerLifecycleTest.java:109-111`). "Success" must therefore be defined on the body's `status.statusCode`, not on `HttpUtils.callSuccessful`, or a strict implementation will diverge from the integration test.
- **Severity**: High.
- **Fix**: create the new order only if the DELETE answers `status.statusCode == SUCCESS` (or a re-read shows CANCELED). Otherwise return `PaymentResult.pending(orderId)` and log at WARN. Reuse `discardTransaction`'s logic for this, so that §1 and §2 share one cancel routine. The red-gate test stays green, because it only asserts that the DELETE was sent.

---

## 5. Medium findings

### M1. The expiry and cleanup analysis is wrong for PayU reservations
- **Spec reference**: Key Decision "Return callback": "Such an order expires through `validityTime` and the cleanup job". Open Question: "WAITING_FOR_CONFIRMATION at expiry: cleanup force-checks first … `discardTransaction` refuses … cleanup still deletes the reservation".
- **Evidence**:
  - `cleanupExpiredReservations` selects only `status = 'PENDING'` reservations (`TicketReservationRepository.java:173`, `:176`).
  - A PayU reservation with a live order is EXTERNAL_PROCESSING_PAYMENT or WAITING_EXTERNAL_CONFIRMATION. The first is handled by `markExpiredInPaymentReservationAsStuck` → `findStuckPaymentsToBeNotified` (repo L182, TRM L1110-1140). In that path:
    - NEW → NOT_RELEVANT → **the reservation is marked STUCK and admins are emailed** (TRM L1139).
    - WAITING → capture → TRANSACTION_INITIATED, not moved because `moveToWaiting=false`, and not stuck. It completes on the next run, so the described "captured payment without a reservation" does not happen there.
    - CANCELED → CANCELLED → PENDING.
  - WAITING_EXTERNAL_CONFIRMATION is selected by no scheduler query.
- **Severity**: Medium (the risk register is wrong, and a real mode is missing).
- **Fix**: rewrite the Open Question. The real modes are "NEW still reported after the reservation validity → STUCK + admin email" (unlikely, because the reservation validity is order validity + 5 min and PayU auto-cancels at `validityTime`) and "WAITING_EXTERNAL_CONFIRMATION with a lost final notification holds tickets indefinitely". Optionally add a test or a log assertion that a CANCELED read during the stuck check returns the reservation to PENDING.

### M2. The PLN e2e needs a harness change that is not planned
- **Spec reference**: Testing §8: "the event is in PLN".
- **Evidence**: `BaseE2ETest.eventDefinition()` is `private` and hard-codes `"CHF"` (`BaseE2ETest.java:140-155`). There is no hook to change the currency. Sandbox POS 300746 most likely rejects CHF.
- **Fix**: add a `protected String currency()` hook (default `"CHF"`) used by `eventDefinition()`, and override it in `PayUPaymentE2ETest`. List `BaseE2ETest` in the e2e task group.

### M3. Test plan gaps on the money paths
- **Evidence**: the §2 matrix is tested only by the 3 red-gate rows. There are no planned tests for:
  - COMPLETED with a match (transaction updated, `successful`) or a mismatch
  - CANCELED → new order
  - read error → no new order (H1)
  - DELETE failure → no new order (H3)
  - `parseTransactionPayload`: invalid signature → empty, extOrderId mismatch → UNRECOGNIZED → `processWebhook` NOT_RELEVANT, refund payload → NOT_RELEVANT
  - `processWebhook` with a different orderId → NOT_RELEVANT

  Group 6 also lists about 11 tests, which is more than the 2–8 rule.
- **Fix**: add a "doPayment matrix" group (6 tests) and a "notification parsing" group (4–5 tests). Split group 6 into controllers and utilities.

### M4. The webhook's 400 for an unknown reservation is retried by PayU
- **Spec reference**: §4: "400 `NOK` for an unknown reservation or an empty body". Goal: "never … cause endless notification retries".
- **Evidence**: PayU retries every non-200 answer. A reservation deleted by cleanup (`ticketReservationRepository.remove`, TRM L1055) makes `purchaseContextManager.findByReservationId` empty, so the controller answers 400 to every retry. Core itself treats "reservation not found" as NOT_RELEVANT, which gives 200 (TRM L1805-1807).
- **Fix**: answer 200 (logged at WARN with the orderId if the body parses) for an unknown reservation. Keep 400 only for an empty body. Or explicitly accept the bounded PayU retry schedule in Open Questions.

### M5. DELETE on PENDING orders is unverified against PayU
- **Spec reference**: §1 `discardTransaction` (NEW or PENDING → DELETE).
- **Evidence**: callers are the return `error` path (§5) and the public `DELETE /reservation/{id}/payment` (`ReservationApiV2Controller.java:693-697`). If PayU allows cancelling a PENDING order whose funds then arrive, the behaviour (auto-return or not) decides whether this is money-safe.
- **Fix**: verify this in the PayU docs or sandbox during implementation. If it is unclear, restrict the DELETE to NEW and return false for PENDING (the reservation stays EXTERNAL_PROCESSING_PAYMENT until PayU resolves it).

### M6. Refund idempotency
- **Spec reference**: §1 Refund: `extRefundId = <reservationId>-refund-<8 random hex>`.
- **Evidence**: a 20 s timeout after PayU accepted the refund returns false (draft L316-334). The operator retries with a new random `extRefundId`, which produces a second refund.
- **Fix**: on a timeout or I/O error, GET `/orders/{id}/refunds` before reporting false, or derive `extRefundId` from the transaction id + amount + the count of existing refunds so that a retry is deduplicated by PayU. At least document the risk.

### M7. The Mollie-precedent claim for the NEW row is inaccurate (affects the double-click UX)
- **Evidence**: Mollie **reuses** an `open` payment by redirecting to its checkout link (`MollieWebhookPaymentManager.java:287-288`); it never deletes it. Under the spec, a double-click deletes order 1 that the first response already sent the browser to, and the buyer lands on a cancelled PayU order and comes back with `error`. That flow is safe for money, but it is a confusing path.
- **Fix**: keep C1 (the owner's decision, required by the red gate), but correct the rationale text. Make sure the overview "Pay" button stays disabled while the request is in flight (verify in `overview.component.ts confirm()`).

---

## 6. Low findings
- **L1. Requirements FR5 is inaccurate.** It says `reTransitionToPending` uses `discardTransaction`, but it is DB-only (TRM L953-959). The spec silently narrows this; it should state it explicitly.
- **L2. Legacy admin paths are wrong.** The partials are at `src/main/webapp/alfio-admin-v1/angular-templates/admin/partials/configuration/{system,organization}.html`, not `alfio-admin-v1/partials/...`. The line numbers L203 and L295-304 are confirmed.
- **L3. `errorToken`**: `PayUToken` implements `PaymentToken`, not `TransactionInitializationToken` (no `getClientSecret`), so it does not "fit". Use a private nested class. Also note that TRM `initTransaction` calls `transitionToExternalProcessingPayment` when `errorToken(..., false)` is returned (TRM L2003-2008). With a PENDING PayU transaction and a PENDING reservation, a direct call to the init endpoint could flip the status. This is unlikely, but `initTransaction` could return `errorToken(msg, true)` to route through the force-check instead.
- **L4. The red-gate test is untracked** (`git status ??`). It must be `git add`-ed into commit 1, as the plan intends.
- **L5. Descriptor regeneration** means temporarily setting `updateDescriptor = true` (`CheckRestApiStability.java:74`, a hard-coded `final` field) and reverting it before the commit. State this in the task.
- **L6. Red-gate mock constraint**: the stub answers only `configurationManager.getFor(Set, level)` (`PayUManagerLifecycleTest.java:87-92`). The single-key `getFor(key, level)` overload returns null and causes an NPE. The implementation must read everything (for example RESERVATION_TIMEOUT) from the ALL_KEYS map in the doPayment, checkOrderStatus and parse paths.
- **L7. Lock duration**: performPayment holds the reservation row lock (TRM L633) across up to about 5 sequential calls of 20 s each (OAuth, GET, PUT or DELETE, POST, a 401 retry). That blocks webhooks and force-checks for about 100 s and can exceed reverse-proxy timeouts. Consider 10 s per request or a total budget.
- **L8. No cancellation toast** when the CANCELED notification beat the return: `cancelPendingPayment` → false, then the force-check is empty → `/book` → overview without the toast. Cosmetic; optionally map "force-check empty + `error` present" to `?paymentCancelled=true`.
- **L9. POST timeout after PayU created the order**: alf.io returns `failed`, leaving an unreachable NEW order that expires through `validityTime`. Acceptable; document it under the "no payable orphan orders" goal.

## 7. Red gate → green (traced against the spec)
| Test | Spec path | Turns green? |
|---|---|---|
| …AwaitsConfirmation | §2 WAITING: PUT (stub 302 + SUCCESS) → `pending` → `!isRedirect`, no POST | Yes |
| …IsBeingPaid | §2 PENDING → `pending`, no POST | Yes |
| …CancelsStillNewOrder… | §2 NEW → DELETE sent; the creation then needs the null-reservation validityTime fallback (specified) and `getShortReservationID` returning null (harmless) | Yes (also with the H3 fix, because only the DELETE is asserted) |
| newOrderIsNotReportedAsPaymentInProgress | §3 NEW → NOT_RELEVANT; `forceTransactionCheck` must not touch the null reservation (specified) | Yes |
| signedRefundNotificationIsAcknowledged | §1 step 4, top-level `refund` → REFUND payload | Yes |
| …WithoutExtOrderIdDoesNotThrow | §1 step 3, null-safe | Yes |
| fixtureCreatesOrder… (control) | unchanged; the public 5-arg constructor is kept | Yes |

## 8. AGENTS.md / CONTRIBUTING compliance of the spec
- No ternary: required explicitly (Java, TS, tests). Note that the existing `PaymentManagerUtils` contains one; it must not be copied.
- Lombok: only `@Getter` on `PaymentSpecification` (already class-level) and the existing `@AllArgsConstructor` controller style. The nested token class is hand-written. OK.
- Jackson: Gson only. `forbiddenApis` blocks `com.fasterxml.jackson.{databind,core,dataformat}` (`build.gradle.kts:276-281`). OK.
- GPL header on new `.java` files: required.
- EN-only keys on `feature/payu`, PL on a fork-only branch: OK.
- New admin: union entry and icon case only (`frontend/admin/src/model/event.ts:61`, `service/reservation-format.ts:25-36` verified). Extending the legacy panel is justified.
- Commits: no `Co-Authored-By` or AI trailers (AGENTS.md overrides any harness attribution default). Plan complies.

## 9. Branch plan feasibility
- Local `feature/payu` is already `main + 2259c9313`; `origin/feature/payu` = `f05742e44` (on top of the Mollie commit `ea770e35f`). Rewording through `git commit --amend -m`, adding the commits one after another, then `git push --force-with-lease origin feature/payu` works with no interactive git.
- `backup/feature-payu-wip` exists **locally only** (it points at `f05742e44`). Push it, or accept that `origin/fix/mollie-ignore-unsupported-methods` plus the reflog is the only remote copy.
- `fix/pl-translations`, `fix/stepper-active-color` and `plo-pl` do not exist yet (local or remote). Creating them from `main` and running `git merge --no-edit` for the four branches into `plo-pl` is non-interactive. A conflict is expected only on `descriptor.json`, and only if another branch changes the API; the regeneration step is already planned.

## 10. Recommendations (ordered)
1. Amend §2 per H1, H2 and H3: create a new order only on a confirmed CANCELED, or a confirmed DELETE of a NEW order. Update the transaction on COMPLETED. Return `pending(orderId)` (never `failed`) once a remote order may exist.
2. Rewrite the expiry and cleanup risk (M1).
3. Add a currency hook to `BaseE2ETest` (M2) and the missing unit-test groups (M3).
4. Decide on the webhook's 400 vs 200 for unknown reservations (M4), DELETE on PENDING (M5) and refund idempotency (M6).
5. Fix the Low items in the task breakdown: paths, the descriptor flag, `git add` of the red-gate test, the nested `errorToken` class and pushing the backup ref.
