# Production Readiness Report

**Date**: 2026-11-07
**Path**: `/Users/rafalnowak/Dev/alf.io`, `git diff main..feature/payu` (14 commits, 38 files) + deployment artefact `plo-pl` @ 1dca809d0 (image `alfio-plo-pl:payu-20261107`)
**Target**: production (PayU live POS, real ticket sales)
**Status**: With Concerns

## TL;DR
- **GO WITH MITIGATIONS.** The code is ready: the money lifecycle is careful, failures are logged, timeouts are set and signatures are checked in constant time. **The deployment is not ready**: three go-live checks must be done first. None of them needs a code change.
- B1: real PayU notifications have never reached this code (the sandbox notifyUrl was localhost; the webhook was only tested with WireMock). B2: nothing guards the live/sandbox switch or catches mixed credentials. B3: nobody watches the money-anomaly log lines or reservations stuck in WAITING_EXTERNAL_CONFIRMATION.
- The artefact exists only locally: `plo-pl` is not pushed, `origin/feature/payu` is still the WIP commit, and the README has build steps but no run, go-live or rollback steps.
- 3 blockers (operational), 11 warnings, 7 info.

## Key Decisions
- **The blockers are operational.** Code-level money safety was audited and holds. Never `failed` while an order may be payable. Cancel is confirmed by re-reading the order. COMPLETED is validated on amount, currency and extOrderId. The webhook re-reads the order instead of trusting the body. The real risk for a one-off event is configuration and reachability that were never tested against live PayU.
- **App-wide gaps are concerns, not blockers.** No error tracker, no metrics and no in-app rate limiting are inherited alf.io traits, not something this change introduced. For a small reunion sale, a log watch (B3) is enough instead of adopting Sentry or Prometheus.
- **Inherited core behaviour stays out of scope** (spec: no change to `TicketReservationManager`). It is still reported where it creates PayU-specific production risk: the WAITING_EXTERNAL_CONFIRMATION gap and the credit note issued before the refund result.
- The Docker image could not be inspected: the OrbStack daemon was unreachable (`docker image inspect` → EOF). Its existence comes from work-log G19 and completeness-report.md.

## Open Questions / Risks
- **Does the production reverse proxy/WAF let PayU's POST reach `/api/payment/webhook/payu/reservation/*`, on HTTPS port 443?** If PayU's bot traffic is blocked (Cloudflare bot fight mode, basic auth, geo-block), every BLIK or pay-by-link payment where the attendee comes back while the order is still PENDING ends up in WAITING_EXTERNAL_CONFIRMATION with no automatic recovery.
- **Are the live POS and the sandbox POS configured at different levels (system vs organization)?** Settings fall back key by key (`configurationManager.getFor(ALL_KEYS, level)`). A missing org-level `PAYU_SECOND_KEY` silently uses the system-level sandbox key, and every notification then fails its signature check (HTTP 500, PayU retries).
- **What proxy read timeout does production use?** Worst case under the reservation lock is now more than 90 s (OAuth + GET + DELETE + up to 5 re-reads with 2 s of sleep + POST, plus 401 retries, at 10 s each), not the 50 s in the spec.
- **Do the test reservations from sandbox testing still exist in the production DB?** After the live switch, their orderIds are looked up on live, return NOT_FOUND → `error` → no action. The stuck job then polls them every 30 s and holds their tickets indefinitely.

## Executive Summary
- **Recommendation**: GO with mitigations (NO-GO until B1–B3 are done)
- **Overall Readiness**: 68%
- **Deployment Risk**: Medium (High until B1–B2 are verified)
- **Blockers**: 3  Concerns: 11  Recommendations: 7

