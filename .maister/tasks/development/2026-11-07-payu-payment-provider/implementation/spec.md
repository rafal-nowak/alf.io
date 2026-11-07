# Specification: PayU payment provider (BLIK, fast transfers, cards)

## TL;DR
Finish the PayU REST v2.1 provider drafted in commit 2259c9313. The attendee is redirected to the PayU hosted page and comes back to a new return callback. Signed notifications and a force-check confirm the payment.
`PayUManager` gets a money-safe order lifecycle. A new order is created only after the previous one is confirmed CANCELED or not found, or is confirmed deleted. Once a payable order may exist, the manager never returns `failed`. It also gets a tri-state order read, a shared cancel routine (DELETE) and `validityTime`, plus the M4–M8 hardening. All 6 red-gate tests turn green.
New: `PayUPaymentWebhookController`, `PayUCallbackController`, the public `payu-payment-proxy`, admin labels and panels, EN i18n, an optional `customerIp` on `PaymentSpecification`, a currency hook in `BaseE2ETest`, and unit, WireMock integration and sandbox e2e tests. There is no Flyway migration and no change to `TicketReservationManager`.
The work ships on `feature/payu`, `fix/pl-translations`, `fix/stepper-active-color` and `plo-pl`, all pushed, with no upstream PRs and no AI trailers. This version includes the spec-audit amendments and the post-implementation amendments (see the last two sections).

## Key Decisions
- **Re-confirm with an existing PENDING transaction** (C1, FR2; the full matrix is in §2):
  - COMPLETED and validated → mark the transaction COMPLETE through the same routine as the webhook, then success.
  - PENDING → pending.
  - WAITING_FOR_CONFIRMATION → capture, then pending.
  - NEW → cancel through the shared cancel routine. Create a new order only when the cancel is confirmed; otherwise return pending.
  - A confirmed CANCELED or NOT_FOUND → create a new order.
  - A read ERROR or a COMPLETED mismatch → pending, keeping the transaction.
  - Rule: **never return `failed` once a remote order may still be payable**, because `failed` makes core delete the PENDING transaction.
  - The status *read* has no side effects. The COMPLETED branch updates the transaction and the WAITING branch captures.
- **The order read is tri-state**: `retrieveOrder` returns FOUND, NOT_FOUND (HTTP 404 / PayU "order not found") or ERROR (timeout, I/O, 5xx, 401 after the retry, non-SUCCESS or unparseable body). Only FOUND+CANCELED and NOT_FOUND allow creating a new order.
- **Cancelling is one shared routine** used by the §2 NEW row and by `discardTransaction`. It sends DELETE `/api/v2_1/orders/{id}`. ~~The order counts as cancelled only when the response **body** has `status.statusCode == SUCCESS`, whatever the HTTP code (the red-gate stub answers 302), or when a re-read shows CANCELED.~~ *(Superseded by post-implementation amendment PI1/PI2: after the DELETE the order is always re-read, and only a CANCELED or NOT_FOUND re-read counts as cancelled.)* Anything else counts as not cancelled.
- **A NEW order, by context**: in webhooks (`processWebhook`) it gives NOT_RELEVANT. In `forceTransactionCheck` it gives `error("PayU order not paid yet")`, which core treats as "no action" and turns into `PaymentResult.pending`.
  - Why: the stuck check (`findStuckPaymentsToBeNotified`, TRM L1110–1140) marks a reservation STUCK and emails admins exactly when the force-check returns NOT_RELEVANT. Meanwhile the red-gate test forbids TRANSACTION_INITIATED (and so forbids `pending()`) for NEW.
  - ERROR is the only non-state-changing result that satisfies both.
- **After a valid signature, `parseTransactionPayload` always returns a payload** (B5). Refunds, unknown shapes, a missing `extOrderId` or an `extOrderId` mismatch become a payload that `processWebhook` answers with NOT_RELEVANT (HTTP 200). Only an invalid signature or unparseable JSON returns empty, which gives ERROR and HTTP 500. That 500 is intentional: PayU retries, so a wrong second key that an admin later fixes is self-healing.
- **Webhook for an unknown reservation** (for example one already deleted by cleanup) → HTTP 200, logged at WARN with the orderId when the body parses. This matches core's own "reservation not found" → NOT_RELEVANT and avoids endless retries. Only an empty body → 400.
- **Return callback**:
  - With `error` present, it cancels the pending payment and goes to the overview with a "payment cancelled" toast. The toast also shows when the cancel is not possible but a force-check finds no pending transaction any more (CANCELED arrived first).
  - Without `error`, it runs a force-check: a failure goes to the overview with the toast, and anything else goes to `/book`.
  - A NEW order with no `error` parameter counts as in progress, not abandoned, because PayU appends `error=501` when the buyer abandons. It ends when PayU auto-cancels at `validityTime` (the CANCELED notification or stuck check returns the reservation to PENDING, and then normal expiry applies).
- **Showing the cancellation**: the overview page gets a small generic hook. Query parameter `paymentCancelled=true` makes it show the toast with the new EN key `reservation-page.payment.cancelled`. The frontend has no other channel for this message. This adds one EN key beyond the three listed in FR12.
- **Refund idempotency**: `extRefundId` is deterministic, `<reservationId>-refund-<amountCents>-<n>`, where n = 1 + the number of refunds GET `/orders/{id}/refunds` lists before the POST. If the POST times out or fails with an I/O error, the refunds are re-read. A refund carrying the `extRefundId` just sent counts as success. ~~so a retry by the operator does not double-refund.~~ *(Superseded by post-implementation amendment PI5: the deterministic id protects a same-call retry and concurrent duplicates, not an operator retry after a refund that landed.)*
- **customerIp**: a new nullable field on `PaymentSpecification`, set through a copying wither `withCustomerIp(String)`. Every existing constructor keeps its signature, including the package-private 7-arg one used by the red-gate test. The IP comes from a new helper in `alfio.util.RequestUtils`: first `X-Forwarded-For` entry, else `remoteAddr`, else `127.0.0.1`.
- **Overriding the PayU endpoint for tests**: a hidden Spring property `alfio.payu.endpoint-override` (blank by default) is injected through a second constructor annotated `@Autowired`. The existing public 5-arg constructor stays, because the red-gate test uses it. The override is not exposed in the admin, and a WARN is logged when it is set.
- **Currency gating** (I3): `isActive` returns false when a purchase context is present and its currency is not one PayU supports. Without a purchase context (system-level listing), no gating applies.
- **Labels**: the public label is "PayU: BLIK, fast transfer, card" (EN base) and "PayU: BLIK, szybki przelew, karta" (PL branch). The legacy admin uses the same EN text. The new admin shows the raw proxy name `PAYU`, consistent with `SAFERPAY`, with the `credit-card` icon.
- **Secrets stay plain TEXT inputs** (I6). Mockup 3 shows masked dots, but masking is out of scope.
- **Gson stays** for PayU JSON (the Saferpay/Mollie precedent). It contains no `com.fasterxml.jackson.*`.

## Open Questions / Risks
- **The public sandbox POS 300746 is shared.** E2E depends on its availability and on PayU's sandbox payment page markup. Selectors and the test payment method (test card, or a pay-by-link test bank) must be verified live when the e2e is written. POS 300746 probably accepts only PLN, so the e2e uses the new `BaseE2ETest` currency hook.
- **DELETE on a PENDING order is unverified** (a BLIK or bank transfer in flight). It is reachable through `discardTransaction` from the return `error` path and from the public `DELETE /api/v2/public/reservation/{id}/payment`. Behaviour must be checked on the sandbox during implementation.
  - *(Resolved on the sandbox, see PI4: DELETE on PENDING → CANCELED, no funds after.)* If PayU refuses the DELETE (no SUCCESS body), the shared routine reports "not cancelled". `discardTransaction` then returns false, the reservation stays EXTERNAL_PROCESSING_PAYMENT, and a re-confirm returns pending.
  - If the docs or sandbox show that a cancelled PENDING order can still receive funds, restrict the DELETE to NEW.
