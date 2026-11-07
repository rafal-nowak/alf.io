# Implementation Plan: PayU payment provider (BLIK, fast transfers, cards)

## TL;DR
The plan has 19 task groups and 113 steps. It plans 62 new JUnit tests (unit and WireMock integration) and 2 sandbox E2E tests, and the 7 red-gate tests must pass.
- **Backend on `feature/payu` (Groups 1–9, 13–15)**: a strict chain, because one Gradle compilation unit means half-written Java breaks every other group's tests.
- **Parallel work**: public frontend (10), admin (11) and EN i18n (12) run in parallel with the backend once commit 1 exists (Group 4). The `fix/pl-translations` (16) and `fix/stepper-active-color` (17) branches are built in separate worktrees with no dependencies.
- **The last two groups**: Group 18 regenerates the API descriptor, runs the full build and produces the E2E proof. Group 19 builds `plo-pl`, the Docker image and pushes every branch.

## Key Decisions
- **The Java groups are a dependency chain** (1→…→9→13→14→15): `./gradlew test` compiles the whole `main` and `test` source sets, so a group in the middle of a `PayUManager.java` edit would break the compilation of any parallel Java group. Frontend and i18n groups do not trigger Java compilation (`./gradlew test` does not build the frontends), so they run alongside.
- **Groups 10, 11 and 12 depend on Group 4** for ordering only: commit 1 is made by `git commit --amend` of the WIP commit 2259c9313. Any earlier commit on `feature/payu` would be amended instead.
- **Spec test groups map onto plan groups** so that each has 2–8 tests:
  - spec 2 (order building) → G1, without the customerIp-from-spec case, which goes to G7
  - spec 3 → G2
  - spec 4, plus discard as 2 tests → G3
  - spec 5 → G4
  - spec 6 → G5
  - spec 7 refund/info (3) + spec 1 signature (5) → G6
  - spec 8 → G8, spec 9 → G9, spec 10 → G7, spec 11 → G13, spec 12 → G15