## Category Breakdown
| Category | Score | Status |
|----------|-------|--------|
| Configuration | 65% | Concerns: live/sandbox switch unguarded, per-key fallback, endpoint override reachable via env |
| Monitoring | 55% | Concerns: ERROR/WARN on anomalies present; no alerting; some captured-money cases only WARN |
| Resilience | 80% | Good: tri-state reads, confirmed cancel, idempotent-ish refund, PayU retries + force-check + stuck job |
| Performance | 75% | 10 s per-request timeouts; long worst-case lock; no connect timeout on shared client |
| Security | 75% | Constant-time MD5/SHA-256 signature, UTF-8 raw body, no secrets in logs; plain-TEXT secrets (inherited) |
| Deployment | 45% | No migration needed; artefact unpushed, no run/rollback/go-live docs |

## What was verified as sound
- **Signature** (`PayUSignature.isValid`): parses `signature`/`algorithm` from `OpenPayu-Signature`, MD5 or SHA-256 of `body + secondKey`, compared with `MessageDigest.isEqual`. The body is read as raw bytes and decoded as UTF-8 (`RequestUtils.readRequest`), so Polish diacritics in buyer names do not break the hash. Falls back to `X-OpenPayU-Signature`.
- **Webhook trust model**: a notification only triggers a re-read of the order from PayU (`checkOrderStatus` → `retrieveOrder`); the body status is never applied directly. Notifications for other orders, refunds and unrecognized shapes → 200 NOT_RELEVANT; errors → 500 so PayU retries.
- **Money anomalies logged at ERROR**: amount, currency or extOrderId mismatch (`completeTransaction`, PayUManager L289); COMPLETED after a cancellation request (L842); unexpected exceptions on create/read/confirm/refund.
- **No secrets in logs**: request bodies and tokens are never logged. OAuth failure logs only the HTTP status (L933). Failure response bodies are PayU error bodies. The token cache key hashes the secret. Both e2e logs grep clean for secret/Bearer.
- **Timeouts**: `.timeout(10 s)` on every request, OAuth included (L904, L924).
- **Retries**: one 401 retry with a fresh token. PayU retries notifications on 500. Recovery paths: return callback force-check, processing page manual force-check, and the 30 s stuck job (`Jobs.cleanupExpiredPendingReservation` → `markExpiredInPaymentReservationAsStuck`, covers EXTERNAL_PROCESSING_PAYMENT whose validity is more than 10 min past). A COMPLETED order found there completes the reservation.
- **Platform basics** (application.properties): `/healthz` exists (IndexController L87), `server.shutdown=graceful`, `server.forward-headers-strategy=native`, `logging.level.alfio=info` (PayU WARN/ERROR are emitted).
- **No Flyway migration** in the change, so rollback to the previous image is DB-safe (PAYU transactions remain as rows with `payment_proxy='PAYU'`).
- `PaymentProxy.PAYU` has `supportRefund=true`; refunds run through `AdminReservationManager.refund` → `PaymentManager.refund` with audit entries.

## Blockers (Must Fix before selling)

### B1. The notification path has never been exercised against real PayU (critical, fixable: true, operational)
- **Location**: `PayUPaymentWebhookController`, `PayUManager.buildOrderRequest` (notifyUrl = `BASE_URL` + `/api/payment/webhook/payu/reservation/{id}`), production reverse proxy.
- **Issue**: the sandbox e2e ran with a localhost notifyUrl (work-log G15: "localhost notifyUrl accepted"), so PayU never delivered a notification. Signature verification against a real PayU body is proven only by computed vectors and WireMock. This matters because of the inherited gap: when the attendee returns while the order is PENDING (common for pay-by-link, possible for BLIK), the return force-check moves the reservation to WAITING_EXTERNAL_CONFIRMATION (`handlePaymentWebhookResult`, `moveToWatingExternalConfirmationAllowed=true`). `findStuckReservationsForUpdate` selects only `IN_PAYMENT`/`EXTERNAL_PROCESSING_PAYMENT`. From then on only the notification, or a manual force-check while the attendee stays on the processing page, confirms it. A broken notify path means money captured, no tickets, no alert.
- **How to fix**:
  1. `BASE_URL` is the public `https://` origin (no port, no trailing path).
  2. `curl -X POST https://<host>/api/payment/webhook/payu/reservation/does-not-exist -d '{}'` from outside returns `200 NOT_RELEVANT`, which proves the path passes the proxy/WAF.
  3. Make one live purchase of a 1–2 PLN test ticket with BLIK and one with pay-by-link. In the logs, check that the notification was answered 200 (no `Discarding PayU notification with invalid signature`) and that the reservation reaches COMPLETE without a manual force-check.
  4. Refund both purchases from the admin (this also exercises B-path refunds on live).

