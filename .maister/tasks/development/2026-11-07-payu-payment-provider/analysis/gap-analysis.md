# Gap Analysis: Complete the PayU payment provider (alf.io fork)

## TL;DR
The PayU draft is wired into the backend: the proxy, payment method, configuration keys and `PayUManager` exist. It is not usable yet. Nothing receives PayU notifications, the public frontend shows "UNKNOWN PAYMENT PAYU", and the admin has no PayU labels or settings panel. There are no tests, and the build will fail on `CheckRestApiStability`. Several lifecycle defects (B1-B3, B5) can double-charge a buyer or cause endless retries.
The work is additive. It follows existing templates (Saferpay for the frontend proxy and callback, Mollie for the webhook and order reuse) and needs no Flyway migration. Risk: medium-high, because this is a payment path with no test coverage today.

## Key Decisions
- Characteristics: **has_reproducible_defect = yes**. B1, B3 and B5 were found by reading the code and can be reproduced in unit tests; no runtime failure has been reported. modifies_existing_code = yes (draft plus shared touchpoints), creates_new_entities = yes (controllers, frontend component, tests), involves_data_operations = yes (transaction and reservation lifecycle, remote PayU order lifecycle), ui_heavy = yes, though light (one selector option, admin labels and a settings panel).
- Change type: **additive**. A new provider is plugged into the existing `PaymentProvider` capability model. `TicketReservationManager` and `PaymentManager` contracts stay unchanged. Compatibility: **strict** for other providers, because the shared lifecycle must not regress.
- The only cross-cutting backend change is the new optional `customerIp` field in `PaymentSpecification`, already chosen by the owner. `PaymentSpecification` is constructed in 14 files, so add an overload or a new constructor and leave the existing signatures unchanged.

## Open Questions / Risks
- `feature/payu` contains the Mollie commit `ea770e35f`, because it was branched from `fix/mollie-ignore-unsupported-methods`. The branch is therefore not cleanly PR-able on its own (see decision I1).
- PayU accepts only some currencies (PLN, EUR, USD, CZK, GBP and a few others). `isActive`/`accept` does not check the purchase context currency, so a CHF event would offer PayU and then fail when the order is created (see I3).
- The PayU sandbox POS 300746 is shared and public. E2E results can be flaky because of sandbox availability, and notifications never reach localhost (already accepted: return plus force-check).
- The local container on port 8090 belongs to another project. E2E must use a dedicated `bootRun`.
- `CheckRestApiStability`'s `descriptor.json` must be regenerated on every branch that changes enums or paths (`feature/payu`, and `plo-pl` after merging).

## Summary
- **Risk Level**: Medium-High
- **Estimated Effort**: Medium-High (about 20 files touched across backend, two admins, public frontend, i18n and three test layers, plus Docker documentation)
- **Detected Characteristics**: has_reproducible_defect, modifies_existing_code, creates_new_entities, involves_data_operations, ui_heavy

## Task Characteristics
- Has reproducible defect: yes (static defects in the WIP draft, reproducible in unit tests)
- Modifies existing code: yes
- Creates new entities: yes
- Involves data operations: yes
- UI heavy: yes (light)

## Gaps Identified

### Missing Features
- **Webhook controller (B4)**: `PayUManager.WEBHOOK_URL_TEMPLATE = /api/payment/webhook/payu/reservation/{reservationId}` is sent to PayU as `notifyUrl`, but no controller maps it, so every notification gets a 404. Template: `controller/payment/api/mollie/MolliePaymentWebhookController.java`. CSRF is already skipped for `/api/payment/webhook/**`.
- **Return/cancel callback (M1)**: `continueUrl` points straight at `/{type}/{id}/reservation/{rid}/book`. PayU's `?error=501` (payment cancelled or failed) is ignored. After an abandoned payment the buyer waits about 20 s of polling, and the reservation stays in WAITING_EXTERNAL_CONFIRMATION. Template: `SaferpayCallbackController`.
- **Public frontend**: `frontend/public/src/app/payment/payu-payment-proxy/` does not exist. `PAYU` is missing from `StaticPaymentMethodNames`, the `PaymentProxy` union and `staticPaymentMethodDetails` in `model/event.ts`. It is also not in `app.module.ts` or `payment-method-selector.component.html`.
- **Legacy admin**: `PAYMENT_PROXY_DESCRIPTIONS` in `filter/util.js` has no PAYU entry. `system.html` and `organization.html` have no `paymentPayu` panel, so an admin cannot configure PayU through the UI.
- **New admin**: the `PaymentProxy` union in `model/event.ts` and `paymentMethodIcon` in `reservation-format.ts` have no PAYU entry.
- **i18n**: no `payu` key in any properties file. `error.STEP2_PAYU_unexpected` is referenced at `PayUManager.java:169,182,185` but not defined, so the error falls back to the Stripe message (M3).
- **Tests**: no PayU tests at any layer. `AdminConsole.paymentMethodLabel` (e2e) throws for PAYU.
- **Docker build documentation**: absent (the fork README and `.maister/docs` contain nothing about it).