- **The commit plan follows the spec**: commit 1 at G4, 2 at G6, 3 at G7, 4 at G9, 5 at G10, 6 at G11, 7 at G12, 8 at G13 and 9 at G15, plus an optional gap-test commit at G14 and the descriptor commit at G18.
  - The descriptor gets its own commit in G18, after the full integration, instead of being folded into commit 8 (the caller's instruction).
- **Frontend groups have no unit tests**: `frontend/public` has no `*.spec.ts` and `frontend/admin` has no test runner. Groups 10, 11 and 17 are verified by a production build, by a self-check against the mockups' acceptance criteria, and by `PayUPaymentE2ETest` (G15/G18).
- **The E2E is written and run against the live sandbox in G15**, so the PayU page selectors are verified before commit 9. G18 only reproduces the run to produce the proof log, so history does not need fixups.
- **pnpm is not on PATH and `node_modules` are absent**: frontend commands use `npx --yes pnpm@11.1.2` (the version pinned in `build.gradle.kts`). The Gradle tasks `frontendBuild` / `frontendAdminBuild` are a fallback, used only when no other Gradle run is active.
- **Side branches use `git worktree`** under `/Users/rafalnowak/Dev/alf.io-worktrees/`, so the main worktree never leaves `feature/payu` while the parallel groups run.

## Open Questions / Risks
- **Concurrent commits on `feature/payu`** (Groups 5–9 vs 10–12) can hit `.git/index.lock`. Each group commits with an explicit pathspec and retries after a few seconds. Never delete the lock file.
- **Ports 5432, 8080 and 8090 are taken on this machine.** The E2E environment needs a dedicated postgres container and a server port, both picked free with `lsof` at run time. The :8090 container and its DB belong to another project and must not be touched.
- **The PayU sandbox is shared, and its unverified behaviour is checked live in G15**: page markup and selectors, the test payment method, DELETE on a PENDING order, IPv6 `customerIp`, whether a `localhost` `notifyUrl` is accepted, and the `DATA_NOT_FOUND` code. If the sandbox refuses a DELETE on a PENDING order, restrict the cancel to NEW (spec Risks).
- **The E2E needs Chrome installed locally** and an admin login on the dev server (from the startup log or the dev profile).
- **The `.maister/` commit on `plo-pl` is a snapshot.** Later orchestrator phases (verification, finalization) update `.maister/` after the push, and those changes are not on origin until they are committed again.
- **Force-push of `feature/payu`**: `backup/feature-payu-wip` must reach origin first, and the lease is pinned to the recorded `origin/feature/payu` SHA.

## Overview
Total Steps: 113
Task Groups: 19
Expected Tests: 62 new JUnit tests (unit + WireMock integration) + 7 red-gate tests (must pass) + 0–10 gap tests (G14) + 2 sandbox E2E tests → 71–81. This is above the generic 16–34 range, because the spec fixes 12 test groups for a payment provider that moves money.

### Conventions used in every group
- **Repo root**: `/Users/rafalnowak/Dev/alf.io` (branch `feature/payu`). Side-branch worktrees: `/Users/rafalnowak/Dev/alf.io-worktrees/pl-translations` and `/Users/rafalnowak/Dev/alf.io-worktrees/stepper-active-color`.
- **PNPM**: `npx --yes pnpm@11.1.2`.
- **Commit procedure**:
  1. `git add <group files>`.
  2. `git commit -m "<subject>" [-m "<body>"] -- <group files>`. The pathspec means the commit contains only this group's files.
  3. Check that `git log -1 --format='%an%n%B'` shows author `rafal-nowak` and contains no `Co-Authored-By`, "Claude" or "Generated with" line.
  4. On `index.lock`, wait and retry. Never use `-a`, and never stage `.maister/`, `custom.jvmargs` or `e2e-test.log` on `feature/payu`.
- **Code rules**: no ternary operator (Java, TS, JS, tests); Lombok limited to `@Getter/@Setter/@AllArgsConstructor/@Data/@Delegate`; a GPL header on new `.java` files (`./gradlew licenseFormat`); no `com.fasterxml.jackson.*`; Gson for PayU JSON. Ternary check on changed files: `git diff --cached -U0 | grep -nE '^\+.*\?[^?.]*:' || true`. Review the hits by hand, because generics, lambdas and Angular templates give false positives.
- **No pushes** before Group 19.

## Implementation Steps

### Task Group 1: PayU order creation and reservation validity
**Dependencies:** None
**Files to Modify:** `src/main/java/alfio/manager/payment/PayUManager.java`, `src/test/java/alfio/manager/payment/PayUManagerTest.java`
**Estimated Steps:** 6

- [x] 1.0 Complete order creation (spec §1 constants, `buildOrderRequest`, `doPayment` step 3)
  - [x] 1.1 Write 7 focused tests in the new `PayUManagerTest`. Templates: `SaferpayManagerTest`, `MaybeConfigurationBuilder`, `TestUtil.clockProvider()`, a mocked `HttpClient` capturing the `HttpRequest`/body.
    - `notifyUrl` uses `WEBHOOK_URL_TEMPLATE`, and `continueUrl` uses the new `RETURN_URL_TEMPLATE` (`/{type}/{id}/reservation/{rid}/payment/payu/return`).
    - With no customer IP on the spec, `customerIp` falls back to `127.0.0.1`.
    - `validityTime` = max(remaining reservation seconds, 300).
    - `validityTime` falls back to `RESERVATION_TIMEOUT` minutes (default 25) when the reservation is null.
    - `buyer.firstName`/`lastName` are omitted when blank.
    - `buyer.language` is omitted when the language is not in the PayU language set.
    - `extOrderId` = `<reservationId>-<8 hex>` and differs across two calls under a fixed clock. On success, `updateValidity` gets now + validityTime + 5 min, then `invalidateExistingTransactions`, then a PENDING insert with metadata `extOrderId`.
  - [x] 1.2 Add the constants: `RETURN_URL_TEMPLATE` (public, shared with G9), `ADDITIONAL_INFO_RESERVATION_ID = "reservationId"`, a `SUPPORTED_CURRENCIES` set (PLN, EUR, USD, CZK, GBP, HUF, RON, BGN, DKK, NOK, SEK, CHF) and a `BUYER_LANGUAGES` set (spec §1 list).
  - [x] 1.3 Read every configuration value, including `RESERVATION_TIMEOUT`, from the `getFor(Set, level)` ALL_KEYS map. Remove every single-key `getFor(key, level)` call on the doPayment, checkOrderStatus and parse paths (L6).
  - [x] 1.4 Update `buildOrderRequest`:
    - set the new `continueUrl`;
    - set `customerIp` through a private `resolveCustomerIp(spec)` that returns `127.0.0.1` for now (G7 wires the spec field);
    - add the buyer names only when non-blank and the language only when it is in the set;
    - keep the existing description/product abbreviation.
  - [x] 1.5 Implement order creation:
    - `extOrderId` with 8 random hex chars;
    - the `validityTime` rule and its fallback;
    - clock-based timestamps everywhere (`clockProvider`, never `new Date()`).
    - After a successful POST: update the reservation status to EXTERNAL_PROCESSING_PAYMENT, call `ticketReservationRepository.updateValidity`, then `invalidateExistingTransactions`, then insert the PENDING transaction (paymentId = orderId, metadata `extOrderId`), then return `PaymentResult.redirect(redirectUri)`.
    - No ternaries.
  - [x] 1.6 Run only the new tests: `./gradlew test --tests 'alfio.manager.payment.PayUManagerTest'`.

**Acceptance Criteria:**
- The 7 tests pass. The fixture test `fixtureCreatesOrderWhenNoTransactionExists` in `PayUManagerLifecycleTest` still passes.
- No single-key `getFor` call is left on the payment paths. No `new Date()` is left in `PayUManager`.
- No commit yet: commit 1 is made in Group 4.

---

### Task Group 2: Re-confirm state matrix (money-safe `doPayment`)
**Dependencies:** 1
**Files to Modify:** `src/main/java/alfio/manager/payment/PayUManager.java`, `src/test/java/alfio/manager/payment/PayUManagerTest.java`
**Estimated Steps:** 6

- [x] 2.0 Complete the `doPayment` re-confirm path (spec §2, H1–H3)
  - [x] 2.1 Write 7 tests in `PayUManagerTest`, each starting with an existing PENDING PAYU transaction:
    - COMPLETED with a validation match → the transaction is updated to COMPLETE and the result is `successful`.
    - COMPLETED with a mismatch (amount, currency or extOrderId) → `pending`, no update, no POST `/orders`.
    - CANCELED → a new order is created (redirect).
    - NOT_FOUND (HTTP 404) → a new order is created.
    - Read ERROR (5xx or `HttpTimeoutException`) → `pending`, no POST, and `invalidateExistingTransactions` is not called.
    - A NEW order whose DELETE is answered without a SUCCESS body → `pending`, no POST.
    - An unknown status → `pending`.
  - [x] 2.2 Make `retrieveOrder` tri-state, as a private enum `OrderLookupOutcome {FOUND, NOT_FOUND, ERROR}` with a private record `OrderLookup(outcome, JsonObject order)` and no Lombok.
    - FOUND: HTTP 2xx, `status.statusCode == SUCCESS`, and a non-empty `orders` array.
    - NOT_FOUND: HTTP 404 or `DATA_NOT_FOUND`.
    - ERROR: everything else, including timeout, I/O and interruption (restore the interrupt flag).
  - [x] 2.3 Implement the shared `cancelOrder(configuration, orderId)`. It sends DELETE `/api/v2_1/orders/{id}` and returns CANCELLED when the body has `status.statusCode == SUCCESS`, whatever the HTTP code (the red-gate stub answers 302). After a non-SUCCESS answer, it re-reads the order: CANCELED or NOT_FOUND → CANCELLED. Anything else → NOT_CANCELLED, logged at WARN.
  - [x] 2.4 Implement `completeTransaction(transaction, order)`.
    - It validates `totalAmount == priceInCents`, `currencyCode == currency`, and `extOrderId` == metadata (when both are present).
    - On a match: `transactionRepository.update(..., COMPLETE, metadata)` and return a match result carrying `PayUToken(orderId)`.
    - On a mismatch: log at ERROR with the orderId, reservationId, expected and actual values, change nothing, and return a mismatch result.
  - [x] 2.5 Rewrite the re-confirm branch of `doPayment` exactly per the §2 matrix. WAITING_FOR_CONFIRMATION → PUT `/orders/{id}/status` COMPLETED, then `pending`. `failed("error.STEP2_PAYU_unexpected")` is returned only when no previous order may still be payable, or when the creation itself fails.
  - [x] 2.6 Run: `./gradlew test --tests 'alfio.manager.payment.PayUManagerTest' --tests 'alfio.manager.payment.PayUManagerLifecycleTest'`. The 7 new tests and the 3 red-gate `doPayment*` tests must pass.

**Acceptance Criteria:**
- The 7 tests pass, and the red-gate tests `doPaymentMustNotCreateSecondOrderWhileFirstAwaitsConfirmation`, `...WhileFirstIsBeingPaid` and `doPaymentCancelsStillNewOrderBeforeCreatingAnotherOne` turn green.
- No code path returns `failed` after a FOUND order in NEW/PENDING/WAITING/COMPLETED/unknown state or after an ERROR read.
- The status read itself has no DB side effects.

---

### Task Group 3: Status mapping, remote discard and server-initiated contract
**Dependencies:** 2
**Files to Modify:** `src/main/java/alfio/manager/payment/PayUManager.java`, `src/test/java/alfio/manager/payment/PayUManagerTest.java`
**Estimated Steps:** 6

- [x] 3.0 Complete `checkOrderStatus`, `forceTransactionCheck` and `ServerInitiatedTransaction` (spec §1, §3, FR4, FR5)
  - [x] 3.1 Write 8 tests:
    - COMPLETED with a match → `successful` and the transaction COMPLETE.
    - COMPLETED with a mismatch → `error` and no update.
    - CANCELED → `cancelled`, the transaction invalidated, and the reservation validity extended by `RESERVATION_TIMEOUT` (clock-based).
    - WAITING_FOR_CONFIRMATION → the capture PUT is sent, then `pending`.
    - NEW → `processWebhook` gives NOT_RELEVANT and `forceTransactionCheck` gives ERROR.
    - An unknown status → `pending`.
    - Parameterized `discardTransaction`:
      - NEW or PENDING → DELETE sent and true;
      - COMPLETED, WAITING_FOR_CONFIRMATION or a read ERROR → false with no DELETE;
      - CANCELED or NOT_FOUND → true with no DELETE.
    - `discardTransaction` with the DELETE refused → false.
  - [x] 3.2 Implement `checkOrderStatus(transaction, configuration, newOrderResult)` per the §3 table. It uses `completeTransaction` for COMPLETED and `retrieveOrder` NOT_FOUND/ERROR → `error(...)`. Remove the draft's NEW → `processStarted`.
  - [x] 3.3 `processWebhook` passes `notRelevant("order not paid yet")` for NEW. `forceTransactionCheck` passes `error("PayU order not paid yet")` and never dereferences the `reservation` argument (the red gate passes null).
  - [x] 3.4 Add `ServerInitiatedTransaction` to the implemented capabilities. `discardTransaction` follows the spec §1 table and uses the shared `cancelOrder`.
  - [x] 3.5 `initTransaction` returns `errorToken("PayU does not support server-initiated transactions", true)` with no remote call. `errorToken` builds a private static nested class implementing `TransactionInitializationToken`, with a hand-written constructor and getters (no Lombok builder, no reuse of `PayUToken`).
  - [x] 3.6 Run: `./gradlew test --tests 'alfio.manager.payment.PayUManagerTest' --tests 'alfio.manager.payment.PayUManagerLifecycleTest'`. The 8 new tests and `newOrderIsNotReportedAsPaymentInProgress` must pass.

**Acceptance Criteria:**
- The 8 tests pass, and red-gate test 4 turns green.
- A NEW order can never yield NOT_RELEVANT from `forceTransactionCheck`, so it cannot mark a reservation STUCK.
- `discardTransaction` never reports a paid, authorized or unknown-state order as discarded.

---

### Task Group 4: Notification parsing and webhook payload model
**Dependencies:** 3
**Files to Modify:** `src/main/java/alfio/manager/payment/PayUManager.java`, `src/main/java/alfio/model/transaction/webhook/PayUWebhookPayload.java`, `src/test/java/alfio/manager/payment/PayUManagerTest.java`, `src/test/java/alfio/manager/payment/PayUManagerLifecycleTest.java` (git add only, unchanged)
**Estimated Steps:** 7

- [x] 4.0 Complete notification parsing (spec §1 `parseTransactionPayload`, B5) and make commit 1
  - [x] 4.1 Write 5 tests:
    - invalid signature → `Optional.empty()`;
    - present `extOrderId` not starting with the reservationId → an UNRECOGNIZED payload, and `processWebhook` gives NOT_RELEVANT;
    - a top-level `refund` payload → REFUND, and `processWebhook` gives NOT_RELEVANT;
    - an order notification with a different `orderId` than `transaction.getPaymentId()` → NOT_RELEVANT;
    - a missing `status` → null-safe (no NPE, type `payu-null` is not produced).
  - [x] 4.2 `PayUWebhookPayload`:
    - Add a nested enum `Kind {ORDER, REFUND, UNRECOGNIZED}`, with hand-written static factories or a constructor (allowed Lombok only).
    - `getType()` returns `payu-<status>`, `payu-refund` or `payu-unrecognized`. `getStatus()` is null-safe. `getOrderId()` may be null.
  - [x] 4.3 Rewrite `parseTransactionPayload`:
    1. Signature (via `PayUSignature`) → empty when invalid.
    2. JSON → empty when unparseable.
    3. An `order` object → ORDER with a null-safe orderId and status. An extOrderId mismatch → UNRECOGNIZED with a WARN log.
    4. A top-level `refund` → REFUND with the top-level `orderId`.
    5. Anything else → UNRECOGNIZED.
    The reservationId always comes from `additionalInfo`.
  - [x] 4.4 `processWebhook` returns `notRelevant` when the kind is not ORDER, or the orderId is null or differs from `transaction.getPaymentId()`. Otherwise it delegates to `checkOrderStatus` with the webhook NEW mapping.
  - [x] 4.5 Run the PayU unit tests: `./gradlew test --tests 'alfio.manager.payment.PayU*'`. `PayUManagerLifecycleTest` must pass 7/7, together with all `PayUManagerTest` tests from G1–G4.
  - [x] 4.6 Run `./gradlew licenseFormat`, then `git status --short` (only the G1–G4 files may change). Run the ternary check on the diff, and `grep -n 'com.fasterxml' PayUManager.java PayUWebhookPayload.java` (it must find nothing).
  - [x] 4.7 Make commit 1:
    1. Check that `git rev-parse HEAD` == `2259c9313…` and `git rev-parse backup/feature-payu-wip` == the same SHA.
    2. `git add src/test/java/alfio/manager/payment/PayUManagerLifecycleTest.java src/test/java/alfio/manager/payment/PayUManagerTest.java`.
    3. `git commit --amend -m "Add PayU payment provider backend" -m "<body: REST v2.1 provider, money-safe order lifecycle, tri-state order read, shared cancel, signed notification parsing; red-gate lifecycle test>" -- src/main/java/alfio/manager/payment/PayUManager.java src/main/java/alfio/model/transaction/webhook/PayUWebhookPayload.java src/test/java/alfio/manager/payment/PayUManagerLifecycleTest.java src/test/java/alfio/manager/payment/PayUManagerTest.java`.
    4. Check the author and that there are no trailers.

**Acceptance Criteria:**
- The 5 tests pass, and `PayUManagerLifecycleTest` passes 7/7 (all 6 former failures green).
- HEAD is the reworded commit 1, with the red-gate test tracked, and the WIP SHA is still reachable through `backup/feature-payu-wip`.

---

### Task Group 5: Hardening: token cache, timeouts, endpoint override, currency gating
**Dependencies:** 4
**Files to Modify:** `src/main/java/alfio/manager/payment/PayUManager.java`, `src/test/java/alfio/manager/payment/PayUManagerTest.java`
**Estimated Steps:** 7

- [x] 5.0 Complete M5/M6 hardening, the test endpoint hook and I3 gating
  - [x] 5.1 Write 7 tests:
    - the token is cached across two `doPayment` calls (one OAuth request);
    - a different client secret → a new OAuth request;
    - a 401 on an API call → the cache key is invalidated and the call retried once with a fresh token (exactly 2 OAuth requests);
    - a missing `expires_in` → no NPE, 300 s used;
    - every captured `HttpRequest` (OAuth included) has `timeout() == 10 s`;
    - with the 6-arg constructor given `http://localhost:1234`, every request URI starts with it;
    - `isActive`: a PLN purchase context → true, a non-supported currency (e.g. `JPY`) → false, no purchase context → no gating.
  - [x] 5.2 Token cache: a Caffeine `Cache<String, AccessToken>`, following the precedent in `MollieWebhookPaymentManager` L112–114.
    - The key is endpoint + clientId + SHA-256 hex of the secret.
    - An entry is used only while `expiresAt` (minus 60 s) is after now on `clockProvider`.
    - `AccessToken` is a private record.
  - [x] 5.3 Add a single private send helper that, on HTTP 401, invalidates the key and retries once. All PayU API calls (GET/POST/PUT/DELETE) go through it.
  - [x] 5.4 Every `HttpRequest.newBuilder()` gets `.timeout(Duration.ofSeconds(10))`. `HttpTimeoutException`/`IOException` map to the caller's ERROR handling (§2 pending, §3 error, refund re-read). The shared `HttpClient` bean is not touched.
  - [x] 5.5 Constructors:
    - Keep the public 5-arg constructor (it delegates with `null`).
    - Add a 6-arg constructor annotated `@Autowired`, taking `@Value("${alfio.payu.endpoint-override:}") String endpointOverride`. When it is non-blank, it replaces SANDBOX_ENDPOINT and LIVE_ENDPOINT, and a WARN is logged at construction.
  - [x] 5.6 `isActive`: add the currency gating when `paymentContext.getPurchaseContext()` is non-null, using `SUPPORTED_CURRENCIES` and if/else only.
  - [x] 5.7 Run: `./gradlew test --tests 'alfio.manager.payment.PayU*'` (all PayU tests stay green).

**Acceptance Criteria:**
- The 7 tests pass. The red gate is still 7/7, and `PayUManagerLifecycleTest` still compiles against the 5-arg constructor.
- No request in `PayUManager` is built without a timeout.

---

### Task Group 6: Refunds, payment info and signature verification tests
**Dependencies:** 5
**Files to Modify:** `src/main/java/alfio/manager/payment/PayUManager.java`, `src/test/java/alfio/manager/payment/PayUManagerTest.java`, `src/test/java/alfio/manager/payment/payu/PayUSignatureTest.java`
**Estimated Steps:** 5

- [x] 6.0 Complete M8 refunds and `getInfo`, test `PayUSignature`, and make commit 2
  - [x] 6.1 Write 8 tests.
    - **`PayUSignatureTest` (5)**:
      - an MD5 vector and a SHA-256 vector, computed independently (e.g. `printf '%s%s' "$body" "$key" | openssl dgst -sha256`) and recorded as literals;
      - an upper-case hex digest is accepted;
      - a wrong second key is rejected;
      - a malformed or missing header is rejected.
    - **`PayUManagerTest` (3)**:
      - the refund POST body carries `extRefundId` = `<rid>-refund-<amountCents>-<k+1>` when GET refunds lists k entries, and `amount` only when the refund is partial;
      - a refund POST timeout followed by a re-read listing that `extRefundId` → true, and a failure of the initial GET → false with no POST;
      - `getInfo` refundedAmount = the sum of FINALIZED refunds, formatted with `MonetaryUtil.formatCents`, or null when there are none, with paidAmount taken from `totalAmount`.
  - [x] 6.2 Implement the idempotent refund per spec §1 M8 steps 1–5.
  - [x] 6.3 Implement `getInfo`: paidAmount/currency from the order, refundedAmount from GET `/api/v2_1/orders/{id}/refunds` (FINALIZED only), fee and platformFee null.
  - [x] 6.4 Run: `./gradlew test --tests 'alfio.manager.payment.PayU*' --tests 'alfio.manager.payment.payu.*'`.
  - [x] 6.5 Run `./gradlew licenseFormat`, then the ternary check. Make commit 2, "Harden PayU token cache, timeouts, validation and refunds", with `-- src/main/java/alfio/manager/payment/PayUManager.java src/test/java/alfio/manager/payment/PayUManagerTest.java src/test/java/alfio/manager/payment/payu/PayUSignatureTest.java`.

**Acceptance Criteria:**
- The 8 tests pass. A retried refund after a timeout is never sent twice under the same `extRefundId`.
- Commit 2 is on `feature/payu`, without trailers.

---

### Task Group 7: Customer IP data flow
**Dependencies:** 6
**Files to Modify:** `src/main/java/alfio/manager/payment/PaymentSpecification.java`, `src/main/java/alfio/util/RequestUtils.java`, `src/main/java/alfio/controller/api/v2/user/ReservationApiV2Controller.java`, `src/main/java/alfio/manager/payment/PayUManager.java`, `src/test/java/alfio/util/RequestUtilsTest.java`, `src/test/java/alfio/manager/payment/PaymentSpecificationTest.java`, `src/test/java/alfio/manager/payment/PayUManagerTest.java`
**Estimated Steps:** 7

- [x] 7.0 Complete M4 (spec §6)
  - [x] 7.1 Write 5 tests:
    - `RequestUtilsTest`: the client IP is the first `X-Forwarded-For` entry, trimmed (`"203.0.113.7, 10.0.0.1"` → `203.0.113.7`).
    - `RequestUtilsTest`: no XFF → `remoteAddr`.
    - `RequestUtilsTest`: both blank → `127.0.0.1`.
    - New `PaymentSpecificationTest`: `withCustomerIp` returns a copy with every field equal plus the IP, and the original is unchanged.
    - `PayUManagerTest`: the order request carries the spec's `customerIp`.
  - [x] 7.2 `PaymentSpecification`:
    - add a nullable `customerIp` with `@Getter`;
    - add a private all-fields constructor;
    - make the existing 18/8/7-arg constructors (including the package-private 7-arg one) delegate with null, keeping their signatures;
    - add a copying `withCustomerIp(String)`.
  - [x] 7.3 Add `RequestUtils.getClientIp(HttpServletRequest)` (XFF first entry → remoteAddr → `127.0.0.1`), using if/else.
  - [x] 7.4 In `ReservationApiV2Controller.confirmOverview` (L311–392), build the spec as today, then `.withCustomerIp(RequestUtils.getClientIp(request))`. No other call site changes.
  - [x] 7.5 `PayUManager.resolveCustomerIp(spec)` uses `spec.getCustomerIp()` when non-blank, else `127.0.0.1`.
  - [x] 7.6 Run: `./gradlew test --tests 'alfio.util.RequestUtilsTest' --tests 'alfio.manager.payment.PaymentSpecificationTest' --tests 'alfio.manager.payment.PayU*'`.
  - [x] 7.7 Run `./gradlew licenseFormat`, then the ternary check. Make commit 3, "Pass the customer IP to payment providers", with this group's 7 paths.

**Acceptance Criteria:**
- The 5 tests pass. All 14 existing constructor call sites compile unchanged.
- Commit 3 is on `feature/payu`.

---

### Task Group 8: PayU notification endpoint
**Dependencies:** 7
**Files to Modify:** `src/main/java/alfio/controller/payment/api/payu/PayUPaymentWebhookController.java`, `src/test/java/alfio/controller/payment/api/payu/PayUPaymentWebhookControllerTest.java`
**Estimated Steps:** 3

- [x] 8.0 Complete FR3 (spec §4, B4)
  - [x] 8.1 Write 6 Mockito tests (mock `TicketReservationManager` and `PurchaseContextManager`, `MockHttpServletRequest`):
    - successful → 200 `OK`;
    - error → 500 with the reason;
    - NOT_RELEVANT → 200;
    - unknown reservation (purchase context empty) → 200 `NOT_RELEVANT` and no `processTransactionWebhook` call;
    - empty body → 400 `NOK`;
    - only the `X-OpenPayU-Signature` header present → that signature is passed through.
  - [x] 8.2 Implement the `@RestController` from the `MolliePaymentWebhookController` template.
    - It is mapped to POST `PayUManager.WEBHOOK_URL_TEMPLATE` and reads the body with `RequestUtils.readRequest`.
    - The signature comes from `OpenPayu-Signature`, falling back to `X-OpenPayU-Signature`.
    - It calls `processTransactionWebhook(body, signature, PaymentProxy.PAYU, Map.of("reservationId", id), new PaymentContext(purchaseContext, reservationId))` and maps the results per the §4 table.
    - An unknown reservation is logged at WARN with the reservationId and, when the body parses with Gson, the orderId.
  - [x] 8.3 Run: `./gradlew test --tests 'alfio.controller.payment.api.payu.PayUPaymentWebhookControllerTest'`.

**Acceptance Criteria:**
- The 6 tests pass. Only ERROR answers 500, and only an empty body answers 400.
- CSRF stays exempt through the existing `/api/payment/webhook/**` rule, with no security config change.
- No commit yet: commit 4 is made in G9.

---

### Task Group 9: PayU return callback
**Dependencies:** 8
**Files to Modify:** `src/main/java/alfio/controller/payment/PayUCallbackController.java`, `src/test/java/alfio/controller/payment/PayUCallbackControllerTest.java`
**Estimated Steps:** 4

- [x] 9.0 Complete FR6 (spec §5, I2/M1)
  - [x] 9.1 Write 6 Mockito tests:
    - `error` present and `cancelPendingPayment` true → `redirect:` to the overview with `paymentCancelled=true`;
    - `error` present, cancel false and force-check empty → the overview with `paymentCancelled=true`;
    - no `error` and force-check successful → `/book`;
    - no `error` and force-check `failed` → the overview with `paymentCancelled=true`;
    - no `error` and force-check pending → `/book`;
    - unknown purchase context → `redirect:/`.
  - [x] 9.2 Implement the `@Controller` from the `SaferpayCallbackController` template.
    - It handles GET `PayUManager.RETURN_URL_TEMPLATE`, with `@RequestParam(required=false) error` and `errorCode`.
    - It uses `purchaseContextManager.findBy`, `ticketReservationManager.cancelPendingPayment`, and `forceTransactionCheck`.
    - Paths are built with `UriComponentsBuilder` from templates, never by concatenating user input.
    - An unknown reservation → `redirect:/{type}/{identifier}`.
  - [x] 9.3 Run: `./gradlew test --tests 'alfio.controller.payment.PayUCallbackControllerTest' --tests 'alfio.controller.payment.api.payu.*'`.
  - [x] 9.4 Run `./gradlew licenseFormat`, then the ternary check. Make commit 4, "Add PayU notification and return endpoints", with the 4 files of G8 and G9.

**Acceptance Criteria:**
- The 6 tests pass. The redirect targets match the §5 table exactly.
- Commit 4 is on `feature/payu`.

---

### Task Group 10: Public frontend PayU option and cancellation notice
**Dependencies:** 4
**Files to Modify:** `frontend/public/src/app/model/event.ts`, `frontend/public/src/app/payment/payu-payment-proxy/payu-payment-provider.ts`, `frontend/public/src/app/payment/payu-payment-proxy/payu-payment-proxy.component.ts`, `frontend/public/src/app/payment/payu-payment-proxy/payu-payment-proxy.component.html`, `frontend/public/src/app/app.module.ts`, `frontend/public/src/app/reservation/payment-method-selector/payment-method-selector.component.html`, `frontend/public/src/app/reservation/overview/overview.component.ts`
**Visual References:**
- mockup: analysis/design-context/ascii/ui-mockups.md
  element: screen:payment-method-selector
  locator: section "Mockup 1 — screen:payment-method-selector" (anchor `#payment-method-selector`, the ASCII frame and the 4 bullets under it)
  acceptance:
  - The PAYU radio is rendered by the existing `@for(methodID of sortedAvailablePaymentMethodIDs)` loop with no radio template change: `input#PAYU`, `label#PAYU-label`, the `fas credit-card` icon, and the label from key `reservation-page.payment-method.payu`.
  - `<app-payu-payment-proxy [proxy] [method] (paymentProvider)="registerCurrentPaymentProvider($event)">` is placed directly after `<app-saferpay-payment-proxy>`.
  - When the panel is selected (`*ngIf="matchProxyAndMethod"`), it shows `h4.text-body-secondary` "PayU", then a `.text-body-secondary` description from key `reservation-page.payment.payu.description`.
  - The existing "Pay <price>" button (`reservation-page.pay`) is unchanged. With PAYU as the only active method, the existing `activePaymentsCount === 1` branch shows the h4 with the icon and label.
**Estimated Steps:** 7

- [x] 10.0 Complete FR10
  - [x] 10.1 Record 4 acceptance checks for this group (`frontend/public` has no `*.spec.ts` harness; behaviour is covered by `PayUPaymentE2ETest` in G15/G18):
    - `npx --yes pnpm@11.1.2 --dir frontend/public run build` exits 0;
    - `grep -n "'PAYU'" frontend/public/src/app/model/event.ts` finds the `StaticPaymentMethodNames`, `PaymentProxy` and details entries;
    - the mockup acceptance items above are self-checked against the template;
    - no ternary is added.
  - [x] 10.2 In `model/event.ts`, add `'PAYU'` to `StaticPaymentMethodNames` and `PaymentProxy`, and `staticPaymentMethodDetails.PAYU = {labelKey: 'reservation-page.payment-method.payu', icon: ['fas','credit-card']}`.
  - [x] 10.3 Create the `payment/payu-payment-proxy/` folder as a copy of `saferpay-payment-proxy`:
    - the provider has `paymentMethodDeferred = false`;
    - `matchProxyAndMethod` is true when `proxy === 'PAYU'`;
    - the template has the h4 "PayU" and the description key;
    - use if/else in the TS.
  - [x] 10.4 Register the component in `app.module.ts` (import and declaration next to Saferpay; `faCreditCard` is already registered at L257). Add the tag in `payment-method-selector.component.html` after `app-saferpay-payment-proxy` (L73–77).
  - [x] 10.5 In `overview.component.ts` `ngOnInit`: when `this.route.snapshot.queryParams['paymentCancelled'] === 'true'`, call `this.feedbackService.showError('reservation-page.payment.cancelled')`. Only that literal value triggers it, no key is read from the URL, and only if/else is used.
  - [x] 10.6 Run `npx --yes pnpm@11.1.2 --dir frontend/public install --frozen-lockfile` if `node_modules` is missing, then `npx --yes pnpm@11.1.2 --dir frontend/public run build`. Then self-check the Visual References acceptance items, and run the ternary check on `git diff -- frontend/public`.
  - [x] 10.7 Make commit 5, "Add PayU option to the public frontend", with this group's 7 paths. Do not commit `frontend/public/node_modules` or `dist`.

**Acceptance Criteria:**
- The production build passes, and all 4 acceptance checks hold.
- The implementation matches each `acceptance` criterion of `screen:payment-method-selector`.
- The "UNKNOWN PAYMENT PAYU" fallback can no longer occur.

---

### Task Group 11: Admin consoles: PayU label, settings panels and icon
**Dependencies:** 4
**Files to Modify:** `src/main/webapp/alfio-admin-v1/js/admin/filter/util.js`, `src/main/webapp/alfio-admin-v1/angular-templates/admin/partials/configuration/system.html`, `src/main/webapp/alfio-admin-v1/angular-templates/admin/partials/configuration/organization.html`, `frontend/admin/src/model/event.ts`, `frontend/admin/src/service/reservation-format.ts`
**Visual References:**
- mockup: analysis/design-context/ascii/ui-mockups.md
  element: screen:admin-payu-settings
  locator: section "Mockup 3 — screen:admin-payu-settings" (anchor `#admin-payu-settings`, the panel frame and the organization-variant bullet)
  acceptance:
  - A Bootstrap 3 `panel panel-default` with `data-ng-if="systemConf.paymentPayu"` is placed directly after the Saferpay panel in `system.html` (~L304).
  - The organization variant uses `organizationConf.paymentPayu` and `organizationConf.delete(config)`, after ~L253 in `organization.html`.
  - The title is `.panel-title` with `fa fa-credit-card` and "PayU".
  - The body is `<setting data-obj="setting" data-display-delete-if-needed … ng-repeat="setting in <conf>.paymentPayu.settings">`, rendering the 6 PAYU_* rows in backend key order (ENABLED, LIVE_MODE, POS_ID, CLIENT_ID, CLIENT_SECRET, SECOND_KEY).
  - `paymentPayu` is added to the `#PAYMENT` header `ng-if` in both files (system ~L203, organization L135).
  - The secrets render as plain text inputs (I6, an accepted deviation from the mockup's dots).
- mockup: analysis/design-context/ascii/ui-mockups.md
  element: component:admin-payment-icon
  locator: section "Mockup 4 — component:admin-payment-icon" (anchor `#admin-payment-icon`, the "NEW" row)
  acceptance:
  - `paymentMethodIcon('PAYU')` returns `credit-card`, through a new `case 'PAYU':` in the existing `credit-card` group of `reservation-format.ts` (L25–36).
  - `alfio-payment-method` renders the icon followed by the raw text `PAYU`, consistent with `SAFERPAY`.
  - `payment-method.ts` is not changed.
**Estimated Steps:** 8

- [x] 11.0 Complete FR11
  - [x] 11.1 Record 3 acceptance checks for this group (no admin unit-test runner; the legacy admin has no build step):
    - `npx --yes pnpm@11.1.2 --dir frontend/admin run build` exits 0 (it includes `tsc`);
    - the panels are self-checked against both mockups' acceptance items;
    - the `AdminConsole.paymentMethodLabel` PAYU text is exercised later by the G15 E2E.
  - [x] 11.2 Read `docs/admin-style-guide.md` (an AGENTS.md requirement before any `frontend/admin` work).
  - [x] 11.3 In `util.js` `PAYMENT_PROXY_DESCRIPTIONS`, add `'PAYU': 'PayU: BLIK, fast transfer, card'`.
  - [x] 11.4 In `system.html`, add the PayU panel after Saferpay, copied from the Saferpay panel, and add `|| systemConf.paymentPayu` to the `#PAYMENT` header `ng-if`.
  - [x] 11.5 In `organization.html`, add the PayU panel after Saferpay with `organizationConf.paymentPayu` / `organizationConf.delete(config)`, and add `|| organizationConf.paymentPayu` to the L135 header `ng-if`.
  - [x] 11.6 New admin: add `'PAYU'` to the `PaymentProxy` union in `frontend/admin/src/model/event.ts` (L61), and add `case 'PAYU':` to the `credit-card` group in `reservation-format.ts`.
  - [x] 11.7 Run `npx --yes pnpm@11.1.2 --dir frontend/admin install --frozen-lockfile` if needed, then `npx --yes pnpm@11.1.2 --dir frontend/admin run build`. Review the change against `docs/admin-style-guide.md` (reuse, no ternaries, Bootstrap Icons name), and self-check both mockups' acceptance items.
  - [x] 11.8 Make commit 6, "Show PayU in the admin consoles", with this group's 5 paths.

**Acceptance Criteria:**
- The admin build passes, and the style-guide review finds no violations.
- The implementation matches each `acceptance` criterion of `screen:admin-payu-settings` and `component:admin-payment-icon`.

---

### Task Group 12: English PayU translations
**Dependencies:** 4
**Files to Modify:** `src/main/resources/alfio/i18n/public.properties`
**Estimated Steps:** 4

- [x] 12.0 Complete FR12 (EN part, spec §9)
  - [x] 12.1 Write 2 shell checks (they are not committed):
    - each of the 4 keys appears exactly once: `for k in reservation-page.payment-method.payu reservation-page.payment.payu.description error.STEP2_PAYU_unexpected reservation-page.payment.cancelled; do test "$(grep -c "^$k=" src/main/resources/alfio/i18n/public.properties)" = 1 || echo "MISSING $k"; done`;
    - the description and error values contain `''` (doubled apostrophe) and the file is pure ASCII: `LC_ALL=C grep -n '[^ -~]' …` finds nothing in the added lines.
  - [x] 12.2 Add the 4 keys exactly as given in spec §9, in `key=value` form, next to the matching Saferpay keys (around L486). Edit no other language file.
  - [x] 12.3 Run both checks (they must print nothing).
  - [x] 12.4 Make commit 7, "Add English PayU translations", with `-- src/main/resources/alfio/i18n/public.properties`.

**Acceptance Criteria:**
- Both checks pass, the 4 EN keys are present, and `public_pl.properties` is untouched on `feature/payu`.

---

### Task Group 13: PayU reservation flow integration test (WireMock)
**Dependencies:** 9
**Files to Modify:** `src/test/java/alfio/controller/api/v2/user/reservation/PayUReservationFlowIntegrationTest.java`
**Estimated Steps:** 5

- [x] 13.0 Complete the backend integration proof (spec test group 11)
  - [x] 13.1 Write 3 tests in a new `@AlfioIntegrationTest` class extending `BaseReservationFlowTest` (template: `StripeReservationFlowIntegrationTest`):
    - **Basic flow**: `confirmOverview` → the redirect URL, and the reservation is EXTERNAL_PROCESSING_PAYMENT. A signed COMPLETED notification is POSTed to `PayUPaymentWebhookController` → 200. The transaction becomes COMPLETE and the reservation COMPLETE, and the order summary is checked.
    - **Return with `error=501`**: WireMock receives a DELETE, and the reservation is back to PENDING.
    - **Signed refund notification after COMPLETE** → 200.
  - [x] 13.2 Fixture:
    - a WireMock 3.13.2 server on a dynamic port, with `@DynamicPropertySource` registering `alfio.payu.endpoint-override`;
    - stubs for `/pl/standard/user/oauth/authorize`, POST `/api/v2_1/orders` (302 + JSON with `redirectUri`/`orderId`), GET `/api/v2_1/orders/{id}`, DELETE and GET refunds;
    - PAYU_* system-level configuration inserted, and the CHF event from `IntegrationTestUtil.initEvent` with `acceptedPaymentProxies` = PAYU;
    - signatures computed with `PayUSignature` and a test second key.
  - [x] 13.3 Run: `./gradlew test --tests 'alfio.controller.api.v2.user.reservation.PayUReservationFlowIntegrationTest'` (TestContainers, so Docker is needed).
  - [x] 13.4 Run the neighbour regressions: `./gradlew test --tests 'alfio.manager.TicketReservationManagerTest' --tests '*PaymentMethodDeserializationTest'`.
  - [x] 13.5 Run `./gradlew licenseFormat`, then make commit 8, "Add PayU reservation flow integration test", with this file only. `descriptor.json` follows in G18.

**Acceptance Criteria:**
- The 3 integration tests pass, and the neighbour tests still pass.
- The WireMock journal shows no second POST `/orders` within one flow.

---

### Task Group 14: Test Review & Gap Analysis
**Dependencies:** 1, 2, 3, 4, 5, 6, 7, 8, 9, 13
**Files to Modify:** `src/test/java/alfio/manager/payment/PayUManagerTest.java`, `src/test/java/alfio/controller/payment/PayUCallbackControllerTest.java`, `src/test/java/alfio/controller/payment/api/payu/PayUPaymentWebhookControllerTest.java`, `src/test/java/alfio/controller/api/v2/user/reservation/PayUReservationFlowIntegrationTest.java`
**Estimated Steps:** 5

- [x] 14.0 Review and fill critical gaps
  - [x] 14.1 Review the 62 tests from G1–G9 and G13 against the spec Success Criteria and the §2/§3/§4/§5 tables.
  - [x] 14.2 Analyse the gaps for this feature only. Likely candidates:
    - the POST `/orders` timeout → `failed` (L9);
    - a PUT capture failure in the WAITING row;
    - a 401 during DELETE;
    - the `forceTransactionCheck` CANCELED path in the callback;
    - an `OpenPayu-Signature` header with `algorithm=MD5`.
  - [x] 14.3 Write up to 10 additional strategic tests. Add no tests for areas outside PayU.
  - [x] 14.4 Run only the feature tests: `./gradlew test --tests 'alfio.manager.payment.PayU*' --tests 'alfio.manager.payment.payu.*' --tests 'alfio.controller.payment.PayUCallbackControllerTest' --tests 'alfio.controller.payment.api.payu.*' --tests 'alfio.util.RequestUtilsTest' --tests 'alfio.manager.payment.PaymentSpecificationTest' --tests 'alfio.controller.api.v2.user.reservation.PayUReservationFlowIntegrationTest'`.
  - [x] 14.5 If any test was added: run `./gradlew licenseFormat` and make the commit "Cover remaining PayU edge cases in tests" with only the touched test files. Otherwise make no commit.

**Acceptance Criteria:**
- All feature tests pass (about 69–79 with the red gate). No more than 10 tests are added.

---

### Task Group 15: Sandbox E2E environment and PayU E2E test
**Dependencies:** 10, 11, 12, 14
**Files to Modify:** `src/test/java/alfio/e2e/BaseE2ETest.java`, `src/test/java/alfio/e2e/AdminConsole.java`, `src/test/java/alfio/e2e/ReservationFlow.java`, `src/test/java/alfio/e2e/PayUPaymentE2ETest.java`, `custom.jvmargs` (gitignored, never committed)
**Estimated Steps:** 8

- [x] 15.0 Complete the FR14 E2E with a live sandbox run
  - [x] 15.1 Write `PayUPaymentE2ETest` (2 tests, `requiredPaymentProxies()` = PAYU only, `currency()` → `"PLN"`, template `CreditCardPaymentE2ETest`):
    - **Test 1**: a PLN purchase pays on the PayU sandbox page, returns, `waitForPaymentConfirmation()` runs (force-check), and the confirmation page is shown.
    - **Test 2**: the buyer abandons on the PayU page (`error=501`) → the overview shows the `reservation-page.payment.cancelled` toast, and the method can be chosen again.
  - [x] 15.2 `BaseE2ETest`: add `protected String currency()` returning `"CHF"`, used by the private `eventDefinition()`. The other E2E tests keep CHF.
  - [x] 15.3 `AdminConsole.paymentMethodLabel`: add `case PAYU -> "PayU: BLIK, fast transfer, card"`.
  - [x] 15.4 `ReservationFlow`: add a PayU step:
    1. select `#PAYU`;
    2. accept the terms and submit;
    3. on the sandbox page, choose the test method (test card or a pay-by-link test bank) and confirm;
    4. wait for the redirect back.
    Also add an abandon step. Selectors are confirmed live in 15.7.
  - [x] 15.5 Set up a dedicated database. Pick a free port: `for p in 55432 55433 55434; do lsof -nP -iTCP:$p -sTCP:LISTEN >/dev/null || { echo $p; break; }; done`. Then run `docker run -d --name alfio-payu-e2e-pg -e POSTGRES_PASSWORD=password -e POSTGRES_DB=alfio -p <port>:5432 postgres:16`. Never touch the :8090 container or the :5432 DB.
  - [x] 15.6 Start the server:
    1. Write `custom.jvmargs` (gitignored) with `-Ddatasource.url=jdbc:postgresql://localhost:<port>/alfio`, `-Ddatasource.username=postgres`, `-Ddatasource.password=password`, and `-Dserver.port=<free port, e.g. 8181>`.
    2. Start `./gradlew bootRun` in the background and wait for "Started".
    3. Get the admin credentials from the startup log.
    4. Configure at system level: `BASE_URL` = `http://localhost:<server port>`, and PAYU_ENABLED/LIVE_MODE=false/POS_ID 300746/CLIENT_ID/CLIENT_SECRET/SECOND_KEY with the public PayU sandbox credentials from the PayU developer docs.
    5. Export `E2E_SERVER_URL`, `E2E_ADMIN_USERNAME` and `E2E_ADMIN_PASSWORD`.
  - [x] 15.7 Run `./gradlew e2eTest --tests 'alfio.e2e.PayUPaymentE2ETest' -Pverbose --console=plain`. Fix the selectors and the test payment choice until both tests pass.
    - While doing so, record the sandbox findings for the risks: DELETE on a PENDING order, IPv6 `customerIp`, the `localhost` notifyUrl, `DATA_NOT_FOUND`.
    - If the sandbox refuses DELETE on PENDING or allows funds after a cancel, apply the spec fallback (restrict the cancel to NEW) in `PayUManager`, add a test, re-run the G1–G9 PayU tests, and commit it as "Restrict PayU remote cancel to new orders".
  - [x] 15.8 Stop `bootRun` and leave the DB container for G18. Run `./gradlew licenseFormat`, then the ternary check. Make commit 9, "Add PayU sandbox E2E test", with the 4 `src/test/java/alfio/e2e/*` paths (never `custom.jvmargs`).

**Acceptance Criteria:**
- Both E2E tests pass against the sandbox from the local server, and the sandbox findings are recorded for the work log.
- `./gradlew compileTestJava` is green, and the other E2E classes still default to CHF.

---

### Task Group 16: Polish translations on `fix/pl-translations`
**Dependencies:** None
**Files to Modify:** `/Users/rafalnowak/Dev/alf.io-worktrees/pl-translations/src/main/resources/alfio/i18n/public_pl.properties`
**Estimated Steps:** 5

- [x] 16.0 Complete FR12 (PL part, spec §9), fork-only
  - [x] 16.1 Create the worktree: `git -C /Users/rafalnowak/Dev/alf.io worktree add -b fix/pl-translations /Users/rafalnowak/Dev/alf.io-worktrees/pl-translations main`.
  - [x] 16.2 Write 2 checks as a scratchpad script (it is not committed):
    - **(a) Key coverage**: collect the translation keys referenced in the purchase-path code in `frontend/public/src/app/{event-display,event-dates,ticket-quantity-selector,item-card,category-sale-period,price-tag,additional-service*,stepper,countdown,shared,payment,subscription-display}` and `reservation/{booking,overview,payment-method-selector,processing-payment,success,summary-table,ticket-form,invoice-form,offline-payment,expired-notification,cancel-reservation}`. Use the `'…' | translate` pipe, `translate="…"` and string-literal keys in `.ts` (including `error.STEP2_*`). Add the 4 PayU keys, `common.confirm`, `event-days.from`/`to` and `subscription.detail.validity.CUSTOM.from`/`to`. Keep the keys present in `public.properties`, and print those missing from `public_pl.properties`.
    - **(b) Validity**: the file parses as UTF-8 (`iconv -f UTF-8 -t UTF-8 >/dev/null`), every key is unique, and it is sorted with `key = value` formatting.
  - [x] 16.3 Add the Polish values for every missing key:
    - `reservation-page.payment-method.payu = PayU: BLIK, szybki przelew, karta`;
    - the description, `error.STEP2_PAYU_unexpected` and `reservation-page.payment.cancelled` in Polish;
    - placeholders (`{0}`) preserved, and doubled apostrophes where a placeholder exists.
    Touch no other file.
  - [x] 16.4 Run both checks: (a) prints nothing, (b) passes.
  - [x] 16.5 Make the commit "Add missing Polish translations for the purchase path and PayU" in the worktree (`git -C <wt> add … && git -C <wt> commit -m … -- src/main/resources/alfio/i18n/public_pl.properties`). Check that there are no trailers.

**Acceptance Criteria:**
- Both checks pass. The branch has exactly one commit on top of `main`, touching only `public_pl.properties`.

---

### Task Group 17: Stepper active badge colour on `fix/stepper-active-color`
**Dependencies:** None
**Files to Modify:** `/Users/rafalnowak/Dev/alf.io-worktrees/stepper-active-color/frontend/public/src/app/stepper/stepper.component.scss`
**Visual References:**
- mockup: analysis/design-context/ascii/ui-mockups.md
  element: component:stepper-active-badge
  locator: section "Mockup 2 — component:stepper-active-badge" (anchor `#stepper-active-badge`, the BEFORE/AFTER lines)
  acceptance:
  - `.wizard2 .wizard2-current .badge { background-color: var(--bs-primary); }` replaces `#007ACC` (L44–46).
  - The markup in `stepper.component.html` is unchanged.
  - The completed steps stay `text-bg-success` and the upcoming steps stay grey `.badge`.
  - No other SCSS rule is changed.
**Estimated Steps:** 5

- [x] 17.0 Complete FR13
  - [x] 17.1 Create the worktree: `git -C /Users/rafalnowak/Dev/alf.io worktree add -b fix/stepper-active-color /Users/rafalnowak/Dev/alf.io-worktrees/stepper-active-color main`.
  - [x] 17.2 Write 2 checks:
    - `grep -c '#007ACC' frontend/public/src/app/stepper/stepper.component.scss` = 0 and `grep -c 'var(--bs-primary)'` ≥ 1 in that rule;
    - the production build of the worktree's public frontend exits 0.
  - [x] 17.3 Replace `#007ACC` with `var(--bs-primary)` in `.wizard2 .wizard2-current .badge`.
  - [x] 17.4 Run the checks: `npx --yes pnpm@11.1.2 --dir /Users/rafalnowak/Dev/alf.io-worktrees/stepper-active-color/frontend/public install --frozen-lockfile && npx --yes pnpm@11.1.2 --dir /Users/rafalnowak/Dev/alf.io-worktrees/stepper-active-color/frontend/public run build`, plus the grep. Then self-check the Visual References acceptance.
  - [x] 17.5 Make the commit "Use the theme primary colour for the active stepper badge" with only the SCSS path (no `node_modules`/`dist`). Check that there are no trailers.

**Acceptance Criteria:**
- Both checks pass, and the implementation matches each `acceptance` criterion of `component:stepper-active-badge`.
- One commit is on top of `main`.

---

### Task Group 18: API descriptor, full build and E2E proof on `feature/payu`
**Dependencies:** 15
**Files to Modify:** `src/test/resources/api/descriptor.json`, `src/test/java/alfio/controller/api/CheckRestApiStability.java` (temporary flag, reverted), `.maister/tasks/development/2026-11-07-payu-payment-provider/verification/e2e-test.log`
**Estimated Steps:** 6

- [x] 18.0 Complete the FR14 proofs on `feature/payu`
  - [x] 18.1 Regenerate the descriptor (L5):
    1. Set `updateDescriptor = true` at `CheckRestApiStability.java` L74.
    2. Run `./gradlew test --tests 'alfio.controller.api.CheckRestApiStability'`.
    3. Revert the flag to `false` (`git diff --exit-code -- src/test/java/alfio/controller/api/CheckRestApiStability.java` must be clean).
    4. Re-run the same test, which must be green.
  - [x] 18.2 Review `git diff src/test/resources/api/descriptor.json`: only the PayU webhook and return endpoints, and any enum additions, change. Make the commit "Update API descriptor for PayU endpoints" with only `descriptor.json`.
  - [x] 18.3 Run the full build: `./gradlew build -Dpgsql.version=16`, which includes the license check, forbiddenApis, `CheckRestApiStability`, the red gate and all tests. Fix regressions at the root and commit any fix to the group that owns the file.
  - [x] 18.4 Produce the E2E proof:
    1. Restart `./gradlew bootRun` with the G15 `custom.jvmargs` (same DB container).
    2. Run `./gradlew e2eTest --tests 'alfio.e2e.PayUPaymentE2ETest' -Pverbose --console=plain 2>&1 | tee /Users/rafalnowak/Dev/alf.io/.maister/tasks/development/2026-11-07-payu-payment-provider/verification/e2e-test.log`.
    3. Both tests must pass.
  - [x] 18.5 Check the log for credentials: `grep -nEi 'secret|password|client_secret|second.?key|<client secret value>|<second key value>' e2e-test.log` must find no credential value. Redact any hit and re-check.
  - [x] 18.6 Tear down: stop `bootRun` and `docker rm -f alfio-payu-e2e-pg`. Delete `custom.jvmargs`, or restore it if it existed before G15. `git status --short` on `feature/payu` must show nothing outside `.maister/`.

**Acceptance Criteria:**
- `./gradlew build -Dpgsql.version=16` is green, and `CheckRestApiStability` is green with the flag back at `false`.
- `e2e-test.log` shows 2 passing PayU E2E tests and no credentials.
- The `feature/payu` history is the 9 spec commits, the optional gap-test commit, an optional sandbox fallback commit and the descriptor commit, all without trailers.

---

### Task Group 19: `plo-pl` integration branch, Docker image and remote publishing
**Dependencies:** 16, 17, 18
**Files to Modify:** `README.md` (on `plo-pl` only), `src/test/resources/api/descriptor.json` (on `plo-pl`, only if regeneration changes it), `.maister/**` (force-added on `plo-pl` only)
**Estimated Steps:** 9

- [x] 19.0 Complete FR15 and publish all branches
  - [x] 19.1 Record `git rev-parse origin/feature/payu` (the lease value). In the main worktree, with no tracked changes, run `git switch -c plo-pl main`.
  - [x] 19.2 Merge in this order with `git merge --no-edit`: `feature/payu`, `fix/mollie-ignore-unsupported-methods`, `fix/pl-translations`, `fix/stepper-active-color`. Resolve any conflict by keeping both sides' intent, and record each conflict in the work log.
  - [x] 19.3 Re-run the descriptor procedure from 18.1 on `plo-pl`. Commit "Update API descriptor after merging fork branches" only if `descriptor.json` changed, and confirm the flag is reverted.
  - [x] 19.4 README: add the section "Building the Docker image (plo-pl fork)". It gives the command `./gradlew distribution -x test && docker build -t alfio-plo-pl:payu-$(date +%Y%m%d) ./build/dockerize` and states the Docker/Podman requirement. Commit it as "Document Docker image build".
  - [x] 19.5 Run the full build on `plo-pl`: `./gradlew build -Dpgsql.version=16` must be green.
  - [~] 19.6 DEFERRED to Phase 14 finalization (orchestrator override): Run `git add -f .maister/`, check that `git diff --cached --stat` lists only `.maister/` paths, then commit "Add maister task records for the PayU work".
  - [x] 19.7 Build the image:
    1. `./gradlew distribution -x test`.
    2. `docker build -t alfio-plo-pl:payu-$(date +%Y%m%d) ./build/dockerize`.
    3. `docker image ls alfio-plo-pl` shows the tag.
  - [~] 19.8 DEFERRED to Phase 14 finalization (orchestrator override): Push, in this order:
    1. `git push origin backup/feature-payu-wip`.
    2. `git push --force-with-lease=feature/payu:<recorded sha> origin feature/payu`.
    3. `git push origin fix/mollie-ignore-unsupported-methods`.
    4. `git push -u origin fix/pl-translations fix/stepper-active-color plo-pl`.
    5. Check every branch with `git ls-remote origin`.
    Open no pull requests, and push nothing to `upstream`.
  - [~] 19.9 PARTIAL (check 1 done; worktree removal deferred to Phase 14): Final checks and cleanup:
    1. `for b in feature/payu fix/pl-translations fix/stepper-active-color plo-pl; do git log main..$b --format='%h %an%n%B' | grep -nEi 'co-authored|claude|generated with'; done` prints nothing, and every author is `rafal-nowak`.
    2. `git worktree remove` both worktrees.
    3. `git switch feature/payu` (the task folder stays git-excluded there).

**Acceptance Criteria:**
- `plo-pl` = main + the 4 merged branches + the README section + `.maister/`, and its full build is green.
- The Docker image `alfio-plo-pl:payu-<yyyymmdd>` exists.
- All five branches and the backup ref are on origin, with no AI trailers and no PRs.

## Execution Order

1. Group 1: PayU order creation and reservation validity (6 steps)
2. Group 2: Re-confirm state matrix (6 steps, depends on 1)
3. Group 3: Status mapping, discard, server-initiated (6 steps, depends on 2)
4. Group 4: Notification parsing + commit 1 (7 steps, depends on 3)
5. Group 5: Hardening (7 steps, depends on 4)
6. Group 6: Refunds, info, signature tests + commit 2 (5 steps, depends on 5)
7. Group 7: Customer IP data flow + commit 3 (7 steps, depends on 6)
8. Group 8: Notification endpoint (3 steps, depends on 7)
9. Group 9: Return callback + commit 4 (4 steps, depends on 8)
10. Group 10: Public frontend + commit 5 (7 steps, depends on 4)
11. Group 11: Admin consoles + commit 6 (8 steps, depends on 4)
12. Group 12: EN translations + commit 7 (4 steps, depends on 4)
13. Group 13: WireMock integration test + commit 8 (5 steps, depends on 9)
14. Group 14: Test Review & Gap Analysis (5 steps, depends on 1–9, 13)
15. Group 15: Sandbox E2E environment and test + commit 9 (8 steps, depends on 10, 11, 12, 14)
16. Group 16: PL translations on `fix/pl-translations` (5 steps, no dependencies)
17. Group 17: Stepper colour on `fix/stepper-active-color` (5 steps, no dependencies)
18. Group 18: Descriptor, full build, E2E proof (6 steps, depends on 15)
19. Group 19: `plo-pl`, Docker image, push (9 steps, depends on 16, 17, 18)

## Standards Compliance

There is no `.maister/docs/standards/` directory in this project. The binding standards are:
- **`AGENTS.md`**:
  - no ternaries;
  - Lombok allow-list;
  - the GPL header via `./gradlew licenseFormat`;
  - no Jackson 2 imports (forbiddenApis);
  - EN keys in the base properties on PR-able branches, PL only on `fix/pl-translations`;
  - read `docs/admin-style-guide.md` before `frontend/admin` work;
  - commits without AI trailers.
- **`CONTRIBUTING.md`**: an integration test for the backend change (G13), an E2E for the frontend change (G15) with `requiredPaymentProxies()` = PAYU only, and `e2e-test.log` without credentials (G18).
- **`docs/admin-style-guide.md`**: reuse `alfio-payment-method` and use Bootstrap Icons names (G11).
- **The spec's code rules** (§1): Gson for PayU JSON, clock-based timestamps, hand-written nested types instead of Lombok builders.

## Notes

- **Test-driven**: each implementation group starts by writing its tests (or, for the frontend, its recorded checks).
- **Run incrementally**: after each group, run only the commands listed in that group. The full build runs only in G18 and G19.
- **Mark progress**: check off steps as they are completed. The `implementation-plan.html` companion carries matching `data-group`/`data-step` markers.
- **Reuse first**:
  - the Saferpay templates (proxy component, callback controller, admin panel);
  - the Mollie templates (webhook controller, Caffeine cache, validity alignment);
  - the Stripe flow test;
  - `CreditCardPaymentE2ETest`.
- **Out of scope**: no change to `TicketReservationManager`, `PaymentManager`, the shared `HttpClient` bean or the Flyway scripts.