### B2. Live/sandbox switch and credential set are unguarded (critical, fixable: true, operational; code guard optional)
- **Location**: `ConfigurationKeys.PAYU_LIVE_MODE` (default `false`), `PayUManager.endpoint()` L971, `isActive` L192 (checks presence only).
- **Issue**: with `PAYU_LIVE_MODE=false` and sandbox credentials left over from testing (the e2e used public POS 300746), production attendees "pay" on secure.snd.payu.com with test methods and receive **confirmed tickets with no money collected**. Nothing in the UI or the logs signals sandbox mode. The reverse case: live credentials with live mode off fails every attempt with a generic error. On top of that, settings fall back key by key from organization to system level, so a partial org-level setup silently mixes sandbox and live values.
- **How to fix**: at go-live, set all six PAYU_* keys at **one** level, delete them at the other level, set `PAYU_LIVE_MODE=true`, and confirm on the first live payment that the redirect host is `secure.payu.com`. Optional code hardening (fixable): log a WARN on every `doPayment` in sandbox mode, or show "SANDBOX" in the payment label.

### B3. No one is watching the money-anomaly signals (critical, fixable: true, operational)
- **Location**: logs only. The only email alert alf.io sends is the STUCK email, and PayU deliberately never produces STUCK for a NEW order.
- **Issue**: every case that needs a human is log-only: amount mismatch (ERROR), COMPLETED after a cancel (ERROR), a COMPLETED notification for a reservation that is unknown or in an incompatible state (WARN only, see W3), refund failures (WARN), and reservations parked in WAITING_EXTERNAL_CONFIRMATION (silent).
- **How to fix** (runbook, for the duration of sales):
  - Alert on `docker logs` with `grep -E 'ERROR .*PayU|PayU notification for unknown reservation|discarding transaction webhook payu-COMPLETED|PayU refund .* failed|does not match its transaction'`, or run that grep daily.
  - Run daily: `select id, status, validity from tickets_reservation where status in ('WAITING_EXTERNAL_CONFIRMATION','EXTERNAL_PROCESSING_PAYMENT','STUCK') and validity < now();`
  - Recover each row with `curl https://<host>/api/v2/public/reservation/<id>/transaction/force-check`, or compare it with the PayU merchant panel and refund manually.

## Concerns (Should Fix)