- **A NEW order past the reservation validity**: the stuck check sees ERROR (no action, no STUCK). The reservation stays EXTERNAL_PROCESSING_PAYMENT until PayU auto-cancels the order at `validityTime`; the reservation validity is order validity + 5 min. If PayU never cancels, the tickets are held with no admin alert, only a WARN log line per run.
- **WAITING_EXTERNAL_CONFIRMATION gap (inherited core behaviour)**: no scheduler selects that status. A PayU PENDING or WAITING order whose final notification is lost holds the tickets until a force-check (processing page, return callback, or admin) runs.
- **WAITING_FOR_CONFIRMATION in the stuck check**: it is captured and reported as pending (not stuck, not moved because `moveToWaiting=false`). It completes on the next run, once PayU reports COMPLETED.
- **An amount, currency or `extOrderId` mismatch on COMPLETED** gives ERROR in webhooks and pending in `doPayment`. PayU then retries and the reservation is not confirmed. This is intentional (it needs an operator), but money can sit captured until someone refunds it manually.
- **A POST `/orders` timeout after PayU created the order**: `doPayment` returns `failed`. The order is unreachable, because the buyer never got its `redirectUri`, and PayU auto-cancels it at `validityTime`. This is the one accepted case where a remote order exists after `failed`. It is not payable by anyone, so it does not break the "no payable orphan orders" goal.
- **Lock duration**: `performPayment` holds the reservation row lock across up to about 5 sequential PayU calls (OAuth, GET, DELETE, POST, a 401 retry). At 10 s per request the worst case is about 50 s, and it blocks webhooks and force-checks for that reservation. A reverse-proxy timeout above 60 s is recommended. *(Superseded by post-implementation amendment PI6: the bound now also includes the cancel polling budget; recommend a reverse-proxy read timeout ≥ 120 s.)*
- **The shared `HttpClient` has no connect timeout.** Per-request timeouts (FR9) cover the response wait but not TCP connect.
- **`X-Forwarded-For` can be spoofed.** It is acceptable here, because PayU uses `customerIp` only for fraud scoring. `remoteAddr` can be IPv6 (`0:0:0:0:0:0:0:1` locally): check that PayU accepts IPv6, and fall back to `127.0.0.1` if the sandbox rejects it.
- **`descriptor.json` must be regenerated twice**: on `feature/payu`, then again on `plo-pl` after the merges if the merged enums or paths differ.
- **The `plo-pl`, `fix/pl-translations` and `fix/stepper-active-color` branches do not exist yet** (local or origin). The backup ref `backup/feature-payu-wip` exists only locally.

## Goal
Make PayU a production-ready alf.io payment proxy. It must be configurable at system or organization level, offered to attendees as "PayU: BLIK, fast transfer, card", and confirmed reliably by return + force-check and by signed notifications. It must never double-charge, leave payable orphan orders, or cause endless notification retries.

## User Stories
- As an **attendee**, I want to pick PayU on the overview, pay with BLIK, a fast transfer or a card on PayU's page, and land on my confirmation. This lets me buy a ticket with the methods common in Poland.
- As an **attendee who cancels on PayU**, I want to come back to the overview with a clear message and pick a method again, so that I am not stuck on a "payment in progress" page.
- As an **attendee who presses "Pay" twice**, I want the second attempt not to create a second payable order, so that I am never charged twice.
- As an **organization or system admin**, I want a PayU panel in the payment settings (enabled, live mode, POS id, client id and secret, second key), so that I can configure PayU without touching the database.
- As a **reservation operator**, I want PayU reservations labelled "PayU" with a card icon, and I want to refund them in full or in part from the reservation detail page (also when I retry after a timeout), so that I can handle cancellations.

## Core Requirements
1. **FR1, order creation.** OAuth client_credentials. POST `/api/v2_1/orders` with `notifyUrl`, `continueUrl` (the return callback), `customerIp`, `merchantPosId`, `description`, `currencyCode`, `totalAmount` (cents), a unique `extOrderId`, `validityTime`, `buyer` and `products`. Parse the 302 + JSON response. On success, the reservation moves to EXTERNAL_PROCESSING_PAYMENT and the transaction becomes PENDING (paymentId = orderId, metadata `extOrderId`). The reservation validity is aligned to the order validity.
2. **FR2, re-confirm with an existing PENDING transaction**, per the state matrix in Technical Approach §2.
3. **FR3, notification endpoint**: POST `/api/payment/webhook/payu/reservation/{reservationId}`. The contract is in §4.
4. **FR4, status mapping**: COMPLETED is validated, then success. CANCELED → cancelled. WAITING_FOR_CONFIRMATION → capture + pending. PENDING → pending. NEW → not relevant (webhooks) or error/no-op (force-check), per §3.
5. **FR5, remote cancel**: `ServerInitiatedTransaction.discardTransaction` sends DELETE for NEW/PENDING orders through the shared cancel routine. In core it is reached from `cancelPendingPayment`, i.e. from the return `error` path, the public "delete payment attempt" endpoint, and expired-PENDING cleanup. `reTransitionToPending` is DB-only and does **not** call it; the wording in requirements FR5 is corrected here. `initTransaction` is unsupported.
6. **FR6, return callback**: GET `@Controller`. The contract is in §5.
7. **FR7, refunds**: full or partial, with a deterministic, retry-safe `extRefundId`. `getInfo` reports the paid amount and the refunded amount (the sum of FINALIZED refunds).
8. **FR8, currency gating**: PLN, EUR, USD, CZK, GBP, HUF, RON, BGN, DKK, NOK, SEK, CHF.
9. **FR9, per-request HTTP timeouts** of 10 s. The shared HttpClient bean is not changed.
10. **FR10, public frontend**: the `PAYU` model entries, the `payu-payment-proxy` component, its registration, and the overview cancellation toast.
11. **FR11, admin**: the legacy `util.js` label and PayU panels in `system.html` and `organization.html` (including the `#PAYMENT` header `ng-if`). The new admin gets the `PaymentProxy` union entry and the icon case.
12. **FR12, i18n**: EN keys on `feature/payu`. PL keys (PayU, `common.confirm`, `event-days.from`/`to`, missing purchase-path keys) go on `fix/pl-translations`.
13. **FR13, stepper**: the active badge uses `var(--bs-primary)`, on `fix/stepper-active-color`.
14. **FR14, tests**: unit, WireMock integration, regenerated API descriptor, and sandbox e2e (return + force-check, PLN) with the PAYU label in `AdminConsole`.
15. **FR15, Docker**: the build command documented in README on `plo-pl` only.

## Visual Design
Mockups in `analysis/design-context/` are binding inputs. The implementation-planner will attach `Visual References` to the UI task groups. All are ASCII at layout fidelity, and every one reuses existing components; there are no new visual patterns.