### Incomplete Features
- **doPayment existing-order handling (B1)**: today only COMPLETED short-circuits. It needs the full state matrix: COMPLETED → success; PENDING or WAITING_FOR_CONFIRMATION → pending (do not create a new order); NEW → cancel the remote order and recreate it; CANCELED → create a new order.
- **Validity (B2)**: no `validityTime` in the order and no `transactionRepository.updateValidity`. Saferpay (:377) and Mollie (:341) both set them.
- **Remote cancellation (B3)**: `PayUManager` does not implement `ServerInitiatedTransaction.discardTransaction`, so the expiry and cancel paths in `TicketReservationManager` (687-707, 953-959) leave PayU orders payable.
- **Notification parsing (B5)**: refund notifications (no `order` key) yield `Optional.empty` → ERROR → HTTP 500 → PayU retries indefinitely. `PayUWebhookPayload.getStatus` is not null-safe.
- **Hardening**: M4 (customerIp is 127.0.0.1), M5 (token cache key leaves out the secret; no invalidation on 401), M6 (no HTTP timeout while the reservation row lock is held), M7 (amount, currency and extOrderId are not validated on status check), M8 (refund has no `extRefundId`; `refundedAmount` is null).
- **Testability**: `getEndpoint` hard-codes the SANDBOX and LIVE URLs (:421-427). An override is needed for WireMock. Per clarifications this was decided by default, not asked.

### Behavioral Changes Needed
- NEW status: from `processStarted` to pending/not-relevant (M2), so polling and the scheduler do not misreport it.
- Unknown or refund notification types: from ERROR (500) to NOT_RELEVANT (200) once the signature is valid.
- Stepper current-step badge: from `#007ACC` to `var(--bs-primary)` (generic branch, decided).

## User Journey Impact Assessment

| Dimension | Current | After | Assessment |
|-----------|---------|-------|------------|
| Reachability (buyer) | PayU radio appears when enabled, labelled "UNKNOWN PAYMENT PAYU"; confirm runs the offline branch | Overview → payment selector → "PayU: BLIK, fast transfer, card" → Confirm → PayU page → return → confirmation | ❌ → ✅ |
| Discoverability (buyer) | 2/10 | 9/10 (radio option in the standard selector, with label, icon and description) | +7 |
| Flow integration (buyer) | Dead end: no notification handler, no cancel handling | Standard redirect flow reusing ProcessingPaymentComponent; the callback handles cancellation | ❌ → ✅ |
| Reachability (admin config) | Only through raw configuration/API; no panel | Settings → Payment → PayU panel (system and organization) | ❌ → ✅ |
| Discoverability (admin) | 1/10 | 8/10 (same place as Saferpay and Mollie) | +7 |
| Reservation views (admins) | Proxy shown as raw `PAYU`, no icon | Label "PayU" plus credit-card icon in the legacy and new admin | ⚠️ → ✅ |

Personas: buyer (public frontend), organization admin (organization-level config), system admin (system-level config, live mode), check-in/reservation operator (labels only). All are covered by the planned touchpoints. Subscriptions also consume `PAYMENT_PROXY_DESCRIPTIONS` (`subscriptions.js` L139/227), so they get the label automatically.

## Data Lifecycle Analysis

### Entity: PayU payment (alf.io `Transaction` + remote PayU order)

