# E2E Verification Report

## TL;DR
- **Verdict: GO WITH CAVEATS.** 6/6 scenarios executed, 6 passed (2 with caveats), 0 failed, 0 blocked. No Critical or Major issues.
- A full PLN purchase on the PayU public sandbox (mTransfer, positive) reached the success page via return, processing page and force-check. The admin new UI shows the reservation with a `credit-card` icon.
- An abandoned payment (`#formReturn`) comes back to the overview with the "payment was not completed" toast. The query parameter is cleared, a reload does not repeat the toast, and PayU can be chosen again (a new order is created).
- Caveats are branch scope only: the stepper badge is still `#007ACC` (fix on `fix/stepper-active-color`) and the PL bundle falls back to EN for PayU keys (fix on `fix/pl-translations`). Both were expected.

## Key Decisions
- Created a fresh event `payu-verify-1107` in org "PayU E2E" (PLN, 10.00 + 23% VAT = 12.30 PLN, category "Regular") with **PayU + Offline (bank transfer)** enabled, so the radio list and "pick another method" behaviour can be checked. No existing event was available.
- The cancellation-toast screenshot was captured by opening the overview with `?paymentCancelled=true` directly. The toast had auto-hidden before the screenshot after the real sandbox cancel, but the DOM check showed the identical text after a real cancel (three real cancels were run).
- PL (scenario 5) was checked through the i18n bundle API (`/api/v2/public/i18n/bundle/pl`), because the event has only EN enabled and the public UI offers no language switch for it.

## Open Questions / Risks
- The stepper colour and the PL label only appear once `fix/stepper-active-color` and `fix/pl-translations` are merged. This run cannot confirm them at runtime. The stepper SCSS on that branch was confirmed in source (`var(--bs-primary)`).
- Pre-existing and unrelated to PayU: the organization settings page logs `500 /admin/api/organization/NaN/promo-code` (the `alfio-promo-code` component loads before the organization id is set). The PayU diff does not touch it.

## 1. Identifier
- **Task**: 2026-11-07-payu-payment-provider
- **Task path**: /Users/rafalnowak/Dev/alf.io/.maister/tasks/development/2026-11-07-payu-payment-provider
- **Spec**: /Users/rafalnowak/Dev/alf.io/.maister/tasks/development/2026-11-07-payu-payment-provider/implementation/spec.md
- **Date**: 2026-11-07
- **Git ref**: 1affdc531 (feature/payu)
- **Tester**: e2e-test-verifier (maister)

## 2. Test Environment
| Field | Value |
|---|---|
| Base URL | http://localhost:8181 (public), http://localhost:8181/admin (legacy + new Lit admin via Vite dev server) |
| Browser | Playwright MCP Chromium (UA Chrome/156.0.0.0) |
| Viewport | 1200×943 (DPR 2) |
| Auth context | anonymous attendee (public); `admin` (admin pages) |
| Test data | live: event `payu-verify-1107` created during the run; PayU public sandbox (POS 300746), `PAYU_LIVE_MODE` false |

## 3. Executive Summary
**Verdict**: ⚠️ GO WITH CAVEATS

| Metric | Count |
|---|---|
| Scenarios planned | 6 |
| Scenarios executed | 6 |
| Passed | 6 |
| Failed | 0 |
| Blocked | 0 |
| Pass rate | 100% |
| Critical issues | 0 |
| Major issues | 0 |
| Minor issues | 2 |
| Cosmetic issues | 3 |

Everything that belongs to `feature/payu` works end to end in the browser. The system and organization PayU settings panels render, and the event shows "PayU: BLIK, fast transfer, card" among its payment methods. Attendees see the `PAYU` radio with a credit-card icon and the PayU redirect panel. A sandbox mTransfer payment completes a reservation (return, processing page, force-check after about 18 s, success). A cancel on PayU brings the attendee back to the overview with a one-time toast. The two Minor items are branch-scope gaps that were expected; the three Cosmetic items are deviations the spec already accepted or that come from existing layout.

## 4. Verification Scenarios