| ID | Mockup | Key elements |
|----|--------|--------------|
| `screen:payment-method-selector` | `analysis/design-context/ascii/ui-mockups.md#payment-method-selector` | A PAYU radio rendered by the existing `@for` loop (id `PAYU`, label id `PAYU-label`, `fas credit-card` icon, label `reservation-page.payment-method.payu`). The new `app-payu-payment-proxy` panel sits next to `app-saferpay-payment-proxy`, with an `h4.text-body-secondary` "PayU" and the description key. "Pay <price>" is the existing button. With only one active method, the existing single-method branch shows the h4 with icon and label. |
| `component:stepper-active-badge` | `analysis/design-context/ascii/ui-mockups.md#stepper-active-badge` | `.wizard2 .wizard2-current .badge` background `#007ACC` → `var(--bs-primary)`. The markup is unchanged. |
| `screen:admin-payu-settings` | `analysis/design-context/ascii/ui-mockups.md#admin-payu-settings` | A Bootstrap 3 `panel panel-default`, a `fa credit-card` "PayU" title, and `<setting>` in `ng-repeat` over `paymentPayu.settings`. It goes after the Saferpay panel in `system.html` (~L304) and `organization.html` (~L253), and `paymentPayu` is added to both `#PAYMENT` header `ng-if`s. The secret inputs render as plain text, a deviation from the mockup per I6. |
| `component:admin-payment-icon` | `analysis/design-context/ascii/ui-mockups.md#admin-payment-icon` | `paymentMethodIcon()` maps `PAYU` to `credit-card`. `alfio-payment-method` renders the raw `PAYU`, consistent with `SAFERPAY`. |

The design resources are in `analysis/design-context/design-resources.md`: Bootstrap 5.3 with FA5 free (public), Lit + Shoelace + Bootstrap Icons (new admin, `docs/admin-style-guide.md`), and AngularJS with Bootstrap 3 (legacy admin).

## Reusable Components

### Existing Code to Leverage
| What | Path | How it is used |
|------|------|----------------|
| PayU draft (the base being completed) | `src/main/java/alfio/manager/payment/PayUManager.java` | Keep the structure, the Gson helpers, `buildOrderRequest`, `confirmOrder` and the refund call. `retrieveOrder` becomes tri-state. Fix the lifecycle and add the hardening. |
| Signature check | `src/main/java/alfio/manager/payment/payu/PayUSignature.java` | Reused as is. It only gains tests. |
| Payload and token models | `src/main/java/alfio/model/transaction/webhook/PayUWebhookPayload.java`, `.../token/PayUToken.java` | The payload gains a kind (order / refund / unrecognized) and a null-safe status. `PayUToken` stays the `PaymentToken` for successful results. |
| Configuration keys, proxy, method | `ConfigurationKeys` (L101–106, `PAYMENT_PAYU` L308), `PaymentProxy.PAYU`, `StaticPaymentMethods.PAYU` | Already in the draft. No change apart from optional description wording. |
| Provider contract | `model/transaction/capabilities/{WebhookHandler,PaymentInfo,RefundRequest,ServerInitiatedTransaction}` | PayU additionally implements `ServerInitiatedTransaction` (`discardTransaction`). |
| Reservation lifecycle | `src/main/java/alfio/manager/TicketReservationManager.java`: `performPayment` L609, `cancelPendingPayment` L687, `cleanupExpiredReservations` L1014 (PENDING only), `findStuckPaymentsToBeNotified` L1110–1140 (EXTERNAL_PROCESSING_PAYMENT; NOT_RELEVANT → STUCK), `processTransactionWebhook` L1771, `handlePaymentWebhookResult` L1830, `forceTransactionCheck` L1906 | Used unchanged. They define what each `PaymentWebhookResult` type does. |
| Completion precedent | `SaferpayManager.doPayment` → `internalProcessWebhook` (L128–133) | The provider itself marks the transaction COMPLETE before `successful`. Core does not do it for PAYU (`ReservationFinalizer.registerAlfioTransaction` handles only OFFLINE/CUSTOM_OFFLINE). |
| Re-confirm and validity precedent | `MollieWebhookPaymentManager.tryToReuseExistingTransaction` (L280–298), `initPayment` validity alignment (L351) | Mollie checks the existing remote payment before creating a new one, and **reuses** an open payment by redirecting to its checkout link. PayU deliberately differs for NEW (owner decision C1): it cancels and recreates, because the draft does not store `redirectUri` and the reservation may have changed. The validity alignment is reused as is. |
| Caffeine cache precedent | `MollieWebhookPaymentManager` L112–114 | Token cache (M5). |
| Webhook controller template | `src/main/java/alfio/controller/payment/api/mollie/MolliePaymentWebhookController.java` | Same shape. The unknown-reservation answer differs (200, see §4). CSRF is already skipped for `/api/payment/webhook/**` (`AbstractFormBasedWebSecurity` L338–339). |
| Return controller template | `src/main/java/alfio/controller/payment/SaferpayCallbackController.java` (+ `PaymentPageInitializeRequestBuilder.CANCEL_URL_TEMPLATE`) | Same `@Controller` and `redirect:` style, with `purchaseContextManager.findBy` and `forceTransactionCheck`. |
| Raw body reader | `alfio.util.RequestUtils.readRequest` | Webhook body. The same class receives the client-IP helper. |
| Overview controller | `src/main/java/alfio/controller/api/v2/user/ReservationApiV2Controller.confirmOverview` (L311–392) | Already receives `HttpServletRequest`. It sets `customerIp` on the spec. |
| Result types | `manager/support/PaymentResult` (`pending`, `redirect`, `failed`), `PaymentWebhookResult` (`notRelevant`, `pending`, `cancelled`, `error`, `successful`) | No new result types. |
| Public proxy template | `frontend/public/src/app/payment/saferpay-payment-proxy/` (provider + component + html) | Copied as `payu-payment-proxy` (`paymentMethodDeferred=false`). |
| Public model, selector, module | `frontend/public/src/app/model/event.ts` (L123–191), `reservation/payment-method-selector/payment-method-selector.component.html` (L73–77), `app.module.ts` (L89, L177; `faCreditCard` already registered L257) | `PAYU` entries, the component tag, and the declaration. |
| Overview, toast, double-click guard | `reservation/overview/overview.component.ts`, `.html` (the Pay button row is hidden while `submitting`, L118), `FeedbackService.showError` | Cancellation toast on `paymentCancelled=true`. A double submit is already prevented in the UI, which was checked. |
| Processing page, guard | `reservation/processing-payment/*`, `reservation/reservation.guard.ts` | Unchanged: after `/book` they route to success or processing. |
| Legacy admin | `src/main/webapp/alfio-admin-v1/js/admin/filter/util.js` (`PAYMENT_PROXY_DESCRIPTIONS`), `src/main/webapp/alfio-admin-v1/angular-templates/admin/partials/configuration/system.html` (L203, L295–304) and `.../configuration/organization.html` (L135, L244–253), the `<setting>` directive; `configuration.js` camel-cases to `paymentPayu` automatically | Label and panels by copying Saferpay. |
| New admin | `frontend/admin/src/model/event.ts` L61, `frontend/admin/src/service/reservation-format.ts` L25–36, `frontend/admin/src/components/payment-method.ts` | Union entry and icon case only. |
| Unit test templates | `src/test/java/alfio/manager/payment/SaferpayManagerTest.java`, `PayUManagerLifecycleTest.java`, `testSupport/MaybeConfigurationBuilder`, `test/util/TestUtil.clockProvider()` | Mocked `HttpClient` with request inspection. |
| Integration template | `controller/api/v2/user/reservation/StripeReservationFlowIntegrationTest.java` + `BaseReservationFlowTest.testBasicFlow` / `performAndValidatePayment`; `IntegrationTestUtil.initEvent` (CHF event, `acceptedPaymentProxies` overload); WireMock 3.13.2 (`SimpleHttpClientIntegrationTest` precedent) | PayU flow test. |
| API stability | `src/test/java/alfio/controller/api/CheckRestApiStability.java` (`updateDescriptor` flag L74), `src/test/resources/api/descriptor.json` | Regenerate the descriptor. |
| E2E harness | `src/test/java/alfio/e2e/{BaseE2ETest,ReservationFlow,AdminConsole,CreditCardPaymentE2ETest}.java` | `requiredPaymentProxies()`, `waitForPaymentConfirmation()` (force-check), the `paymentMethodLabel` switch, and a new `currency()` hook. |

