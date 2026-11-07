# Pragmatic Review: PayU payment provider

## TL;DR
Status: **Appropriate, with minor trims** (0 critical, 3 warning, 8 info).
`PayUManager` has 1093 LOC, compared with 403 for Saferpay and 682 for Mollie. Most of the extra size is the money-safety lifecycle: the tri-state read, cancel-then-confirm, completion validation and the 401 retry. Each of those mechanisms has a spec rule or a sandbox finding behind it, so it earns its place.
The weak spots: the "deterministic, retry-safe" `extRefundId` does not deliver what it claims; `fix/pl-translations` goes against the upstream rule that translations are POEditor-only; and the cancel polling adds wall-clock sleeps both under the reservation lock and in the unit tests.
Nothing blocks the merge. About 60 to 80 LOC could be removed with no loss of safety.

## Key Decisions
- The order lifecycle (tri-state `retrieveOrder`, `cancelOrder` with a re-read, "never `failed` once an order may be payable") is **kept as justified**. Core deletes the PENDING transaction on `failed`, and the sandbox showed that DELETE on a COMPLETED order answers SUCCESS (work-log L134). The re-read is therefore needed for correctness.
- The bounded cancel polling (5 reads, 500 ms apart) is **kept**. E2E found the regression it fixes (commit 6475c6ecb: the sandbox applies DELETE asynchronously).
- The separate NEW mapping for webhooks and force-checks (`newOrderResult` parameter) is **kept**. It is the smallest way to avoid both STUCK and TRANSACTION_INITIATED, and it is documented in the spec.
- The refund idempotency scheme is **flagged** (W1): it adds a mandatory GET and a failure mode, but does not protect against an operator retry.
- `fix/pl-translations` is **flagged** (W2) for upstream-PR-ability only. It is fine for the fork.

## Open Questions / Risks
- **Lock duration has grown since the spec's estimate.** The spec risk section estimates about 5 calls, roughly 50 s. The worst case is now OAuth + GET + DELETE + up to 5 GET re-reads (with 2 s of sleep) + POST, plus 401 retries: about 9 or more calls at 10 s each, so over 90 s under the reservation row lock. In practice the calls are fast, but the "reverse proxy > 60 s" recommendation in the spec is now too low.
- **Is a double refund by an operator actually prevented?** See W1. It depends on whether alf.io core or the admin UI already blocks a second refund. If it does, the counter scheme can be simplified away.

---

## 1. Complexity Assessment

| Provider | Main class LOC | Unit test LOC | Capabilities |
|---|---|---|---|
| Saferpay | 403 | 156 | PaymentProvider, WebhookHandler, RefundRequest, PaymentInfo |
| Mollie (webhook) | 682 | n/a | + cache, reuse of the existing payment |
| Stripe (webhook) | 548 | n/a | + ServerInitiatedTransaction |
| **PayU** | **1093** | **1185 + 214 (lifecycle) + 561 (IT)** | all five, including ServerInitiatedTransaction |

The project is a mature open-source product, and the branch must be acceptable as an upstream PR. That puts it on the production scale. Money correctness is a hard requirement there (spec Goal: never double-charge, no payable orphan orders, no endless retries).

Where the size comes from:
- About 250 LOC: the lifecycle. Re-confirm matrix, `checkOrderStatus`, `discardTransaction`, `cancelOrder` with polling, `completeTransaction`. **Justified.**
- About 150 LOC: HTTP and OAuth plumbing. Token cache, 401 retry, `sendAuthorized`/`authorizedRequest`, timeouts. **Justified.** Saferpay and Mollie have equivalents spread across helpers.
- About 130 LOC: refunds and `getInfo`, including the refund counter and re-read. **Partly justified** (see W1).
- About 120 LOC: Gson null-safe helpers, payload classification, IP normalization, the error token. **Justified.** IP normalization fixed a real sandbox rejection.
- About 100 LOC: Javadoc and comments, which are useful here.

Overall complexity: **Medium-High, appropriate for the problem.** There are no new infrastructure components, no new abstraction layers, and no new DB migration. Everything stays in one class next to the existing providers. Reusing the existing patterns (Caffeine as in Mollie, the `@Controller` redirect as in Saferpay, Gson as in Saferpay and Mollie) is a strength.

## 2. Key Issues Found

### Critical
None.

### Warning