### 4.1 Admin: PayU settings panels and event payment-method label — ✅ Passed
- **User story / acceptance criterion**: User story "organization or system admin … PayU panel"; FR11; screen:admin-payu-settings
- **Preconditions**: logged in as `admin`; PayU sandbox configured at system level

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Open `/admin/#/configuration/system` | Panel "PayU" with `fa-credit-card` and 6 PAYU_* settings | Panel "PayU", icon `fa fa-credit-card`; PayU enabled (toggle, on), Live Mode (toggle, off), POS ID, client_id, client_secret, second key | ✅ |
| 2 | Open `/admin/#/configuration/organization/1` | Same panel, organization scope, `#PAYMENT` header present | Panel shown with the same 6 settings (organization badge, empty values), `#PAYMENT` header present | ✅ |
| 3 | New event form, "Accepted payment methods" | Checkbox "PayU: BLIK, fast transfer, card" | Present, selectable | ✅ |
| 4 | Event detail, "Payment methods" | Shows "PayU: BLIK, fast transfer, card" | "Offline payment (bank transfer, invoice, etc.), PayU: BLIK, fast transfer, card" | ✅ |

- **Issues observed**: §5.4 C2 (secret values shown as plain text, accepted in the spec as I6)
- **Evidence**: `screenshots/01-admin-system-payu-panel.png`, `screenshots/22-admin-organization-payu-panel.png`, `screenshots/02-admin-event-payment-methods.png`, `screenshots/03-admin-event-detail-payment-label.png`
- **Acceptance criteria checklist**:
  - [x] PayU panel in system settings with 6 settings
  - [x] PayU panel in organization settings
  - [x] Legacy `util.js` label "PayU: BLIK, fast transfer, card"

