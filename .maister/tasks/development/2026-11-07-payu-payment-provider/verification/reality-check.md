# Reality Check: PayU payment provider (alf.io fork, XX-lecie PLO PŁ)

## TL;DR
**Status: ⚠️ Issues Found.** The work is usable for the reunion once two verifications are done. Code changes are optional.
The browser path works for real: buy, PayU sandbox page, pay, return, reservation confirmed. Abandoning also works and returns the attendee to the overview with a toast. Both were shown on the public PayU sandbox (E2E 2/2), and the 90 targeted PayU tests pass again here.
Two things were never exercised against real PayU. **(1)** A real PayU notification reaching the server: the sandbox runs could only call localhost. **(2)** Live mode with a production POS.
If notifications fail in production, an attendee who pays and closes the tab before coming back stays in WAITING_EXTERNAL_CONFIRMATION indefinitely. No job re-checks that state, and the organizer gets no alert.
Branches are organized locally only. Nothing is pushed, `origin/feature/payu` still holds the old WIP, and the `.maister` commit on plo-pl is pending (Phase 14).

## Key Decisions
- Overall status is **⚠️ and not ❌**. The core business path is proven end-to-end against the real PayU sandbox, every reviewed error path has a sensible outcome, and the open gaps are verification tasks with no known defects behind them. The event is small (one reunion), so manual reconciliation of a rare stuck reservation is an acceptable fallback.
- An unverified notification path is rated **warning, not critical**. The return callback and the processing page's "force check" button confirm a payment without any notification. The stuck job also re-checks reservations in EXTERNAL_PROCESSING_PAYMENT. Only the "paid, then closed the tab" case relies on notifications alone.
- I did not rerun the full suite: 1047/1050 green is recorded. I reran only the targeted PayU tests (90/90 green, see Evidence). I started no servers and touched no containers.
- Gson use is rated **info** despite AGENTS.md ("JSON uses Jackson 3 or alfio.util.Json"). Mollie, Saferpay and Stripe on `main` all use Gson, and only `com.fasterxml` is enforced by forbiddenApis.

## Open Questions / Risks
- Will `BASE_URL` in production be a public URL that PayU can reach, with no auth or WAF in front of `/api/payment/webhook/payu/**`? No test covers this, and the notification path depends on it.
- Live POS: are the production `client_id`/`client_secret`/`second key` from the same POS, and is the second key the MD5 one the signature expects? This has not been verified.
- When an unpaid order's `validityTime` expires, does PayU send a CANCELED notification, or only change the status? The design assumes the order becomes CANCELED, so the stuck job (or a notification) moves the reservation back to PENDING and releases it. This was not observed on the sandbox because it needs at least a 5-minute wait.
- Did anyone run the Docker image `alfio-plo-pl:payu-20261107`? It was built but never started.

---

## 1. Evidence reviewed
- `implementation/work-log.md`: groups G1–G19 + G15b. Sandbox findings: DELETE on PENDING gives CANCELED; DELETE on COMPLETED stays COMPLETED (fixed by re-read); DELETE is applied asynchronously (fixed by a bounded re-read); IPv6 brackets fixed; `DATA_NOT_FOUND`/404 and the refunds listing shape confirmed.
- `verification/e2e-test.log`: `PayUPaymentE2ETest` 2/2, `BUILD SUCCESSFUL`. The `payWithPayU` log line "payment confirmation is taking longer than expected, forcing check" shows that confirmation happened through the processing page's force-check, **not** a notification. This is expected on localhost.
- `verification/spec-audit.md`: 3 High issues before implementation (H1–H3). Code check: H1 is fixed by the tri-state `retrieveOrder`, H2 by `completeTransaction` on re-confirm, and H3 by `cancelOrder` re-reading the order.
- Code: `git diff main..feature/payu` (38 files, +4297/−39), with a full read of `PayUManager`, `PayUCallbackController`, `PayUPaymentWebhookController` and `PayUSignature`, and the related core paths in `TicketReservationManager` (L1089–1142, L1771–1953), `TicketReservationRepository` L182 and `Jobs` L162–164.
- **Independent test run** (this assessment): `./gradlew test --tests 'alfio.manager.payment.PayU*' --tests 'alfio.manager.payment.payu.*' --tests 'alfio.controller.payment.*PayU*' --tests 'alfio.controller.payment.api.payu.*' --tests '…PayUReservationFlowIntegrationTest' -Dpgsql.version=16`, BUILD SUCCESSFUL:

| Suite | Tests | Fail |
|---|---|---|
| PayUManagerTest | 58 | 0 |
| PayUManagerLifecycleTest | 7 | 0 |
| PayUSignatureTest | 5 | 0 |
| PayUCallbackControllerTest | 7 | 0 |
| PayUPaymentWebhookControllerTest | 6 | 0 |
| PayUReservationFlowIntegrationTest (Postgres 16, WireMock) | 7 | 0 |
| **Total** | **90** | **0** |

## 2. Reality vs claims

| Claim | Reality | Evidence | Verdict |
|---|---|---|---|
| buy, PayU page, pay, return, confirmed | Works on the real sandbox (mTransfer simulator) | e2e log `payWithPayU` passed in 36 s | ✅ |
| Abandon on PayU, back to overview, retry possible | Works: `error=501` triggers DELETE and a confirmed CANCELED, then the overview shows the toast and the method can be picked again | e2e `abandonPaymentOnPayU`; integration `returnWithError…` | ✅ |
| Signed notifications confirm the reservation | Proven **only with self-signed fixtures** (WireMock plus hand-computed MD5/SHA-256). No real PayU notification ever reached the server | `PayUPaymentE2ETest` javadoc: "notifications cannot reach a local server" | ⚠️ unverified |
| Admin can configure PayU | System and organization panels exist (`paymentPayu` category, 6 keys) and the E2E server was configured with them. Secrets are plain-text inputs (accepted decision I6) | system.html/organization.html diff, ConfigurationKeys | ✅ |
| PL translations | All 4 new EN keys plus `common.confirm`/`event-days.*` are present in `public_pl.properties` on plo-pl. A native-speaker review is suggested ("subskrypcja" vs "karnet") | grep on plo-pl | ✅ (review advised) |
| Docker image buildable | Image `alfio-plo-pl:payu-20261107` was built and the README section is on plo-pl. The image was **never started** | work-log G19 | ⚠️ boot not verified |
| Branches organized, feature/payu PR-able | Locally yes: plo-pl contains all four branches, feature/payu sits on current `main` (0 behind) and has 14 clean commits. **Nothing is pushed**: `origin/feature/payu` = WIP `f05742e44` ("ahead 14, behind 2", which needs `--force-with-lease`). The fix branches have no remote. The `.maister` commit on plo-pl is pending. No PR exists | `git branch -vv` | ⚠️ incomplete (Phase 14) |
| Live mode | Endpoint switch to `https://secure.payu.com` exists and uses the same OAuth path. **Never exercised** | code L971–976 | ⚠️ unverified |

## 3. Error-path walk-through (code-verified)