**W1. The refund idempotency is more complex than the guarantee it gives**
- Location: `PayUManager.java` L667–726 (`refund`, `refundExists`), and the spec's "Refund idempotency" key decision.
- Problem: `extRefundId = <reservationId>-refund-<amountCents>-<k+1>`, where k is the number of refunds listed before the POST. The safety comes entirely from the **in-call** re-read after an `IOException`. Determinism adds nothing across calls. If a timed-out refund actually landed, the operator's retry sees k+1 refunds, computes `-k+2`, and PayU accepts it as a new refund. That is a double refund, which the spec claims cannot happen ("a retry by the operator does not double-refund"). If the earlier refund did not land, any id works.
- Cost: a mandatory extra GET before every refund, and a new failure mode ("cannot read refunds → refund refused") that a random id would not have.
- Real benefit: it does deduplicate two **concurrent** refunds of the same amount computed from the same k (PayU rejects the duplicate `extRefundId`). That is narrow.
- Suggestion: either (a) keep the scheme, but correct the Javadoc and the spec so they claim only in-call recovery and concurrent-duplicate protection; or (b) simplify to a random id (`<reservationId>-refund-<8 hex>`, the same generator as `extOrderId`) and keep the re-read on timeout. Option (b) removes the pre-GET and about 6 LOC, and keeps the same real guarantee. Either way, operator double-refunds after an ambiguous timeout are a residual risk. Return `false` with a clear log line ("check the PayU panel before retrying"), which the code nearly does already.
- Effort: about 15 min (doc fix) or about 30 min (simplify and adjust 2 tests).

**W2. `fix/pl-translations` goes against the upstream translation rule**
- Location: branch `fix/pl-translations`, `src/main/resources/alfio/i18n/public_pl.properties` (+67 lines).
- Problem: AGENTS.md says: "add the new keys to the English files … and leave the other languages to POEditor." The branch edits `public_pl.properties` directly, and roughly 60 of the keys are unrelated to PayU (invoice fields, my-profile, Italian e-invoice keys). It also adds `error.STEP2_PAYU_unexpected` in PL. Merged alone, that key belongs to a provider that does not exist upstream yet.
- Impact: none for the reunion fork. As an upstream PR it would probably be rejected or overwritten by the next POEditor sync.
- Suggestion: keep the branch fork-only (merged into `plo-pl`). If upstream contribution is wanted, submit the PL strings through POEditor instead of a PR. Do not reference it from the `feature/payu` PR.
- Effort: none (a process decision).

**W3. The cancel polling sleeps in real time, under the lock and in the unit tests**
- Location: `PayUManager.java` L104–105 and L819–831; tests `discardTransactionWaitsForAnAcceptedCancellationToBeApplied` and `discardTransactionGivesUpWhenAnAcceptedCancellationIsNeverApplied` (`PayUManagerTest` L674–694).
- Problem: `Thread.sleep(CANCEL_CONFIRMATION_PAUSE)` is a hard-coded constant. The "gives up" test sleeps 4 × 500 ms = 2 s of wall clock on every run, and the "waits" test sleeps a further 0.5–1.5 s. In production, the sleeps run inside `performPayment` with the reservation row lock held (see Risks).
- The mechanism itself is justified (sandbox finding, commit 6475c6ecb). The DX cost is the issue.
- Suggestion: make the pause a constructor-injected or package-private `Duration` field (the class already has a test-only constructor), and use `Duration.ZERO` in unit tests. Optionally reduce the reads to 3 if the sandbox usually settles on the second read. Update the spec's lock-duration risk.
- Effort: about 20 min.

### Info

**I1. The webhook controller re-parses the JSON only for a log line.** `PayUPaymentWebhookController.extractOrderId` (about 25 LOC) duplicates the manager's Gson navigation so that it can log the orderId for an unknown reservation. Logging `reservationId` plus the body length, or the first 200 characters of the body, is enough to debug. Suggestion: remove it (about −25 LOC and one test case). Low priority, because it is harmless.

**I2. Two layers of loopback fallback for `customerIp`.** `RequestUtils.getClientIp` already returns `127.0.0.1` when nothing is found, and `PayUManager.resolveCustomerIp` falls back to `127.0.0.1` again (`DEFAULT_CUSTOMER_IP`). The manager-side fallback is still needed for specs built outside the controller (for example the admin or `PaymentSpecification` without the wither), so keep it. The helper's own fallback could return `null`, which keeps the choice in one place. Cosmetic.

**I3. Test-only production constructor.** The public 5-arg `PayUManager` constructor exists only because the red-gate test calls it (spec §1). Once the red-gate tests are green and owned by the branch, the tests could call the 6-arg constructor with `null`, and the extra constructor and its delegation (about 8 LOC) could go. The same applies to keeping the package-private 7-arg `PaymentSpecification` constructor stable "for the red-gate test". Defer: it is low value, and upstream reviewers may prefer it as is.

**I4. Token cache: double expiry and a hashed key.** The Caffeine `expireAfterWrite(12h)` and the manual `expiresAt` check overlap. The SHA-256 of the secret in the cache key is defensible: it keys on credentials across organizations, so a changed secret gets a new token. Mollie keys by organization id instead, which is simpler, but then a changed secret would need explicit invalidation. Acceptable as is. A Caffeine `Expiry` would replace the manual check, but it is not worth the churn.

**I5. `PayUWebhookPayload` has slightly more structure than it needs.** The REFUND and UNRECOGNIZED kinds both end as `notRelevant`. They differ only in `getType()` (`payu-refund` vs `payu-unrecognized`), which core stores for audit and logging. `getOrderStatus()` has no caller outside the class. The distinction is cheap and useful in logs, so keep the kinds. Optionally remove `getOrderStatus()` as dead code.