| Operation | Backend | UI | Access | Status |
|-----------|---------|-----|--------|--------|
| CREATE (order + PENDING tx) | `PayUManager.doPayment` → POST `/api/v2_1/orders` (B1 and B2 defects) | Missing: no payu-payment-proxy, no `staticPaymentMethodDetails` entry | Selector radio is shown but mislabelled; confirm takes the wrong branch | ❌ |
| READ (status) | `checkOrderStatus` / `getInfo`; webhook controller missing | ProcessingPaymentComponent (generic), admin reservation views (no label) | Force-check endpoint exists; webhook 404 | ⚠️ |
| UPDATE (confirm / capture / validity) | `confirmOrder` on WAITING; no `updateValidity`; the old tx is invalidated wrongly (B1) | n/a (server-driven) | n/a | ⚠️ |
| DELETE (cancel remote order) | Missing: no `discardTransaction` / DELETE order | Cancel-on-return missing (no callback) | Expiry scheduler exists but does not reach PayU | ❌ |
| REFUND | `refund()` exists, no `extRefundId`; refund notification causes 500s | Legacy admin refund button (generic, `supportRefund=true`) | Reachable | ⚠️ |

**Completeness**: about 25%. The backend layer is about 70%. The UI and access layers are missing for the buyer path and for admin configuration.
**Orphaned Operations**: (1) the backend creates orders, but there is no buyer UI to trigger them correctly (backend exists, no UI); (2) PayU sends status updates (CREATE of payment state on PayU's side) and no READ endpoint receives them; (3) remote orders are created, but nothing cancels them (CREATE without DELETE); (4) the PayU config keys exist, but there is no admin panel to set them (backend exists, no UI).
**Missing Touchpoints**: admin settings panels (system and organization); reservation detail labels and icons (both admins); e2e `AdminConsole.paymentMethodLabel`; payment-method selector; overview confirm branch; email/PDF templates show the payment method through `PaymentProxy` description (no change needed, they use the generic label path).

All orphaned operations are already in the task scope (items 1-2 of the task), so no scope expansion beyond the brief is needed. The lifecycle fixes B1-B3/B5 are not optional parts of "complete" (see C1).

## Defect Analysis

### Reproduction Data
- **B1 (double charge / orphaned payable order)**: reservation in EXTERNAL_PROCESSING_PAYMENT with PENDING tx (order NEW or WAITING_FOR_CONFIRMATION) → buyer returns and presses Confirm again → `doPayment` sees non-COMPLETED → (WAITING: captures via `confirmOrder`) → creates a second order and invalidates the first tx → a later COMPLETED notification for order 1 is NOT_RELEVANT. Expected: a single payable order; the existing one is reused or cancelled. Actual: two payable orders; the payment for order 1 is lost.
- **B3**: reservation expires after a PayU order is created → `cleanupExpiredReservations` → `cancelPendingPayment` skips `discardTransaction` (not a ServerInitiatedTransaction) → the order remains payable on PayU, so the buyer can pay for a deleted reservation.
- **B5**: PayU sends a refund notification `{orderId, extOrderId, refund:{...}}` → `parseTransactionPayload` returns empty → ERROR → 500 → PayU keeps retrying.

### Root Cause Hypothesis
The draft copied the Saferpay shape (one-shot, server-validated) but not Mollie's "reuse existing remote transaction" and expiry semantics. PayU orders are long-lived and payable until they are cancelled or expire, which neither part of the draft handles.

### Regression Risk Areas
- `PaymentSpecification` constructor change (14 call sites) and `ReservationApiV2Controller` (customerIp).
- `CheckRestApiStability` descriptor (enum and path additions).
- `TicketReservationManagerTest` and `PaymentMethodDeserializationTest` iterate the enums.
- `payment-method-selector` and `overview.component.ts confirm()`: other proxies must keep their branch.
- The shared `HttpClient` bean, if timeouts are added globally rather than per request.

## Issues Requiring Decisions

### Critical (Must Decide Before Proceeding)
1. **C1 - NEW-order handling on re-confirm (B1)**: what to do when a PENDING tx exists and the PayU order is NEW.
   - Options: [A] Cancel the remote order (DELETE `/api/v2_1/orders/{id}`), then create a new one. [B] Reuse the existing order by storing `redirectUri` in tx metadata and redirecting again. [C] Return pending and block a new order until it expires.
   - Recommendation: A. It is the Mollie precedent, it is always consistent with the current amount, buyer and validity, and the same DELETE call implements B3 `discardTransaction`. B breaks if the reservation changed in between. C traps the buyer.
2. **C2 - Hardening scope in this task**: M4-M8 (customerIp, token cache, per-request timeout, amount/currency/extOrderId validation, extRefundId/refundedAmount).
   - Options: [A] All in scope on `feature/payu`, with unit tests. [B] B1-B5 and M1-M4 now; M5-M8 deferred to a follow-up. [C] Only the blockers B1-B5.
   - Recommendation: A. Each item is small and local to PayUManager, money is involved, and production use for the reunion is imminent. M6 should be a per-request timeout in PayUManager, not a change to the shared bean.

### Important (Should Decide)
1. **I1 - Branch base for `feature/payu`**: the branch sits on top of the Mollie commit `ea770e35f`.
   - Options: [A] Rebase `feature/payu` onto `main` (drop the Mollie commit from it; Mollie stays on its own branch; `plo-pl` merges both). [B] Keep the stacked branch.
   - Default: A. Rationale: the rules require generic fixes on separate PR-able branches, and PayU must not carry the Mollie change. Also reword or squash the "WIP" commit into meaningful commits.
2. **I2 - Return callback controller**: not explicitly confirmed by the owner, though the e2e decision ("return + callback/force-check") implies it.
   - Options: [A] Add a GET `PayUCallbackController` (handles `error=501` → cancel the order and revert to PENDING with an error; otherwise force-check → redirect to `/book`). [B] Keep `continueUrl` → `/book` and rely on polling plus force-check.
   - Default: A. Rationale: it gives immediate feedback on cancellation, avoids a 20 s wait and a stuck WAITING_EXTERNAL_CONFIRMATION, and follows the Saferpay template.
3. **I3 - Currency gating**: PayU supports a limited set of currencies.
   - Options: [A] In `accept`/`isActive`, offer PayU only if the purchase context currency is in the PayU supported set. [B] No gating (the order fails at creation with a generic error).
   - Default: A. Rationale: it avoids a broken option for non-supported events. It is cheap and testable.
4. **I4 - Payment method icon / logo**: FontAwesome 5 free has no PayU brand icon.
   - Options: [A] `fas credit-card` (or `money-check-alt`) in both frontends. [B] Add the official PayU logo asset (the Mollie badge precedent in `assets/img/payment/`).
   - Default: A. Rationale: it avoids trademark and asset licensing questions in an upstream-able branch. The label text "PayU: BLIK, fast transfer, card" carries the brand.
5. **I5 - Where to document the Docker build command**:
   - Options: [A] In `.maister/docs` (or a fork note) on `plo-pl` only. [B] In `README.md` on `plo-pl` only. [C] In `README.md` on `feature/payu`.
   - Default: B. Rationale: it is visible to whoever builds the fork image, keeps PR branches free of fork-specific content (the image tag `alfio-plo-pl:payu-<date>` is fork-specific), and complies with the rule that `.maister` lives only on `plo-pl`.
6. **I6 - Secret configuration fields**: `PAYU_CLIENT_SECRET` and `PAYU_SECOND_KEY` are `ComponentType.TEXT`.
   - Options: [A] Keep TEXT (consistent with Stripe, Saferpay and Mollie secrets). [B] Introduce masked input for PayU only.
   - Default: A. Rationale: it matches the existing convention. A change would be cross-provider and out of scope.

## Recommendations
- Run TDD in this order: PayUSignature vectors, the doPayment state matrix (C1), notification parsing (B5), discardTransaction (B3), validity (B2), then hardening (C2).
- Add an overridable base URL (an injectable endpoint resolver or a package-private constructor) for the WireMock integration test that extends `BaseReservationFlowTest` (template `StripeReservationFlowIntegrationTest`).
- Webhook controller: read the raw body via `RequestUtils.readRequest` and the `OpenPayu-Signature` header (with an `X-OpenPayU-Signature` fallback). Return 200 for SUCCESSFUL/NOT_RELEVANT and 500 only for ERROR.
- Frontend: copy `saferpay-payment-proxy`, use `if`/`else` instead of ternaries, and add the `PAYU` entries to every union and details map. In the new admin, follow `docs/admin-style-guide.md`.
- Regenerate `src/test/resources/api/descriptor.json`, run `./gradlew licenseFormat`, then run `./gradlew build -Dpgsql.version=16` before e2e.
- E2E: a new test with `requiredPaymentProxies()` = PAYU, a PAYU case in `AdminConsole.paymentMethodLabel`, the public sandbox credentials only (never CheckIT POS data), and `e2e-test.log` attached per CONTRIBUTING.md.

## Risk Assessment
- **Complexity Risk**: Medium. Each part follows a template, but the payment state machine needs care.
- **Integration Risk**: Medium-High. It depends on an external sandbox, the signature format, OAuth token lifetime, and the 302-with-JSON response (which relies on redirect=NEVER on the shared client).
- **Regression Risk**: Low-Medium. Changes are additive. The `PaymentSpecification` change and the descriptor regeneration are the main shared-surface risks.