| Scenario | What happens | Outcome |
|---|---|---|
| **Abandon via PayU "back/cancel"** (`error` param) | `cancelPendingPayment` runs `discardTransaction`. For NEW or PENDING it sends DELETE and re-reads up to 5 times every 500 ms. Once CANCELED, the reservation goes back to PENDING and the overview shows the toast | ✅ sandbox-verified |
| **Abandon by closing the tab** (order NEW) | The reservation is EXTERNAL_PROCESSING_PAYMENT, valid for `validityTime` + 300 s. After that the stuck job calls `forceTransactionCheck`. NEW gives `error`: nothing changes, the reservation is not marked STUCK, and the job re-checks it on its next run. Once PayU expires the order (CANCELED), the reservation goes back to PENDING with a new timeout, and cleanup releases the tickets | ✅ by design. The PayU auto-expiry was not observed (see Open Questions) |
| **Payment completes, notification lost, attendee returns** | The return callback's `forceTransactionCheck` reads the order. COMPLETED confirms the reservation. PENDING sends the attendee to `/book`, then the processing page, where the force-check button confirms it later | ✅ e2e-verified (this is exactly what happened in `payWithPayU`) |
| **Payment completes, notification lost, attendee closed the tab** | If the order was still NEW, the stuck job settles it (above). **If PayU had already reported PENDING** (BLIK and transfers do so immediately on the return or force-check path, or through a notification), the reservation is in **WAITING_EXTERNAL_CONFIRMATION**. `findStuckReservationsForUpdate` selects only `IN_PAYMENT`/`EXTERNAL_PROCESSING_PAYMENT`. Nothing re-checks it: the money is captured, there are no tickets and no organizer alert | ⚠️ inherited core gap. It matters only if notifications fail |
| **Notification before return** | The webhook confirms the reservation (`transitionToComplete`). The return's `forceTransactionCheck` finds no PENDING transaction, so it returns empty, the attendee is redirected to `/book`, and the guard shows success | ✅ |
| **Notification signature wrong** (wrong second key) | `parseTransactionPayload` returns empty, giving "payload not recognized" (ERROR) and HTTP 500, so PayU retries. The reservation still relies on the return or force-check | ⚠️ fails loudly in logs but silently for the business. Hence action A1 |
| **Double confirm while the order is being paid** | The re-confirm matrix returns `pending` for PENDING and never creates a second order. A read error also gives `pending` (spec-audit H1 fixed) | ✅ integration `secondConfirmWhileOrderIsBeingPaid…` |
| **COMPLETED with wrong amount** | Logged as ERROR, the transaction stays PENDING and nothing is confirmed | ✅ integration test |
| **DELETE on an already COMPLETED order** | The re-read shows COMPLETED, so the result is NOT_CANCELLED and the reservation is not released | ✅ sandbox finding, fix G15b |
| **Refund** | Full or partial refund with a deterministic `extRefundId`. On a timeout the refunds are re-read. `getInfo` sums the FINALIZED refunds. Not exercised on the sandbox end-to-end, but the listing shape was confirmed there | ✅ (small edge, see I3) |
| **PayU outage during cancel or return** | Each call has a 10 s timeout. `cancelOrder` can take 1 DELETE + 5 reads + 2 s of pauses (plus a token fetch) under the reservation row lock: about 60 s in the worst case | ℹ️ acceptable at reunion scale |

## 4. Gaps

### Critical
None.

### Warning
| # | Gap | Evidence | Impact | Fixable |
|---|---|---|---|---|
| W1 | **Real PayU notifications never received.** The header name, signature over the raw body, body shape and HTTP reachability of `notifyUrl` are unproven against PayU itself | e2e javadoc and log ("forcing check"); webhook tests call the controller directly with a self-signed body | If broken, every "pay then close the tab" case depends on manual reconciliation | Yes, with a verification run (A1). No code change is expected |
| W2 | **WAITING_EXTERNAL_CONFIRMATION is never reconciled** when the final notification is lost (inherited core behaviour, already flagged in the spec audit) | `TicketReservationRepository` L182; `TicketReservationManager` L1110–1142 | Paid attendee without tickets and no organizer alert. Rare if W1 works | Yes: operational check (A3) or a small PayU-only reconciliation (A6, optional) |
| W3 | **Live mode never exercised** (production POS, real BLIK, real refund) | No live run in the work log | First real attendee becomes the test | Yes (A2) |
| W4 | **Branches not published / PR not opened.** `origin/feature/payu` still points to WIP; `fix/pl-translations` and `fix/stepper-active-color` have no remote; the plo-pl `.maister` commit is pending; worktrees remain | `git branch -vv`, `git worktree list`, orchestrator-state Phase 14 | The "feature/payu PR-able" claim is true locally only | Yes (A4) |

