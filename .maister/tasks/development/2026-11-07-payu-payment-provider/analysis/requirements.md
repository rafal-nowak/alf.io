# Requirements: PayU payment provider for the alf.io fork

## TL;DR
PayU (REST v2.1, hosted payment page: BLIK, pay-by-link, cards) becomes a production-ready alf.io payment proxy, configured at system or organization level. Attendees pick "PayU: BLIK, szybki przelew, karta". They are redirected to PayU and come back to a callback that checks the order and confirms the reservation. Signed notifications also confirm it. Admins configure PayU in the legacy admin settings. The work is delivered on clean branches with no AI trailers, without upstream PRs.

## Key Decisions
- Assumptions were auto-confirmed (autonomous mode, per user instruction 2026-11-07); see the Q&A below
- The lifecycle follows Mollie (order reuse/cancel). The controllers follow the Mollie webhook and the Saferpay callback. The frontend follows saferpay-payment-proxy.

## Open Questions / Risks
- The public sandbox POS 300746 is shared; e2e depends on its availability
- Event currency must be supported by PayU (PLN for the reunion)

## Initial description
See orchestrator-state.yml `task.description` and the original prompt: scope items 1–6 and the branch/commit rules.

## Q&A (assumptions, auto-confirmed)
1. **User journey**: an attendee on the overview step (`/event/{slug}/reservation/{id}/overview`) selects the PayU radio and clicks "Pay <price>". The browser goes to the PayU hosted page and pays (BLIK/transfer/card). PayU returns to `/{type}/{id}/reservation/{rid}/payment/payu/return` (new callback). The callback runs force-check and redirects to `/book`; the reservation guard then routes to success or processing-payment. On `error=501`, or if the order was abandoned (NEW/CANCELED), the attendee returns to the overview with the error `error.STEP2_PAYU_unexpected` / cancelled message and can pick a method again. Personas: attendee (public), organizer/admin (settings, refunds in reservation detail).
2. **Existing code reuse**: MollieWebhookPaymentManager (tryToReuseExistingTransaction, Caffeine cache, validity alignment); MolliePaymentWebhookController (webhook); SaferpayCallbackController (return); SaferpayManager (validity, invalidate); saferpay-payment-proxy (frontend); StripeReservationFlowIntegrationTest + BaseReservationFlowTest (integration); CreditCardPaymentE2ETest + ReservationFlow + AdminConsole (e2e); SaferpayManagerTest (unit, mocked HttpClient).
3. **Visual assets**: ASCII mockups in `analysis/design-context/` (no external mockups). No new screens; only existing components are reused.
4. **customerIp**: a new optional field on `PaymentSpecification`, set in `ReservationApiV2Controller` from `HttpServletRequest`. It honours `X-Forwarded-For` (first entry) and falls back to `remoteAddr`, then to `127.0.0.1`. Existing constructor signatures stay unchanged (an extra constructor or a wither is added).
5. **Configuration**: PAYU_ENABLED, PAYU_LIVE_MODE, PAYU_POS_ID, PAYU_CLIENT_ID, PAYU_CLIENT_SECRET, PAYU_SECOND_KEY at SYSTEM/ORGANIZATION level. A hidden test hook (package-private/constructor or `@Value` property) overrides the PayU base URL for WireMock integration tests; it is not exposed in the admin.