### New Components Required
| Component | Why existing code cannot be reused |
|-----------|------------------------------------|
| `src/main/java/alfio/controller/payment/api/payu/PayUPaymentWebhookController.java` | `notifyUrl` points at a path with no handler today (B4). Each provider has its own controller, because the signature header and body handling are provider-specific. |
| `src/main/java/alfio/controller/payment/PayUCallbackController.java` | PayU needs a GET return handler for `error=501` and abandonment (M1). The Saferpay callback is bound to its own path and does not cancel. |
| `frontend/public/src/app/payment/payu-payment-proxy/payu-payment-provider.ts`, `payu-payment-proxy.component.ts`, `.component.html` | Each proxy matches its own `proxy` value and shows its own description. The Saferpay component hard-codes `'SAFERPAY'`. |
| A client-IP helper method in `alfio.util.RequestUtils` (no new class) | No helper in `src/main/java` reads `X-Forwarded-For` or `remoteAddr` today. |
| `withCustomerIp` wither + field on `PaymentSpecification` (no new class) | There is no IP field. Using a wither avoids changing 14 constructor call sites. |

## Technical Approach

### 1. PayUManager (modify `src/main/java/alfio/manager/payment/PayUManager.java`)
- **Capabilities**: `PaymentProvider, WebhookHandler, PaymentInfo, RefundRequest, ServerInitiatedTransaction`.
- **Constructors**: keep the public 5-arg constructor. It delegates with a null override. Add an `@Autowired` constructor with the extra `@Value("${alfio.payu.endpoint-override:}")` string. When that string is non-blank, it replaces ~~both SANDBOX_ENDPOINT and LIVE_ENDPOINT~~ SANDBOX_ENDPOINT only *(superseded by post-implementation amendment PI7: the override is ignored in live mode)*.
- **Configuration access**: everything, including `RESERVATION_TIMEOUT`, is read from the `ALL_KEYS` map returned by `configurationManager.getFor(Set, level)`. Do not use the single-key `getFor(key, level)` overload in the doPayment, checkOrderStatus or parse paths. The red-gate mock stubs only the `Set` overload, so the single-key overload returns null there.
- **Constants**: `RETURN_URL_TEMPLATE` becomes `/{purchaseContextType}/{purchaseContextIdentifier}/reservation/{reservationId}/payment/payu/return`, shared by the callback controller. `WEBHOOK_URL_TEMPLATE` is unchanged. Add `ADDITIONAL_INFO_RESERVATION_ID = "reservationId"`. Add a supported-currencies set (FR8) and a PayU buyer-language set (pl, en, cs, sk, de, es, fr, it, hu, ro, bg, uk, lt, lv, et, hr, sl, el, pt, ru, tr), to be verified against the PayU docs. When the language is not in the set, `buyer.language` is omitted.
- **`isActive`**: as today, plus the currency gating when `paymentContext.getPurchaseContext()` is non-null.
- **`retrieveOrder` (tri-state)**: returns an internal result with outcome FOUND (with the order JSON), NOT_FOUND or ERROR, as a private record/enum pair with no Lombok.
  - FOUND: HTTP success, body `status.statusCode == SUCCESS`, and a non-empty `orders` array.
  - NOT_FOUND: HTTP 404, or a body whose status code is PayU's "not found" code (`DATA_NOT_FOUND`, to be verified on the sandbox).
  - ERROR: everything else (timeout, I/O, interruption, 5xx, 401 after the single retry, a non-SUCCESS or unparseable body, an empty `orders` array).
- **Shared cancel routine `cancelOrder(configuration, orderId)`**: sends DELETE `/api/v2_1/orders/{id}` and returns CANCELLED or NOT_CANCELLED.
  - ~~CANCELLED: the response body has `status.statusCode == SUCCESS`, judged on the body whatever the HTTP code. Also CANCELLED when, after a non-SUCCESS answer, a re-read is FOUND with CANCELED, or is NOT_FOUND.~~ *(Superseded by post-implementation amendments PI1/PI2.)* CANCELLED: after the DELETE (any response, including a timeout or I/O error) the order is always re-read; CANCELLED only when the re-read is FOUND+CANCELED or NOT_FOUND. A COMPLETED re-read → ERROR log, NOT_CANCELLED.
  - NOT_CANCELLED: everything else, logged at WARN.
  - It is used by the §2 NEW row and by `discardTransaction`.
- **`doPayment`**:
  1. Load the existing PENDING PAYU transaction (`loadOptionalByStatusAndPaymentProxyForUpdate`, as today).
  2. If one exists, read the order with `retrieveOrder` (no DB side effects) and apply the matrix in §2.
  3. Otherwise, or when the matrix says "create", create the order:
     - `extOrderId` = `<reservationId>-<8 random hex chars>`. It starts with the reservationId and does not collide under a fixed clock (L9).
     - `validityTime` = max(remaining reservation seconds, 300). When the reservation or its validity is unavailable, use `RESERVATION_TIMEOUT` minutes (default 25). This is required because the red-gate tests return a null reservation.
     - After success: update the reservation status, call `ticketReservationRepository.updateValidity` (now + validityTime + 5 min slack) and `invalidateExistingTransactions`, insert the PENDING transaction with metadata `extOrderId`, and return `PaymentResult.redirect(redirectUri)`.
  4. Use clock-based timestamps everywhere (`clockProvider`, not `new Date()`) (L1).
  5. `failed("error.STEP2_PAYU_unexpected")` is returned only when no previous order may still be payable: there was no existing transaction, or the previous one was confirmed CANCELED, NOT_FOUND or cancelled. It is also returned when the creation itself fails (see the POST-timeout risk).
- **Completion routine `completeTransaction(transaction, order)`**: shared by §2 and §3.
  1. Validate that `totalAmount` == `transaction.getPriceInCents()`, `currencyCode` == `transaction.getCurrency()`, and `extOrderId` == metadata `extOrderId` (when both are present).
  2. On a match, `transactionRepository.update(..., COMPLETE, metadata)` and return a match result carrying `PayUToken(orderId)`.
  3. On a mismatch, log at ERROR (orderId, reservationId, expected and actual values), change nothing, and return a mismatch result.
- **`buildOrderRequest`**: `continueUrl` uses the new return template. `customerIp` comes from `spec.getCustomerIp()`, falling back to `127.0.0.1`. `buyer.firstName`/`lastName` are added only when they are non-blank (L4). `description` and product name are abbreviated as today.
- **`parseTransactionPayload`**:
  1. Verify the signature. If it is invalid, return empty.
  2. Parse the JSON. If it is unparseable, return empty.
  3. If an `order` object is present, take `orderId` and `status`, both null-safe. A present `extOrderId` that does not start with the reservationId gives an **unrecognized** payload (logged at WARN).
  4. If a top-level `refund` object is present, give a **refund** payload, taking `orderId` from the top level.
  5. Anything else gives an **unrecognized** payload.
  The reservationId always comes from `additionalInfo`.