| # | Severity | Category | Location | Issue | Recommendation | Fixable |
|---|---|---|---|---|---|---|
| W1 | warning | configuration / security | `PayUManager` ctor L143–163 | `alfio.payu.endpoint-override` is bindable from the environment (`ALFIO_PAYU_ENDPOINT_OVERRIDE` via relaxed binding). It overrides the **live** endpoint too, so the OAuth `client_secret` and every order would go to that host. The only guard is a WARN at startup. | Make sure the env var is not set in prod (`docker inspect`, compose file). Code: ignore the override (or fail startup) unless the integration-test profile is active, or ignore it when live mode is on. | true |
| W2 | warning | security | `ConfigurationKeys` PAYU_CLIENT_SECRET / PAYU_SECOND_KEY (`ComponentType.TEXT`) | Secrets are shown in clear text in the admin and stored in plain text in the `configuration` table. Same as Stripe, Saferpay and SMTP (inherited); masking is out of scope (I6). | Limit admin accounts with system/org config access; do not screenshot the panel; rotate the live secret if it was ever shared; restrict DB backups. | false |
| W3 | warning | monitoring | `PayUPaymentWebhookController` L59 ("unknown reservation" WARN), core `processTransactionWebhook` ("discarding transaction webhook … payu-COMPLETED" WARN) | A COMPLETED notification for a deleted reservation, or for a transaction no longer PENDING, means money captured with no ticket, yet it is logged at WARN and answered 200. | Log at ERROR when the notified order status is COMPLETED (the controller already parses the body; extend `extractOrderId` to read the status as well). Until then, B3 covers it with a grep. | true |
| W4 | warning | resilience | core: `findStuckReservationsForUpdate` (TicketReservationRepository L182) | WAITING_EXTERNAL_CONFIRMATION is never re-checked by a job (inherited; spec Risks). Recovery depends on the notification. | Covered by B1 + B3. Longer term: a PayU-side or core job that force-checks WAITING_EXTERNAL_CONFIRMATION older than N minutes. | false (core) |
| W5 | warning | performance | `PayUManager.cancelOrder` L802–850, `performPayment` lock | Worst case more than 90 s under the reservation row lock (pragmatic review). If the proxy times out first, the attendee sees an error while the order may already exist. The re-confirm matrix makes a retry safe, but the UX is poor. | Set the proxy read timeout to at least 120 s (nginx `proxy_read_timeout`). Update the spec figure. | true |
| W6 | warning | resilience | `PayUManager.refund` L668–711 | (a) An operator who repeats a refund after a *successful* one gets a new `extRefundId` (k+1), so a double refund is possible (pragmatic W1). (b) `true` means accepted, not FINALIZED; refund notifications are ignored (NOT_RELEVANT), so a refund that PayU later cancels (e.g. low POS balance) is invisible. (c) Core issues the credit note before the refund result (inherited). | Runbook: after each refund, check `getInfo` refunded amount on the reservation page or in the PayU panel before retrying; keep a POS balance. | partly |
| W7 | warning | resilience | `checkOrderStatus` NOT_FOUND → `error` | Sandbox transactions left in the DB after switching to live are NOT_FOUND forever. They are never STUCK, never released, and get a PayU GET every 30 s. | Before go-live, cancel or delete test reservations made in sandbox mode, or make sure none is in EXTERNAL_PROCESSING_PAYMENT or WAITING_EXTERNAL_CONFIRMATION. | true |
| W8 | warning | deployment | git remotes, local Docker | `plo-pl`, `fix/pl-translations` and `fix/stepper-active-color` exist only locally; `origin/feature/payu` = f05742e44 (WIP); the image lives only in local OrbStack. One disk failure loses the release. | Do the deferred Phase-14 pushes; push the image to a registry or keep a `docker save` tarball; tag the image with the commit (`payu-1dca809d0`), not only the date. | true |
| W9 | warning | deployment | `plo-pl:README.md` §"Building the Docker image (plo-pl fork)" | Build-only. Missing: how to run the image (DB connection, `SPRING_PROFILES_ACTIVE`, `ALFIO_JAVA_OPTS`), the PayU go-live checklist (B1/B2), proxy requirements (webhook path open, read timeout), rollback (`docker run` the previous tag; no migration involved), and a note that `-x test` must follow a green `./gradlew build` of the **same** commit. | Add a short "Deploying / Go-live / Rollback" subsection. | true |
| W10 | warning | deployment | `src/main/dist/Dockerfile` | `LABEL org.opencontainers.image.source` points to upstream alf.io, so the fork image is mislabelled. Minor, but it confuses provenance. | Override the label in the build command (`--label org.opencontainers.image.revision=$(git rev-parse HEAD)`). | true |
| W11 | warning | monitoring | platform | No error tracking, metrics or in-app rate limiting (inherited). The public `force-check` and the webhook path are unauthenticated and each triggers PayU calls (the webhook only after a valid signature). | Rate-limit `/api/v2/public/reservation/*/transaction/force-check` and `/api/payment/webhook/**` at the proxy; external uptime check on `/healthz`. | true (infra) |

