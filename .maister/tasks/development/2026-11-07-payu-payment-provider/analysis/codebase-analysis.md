# Codebase Analysis Report

**Date**: 2026-11-07
**Task**: Complete the PayU payment provider in the alf.io fork (branch `feature/payu`, WIP commit f05742e44)
**Description**: Complete PayU: webhook controller, public frontend (payu-payment-proxy), admin labels (legacy + new), translations (EN + PL on request), unit + integration tests, e2e on public PayU sandbox, side commits (missing PL keys for Confirm button / from-to dates, active step icon colour), Docker image build command. Fork rules: no AI trailers in commits, no ternary, restricted Lombok, GPL headers, Jackson 3 (Gson acceptable where existing managers use it), EN keys in base files.
**Analyzer**: codebase-analyzer skill (3 Explore agents: Code Analysis + Pattern Mining (backend), File Discovery (frontend/admin/i18n), Context Discovery (tests/e2e/Docker/CI))

---

## TL;DR
The WIP backend (7 files, +661 lines) is structurally sound and follows conventions (it mirrors Saferpay, uses Gson, GPL headers, no ternaries). It is not yet safe to ship. The order lifecycle in `PayUManager.doPayment` can double-charge (B1). Orders have no validity time and are never cancelled remotely (B2/B3). Refund notifications cause endless 500 retries (B5). The webhook controller is missing (B4). The frontend and admin wiring are small and follow the Saferpay pattern. The OpenAPI stability test (`CheckRestApiStability`) will break the build until its descriptor is regenerated. No Flyway migration is needed.

## Key Decisions
- Extend the existing `PaymentProvider` capability model (`WebhookHandler`, `RefundRequest`, `PaymentInfo`, plus `ServerInitiatedTransaction` for `discardTransaction`). Do not build a new module. `PaymentManager` auto-registers `@Component` providers.
- Model the webhook controller on `MolliePaymentWebhookController`. Read the raw body with `RequestUtils.readRequest` and the `OpenPayu-Signature` header (fallback `X-OpenPayU-Signature`). Return 200 for SUCCESSFUL/NOT_RELEVANT and 500 only for ERROR.
- Add a GET return/cancel callback controller modelled on `SaferpayCallbackController`. Do not point `continueUrl` straight at `/book`. This handles `?error=501` and abandoned payments.
- Reuse Mollie's `tryToReuseExistingTransaction` pattern for an existing transaction: COMPLETED→success, PENDING/WAITING→pending, NEW→DELETE the remote order and create a new one, CANCELED→new.
- Make the PayU base URL overridable (config or a bean). Integration tests can then stub PayU with WireMock (already on the classpath) or a test `@Configuration`.
- Keep Gson for PayU JSON (precedent: Saferpay/Mollie). forbiddenApis bans only `com.fasterxml.jackson.*`.
- E2E against the PayU sandbox relies on the return + force-check path, because notifications cannot reach localhost unless a tunnel is used.

## Open Questions / Risks
- **PL translations vs AGENTS.md**: the rules say to add new keys in English only and leave other languages to POEditor, but the user asked for `public_pl.properties` edits (and `admin_pl.properties` does not exist). Decide whether these are fork-only commits or upstream PR content, and keep them in separate commits.
- **Customer IP**: `PaymentSpecification` has no IP field (the draft hardcodes 127.0.0.1). Choose between adding a field (it has 18-, 8- and 7-arg constructors, so the change ripples) and `RequestContextHolder`.
- **Build blocker**: `CheckRestApiStability` compares the OpenAPI output with `src/test/resources/api/descriptor.json`, which contains the PaymentProxy, ConfigurationKeys and SettingCategory enums and the webhook paths. Regenerate it (set `updateDescriptor=true` once, then revert) and commit.
- **Stepper colour**: the intended colour is undecided (`var(--bs-primary)` vs a brand colour). Event custom CSS may override it.
- **E2E target**: the local container on port 8090 (`xx-lecie-plo-pl-alfio-1`) belongs to another project. Do not use it. Run a dedicated `./gradlew bootRun` on the dev DB.
- **No HTTP timeouts** on the shared `HttpClient` while the reservation row lock is held (M6). This is a cross-cutting change; consider per-request timeouts in PayUManager only.
- **No frontend unit tests** exist (no `*.spec.ts`). Frontend coverage must come from E2E, which CONTRIBUTING.md requires for frontend changes.

---