- **`PayUWebhookPayload`**: add a kind (ORDER / REFUND / UNRECOGNIZED). `getType()` returns `payu-<status>`, `payu-refund` or `payu-unrecognized`. `getStatus()` is null-safe (L5). `getOrderId()` may be null.
- **`processWebhook`**: when the kind is not ORDER, or the orderId is null or differs from `transaction.getPaymentId()`, return `notRelevant`. Otherwise return `checkOrderStatus` with the webhook NEW mapping (NOT_RELEVANT).
- **`forceTransactionCheck`**: delegates to `checkOrderStatus` with the force-check NEW mapping (`error("PayU order not paid yet")`). It must not dereference the `reservation` argument, because the red-gate test passes null.
- **`checkOrderStatus`**: the mapping table in §3. The draft's NEW → `processStarted` is removed.
- **`discardTransaction`**: read the order with `retrieveOrder`.

  | Order state | Action | Returns |
  |---|---|---|
  | FOUND + NEW or PENDING | the shared cancel routine (DELETE on PENDING to be verified on the sandbox, see Risks) | true when CANCELLED, else false |
  | FOUND + CANCELED, or NOT_FOUND | none | true |
  | FOUND + COMPLETED or WAITING_FOR_CONFIRMATION, or ERROR | none, WARN log | false, so a paid, authorized or unknown-state order is never treated as discarded |

- **`initTransaction`**: returns `errorToken("PayU does not support server-initiated transactions", true)` with no remote call. `reservationStatusChanged=true` makes core reconcile through the standard force-check, instead of calling `transitionToExternalProcessingPayment` (TRM L2003–2008).
- **`errorToken(message, statusChanged)`**: returns a minimal private static nested class implementing `TransactionInitializationToken`, written by hand with no Lombok builders. `PayUToken` implements `PaymentToken`, not `TransactionInitializationToken`, so it is not reused.
- **Token cache (M5)**: a Caffeine `Cache<String, AccessToken>` keyed by endpoint + clientId + SHA-256 hex of the secret.
  - An entry is used only while `expiresAt` is after now (renewed 60 s early).
  - A missing `expires_in` defaults to 300 s, with no NPE.
  - Any API call answered with HTTP 401 invalidates the key and is retried once with a fresh token.
- **Timeouts (M6)**: every `HttpRequest` built by PayUManager, including OAuth, sets `.timeout(Duration.ofSeconds(10))`. A timeout is an ERROR outcome, and each caller handles it per its rules (§2: pending; §3: error; refund: re-read).
- **Refund (M8, idempotent)**:
  1. `amountCents` = the requested amount, or `transaction.getPriceInCents()` for a full refund.
  2. GET `/orders/{id}/refunds` gives the existing count k. `extRefundId` = `<reservationId>-refund-<amountCents>-<k+1>`.
  3. POST the refund with `extRefundId`, and `amount` when partial. Success means `status.statusCode == SUCCESS`.
  4. On a timeout or I/O error, re-read the refunds and return true when one has that `extRefundId`, false otherwise.
  5. If the initial GET fails, return false without sending the POST.
- **`getInfo`**: paidAmount comes from the order's `totalAmount` and `currencyCode`. refundedAmount comes from GET `/api/v2_1/orders/{id}/refunds`: the sum of FINALIZED refund amounts, formatted with `MonetaryUtil.formatCents`, or null when there are none. fee and platformFee are null.
- **Code rules**: no ternaries (also do not copy the ternary in `PaymentManagerUtils`), Lombok limited to the allowed annotations, GPL header on every new file, no Jackson 2 imports.

### 2. Re-confirm state matrix (`doPayment` with an existing PENDING transaction)
| `retrieveOrder` outcome / PayU status | Remote action | Result | Transaction | Test |
|---|---|---|---|---|
| FOUND + COMPLETED, validation matches | none | `PaymentResult.successful(orderId)` | updated to COMPLETE (completion routine) | group 3 |
| FOUND + COMPLETED, mismatch | none (ERROR log) | `PaymentResult.pending(orderId)` | kept PENDING | group 3 |
| FOUND + PENDING | none | `PaymentResult.pending(orderId)`: no new order, no redirect | kept | red gate …WhileFirstIsBeingPaid |
| FOUND + WAITING_FOR_CONFIRMATION | PUT `/orders/{id}/status` COMPLETED (capture) | `PaymentResult.pending(orderId)`: no new order, no redirect | kept | red gate …WhileFirstAwaitsConfirmation |
| FOUND + NEW, cancel CANCELLED | DELETE through the shared cancel routine, then create a new order | redirect to the new order | the old one invalidated by `invalidateExistingTransactions` | red gate …CancelsStillNewOrder… (stub: 302 + SUCCESS body) |
| FOUND + NEW, cancel NOT_CANCELLED | DELETE attempted | `PaymentResult.pending(orderId)` (WARN) | kept | group 3 |
| FOUND + CANCELED | none | create a new order and redirect | the old one invalidated | group 3 |
| NOT_FOUND | none | create a new order and redirect | the old one invalidated | group 3 |
| FOUND + unknown status | none | `PaymentResult.pending(orderId)` | kept | group 3 |
| ERROR (timeout, 5xx, 401 after retry, bad body) | none | `PaymentResult.pending(orderId)` | kept | group 3 |

- **How `pending` surfaces**: `ReservationApiV2Controller.confirmOverview` turns `pending` into a 422 with the generic processing error message (core does nothing else). The attendee normally never reaches this point: the guard routes EXTERNAL_PROCESSING_PAYMENT to the processing page, and the overview hides the Pay button while submitting.
- **Why `failed` is never used once a previous order may be payable**: `performPayment` then calls `reTransitionToPending`, which deletes the PENDING transaction and loses the record of the order.

### 3. Status mapping (`checkOrderStatus`, used by the webhook and the force-check)
| PayU status | Validation / side effect | Webhook result (`processWebhook`) | Force-check result (`forceTransactionCheck`, also the stuck check) | Effect in TicketReservationManager |
|---|---|---|---|---|
| COMPLETED | completion routine (match → transaction COMPLETE) | `successful(PayUToken)`, or `error("...mismatch")` | same | complete the reservation / nothing (webhook → 500, PayU retries) |
| CANCELED | invalidate the transaction; extend the reservation validity by `RESERVATION_TIMEOUT` (clock-based) | `cancelled()` | same | back to PENDING; force-check → `failed` |
| WAITING_FOR_CONFIRMATION | capture (PUT status COMPLETED) | `pending()` | same | EXTERNAL_PROCESSING_PAYMENT → WAITING_EXTERNAL_CONFIRMATION (not moved in the stuck check) |
| PENDING | none | `pending()` | same | as above |
| NEW | none | `notRelevant("order not paid yet")` | `error("PayU order not paid yet")` (red gate newOrderIsNotReportedAsPaymentInProgress; avoids STUCK) | none; force-check → `pending` |
| other / unknown | none | `pending()` | same | as above |
| `retrieveOrder` NOT_FOUND or ERROR | none | `error(...)` | `error(...)` | none; force-check → `pending`; webhook → 500 |

### 4. Notification endpoint contract (new `PayUPaymentWebhookController`, `@RestController`, Mollie template)
- **Route**: POST `PayUManager.WEBHOOK_URL_TEMPLATE` = `/api/payment/webhook/payu/reservation/{reservationId}`. CSRF is already exempt.
- **Input**: raw body via `RequestUtils.readRequest`. Signature from header `OpenPayu-Signature`, falling back to `X-OpenPayU-Signature`.
- **Flow**: `purchaseContextManager.findByReservationId`, then `ticketReservationManager.processTransactionWebhook(body, signature, PaymentProxy.PAYU, Map.of("reservationId", id), new PaymentContext(purchaseContext, reservationId))`.
- **Responses**:

| Outcome | HTTP |
|---|---|
| successful | 200 `OK` |
| error (invalid signature, unparseable JSON, provider not found, order unreadable or mismatched) | 500 with the reason, so PayU retries |
| anything else (NOT_RELEVANT, TRANSACTION_INITIATED, CANCELLED, FAILED) | 200 with the reason |
| unknown reservation (purchase context not found) | 200 `NOT_RELEVANT`, logged at WARN with the reservationId and, if the body parses, the orderId; no signature check is possible without the configuration level |
| empty or unreadable body | 400 `NOK` |