### Info
| # | Gap | Fixable |
|---|---|---|
| I1 | Docker image built but not started (Flyway against a fresh DB, PayU bean wiring in the packaged jar) | Yes (A5) |
| I2 | `cancelOrder` sleeps while holding the reservation lock, up to about 2.5 s normally and about 60 s if PayU is down | Yes, but not worth it at this scale |
| I3 | Refund edge: when the POST times out **and** the re-read fails, `false` is returned. If the admin retries, `n` increments, so a second refund with a new `extRefundId` can be sent. A full refund would be rejected by PayU; a partial one could be duplicated | Yes (low priority) |
| I4 | `RequestUtils.getClientIp` trusts the first `X-Forwarded-For` entry. Behind no proxy it can be spoofed, but it is only sent to PayU as `customerIp` (anti-fraud signal) | Yes. Document the reverse-proxy requirement |
| I5 | Return with `error` for a reservation already COMPLETE (paid, then the attendee navigates back) redirects to the overview with "payment cancelled". The guard should route a completed reservation to success, so this is cosmetic at most | Yes (low) |
| I6 | Gson instead of Jackson 3 contradicts the letter of AGENTS.md, but it follows the Mollie/Saferpay/Stripe precedent. An upstream reviewer may ask for it to change | Yes, if upstreamed |
| I7 | PL wording ("subskrypcja" vs "karnet") needs a native review | Yes |
| I8 | Admin secret fields are plain text (accepted decision I6) | Out of scope |

## 5. Functional completeness
About **90%** of the stated problem is done and proven. The remaining 10% is verification against real infrastructure (notifications, live POS, image boot) and publishing the branches. No missing functionality was found in the code.

## 6. Pragmatic action plan

| # | Action | Success criteria | Priority | Effort |
|---|---|---|---|---|
| A1 | Deploy plo-pl to the real host (or a public staging URL) with **sandbox** credentials and `BASE_URL` = the public HTTPS URL. Make one sandbox payment and **close the tab on the PayU confirmation page without returning** | The server log shows the PayU webhook answered 200 "OK" with no "invalid signature" warning, and the reservation becomes COMPLETE with no force-check | Critical for go-live | 30–60 min |
| A2 | Switch to the live POS (`PAYU_LIVE_MODE=true` plus production keys), buy the cheapest ticket with real BLIK, then refund it from the admin | Reservation confirmed, refund FINALIZED on PayU, and `getInfo` in the admin shows the refunded amount | High | 30 min |
| A3 | Runbook for W2: before and during sales, check the reservations in WAITING_EXTERNAL_CONFIRMATION or STUCK in the admin daily and reconcile them against the PayU merchant panel | Written note for the organizer, plus a checklist item | High (cheap) | 15 min |
| A4 | Finish Phase 14. Push `feature/payu` with `--force-with-lease=feature/payu:f05742e44`, push `fix/pl-translations`, `fix/stepper-active-color` and `plo-pl`. Commit `.maister/` on plo-pl only. Remove the worktrees. Open the PR(s) per CONTRIBUTING.md if wanted (issue first) | Remote branches match local and `git status` is clean in every worktree | High | 20 min |
| A5 | `docker run` the image against an empty Postgres and open `/admin` | The app starts, Flyway passes, and the PayU panel is visible under Payment settings | Medium | 15 min |
| A6 | (Optional) Let the PayU stuck check also re-read WAITING_EXTERNAL_CONFIRMATION reservations older than N hours. This needs a core query change, so it is a separate PR | An integration test where a lost COMPLETED notification is settled by the job | Low | 2–3 h |
| A7 | Native-speaker pass over `public_pl.properties` | Wording approved | Low | 15 min |

## 7. Completion verdict
**⚠️ Gaps.** The code is complete for the intended purpose and behaves correctly on every error path I traced. The happy and abandon paths are proven against the real PayU sandbox, and the 90 targeted tests pass. It is not "done" for the reunion until A1 (a real notification on a public URL) and A2 (a live POS smoke payment) succeed, and the branches are published (A4). None of these is expected to need a code change. If A1 fails, look first at `BASE_URL`, the reverse proxy and the second key.