## Summary

The WIP commit already wires PayU into configuration (`ConfigurationKeys` 101-106, `SettingCategory.PAYMENT_PAYU` 308), `PaymentProxy.PAYU` (supportRefund=true), `StaticPaymentMethods.PAYU`, the OAuth/order/notification/refund logic in `PayUManager`, and a correct `PayUSignature`. What remains is the webhook and return controllers, lifecycle fixes in `PayUManager`, the public Angular component plus model entries, admin labels, i18n keys, tests (unit, integration, the API descriptor, e2e `AdminConsole` mapping) and documentation of the Docker build. The work is moderate in size, but payment correctness makes the risk medium-high.

---

## Files Identified

### Primary Files

**src/main/java/alfio/manager/payment/PayUManager.java** (456 lines)
- Core provider: OAuth token (cache at :93, :392-420), `doPayment` (:147-228), `parseTransactionPayload`/`processWebhook` (:240-257), `checkOrderStatus` (:268-296), `getInfo` (:301-303), refund (:309-315), `isSuccess` (:429-433).
- Holds all of B1, B2, B3, B5 and M2-M8.

**src/main/java/alfio/manager/payment/payu/PayUSignature.java** (85 lines)
- Notification signature check: hash(body+secondKey), constant-time, case-insensitive. Correct, but untested. Add tests that use the PayU documentation vectors.

**src/main/java/alfio/model/transaction/webhook/PayUWebhookPayload.java** (63 lines)
- `getStatus` is not null-safe (L5). Refund notifications carry `{orderId, extOrderId, refund}` and no `order` key.

**src/main/java/alfio/model/transaction/token/PayUToken.java** (46 lines)
- Token model. Unused by the frontend flow, which is redirect-based.

**src/main/java/alfio/controller/payment/api/payu/PayUPaymentWebhookController.java** (NEW, needed)
- Template: `src/main/java/alfio/controller/payment/api/mollie/MolliePaymentWebhookController.java:44-62`. Route: `POST /api/payment/webhook/payu/reservation/{rid}`. CSRF is already skipped for `/api/payment/webhook/**` (`AbstractFormBasedWebSecurity.java:338-339`).

**src/main/java/alfio/controller/payment/PayUCallbackController.java** (NEW, recommended)
- Template: `src/main/java/alfio/controller/payment/SaferpayCallbackController.java:39-55`.