### 5. Return callback contract (new `PayUCallbackController`, `@Controller`, Saferpay template)
- **Route**: GET `PayUManager.RETURN_URL_TEMPLATE`, with optional `error` and `errorCode` parameters.

| Condition | Action | Redirect |
|---|---|---|
| purchase context not found | – | `redirect:/` |
| reservation not found | – | `redirect:/{type}/{identifier}` |
| `error` present, `cancelPendingPayment(rid, purchaseContext)` true | `discardTransaction` (DELETE), then back to PENDING | `/{type}/{identifier}/reservation/{rid}/overview?paymentCancelled=true` |
| `error` present, cancel false | `forceTransactionCheck` | empty (no pending transaction any more, e.g. the CANCELED notification arrived first) or `failed` → overview?paymentCancelled=true. Otherwise → `/book` |
| no `error` | `forceTransactionCheck` | `failed` → overview?paymentCancelled=true. Empty, successful or pending → `/book` (the Angular guard routes to success, processing-payment or the overview) |

- **Path building**: paths are built with `UriComponentsBuilder` from the templates, never by string concatenation of user input.

### 6. customerIp data flow (M4)
`ReservationApiV2Controller.confirmOverview` builds the spec as today, then calls `.withCustomerIp(RequestUtils.<clientIp>(request))`. `PaymentSpecification` gains a nullable `customerIp` field (via `@Getter`). The existing 18/8/7-arg constructors set it to null through a private all-fields constructor, and the wither copies every field. The other 13 call sites are untouched, so PayU falls back to `127.0.0.1` for admin and webhook-built specs, which never create orders.

### 7. Public frontend
- **`model/event.ts`**: add `'PAYU'` to `StaticPaymentMethodNames` and to `PaymentProxy`. Add `staticPaymentMethodDetails.PAYU = {labelKey 'reservation-page.payment-method.payu', icon ['fas','credit-card']}`. This also fixes the "UNKNOWN PAYMENT PAYU" fallback and the custom-offline branch in `overview.component.ts confirm()`.
- **The new `payu-payment-proxy` folder**: a copy of Saferpay. `matchProxyAndMethod` is true when proxy is `'PAYU'`. The title is "PayU" and the description key is `reservation-page.payment.payu.description`.
- **Registration**: `app.module.ts` gets the import and declaration. `payment-method-selector.component.html` gets the tag after `app-saferpay-payment-proxy`.
- **`overview.component.ts`**: in `ngOnInit`, when the query parameter `paymentCancelled` equals `'true'`, call `feedbackService.showError('reservation-page.payment.cancelled')`. Only this literal value triggers it; no key is taken from the URL. Use `if`/`else` only, with no ternaries.

### 8. Admin
- **Legacy**: in `src/main/webapp/alfio-admin-v1/js/admin/filter/util.js`, `'PAYU': 'PayU: BLIK, fast transfer, card'`. `src/main/webapp/alfio-admin-v1/angular-templates/admin/partials/configuration/system.html` and `organization.html` get the `#PAYMENT` `ng-if` plus `paymentPayu` and a PayU panel copied from Saferpay; the organization variant uses `organizationConf.delete(config)`.
- **New admin**: `frontend/admin/src/model/event.ts` `PaymentProxy` gets `'PAYU'`. `frontend/admin/src/service/reservation-format.ts` adds `case 'PAYU':` to the `credit-card` group. Review against `docs/admin-style-guide.md` (no ternaries, reuse).

### 9. i18n
- **EN base, `public.properties` (feature/payu)**, ASCII `key=value` with doubled apostrophes:
  - `reservation-page.payment-method.payu=PayU: BLIK, fast transfer, card`
  - `reservation-page.payment.payu.description=You will be redirected to PayU''s secure checkout to complete the purchase.`
  - `error.STEP2_PAYU_unexpected=An unexpected error occurred while contacting PayU. Please try again or contact the event''s organizers.`
  - `reservation-page.payment.cancelled=The payment was not completed. You can choose a payment method and try again.`
- **PL, `public_pl.properties` (fix/pl-translations)**, UTF-8, sorted, `key = value`. It holds the four keys above in Polish (label "PayU: BLIK, szybki przelew, karta"), `common.confirm`, `event-days.from`/`to`, and every key missing from the purchase path. The purchase path is booking → overview → payment → processing → success, plus `subscription.detail.validity.CUSTOM.from`/`to`. It is determined by diffing keys against `public.properties`. Placing PayU keys there before `feature/payu` is merged is harmless.

### 10. Stepper (fix/stepper-active-color)
- `frontend/public/src/app/stepper/stepper.component.scss` L44–46: `#007ACC` → `var(--bs-primary)`. One commit.

### 11. Docker (plo-pl)
- **README.md**: a section "Building the Docker image (plo-pl fork)". It states that the command is `./gradlew distribution -x test && docker build -t alfio-plo-pl:payu-<date> ./build/dockerize` and mentions the Docker/Podman requirement.

## Implementation Guidance

### Testing Approach
- 2-8 focused tests per implementation step group. Verification of a group runs only that group's new tests (e.g. `./gradlew test --tests 'alfio.manager.payment.PayU*'`), not the entire suite. A full `./gradlew build -Dpgsql.version=16` runs once before the e2e and before pushing.
- **Red gate** (`src/test/java/alfio/manager/payment/PayUManagerLifecycleTest.java`): the file is untracked today. `git add` it in commit 1, together with the lifecycle fix. All 7 must pass. The 6 that turn green:

| Test | Fixed by |
|---|---|
| doPaymentMustNotCreateSecondOrderWhileFirstAwaitsConfirmation | §2 WAITING row |
| doPaymentMustNotCreateSecondOrderWhileFirstIsBeingPaid | §2 PENDING row |
| doPaymentCancelsStillNewOrderBeforeCreatingAnotherOne | §2 NEW row (DELETE; the stub's 302 + SUCCESS body counts as cancelled) |
| newOrderIsNotReportedAsPaymentInProgress | §3 NEW → force-check `error` |
| signedRefundNotificationIsAcknowledged | §1 parseTransactionPayload refund payload |
| signedNotificationWithoutExtOrderIdDoesNotThrow | §1 null-safe parsing |

- **Planned test groups** (each group stays within 2–8 tests):

  1. **`PayUSignatureTest`** (5): MD5 and SHA-256 vectors computed independently and recorded in the test, a case-insensitive hex digest, a wrong key, a malformed or missing header.
  2. **`PayUManagerTest`, order building** (7): notifyUrl and continueUrl paths, customerIp from the spec and its fallback, validityTime from the remaining validity and its fallback, buyer names omitted when blank, a language outside the set omitted, the `extOrderId` prefix, and updateValidity called.
  3. **`PayUManagerTest`, doPayment re-confirm matrix** (7):
     - COMPLETED match → transaction updated to COMPLETE + `successful`.
     - COMPLETED mismatch → `pending`, no update, no POST.
     - CANCELED → new order created.
     - NOT_FOUND (404) → new order created.
     - Read ERROR (5xx or timeout) → `pending`, no POST, transaction not invalidated.
     - DELETE answered without a SUCCESS body → `pending`, no POST.
     - Unknown status → `pending`.
  4. **`PayUManagerTest`, status mapping** (6): COMPLETED match → successful; mismatch → error with no update; CANCELED → cancelled with the transaction invalidated; WAITING → capture PUT sent, then pending; NEW → `processWebhook` NOT_RELEVANT and `forceTransactionCheck` ERROR; unknown → pending.
  5. **`PayUManagerTest`, notification parsing** (5): invalid signature → empty; extOrderId mismatch → UNRECOGNIZED → `processWebhook` NOT_RELEVANT; refund payload → `processWebhook` NOT_RELEVANT; a different orderId → NOT_RELEVANT; a missing status is null-safe.
  6. **`PayUManagerTest`, hardening** (7): token cached across two calls (one OAuth request), a different secret → new token, 401 → invalidate and retry once, missing `expires_in` with no NPE, a 10 s timeout on every request, the endpoint override applied, currency gating (PLN active, a non-supported currency inactive).
  7. **`PayUManagerTest`, discard, refund and info** (8):
     - discard NEW → DELETE + true; PENDING → DELETE + true; COMPLETED/WAITING → false with no DELETE; CANCELED or NOT_FOUND → true; DELETE refused → false.
     - Refund `extRefundId` is deterministic (`-refund-<amount>-<k+1>`); a refund timeout followed by a re-read that finds the id → true.
     - getInfo refunded = sum of FINALIZED refunds.
  8. **`PayUPaymentWebhookControllerTest`** (Mockito, 6): successful → 200; error → 500; NOT_RELEVANT → 200; unknown reservation → 200 (WARN); empty body → 400; `X-OpenPayU-Signature` fallback.
  9. **`PayUCallbackControllerTest`** (Mockito, 6): `error` + cancel true → overview?paymentCancelled; `error` + cancel false + force-check empty → overview?paymentCancelled; no error + success → `/book`; no error + failed → overview; no error + pending → `/book`; unknown context → `/`.
  10. **Utilities** (4): `RequestUtils` client IP (XFF first entry, remoteAddr, fallback); `withCustomerIp` copies every field.
  11. **`PayUReservationFlowIntegrationTest`** (`@AlfioIntegrationTest`, extends `BaseReservationFlowTest`, Stripe template, 3 tests). PayU is stubbed by WireMock with a dynamic port via `@DynamicPropertySource` `alfio.payu.endpoint-override`. The PAYU_* config is inserted at system level, and the CHF event accepts PAYU.
      - **Basic flow**: confirmOverview → redirect URL, EXTERNAL_PROCESSING_PAYMENT, then a signed COMPLETED notification sent to `PayUPaymentWebhookController` → 200, COMPLETE, then the transaction is COMPLETE and the order summary is checked.
      - **Return callback with `error=501`**: DELETE received by WireMock, reservation back to PENDING.
      - **Signed refund notification** after COMPLETE → 200.
      - **Afterwards**: regenerate the descriptor. Temporarily set the hard-coded `final` field `updateDescriptor = true` in `CheckRestApiStability.java` L74, run `./gradlew test --tests 'alfio.controller.api.CheckRestApiStability'`, revert the flag to `false`, rerun to confirm green, and commit only `descriptor.json`. `TicketReservationManagerTest` and `PaymentMethodDeserializationTest` still pass.
  12. **E2E `src/test/java/alfio/e2e/PayUPaymentE2ETest.java`** (2–3): `requiredPaymentProxies()` = PAYU. It runs against a local `./gradlew bootRun` on a dedicated DB, never the :8090 container, with POS 300746 and the public sandbox credentials.
      - **Currency**: add a `protected String currency()` hook in `BaseE2ETest` (default `"CHF"`), used by the private `eventDefinition()`. `PayUPaymentE2ETest` overrides it with `"PLN"`.
      - **`AdminConsole.paymentMethodLabel`** gets `case PAYU -> "PayU: BLIK, fast transfer, card"`.
      - **`ReservationFlow`** gets a PayU step: select `PAYU`, accept and submit, pay on the PayU sandbox page, return, then `waitForPaymentConfirmation()` (force-check) → confirmation.
      - **Proof**: `./gradlew e2eTest --tests 'alfio.e2e.PayUPaymentE2ETest' -Pverbose --console=plain 2>&1 | tee e2e-test.log` is the e2e proof, and the log is checked for credentials.

### Standards Compliance
There are no `.maister/docs/standards`. The applicable rules are:
- **`AGENTS.md`**:
  - No ternary operator (Java, TS, JS, including tests).
  - Lombok only `@Getter/@Setter/@AllArgsConstructor/@Data/@Delegate`.
  - GPL header on every new `.java` (`./gradlew licenseFormat`).
  - No `com.fasterxml.jackson.*` (forbiddenApis). Gson is allowed by precedent.
  - EN keys only in base files on PR-able branches.
  - New admin screens go in `frontend/admin` only (no new legacy screens: the PayU panel extends an existing legacy screen, which is acceptable).
  - npjt repositories untouched.
- **`CONTRIBUTING.md`**:
  - At least one integration test for the backend and an E2E test for the frontend.
  - `requiredPaymentProxies()` declares only PAYU.
  - `e2e-test.log` with no credentials.
  - No `Co-Authored-By` / AI trailers in commits.
- **`docs/admin-style-guide.md`**: reuse `alfio-payment-method`, no ternaries, Bootstrap Icons names.

## Branch / Commit Plan
Interactive rebase is unavailable, so history is reshaped with `git reset --soft` / `git commit --amend`. Push `backup/feature-payu-wip` to origin before the force-push, because it exists only locally today. Pushes use `--force-with-lease`. No commit carries an AI trailer, and no PRs are opened.

| Branch (base) | Commits (suggested, meaningful) |
|---|---|
| `feature/payu` (main) | 1. "Add PayU payment provider backend": reworded WIP + lifecycle fixes + `git add` of the untracked red-gate test. 2. "Harden PayU token cache, timeouts, validation and refunds". 3. "Pass the customer IP to payment providers". 4. "Add PayU notification and return endpoints". 5. "Add PayU option to the public frontend". 6. "Show PayU in the admin consoles". 7. "Add English PayU translations". 8. "Add PayU reservation flow integration test and update API descriptor" (the flag reverted). 9. "Add PayU sandbox E2E test" (including the `BaseE2ETest` currency hook) |
| `fix/mollie-ignore-unsupported-methods` | unchanged |
| `fix/pl-translations` (main) | "Add missing Polish translations for the purchase path and PayU" (fork-only) |
| `fix/stepper-active-color` (main) | "Use the theme primary colour for the active stepper badge" (upstream PR candidate, not opened) |
| `plo-pl` (new, from main) | `git merge --no-edit` of all four branches, regenerate `descriptor.json` if the merge changed enums or paths (same flag procedure), "Document Docker image build" (README), commit `.maister/` |

## Out of Scope
- A PayU logo asset, masked secret inputs, and a PayU settings page in the new admin.
- A notification tunnel for e2e. Notifications are covered by the integration test only.
- Full PL translation of all 328 missing keys (purchase-path keys only).
- Upstream PRs.
- Any change to `TicketReservationManager`, `PaymentManager`, the shared `HttpClient` bean, or the Flyway scripts. This includes the WAITING_EXTERNAL_CONFIRMATION scheduler gap and the stuck-check semantics.
- PayU card tokenization or recurring payments (`PayUToken` is not used by the frontend).
- Reusing an open PayU order by redirecting to a stored `redirectUri` (Mollie style). C1 deletes and recreates instead.

## Success Criteria
- `PayUManagerLifecycleTest`: 7/7 pass (the 6 former failures turn green).
- `./gradlew build -Dpgsql.version=16` is green, including `CheckRestApiStability`, `licenseFormat`/license check and forbiddenApis, on `feature/payu` and on `plo-pl`.
- The WireMock integration test confirms a reservation through a signed notification, with the transaction COMPLETE. A cancelled return leaves the reservation PENDING with a DELETE sent.
- The sandbox e2e completes a PLN purchase via return + force-check, and `e2e-test.log` is produced.
- One remote order per payment attempt:
  - no second POST `/orders` while an order is PENDING, WAITING or of unknown state (read error);
  - no second POST after a NEW-order DELETE that is not confirmed.
- `doPayment` never returns `failed` while a previous order may still be payable.
- A NEW order never makes the stuck check mark a reservation STUCK.
- Refund, unknown and unknown-reservation notifications answer 200, and only ERROR answers 500. A refund retried after a timeout is not duplicated.
- The PayU option shows the correct label, icon and description. The admin panels render in system and organization settings, and the new admin shows the card icon for PAYU.
- All five branches and the backup ref are pushed to origin. `git log` on every branch shows no AI trailers.

## Audit amendments
Applied from `verification/spec-audit.md` (verdict pass-with-concerns; owner mode autonomous, recommended fixes applied).

| ID | Change |
|---|---|
| H1 | `retrieveOrder` is tri-state (FOUND / NOT_FOUND / ERROR). Re-confirm creates a new order only after FOUND+CANCELED or NOT_FOUND. ERROR → `pending(orderId)`, keeping the transaction. §1, §2, test group 3. |
| H2 | A shared completion routine marks the transaction COMPLETE on COMPLETED in both §2 and §3 (the Saferpay precedent; core does not do it for PAYU). A mismatch → ERROR log, then `pending` in doPayment and ERROR in webhooks. There is a rule never to return `failed` while a previous order may be payable. The "without side effects" Key Decision is reworded. |
| H3 | A shared cancel routine (DELETE). Success is judged on the body `status.statusCode == SUCCESS` (any HTTP code), or on a re-read showing CANCELED or NOT_FOUND *(the SUCCESS-body criterion is superseded by PI1)*. NEW → a new order only when the cancel is confirmed, otherwise `pending`. `discardTransaction` uses the same routine. |
| M1 | Expiry analysis corrected: PayU reservations go through the stuck check (`findStuckPaymentsToBeNotified`, TRM L1110–1140), not `cleanupExpiredReservations` (PENDING only). Checked in the code: NOT_RELEVANT marks STUCK, and the red gate forbids TRANSACTION_INITIATED. NEW therefore maps to NOT_RELEVANT in webhooks and to `error` (no-op, no STUCK) in `forceTransactionCheck`. The WAITING_EXTERNAL_CONFIRMATION gap is recorded as a risk. The incorrect "captured payment deleted by cleanup" risk is removed. |
| M2 | A `protected String currency()` hook in `BaseE2ETest` (default CHF), overridden with PLN in `PayUPaymentE2ETest`. |
| M3 | New test groups: the doPayment re-confirm matrix (COMPLETED match and mismatch, CANCELED, NOT_FOUND, read error, DELETE failure, unknown) and notification parsing (invalid signature, extOrderId mismatch, refund, different orderId, null status). The old group 6 is split into the webhook controller, callback controller and utilities groups, each with 2–8 tests. |
| M4 | Webhook for an unknown reservation → 200 + WARN. 400 is kept only for an empty body. |
| M5 | DELETE on a PENDING order is marked to be verified on the sandbox. If PayU refuses it, the order counts as not cancelled, `discardTransaction` returns false, and re-confirm returns `pending`. If funds can still arrive, restrict it to NEW. |
| M6 | `extRefundId` is deterministic (`<reservationId>-refund-<amountCents>-<k+1>`, where k comes from GET refunds), and a timeout or I/O failure triggers a re-read before reporting failure. |
| M7 | The Mollie-precedent rationale is corrected: Mollie reuses an open payment by redirecting to it, while PayU's C1 cancels and recreates. The overview double-submit guard was checked (the Pay button row is hidden while `submitting`). |
| L1 | FR5 wording: `reTransitionToPending` is DB-only. `discardTransaction` is reached through `cancelPendingPayment`. |
| L2 | Legacy admin paths corrected to `src/main/webapp/alfio-admin-v1/angular-templates/admin/partials/configuration/…` and `src/main/webapp/alfio-admin-v1/js/admin/filter/util.js`. |
| L3 | `errorToken` is a private static nested `TransactionInitializationToken` (it does not reuse `PayUToken`). `initTransaction` returns `errorToken(msg, true)`, so core reconciles via the force-check. |
| L4 | The untracked red-gate test is `git add`-ed in commit 1. |
| L5 | The descriptor procedure: flip `updateDescriptor` (L74), run, revert, rerun, then commit only `descriptor.json` (on `feature/payu` and, if needed, on `plo-pl`). |
| L6 | The note on the mock overload: everything is read from the `ALL_KEYS` map via `getFor(Set, level)`, and the single-key overload is not used. |
| L7 | The lock-duration note. The per-request timeout is lowered from 20 s to 10 s (worst case about 50 s under the reservation lock). |
| L8 | The callback shows the cancellation toast when the cancel fails but the force-check finds no pending transaction (CANCELED arrived first). |
| L9 | The note on an orphan order after a POST timeout: it is unreachable by the buyer and auto-cancelled at `validityTime`. It is the only accepted `failed` while a remote order exists. |
| Extra | Push `backup/feature-payu-wip` to origin before the force-push. The IPv6 `customerIp` check is added to the risks. |

## Post-implementation amendments
Recorded after implementation and the sandbox runs (CP-W1, spec drift). Where an earlier statement in this spec disagrees, these rows win.

| ID | Change |
|---|---|
| PI1 | `cancelOrder`: after the DELETE (any response: SUCCESS, non-SUCCESS, timeout or I/O error) the order is **always re-read**. CANCELLED only when the re-read is FOUND+CANCELED or NOT_FOUND. A COMPLETED re-read → ERROR log, NOT_CANCELLED. Reason: on the sandbox, DELETE on a COMPLETED order returns SUCCESS but the order stays COMPLETED, so a SUCCESS body alone proves nothing. Commit dcedbd9ad. |
| PI2 | PayU applies an accepted DELETE asynchronously (the order can still be NEW or PENDING right after it). After an accepted DELETE the order is re-read with bounded polling (commit 6475c6ecb), now with an overall time budget and an injectable pause (verification fix batch), so the time spent polling under the reservation lock is bounded. |
| PI3 | IPv6 `customerIp` is normalized before it is sent: brackets and the zone index (`%eth0`) are stripped, because PayU rejects them. Commit 81ae831b2. |
| PI4 | Sandbox facts: `DATA_NOT_FOUND` comes with HTTP 404. DELETE on a PENDING order → CANCELED, and no funds arrive after it (so the M5 "restrict to NEW" fallback is not needed). A `localhost` `notifyUrl` is accepted at order creation. The refunds listing has the shape `{refunds:[{refundId, extRefundId, amount, currencyCode, status PENDING|FINALIZED|…}]}`. |
| PI5 | Refund idempotency wording: the deterministic `extRefundId` protects a same-call retry after a timeout (the re-read finds it) and concurrent duplicates. It does **not** protect an operator retry after a refund that actually landed: the refund count k has grown, so the next id is new and PayU accepts a second refund. Operators must check the refunds before retrying. |
| PI6 | Lock-duration risk: the worst case under the reservation lock is bounded by the HTTP timeouts (10 s each, for the sequential OAuth / GET / DELETE / re-read / POST calls and the single 401 retry) plus the cancel polling budget. Recommend a reverse-proxy read timeout ≥ 120 s. |
| PI7 | The hidden `alfio.payu.endpoint-override` is ignored in live mode (`PAYU_LIVE_MODE=true` always uses `https://secure.payu.com`), so a stray test property cannot send production traffic elsewhere (verification fix). |