## Recommendations (Nice to Have)
- I1 (info, security): `RequestUtils.getClientIp` trusts the first `X-Forwarded-For` entry, which can be spoofed. Accepted, because PayU uses `customerIp` only for fraud scoring; `forward-headers-strategy=native` is already set. Make sure the proxy overwrites XFF rather than appending client-supplied values.
- I2 (info, resilience): `confirmOrder` swallows `InterruptedException` without a log line (L874–876). Add a WARN.
- I3 (info, performance): the shared `HttpClient` bean (`DataSourceConfiguration` L224) has no `connectTimeout`; the per-request timeout is the only bound (spec Risks). A PayU-specific client with `connectTimeout(5 s)` would bound the lock tighter.
- I4 (info, monitoring): a NEW order past the reservation validity holds tickets until PayU auto-cancels at `validityTime` (≤ reservation timeout, then +`RESERVATION_TIMEOUT` after CANCELED), with no log line per run (the spec says "a WARN per run", but `checkOrderStatus` NEW logs nothing). Acceptable at this scale.
- I5 (info, monitoring): the CANCELED branch logs at DEBUG (L542); INFO would help reconstruct abandoned payments during support.
- I6 (info, documentation): spec §1/H3 still says that a DELETE SUCCESS body alone means cancelled; the code re-reads (dcedbd9ad, 6475c6ecb). Align the spec so operators reading it are not misled.
- I7 (info, resilience): circuit breaker for PayU is not needed at this volume; the stuck job's per-reservation GET every 30 s is fine for tens of reservations.

## Next Steps
1. **Before go-live (blockers)**: configure the live POS at one level only, with `PAYU_LIVE_MODE=true` (B2). Clear sandbox test reservations (W7). Make sure `ALFIO_PAYU_ENDPOINT_OVERRIDE` is not set (W1). Set the proxy read timeout to at least 120 s (W5).
2. **Smoke test on production (B1)**: an external POST to the webhook path answers 200. Make two live low-value purchases (BLIK and pay-by-link); both must reach COMPLETE through the notification; then refund both and check FINALIZED in the PayU panel (W6).
3. **Monitoring for the sales window (B3)**: a log grep/alert for PayU ERROR and anomaly lines, plus a daily SQL check for reservations in WAITING_EXTERNAL_CONFIRMATION, EXTERNAL_PROCESSING_PAYMENT or STUCK, recovered with the public force-check curl.
4. **Artefact hygiene (W8–W10)**: push the branches, store the image off the laptop, tag it with the commit, and add Deploy/Go-live/Rollback to the README.
5. **Small code follow-ups (optional, after launch)**: ERROR log for a COMPLETED notification on an unknown or incompatible reservation (W3), endpoint-override guard (W1), sandbox-mode WARN (B2), WARN in `confirmOrder` interrupt (I2).

## Structured Result
```yaml
status: "with_concerns"
recommendation: "GO_WITH_MITIGATIONS"
report_path: "/Users/rafalnowak/Dev/alf.io/.maister/tasks/development/2026-11-07-payu-payment-provider/verification/production-readiness-report.md"
overall_readiness: 68
deployment_risk: "medium"
categories:
  configuration: { score: 65, status: "concerns" }
  monitoring: { score: 55, status: "concerns" }
  resilience: { score: 80, status: "good" }
  performance: { score: 75, status: "good_with_concerns" }
  security: { score: 75, status: "good_with_concerns" }
  deployment: { score: 45, status: "concerns" }
issue_counts:
  critical: 3
  warning: 11
  info: 7
```