**frontend/public/src/app/payment/payu-payment-proxy/** (NEW: provider .ts, component .ts/.html)
- Copy `saferpay-payment-proxy/`: it extends `SimplePaymentProvider` with `paymentMethodDeferred=false`, has inputs method/proxy/reservation and output paymentProvider, and checks `matchProxyAndMethod` for proxy==='PAYU'.

**frontend/public/src/app/model/event.ts**
- L123-125: add `'PAYU'` to `StaticPaymentMethodNames`. L127: add it to the `PaymentProxy` union. L133-191: add a `staticPaymentMethodDetails` entry. Without that entry the selector shows "UNKNOWN PAYMENT PAYU" (`payment-method-selector.component.ts:79-104`) and `overview.component.ts confirm()` (~L201) treats PayU as custom offline.

**src/test/resources/api/descriptor.json**
- Must be regenerated or the build fails.

### Related Files

**Backend**
- `src/main/java/alfio/manager/PaymentManager.java`: provider registry (:62) and lookups (:64, :71, :111). No change expected.
- `src/main/java/alfio/manager/TicketReservationManager.java`: `performPayment` (:609), `cancelPendingPayment` (:687-707), `reTransitionToPending` (:953-959), `cleanupExpiredReservations` (:1016), `processTransactionWebhook` (:1771), `handlePaymentWebhookResult` (:1830), `forceTransactionCheck` (:1906). No change expected; these define the provider contract.
- `src/main/java/alfio/controller/api/v2/user/ReservationApiV2Controller.java` (~320-385): the error-code fallback to STRIPE_UNEXPECTED is at :381-382.
- `src/main/java/alfio/controller/api/v2/user/PaymentApiController.java` (:84-92): the force-check endpoint.
- `src/main/java/alfio/manager/payment/SaferpayManager.java` (validity :377) and `MollieWebhookPaymentManager.java` (validity :341, reuse :272-293, Caffeine cache :98-99): pattern sources.
- `src/main/java/alfio/config/DataSourceConfiguration.java:224-231`: the shared HttpClient (redirect NEVER, no timeouts).
- `src/main/java/alfio/manager/system/ConfigurationManager.java:323-327`: hides a blacklisted category.
- `src/main/java/alfio/model/system/ConfigurationKeys.java`, `PaymentProxy.java:39`, `StaticPaymentMethods.java`: already changed.

**Public frontend**
- `frontend/public/src/app/app.module.ts`: Saferpay import (L89) and declaration (L177). Icons are registered at L255-261 (FA5 free; there is no PayU brand icon, so use `fas credit-card` or `money-check-alt`).
- `frontend/public/src/app/reservation/payment-method-selector/payment-method-selector.component.html` (L37-84; the Saferpay block is L73-77): add the PayU proxy component. Radio label id is `PAYU-label`.
- `frontend/public/src/app/reservation/overview/overview.component.ts` (confirm L92-165, redirect L125-126) and `overview.component.html` (Confirm button L121-128).
- `frontend/public/src/app/reservation/reservation.guard.ts` (L67-81): routes to ProcessingPaymentComponent, which polls every 2s and force-checks after 10 polls.
- `frontend/public/src/app/stepper/stepper.component.scss` (L44-46): current-step badge `#007ACC`, which is hardcoded. The side commit changes this.
- `frontend/public/src/app/event-dates/event-dates.component.html` (L13, L18): `event-days.from`/`to`.
- Optional logo: `src/main/resources/images/payment/` and `frontend/public/src/assets/img/payment/` (Mollie badge precedent).

**Legacy admin (`src/main/webapp/alfio-admin-v1`)**
- `js/admin/filter/util.js` L5-13 `PAYMENT_PROXY_DESCRIPTIONS`: add PAYU. Consumers: `admin-application.js` L655, `configuration.js` L850, `subscriptions.js` L139/227.
- `js/admin/.../configuration.js` L220-227: the camelCase conversion produces `paymentPayu` automatically.
- `angular-templates/admin/partials/configuration/system.html`: L203 ng-if gets `|| systemConf.paymentPayu`; copy the panel at L295-304.
- `angular-templates/admin/partials/configuration/organization.html`: ng-if at L135, copy the panel at L244-253.

**New admin (`frontend/admin/src`)**
- `model/event.ts` L61: `PaymentProxy` union.
- `service/reservation-format.ts` L25-36: `paymentMethodIcon` case → `'credit-card'`. There is no settings page. Follow `docs/admin-style-guide.md` (use `when()`, no ternaries).

**i18n (`src/main/resources/alfio/i18n`)**
- `public.properties`: ASCII, `key=value`, doubled apostrophes. New keys: `reservation-page.payment-method.payu`, `reservation-page.payment.payu.description`, and (if kept) `error.STEP2_PAYU_unexpected`.
- `public_pl.properties`: UTF-8, sorted, `key = value`. 328 keys are missing, including `common.confirm`, `event-days.from`/`to`, `subscription.detail.validity.CUSTOM.from/to` and the payment-flow keys.
- `admin.properties`: 20 keys, none for payments. `admin_pl.properties` does not exist.

**Tests / build**
- `src/test/java/alfio/controller/api/CheckRestApiStability.java`
- `src/test/java/alfio/manager/payment/SaferpayManagerTest.java` (unit template), `MollieWebhookPaymentManagerTest`, `StripeWebhookPaymentManagerTest`
- `src/test/java/alfio/controller/api/v2/user/reservation/BaseReservationFlowTest.java` (1823 lines) and `StripeReservationFlowIntegrationTest.java` (204 lines, closest integration template)
- `ReservationPaymentDeadlockIntegrationTest` (test `@Configuration` precedent, L274-300), `SimpleHttpClientIntegrationTest` (`@WireMockTest`)
- `src/test/java/alfio/e2e/` (`BaseE2ETest`, `AdminConsole.paymentMethodLabel`, which needs a PAYU case and currently throws for it, and `ReservationFlow`)
- `src/test/java/alfio/test/util/TestUtil` (fixed clock), `testSupport/MaybeConfigurationBuilder`
- `TicketReservationManagerTest` and `PaymentMethodDeserializationTest` iterate enums, so verify they still pass.
- `src/main/dist/Dockerfile` and `build.gradle.kts` (`dockerize`, `distribution`, frontend tasks L496-520)

---

## Current Functionality

### Key Components/Functions
- **PayUManager.doPayment**: reuses an existing PENDING transaction only if PayU reports COMPLETED. Otherwise it gets an OAuth token, POSTs `/api/v2_1/orders` (302 with a JSON body that stays readable because redirect is NEVER), moves the reservation to EXTERNAL_PROCESSING_PAYMENT, invalidates the old transaction, inserts a PENDING transaction with paymentId=orderId, and returns `PaymentResult.redirect`.
- **parseTransactionPayload / processWebhook**: verifies the signature, then re-reads the order status through `checkOrderStatus` (GET orders/{id}) rather than trusting the payload.
- **checkOrderStatus**: maps COMPLETED→success, WAITING_FOR_CONFIRMATION→confirmOrder (a capture), NEW→processStarted (M2) and CANCELED→failed.
- **PayUSignature**: correct implementation.

### Data Flow
1. Overview `confirm()` → `POST /api/v2/public/reservation/{id}` → `TicketReservationManager.performPayment` → `PayUManager.doPayment` → the browser is redirected to PayU.
2. PayU notification → `POST /api/payment/webhook/payu/reservation/{rid}` (**missing controller**) → `processTransactionWebhook` → lock the reservation and the latest transaction → if not relevant, return NOT_RELEVANT (idempotent) → `processWebhook` → `handlePaymentWebhookResult` (SUCCESSFUL→complete; TRANSACTION_INITIATED→WAITING_EXTERNAL_CONFIRMATION; FAILED/CANCELLED→revert).
3. Browser `continueUrl` → `/{type}/{id}/reservation/{rid}/book` → guard → ProcessingPaymentComponent polls every 2s → after 10 polls, force-check → `forceTransactionCheck`.
4. Scheduler `cleanupExpiredReservations` → force-check → if not successful, `cancelPendingPayment` (calls `discardTransaction` only for `ServerInitiatedTransaction`) → delete.

### Failure Points (ranked)
| ID | Severity | Location | Issue | Fix |
|----|----------|----------|-------|-----|
| B1 | Blocker | PayUManager:147-153, :173, :257, :287-290 | Only COMPLETED short-circuits. A WAITING order is captured and a new order is still created (double charge). PENDING creates a new order. A NEW order stays payable. Because the old transaction is invalidated, its later COMPLETED notification is dropped as NOT_RELEVANT. | COMPLETED→success; PENDING/WAITING→`PaymentResult.pending`; NEW→DELETE `/api/v2_1/orders/{id}` then create new; CANCELED→new |
| B2 | High | :189-228 | No `validityTime` in the order body and no `updateValidity` | Align with reservation expiry (as Saferpay :377 and Mollie :341 do) |
| B3 | High | TRM:687-707, :953-959 | Remote orders are never cancelled | Implement `ServerInitiatedTransaction.discardTransaction` → DELETE the order (`initTransaction` unsupported) |
| B4 | Blocker | n/a | Webhook controller missing | New controller (Mollie template) |
| B5 | High | :240-251 | Refund notification → Optional.empty → ERROR → 500 → endless retries; NPE when extOrderId/status is missing | After a valid signature always return the payload; map unknown types to notRelevant |
| M1 | Medium | :80, :199-200 | No return/cancel handling; `?error=501` is ignored; the user waits about 20s; an abandoned NEW order is stuck in WAITING_EXTERNAL_CONFIRMATION | GET callback controller |
| M2 | Medium | :291-292 | NEW → processStarted | Map to notRelevant/pending, and make it cancellable |
| M3 | Medium | :169, :182, :185 | `error.STEP2_PAYU_unexpected` key is missing, so the controller falls back to the Stripe message | Add the key, or use `ErrorsCode.STEP_2_PAYMENT_REQUEST_CREATION` |
| M4 | Medium | :202 | customerIp is hardcoded to 127.0.0.1 | Real client IP |
| M5 | Medium | :93, :392-420 | Token cache key leaves out the secret, no invalidation on 401, NPE when `expires_in` is missing | Caffeine, as Mollie does |
| M6 | Medium | shared HttpClient | No timeouts while the row lock is held | Per-request timeout |
| M7 | Medium | :268-296 | Amount, currency and extOrderId are not validated against the transaction | Validate |
| M8 | Low-Med | :301-315 | Refund has no `extRefundId`; `refundedAmount` is null | Add both |
| L1-L9 | Low | various | `purchaseContext.now(clockProvider)`, store PAYMENT_ID, brittle `isSuccess`, null buyer names, PayU language codes, signature tests, null-safe status, the extOrderId format collides under a fixed test clock, config descriptions | Clean up |

No backend `switch` over `PaymentProxy` needs a PAYU case. No Flyway migration is needed: `payment_proxy` is varchar, and `V200_001__DEFAULT_PAYMENT_PROVIDERS.sql` only enables the default providers.

---

## Dependencies

### Imports (What This Depends On)
- ConfigurationManager (PAYU keys, SYSTEM/ORGANIZATION level), java.net.http.HttpClient, TicketReservationRepository, TransactionRepository, ClockProvider, Gson, and the capability interfaces in `model/transaction/capabilities`.

### Consumers (What Depends On This)
- **PaymentManager**: discovers providers and looks them up by proxy, transaction or method.
- **TicketReservationManager**: performPayment, webhook processing, force-check, cleanup and refunds.
- **Webhook controller / callback controller (new)**: the entry points for PayU.
- **Public frontend**: model, selector, overview, guard.
- **Legacy admin**: util.js descriptions, configuration panels, subscriptions.
- **New admin**: reservation-format icon.
- **CheckRestApiStability**: the enum and path descriptor.

**Consumer Count**: about 8 backend and frontend touchpoints
**Impact Scope**: Medium. The changes are additive and isolated to a new proxy. The shared lifecycle in TicketReservationManager is reused without modification.

---

## Test Coverage

### Test Files
- None exist for PayU yet.
- Templates: `SaferpayManagerTest` (MockitoExtension, `@Mock HttpClient`, `ArgumentCaptor<HttpRequest>`, verify `transactionRepository.update`/`invalidateById`/`updateValidity`), `StripeReservationFlowIntegrationTest` (signed payload fixtures in `src/test/resources/transaction-json/`, controller called directly with `MockHttpServletRequest`).
- Stack: JUnit 5, Mockito 5.23, plain Assertions (no AssertJ), WireMock 3.13.2, TestContainers Postgres (default `postgres:10`; use `-Dpgsql.version=16`) plus stripe-mock.

### Coverage Assessment
- **Test count**: 0 PayU tests.
- **Gaps**: signature (documentation vectors), doPayment state matrix (COMPLETED/PENDING/WAITING/NEW/CANCELED), refund-notification handling, token cache, the webhook controller HTTP codes, a full reservation flow (needs an overridable PayU base URL), and an e2e flow on the sandbox (`AdminConsole.paymentMethodLabel` needs a PAYU case). Frontend unit tests are absent across the whole project.

---

## Coding Patterns

### Naming Conventions
- **Backend**: `<Provider>Manager` in `alfio.manager.payment`, helpers in a sub-package (`payu/`), webhook controller `controller/payment/api/<provider>/<Provider>PaymentWebhookController`, callback controller `controller/payment/<Provider>CallbackController`.
- **Frontend**: `payment/<provider>-payment-proxy/<provider>-payment-provider.ts` and `.component.{ts,html}`, selector `app-<provider>-payment-proxy`.
- **i18n**: `reservation-page.payment-method.<x>`, `reservation-page.payment.<x>.description`, `error.STEP2_<X>_unexpected`.

### Architecture Patterns
- **Style**: Spring components with constructor injection; provider capabilities expressed as interfaces; npjt repositories.
- **State**: reservation status machine (PENDING → EXTERNAL_PROCESSING_PAYMENT → WAITING_EXTERNAL_CONFIRMATION → COMPLETE) plus transaction status. Webhooks are idempotent through locking and the NOT_RELEVANT result.
- **Constraints**: no ternary, Lombok limited to Getter/Setter/AllArgsConstructor/Data/Delegate, GPL header (`./gradlew licenseFormat`), no `com.fasterxml.jackson.*`, commits without AI trailers.

---

## Complexity Assessment

| Factor | Value | Level |
|--------|-------|-------|
| File Size | PayUManager 456 lines; ~20 files touched | High (file count) |
| Dependencies | ~6 injected/imported collaborators | Medium |
| Consumers | ~8 touchpoints | High |
| Test Coverage | 0 tests | Low (High risk) |

### Overall: Moderate
Each change follows a well-established pattern (Saferpay/Mollie). The complexity comes from the breadth of the change and from getting payment-state correctness right, not from new architecture.

---

## Key Findings

### Strengths
- The draft follows fork conventions, and its signature verification is correct.
- Provider auto-registration and the capability model need no changes to core managers.
- CSRF and security already cover the webhook path. The HttpClient's redirect=NEVER setting makes the 302 JSON body readable.
- There are rich test harnesses (BaseReservationFlowTest, WireMock, MaybeConfigurationBuilder, a fixed clock).

### Concerns
- Several paths can lose money (B1-B3, B5).
- Endpoints are hard-coded (SANDBOX/LIVE), which blocks integration testing.
- The API descriptor stability test will break the build.
- The PL translation request conflicts with AGENTS.md.
- E2E on the sandbox cannot receive notifications locally.

### Opportunities
- Add many missing PL keys in one side commit (328 are missing; a bigger PL fill-in is optional).
- Replace the stepper's hardcoded `#007ACC` with `var(--bs-primary)`.
- Document the Docker build (`./gradlew distribution -x test && docker build -t alfio/alf.io ./build/dockerize`).

---

## Impact Assessment

- **Primary changes**: `PayUManager.java`, `PayUWebhookPayload.java`, new `PayUPaymentWebhookController`, new `PayUCallbackController`, new `frontend/public/src/app/payment/payu-payment-proxy/*`, `frontend/public/src/app/model/event.ts`, `app.module.ts`, `payment-method-selector.component.html`.
- **Related changes**: legacy admin `util.js`, `system.html`, `organization.html`; new admin `model/event.ts`, `reservation-format.ts`; `public.properties` (plus `public_pl.properties`); possibly `admin.properties`; `ConfigurationKeys` (base URL override, descriptions); `stepper.component.scss`.
- **Test updates**: new `PayUManagerTest`, `PayUSignatureTest`, the webhook controller test, `PayUReservationFlowIntegrationTest` (WireMock or a stub config), a regenerated `descriptor.json`, a PAYU case in `AdminConsole.paymentMethodLabel`, a new e2e test with `requiredPaymentProxies()` returning PAYU.

### Risk Level: Medium-High
The surface is additive and isolated, but payment correctness (double charge, orphaned payable orders, retry storms) and zero existing coverage raise the risk. The core TicketReservationManager is unchanged, which keeps the blast radius for other providers low.

---

## Recommendations

**Implementation strategy (modifying existing draft + new capability):**
1. **Fix the lifecycle first, with TDD**: write a unit test for each doPayment state (B1), then add the validity time (B2), `discardTransaction` (B3), refund/unknown notifications mapped to NOT_RELEVANT plus null-safety (B5) and NEW→pending (M2).
2. **Controllers**: the webhook controller (Mollie template, header fallback, status-code mapping) and the GET callback controller (Saferpay template; it handles `error=501`, triggers a force-check, and redirects to `/book`).
3. **Hardening**: Caffeine token cache keyed on clientId+secret with invalidation on 401 (M5), a per-request timeout (M6), amount/currency/extOrderId validation (M7), extRefundId and refundedAmount (M8), the real client IP (M4; decide the mechanism), and the error key (M3).
4. **Testability**: add a configurable base URL (a new config key or an injectable endpoint resolver) and use WireMock in the integration test that extends `BaseReservationFlowTest`.
5. **Frontend**: copy the Saferpay proxy and add the model entries (icon `fas credit-card`). Use an `if`/`else` instead of a ternary.
6. **Admin**: legacy panels and labels, plus the new admin union and icon case.
7. **i18n**: EN keys in the base files. PL keys go in a separate, clearly labelled commit, per the user's request.
8. **Build hygiene**: regenerate `descriptor.json`, run `./gradlew licenseFormat` and `./gradlew build -Dpgsql.version=16`.
9. **E2E**: run against a dedicated local `bootRun` with PayU sandbox credentials, relying on return + force-check. Attach `e2e-test.log` as CONTRIBUTING.md requires.
10. **Side commits**: PL `common.confirm`, `event-days.from/to` and `subscription.detail.validity.CUSTOM.from/to`; the stepper colour; Docker build instructions.

**Backward compatibility**: all changes are additive. The new enum constants are already appended, so the enum order is safe (L9).

---

## Next Steps

Invoke the gap-analyzer with this report. Before writing the specification, confirm with the user: (1) how PL translations relate to AGENTS.md (fork-only commits?), (2) the customer IP mechanism, (3) the stepper colour, (4) whether to make the PayU base URL configurable (recommended) and (5) the e2e approach (force-check only, or a tunnel for notifications).