## Functional requirements
- FR1 Order creation: OAuth client_credentials (cached per endpoint+clientId+secret hash, refreshed before expiry, invalidated and retried once on 401). POST /api/v2_1/orders with notifyUrl, continueUrl (callback), customerIp, merchantPosId, description, currencyCode, totalAmount (cents), extOrderId (unique), validityTime (remaining reservation time in seconds), buyer (email, names only when available, language limited to PayU-supported), products. The 302 + JSON response is parsed. On success the reservation moves to EXTERNAL_PROCESSING_PAYMENT, the transaction is PENDING (paymentId=orderId, metadata extOrderId), and the reservation validity is aligned to the order validity.
- FR2 Existing PENDING transaction on re-confirm: COMPLETED → success; PENDING / WAITING_FOR_CONFIRMATION → pending (no new order; WAITING is confirmed/captured); NEW → DELETE the remote order, then create a new one; CANCELED → create a new one.
- FR3 Notification endpoint POST `/api/payment/webhook/payu/reservation/{reservationId}`: reads the raw body and the `OpenPayu-Signature` header (fallback `X-OpenPayU-Signature`), then calls `processTransactionWebhook`. It returns 200 for SUCCESSFUL/NOT_RELEVANT/other non-error outcomes, 500 only for ERROR (so PayU retries), and 400 for an unknown reservation. Signed refund or unknown-shape notifications are acknowledged as NOT_RELEVANT. An invalid signature is rejected.
- FR4 Status mapping: COMPLETED → successful (after validating totalAmount, currencyCode and extOrderId against the transaction; a mismatch is an error); CANCELED → cancelled (transaction invalidated); WAITING_FOR_CONFIRMATION → capture + pending; PENDING → pending; NEW → not relevant / pending (never TRANSACTION_INITIATED).
- FR5 Remote cancel: `ServerInitiatedTransaction.discardTransaction` sends DELETE for NEW/PENDING orders. It is used by cancelPendingPayment, reTransitionToPending and cleanup. `initTransaction` is unsupported.
- FR6 Return callback GET (`@Controller`) handles the return as described in Q&A 1.
- FR7 Refund: POST refunds with a unique `extRefundId`, full or partial. `getInfo` reports paid amount and refunded amount (sum of FINALIZED refunds).
- FR8 Currency gating: PayU is active only for currencies supported by PayU (PLN, EUR, USD, CZK, GBP, HUF, RON, BGN, DKK, NOK, SEK, CHF).
- FR9 Per-request HTTP timeouts (e.g. 10–30 s); the shared HttpClient bean is not changed.
- FR10 Public frontend: 'PAYU' added to the PaymentProxy and StaticPaymentMethodNames types; staticPaymentMethodDetails.PAYU {labelKey reservation-page.payment-method.payu, icon ['fas','credit-card']}; payu-payment-proxy component (paymentMethodDeferred=false) registered in app.module and payment-method-selector.
- FR11 Admin: the legacy util.js label and PayU panels in system.html and organization.html; the new admin PaymentProxy type plus the credit-card icon case.
- FR12 i18n (EN, on feature/payu): `reservation-page.payment-method.payu=PayU: BLIK, fast transfer, card`, `reservation-page.payment.payu.description=You will be redirected to PayU''s secure checkout to complete the purchase.`, `error.STEP2_PAYU_unexpected=...`. PL (fork-only branch fix/pl-translations): PayU keys, `common.confirm`, `event-days.from`/`to`, plus the missing purchase-path keys.
- FR13 Stepper: active badge uses `var(--bs-primary)` (separate branch fix/stepper-active-color).
- FR14 Tests: unit tests (PayUSignature MD5/SHA-256 vectors, order building, status mapping, token cache, lifecycle red-gate tests, webhook payload parsing); integration test (reservation flow with PayU stubbed through WireMock, signed webhook, COMPLETE); API descriptor regenerated; e2e on the public PayU sandbox (local bootRun on a dedicated DB, not the :8090 container) with an AdminConsole PAYU label.
- FR15 Docker: `./gradlew distribution -x test && docker build -t alfio-plo-pl:payu-<date> ./build/dockerize`, documented in README on plo-pl.

## Scope boundaries
Included: everything above. Excluded: a PayU logo asset, masked secret inputs, a settings page in the new admin, upstream PRs, a full PL translation of all 328 keys (only purchase-path keys), a tunnel for notifications.

## Branch / commit plan
- `feature/payu` (on main): PayU backend, frontend, admin, EN i18n, tests, descriptor; meaningful commits, no AI trailers
- `fix/mollie-ignore-unsupported-methods`: unchanged
- `fix/pl-translations` (on main): PL keys (fork-only)
- `fix/stepper-active-color` (on main): stepper colour (PR candidate)
- `plo-pl` = main + all of the above + README Docker section + `.maister/`