**I6. Stale "to be verified" comments.** `ORDER_NOT_FOUND_STATUS` (L96) and `BUYER_LANGUAGES` (spec §1) still say "to be verified on the sandbox". The work-log (L134) confirms `DATA_NOT_FOUND`. Update the comment so that upstream reviewers do not read it as an open issue. Verify the language list or trim it to the documented set.

**I7. Unrelated change in the E2E harness.** `BaseE2ETest` now sets a 1920×1080 window when not on CI. It is useful, but unrelated to PayU. Mention it in the PR description, or split it into its own commit, so upstream reviewers do not question it.

**I8. Similar switches in `reconfirmExistingOrder`, `checkOrderStatus` and `discardTransaction`.** There are three status switches over the same PayU states. They return different result types and have different side-effect rules (the spec's matrices §2, §3 and the discard table), so a shared abstraction (strategy or table) would be harder to read than three explicit switches. **No change recommended.** It is listed here only to record that this was considered and rejected.

## 3. Developer Experience
- **Good:** one class per concern, each following an existing sibling. The Javadoc explains *why* (for example "PayU answers 200 SUCCESS to DELETE on a COMPLETED order"). There are no ternaries, Lombok use stays within the allowed annotations, and the hidden `alfio.payu.endpoint-override` property makes WireMock integration tests possible without touching admin configuration.
- **Friction:** the unit tests contain real sleeps (W3). `PayUManagerTest` is 1185 lines with 51 tests, against Saferpay's 156 lines. That is proportional to the state matrix, but the file is long. Splitting it into `PayUManagerOrderTest`, `PayUManagerNotificationTest` and `PayUManagerRefundTest` would help navigation. This is optional.
- **E2E:** it depends on the shared public sandbox POS and on PayU's page selectors. It is fragile by nature, but it is already excluded from `./gradlew build`, which is fine.

## 4. Requirements Alignment
- FR1–FR15 map one-to-one to the code. Two kinds of work go beyond the plan, and both were driven by sandbox evidence: IPv6 normalization (81ae831b2) and the cancel re-read plus polling (dcedbd9ad, 6475c6ecb). That is good requirement discipline.
- The one inflation: the refund "retry-safe" requirement (FR7 / M8) is implemented with more machinery than the guarantee it actually delivers (W1).
- There is no speculative future-proofing: no multi-POS support, no stored `redirectUri` reuse, no admin masking of secrets (I6 in the spec was explicitly deferred). The currency gating and the buyer-language list are small, static sets.

## 5. Context Consistency
- The order-lookup error handling is consistent everywhere: ERROR leads to "do nothing" or pending, and NOT_FOUND leads to "safe to recreate".
- Exception handling follows one pattern: an `InterruptedException` re-sets the interrupt flag, and other exceptions are logged. `confirmOrder` swallows the interrupt without logging (L874–875), which is minor.
- Unused code: `PayUWebhookPayload.getOrderStatus()` has no callers. No other dead private methods were found.
- The spec still says the lock holds "about 5 calls, about 50 s", while the code now does more (see Risks). The spec text is out of date, not the code.

## 6. Recommended Simplifications (top 3)

1. **Refund id (W1).** Either fix the docs, or simplify the id.
   ```java
   // before: GET refunds first, then extRefundId = reservationId + "-refund-" + amountCents + "-" + (k + 1)
   // after
   var extRefundId = transaction.getReservationId() + "-refund-" + randomHex();
   // keep: on IOException → refundExists(configuration, orderId, extRefundId)
   ```
   Impact: one HTTP call fewer per refund, one failure mode fewer, and an honest guarantee. About −6 LOC, and 2 tests to adjust.

2. **Injectable cancel pause (W3).**
   ```java
   // before
   Thread.sleep(CANCEL_CONFIRMATION_PAUSE);
   // after (field, set to Duration.ZERO by the unit tests)
   Thread.sleep(cancelConfirmationPause);
   ```
   Impact: about 3 s less per unit-test run. It also makes the lock cost visible and tunable.

3. **Remove `extractOrderId` from the webhook controller (I1).** Log `reservationId` and the body size instead. Impact: about −25 LOC and one less copy of Gson navigation.

## 7. Summary Statistics

| Metric | Current | After the top-3 |
|---|---|---|
| `PayUManager` LOC | 1093 | ~1085 |
| Webhook controller LOC | 106 | ~80 |
| HTTP calls per refund | 2 (GET + POST) | 1 (POST) |
| Real sleep in unit tests | ~3 s | 0 |
| New infrastructure / dependencies | 0 | 0 |

## 8. Conclusion
The PayU work is appropriately engineered for a money-handling provider that is meant to go upstream. Its complexity is concentrated in the mechanisms the spec and the sandbox proved necessary, and it introduces no new layers, infrastructure or dependencies. Recommended actions, about 1 h in total:
1. W1: fix the refund guarantee wording, or simplify the id.
2. W3: inject the cancel pause, and update the spec's lock-duration risk.
3. W2: keep `fix/pl-translations` fork-only.
4. Optionally: I1, I6, I7 (comment and PR-hygiene items).