### 4.2 Public: PayU option, proxy panel and stepper — ⚠️ Passed with issues
- **User story / acceptance criterion**: Attendee story "pick PayU on the overview"; FR10; screen:payment-method-selector; component:stepper-active-badge (FR13)
- **Preconditions**: event `payu-verify-1107` published; 1 ticket reserved; contact data filled

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Select 1 ticket, continue, fill first/last name and e-mail, continue | Overview page | `/reservation/{id}/overview` loaded | ✅ |
| 2 | Inspect payment radios | `input#PAYU`, `label#PAYU-label` "PayU: BLIK, fast transfer, card" with credit-card icon | `BANK_TRANSFER` and `PAYU`; label text matches; `fa-icon` credit-card rendered | ✅ |
| 3 | Click `#PAYU-label` | `app-payu-payment-proxy` with `h4.text-body-secondary` "PayU" + description | h4 class `text-body-secondary`, "PayU" / "You will be redirected to PayU's secure checkout to complete the purchase." | ✅ |
| 4 | Pay button | "Pay <price>" | "Pay zł 12.30" | ✅ |
| 5 | Stepper current badge colour | `var(--bs-primary)` (#0d6efd) | `rgb(0, 122, 204)` = `#007ACC`; `--bs-primary` is `#0d6efd` | ⚠️ |

- **Issues observed**: §5.3 M1 (stepper fix not on this branch, expected); §5.4 C3 (horizontal radio layout)
- **Evidence**: `screenshots/06-overview-payment-methods.png`, `screenshots/07-overview-payu-selected.png`, `screenshots/08-stepper-active-badge.png`
- **Acceptance criteria checklist**:
  - [x] PayU option shows the correct label, icon and description
  - [ ] Stepper active badge uses the theme primary colour (lives on `fix/stepper-active-color`)

### 4.3 Pay on the PayU sandbox and land on the confirmation — ✅ Passed
- **User story / acceptance criterion**: Attendee story "pay … and land on my confirmation"; FR1, FR4, FR6; success criterion "sandbox e2e completes a PLN purchase via return + force-check"
- **Preconditions**: overview of reservation `ac99828b…` with PayU selected, terms accepted

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Click "Pay zł 12.30" | Redirect to PayU hosted page | `merch-prod.snd.payu.com/pay/?orderId=S2PC21THDD261107GUEST000P01` | ✅ |
| 2 | `a[href='#/payment/wire']` → `button[title='mTransfer']` | Bank simulator | URL contains `/simulator/` | ✅ |
| 3 | `#formSubmit` (positive) → `/auth` `#formSubmit` "Kontynuuj" | Back to alf.io | `/reservation/{id}/processing-payment` | ✅ |
| 4 | Wait, then click "Force check" when shown | Success page | "The confirmation is taking longer than expected… Force check" shown; clicked; `/success` after about 18 s in total | ✅ |
| 5 | Success page | Reservation completed, ticket listed | "Hooray! Your reservation has been completed…", Ticket #1 Regular, Test PayUVerify | ✅ |

- **Issues observed**: _None observed._ (Notifications cannot reach localhost, so the force-check path was used, as expected.)
- **Evidence**: `screenshots/09-payu-hosted-page.png`, `screenshots/10-payu-simulator.png`, `screenshots/11-payu-auth.png`, `screenshots/12-return-processing.png`, `screenshots/13-force-check-button.png`, `screenshots/14-reservation-success.png`
- **Acceptance criteria checklist**:
  - [x] Order created and buyer redirected to PayU
  - [x] Return callback routes to processing page
  - [x] Force-check confirms the COMPLETED order and the reservation becomes COMPLETE

### 4.4 Abandon on PayU, come back, pick again — ✅ Passed
- **User story / acceptance criterion**: Attendee story "who cancels on PayU … clear message and pick a method again"; FR6 (`error` → `overview?paymentCancelled=true`); spec §7 toast rule
- **Preconditions**: new reservation `c67d93f3…`, PayU selected, terms accepted

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Pay → wire → mTransfer → `#formReturn` (negative) → `/auth` `#formSubmit` | Back to overview with cancel notice | Overview loaded; `ngb-toast` "The payment was not completed. You can choose a payment method and try again." | ✅ |
| 2 | Check URL after landing | `paymentCancelled` removed | URL is `/overview` without query | ✅ |
| 3 | Reload page | Toast does not reappear | No `ngb-toast` after 3 s | ✅ |
| 4 | Select Bank Transfer, then PayU | Both selectable, PayU panel shown | Both radios checked in turn; PayU panel rendered | ✅ |
| 5 | Pay with PayU again, cancel again | New order, same cancel notice | New order `PL7VWSWR3L261107GUEST000P01`; toast shown again; overview | ✅ |
| 6 | Open `overview?paymentCancelled=error.foo` | No toast (only literal `true` triggers) | No toast | ✅ |

- **Issues observed**: _None observed._
- **Evidence**: `screenshots/15-payu-auth-after-negative.png`, `screenshots/16-cancelled-overview-toast.png`, `screenshots/16b-cancelled-toast-closeup.png`, `screenshots/17-reload-no-toast.png`, `screenshots/18-reselect-payu-after-cancel.png`
- **Acceptance criteria checklist**:
  - [x] Cancel returns to the overview with a clear message
  - [x] Message shown once (query parameter cleared)
  - [x] Another method or PayU can be chosen again
  - [x] Only the literal `true` value triggers the toast

### 4.5 Polish UI label — ⚠️ Passed with issues
- **User story / acceptance criterion**: FR12 (PL keys on `fix/pl-translations`); expected EN fallback on `feature/payu`
- **Preconditions**: overview of reservation `c67d93f3…`

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Look for a language switch | Switch if the event offers PL | None (event created with EN only) | ⚠️ |
| 2 | Open overview with `?lang=pl` | PL UI or EN fallback | Page stays EN (`html lang="en"`), label "PayU: BLIK, fast transfer, card" | ⚠️ |
| 3 | GET `/api/v2/public/i18n/bundle/pl` | PayU keys fall back to EN on this branch | `reservation-page.payment-method.payu` = "PayU: BLIK, fast transfer, card", `reservation-page.payment.cancelled` EN; other keys PL ("Podsumowanie zamówienia") | ✅ |

- **Issues observed**: §5.3 M2 (PL fallback, expected)
- **Evidence**: `screenshots/19-overview-lang-pl.png`
- **Acceptance criteria checklist**:
  - [x] EN keys present on `feature/payu`
  - [ ] PL label "PayU: BLIK, szybki przelew, karta" (lives on `fix/pl-translations`)

### 4.6 New admin: PayU payment icon — ✅ Passed
- **User story / acceptance criterion**: Operator story "PayU reservations labelled … with a card icon"; FR11; component:admin-payment-icon
- **Preconditions**: completed PayU reservation `AC99828B`; logged in as admin

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Open `/admin#/events/payu-verify-1107/reservations/` (Completed tab) | `alfio-payment-method` with `sl-icon name="credit-card"` + method | Row AC99828B, Payment column: credit-card icon + "PAYU", PLN 12.30 | ✅ |
| 2 | Open `/admin#/events/payu-verify-1107/transactions/` | Same component in the payments list | `method=PAYU`, `sl-icon` `credit-card` | ✅ |

- **Issues observed**: §5.4 C1 (raw "PAYU" text, spec-accepted)
- **Evidence**: `screenshots/20-new-admin-reservations-payu-icon.png`, `screenshots/21-new-admin-transactions-payu-icon.png`
- **Acceptance criteria checklist**:
  - [x] New admin shows the card icon for PAYU

## 5. Discrepancies

### 5.1 Critical
_None observed._

### 5.2 Major
_None observed._

### 5.3 Minor

**M1. Stepper active badge still `#007ACC` on `feature/payu`**
- **Spec requirement**: FR13 / §10: `.wizard2 .wizard2-current .badge` uses `var(--bs-primary)`, "on `fix/stepper-active-color`"
- **Expected**: theme primary (#0d6efd)
- **Actual**: computed background `rgb(0, 122, 204)` (#007ACC); `frontend/public/src/app/stepper/stepper.component.scss` on HEAD still has `#007ACC`, while `fix/stepper-active-color` has `var(--bs-primary)`
- **Evidence**: `screenshots/08-stepper-active-badge.png`
- **Root cause hypothesis**: by design, the fix is on a separate branch that is not merged into `feature/payu`
- **User impact**: none for PayU; the badge ignores event themes until the branch is merged
- **Recommended fix**: merge `fix/stepper-active-color`, then check the badge once more
- **Workaround**: none needed

**M2. PL bundle falls back to EN for PayU keys**
- **Spec requirement**: FR12: PL keys (label "PayU: BLIK, szybki przelew, karta") go on `fix/pl-translations`
- **Expected**: on `feature/payu`, EN fallback (stated in the brief)
- **Actual**: `/api/v2/public/i18n/bundle/pl` returns the EN PayU label and cancel message; the event offers no PL switch
- **Evidence**: `screenshots/19-overview-lang-pl.png`
- **Root cause hypothesis**: by design, PL keys are on a separate branch
- **User impact**: Polish attendees see the PayU label and cancel notice in English until the branch is merged
- **Recommended fix**: merge `fix/pl-translations`, then check with an event that has PL enabled
- **Workaround**: system-level translation override

### 5.4 Cosmetic

**C1. New admin shows raw "PAYU" instead of "PayU"**
- **Spec requirement**: Visual Design, component:admin-payment-icon: "`alfio-payment-method` renders the raw `PAYU`, consistent with `SAFERPAY`"
- **Expected**: credit-card icon + raw proxy name (the mockup sketch shows "PayU")
- **Actual**: credit-card icon + "PAYU"
- **Evidence**: `screenshots/20-new-admin-reservations-payu-icon.png`
- **Root cause hypothesis**: the component prints `this.method` verbatim (existing behaviour for every provider)
- **User impact**: negligible; consistent with other providers
- **Recommended fix**: none (accepted in the spec); optionally a display-name map later
- **Workaround**: n/a

**C2. PayU secrets render as plain text in legacy admin**
- **Spec requirement**: Visual Design: "The secret inputs render as plain text, a deviation from the mockup per I6"
- **Expected**: per the mockup, masked; per the spec, plain text (accepted)
- **Actual**: `PAYU_CLIENT_SECRET` and `PAYU_SECOND_KEY` are `input[type=text]` showing values
- **Evidence**: `screenshots/01-admin-system-payu-panel.png`
- **Root cause hypothesis**: the existing `<setting>` directive does not mask; the same applies to the other providers
- **User impact**: secrets visible to anyone looking at the admin screen (the values shown are PayU's public sandbox credentials)
- **Recommended fix**: none in this task; possible follow-up for all providers
- **Workaround**: n/a

**C3. Payment radios in a horizontal row, not the vertical list of the mockup**
- **Spec requirement**: screen:payment-method-selector (mockup shows a vertical col-md-5 list + col-md-7 panel)
- **Expected**: the radio rendered by the existing `@for` loop (no template change)
- **Actual**: existing horizontal layout with 2 methods; the PayU panel sits below the radios
- **Evidence**: `screenshots/07-overview-payu-selected.png`
- **Root cause hypothesis**: the existing selector picks the horizontal layout for few methods; the mockup sketched the vertical variant
- **User impact**: none
- **Recommended fix**: none
- **Workaround**: n/a

## 6. Console & Network Errors
| Source (file:line) | Message | Frequency | Severity | Impact |
|---|---|---|---|---|
| https://test.assets.payu.com/poses/og5Hy59e/config.json:0 | 404 on PayU sandbox asset | Every PayU page load (4×) | Cosmetic | Third-party sandbox; no effect |
| https://dev-charitysimulator.simulators.test.payudc.net/icons/siepomaga.png:0 | ERR_NAME_NOT_RESOLVED | Every PayU page load (4×) | Cosmetic | Third-party sandbox; no effect |
| bank-simulator-merch-prod.snd.payu.com/favicon.ico, localhost:8181/favicon.ico | 404 favicon | Occasional | Cosmetic | None |
| http://localhost:8181/admin/api/organization/NaN/promo-code:0 | 500 on organization settings page | Once per page load | Minor | Pre-existing, unrelated to PayU (not in the feature diff); the PayU panel renders correctly |
| localhost:5173 (Vite) | "Lit is in dev mode", Lit "scheduled an update after an update completed" warnings | Per new-admin load | Cosmetic | Dev-mode only |

No PayU-related console errors on alf.io pages.

## 7. Spec Alignment
- **Fully implemented**: FR1 order creation + redirect (PLN); FR4/FR6 return callback → processing → force-check → success; FR6 `error` path → `overview?paymentCancelled=true`; FR10 `PAYU` model entry, `payu-payment-proxy` panel, cancellation toast (literal `true` only, shown once); FR11 legacy label, system + organization panels with `#PAYMENT` header, new admin `credit-card` icon; FR12 EN keys; one remote order per attempt (a new order ID after a cancelled one)
- **Partially implemented**: FR12 PL keys and FR13 stepper colour are not on `feature/payu` (on `fix/pl-translations` and `fix/stepper-active-color`, as the spec plans)
- **Not implemented**: _None._ (Webhooks, refunds and double-submit were not exercised in the browser: notifications cannot reach localhost, and refunds and the re-confirm matrix are covered by the integration tests.)
- **Extra (unspecified) behavior**: _None observed._

## 8. Variances from Plan
- Created a new event (`payu-verify-1107`) with PayU **and** Offline payment instead of PayU only, so method switching could be checked.
- Scenario 5: there is no language switch for an EN-only event, so the PL fallback was checked through the i18n bundle API.
- Scenario 4 screenshot `16-cancelled-overview-toast.png` was taken by opening `overview?paymentCancelled=true` directly, because the real-cancel toast auto-hid before capture. The toast text after the real cancel was verified in the DOM (3 real cancels).
- Added a check of the organization-level PayU panel and of the `paymentCancelled=error.foo` negative case (not in the brief).

## 9. Evaluation Against Exit Criteria
| Criterion (from spec) | Status | Evidence |
|---|---|---|
| "The sandbox e2e completes a PLN purchase via return + force-check" | ✅ | §4.3, `screenshots/14-reservation-success.png` |
| "The PayU option shows the correct label, icon and description." | ✅ | §4.2, `screenshots/07-overview-payu-selected.png` |
| "The admin panels render in system and organization settings" | ✅ | `screenshots/01-admin-system-payu-panel.png`, `screenshots/22-admin-organization-payu-panel.png` |
| "the new admin shows the card icon for PAYU" | ✅ | `screenshots/20-new-admin-reservations-payu-icon.png` |
| "One remote order per payment attempt" (browser-observable part) | ✅ | Order IDs differ per attempt after a cancel (§4.4 step 5) |
| Attendee who cancels "come[s] back to the overview with a clear message" | ✅ | `screenshots/16-cancelled-overview-toast.png`, `screenshots/17-reload-no-toast.png` |
| FR13 "the active badge uses `var(--bs-primary)`" | ❌ | `screenshots/08-stepper-active-badge.png` (fix on `fix/stepper-active-color`, expected) |

## 10. Recommendations
- **Must fix before merge**: _None._
- **Should fix soon**: §5.3 M1 and §5.3 M2: merge `fix/stepper-active-color` and `fix/pl-translations`, then check the badge colour and the PL label at runtime.
- **Nice-to-have**: §5.4 C1 (display-name map for payment methods in the new admin), §5.4 C2 (mask secret settings for all providers); the pre-existing `organization/NaN/promo-code` 500 (§6), separately from this task.

## 11. Artifacts
- **Screenshots**: `verification/screenshots/` (23 files)
- **Visual-fidelity report**: `verification/visual-fidelity.md`
- **Console log dump**: inline in §6

## 12. Conclusion
Verdict: ⚠️ GO WITH CAVEATS. Every PayU behaviour that belongs to `feature/payu` works in a real browser against the PayU sandbox: configuration, the option and panel, a completed PLN purchase via force-check, and a clean one-time cancellation flow. The caveats are the stepper colour and the PL strings, which the branch plan puts on separate branches. Next step: merge `feature/payu`, then check the badge and the PL label after `fix/stepper-active-color` and `fix/pl-translations` land.
