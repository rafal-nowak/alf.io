# Visual Fidelity Report

**Mode**: Report-only (does NOT gate completion)
**Comparison**: LLM-judged structural match (not pixel-perfect)
**Source**: analysis/design-context/INDEX.md
**Captured**: verification/screenshots/

## TL;DR
- 4 design IDs compared: 1 match (✓), 2 minor deviations (⚠), 1 drift (✗).
- The PayU selector, panel copy, admin panels and admin icon follow the ASCII mockups structurally. The deviations were already accepted in the spec (plain-text secrets, horizontal radio layout from the existing component).
- The single ✗ is the stepper badge: still `#007ACC` on `feature/payu`, because the fix lives on `fix/stepper-active-color` as planned.

## Open Questions / Risks
- component:stepper-active-badge: confirm at runtime after `fix/stepper-active-color` is merged (source on that branch already has `var(--bs-primary)`).

## Summary
- Total screens compared: 4
- Match (✓): 1
- Minor deviation (⚠): 2
- Substantive drift (✗): 1

## Per-Screen Comparison

### screen:payment-method-selector (⚠ Minor Deviation)
- Mockup: analysis/design-context/ascii/ui-mockups.md#payment-method-selector
- Screenshot: verification/screenshots/07-overview-payu-selected.png, verification/screenshots/06-overview-payment-methods.png
- Radio: `input#PAYU` / `label#PAYU-label`, `fa credit-card` icon, label "PayU: BLIK, fast transfer, card" ✓ (EN label; the mockup shows the PL string, which lives on `fix/pl-translations`)
- Proxy panel: `h4.text-body-secondary` "PayU" + "You will be redirected to PayU's secure checkout to complete the purchase." ✓ (copy matches the mockup word for word)
- Primary action: "Pay zł 12.30" (existing `reservation-page.pay` button) ✓
- Deviation: with 2 methods the existing selector renders the radios in a horizontal row with the panel below, not the vertical col-md-5 / col-md-7 split sketched in the mockup. The mockup itself says "no template change is needed for the radio", so this is the existing layout.
- States covered: unselected, PayU selected, cancelled (toast) — `verification/screenshots/16-cancelled-overview-toast.png`
- Recommendation: none

### component:stepper-active-badge (✗ Substantive Drift)
- Mockup: analysis/design-context/ascii/ui-mockups.md#stepper-active-badge
- Screenshot: verification/screenshots/08-stepper-active-badge.png
- Layout: 4 steps, completed = green check, current = bold label + coloured badge, future = grey ✓
- Drift: current badge computed colour `rgb(0, 122, 204)` (#007ACC). The mockup's AFTER state is `var(--bs-primary)` (#0d6efd on this theme).
- Implementer's justification: the spec (FR13, §10, Branch / Commit Plan) puts this one-line change on `fix/stepper-active-color`. That branch's `stepper.component.scss` has `var(--bs-primary)`; `feature/payu` does not.
- Impact: visual only; the badge does not follow event themes until the branch is merged
- Recommendation: check again after the merge

### screen:admin-payu-settings (⚠ Minor Deviation)
- Mockup: analysis/design-context/ascii/ui-mockups.md#admin-payu-settings
- Screenshot: verification/screenshots/01-admin-system-payu-panel.png, verification/screenshots/22-admin-organization-payu-panel.png
- Layout: Bootstrap 3 `panel panel-default`, heading `fa fa-credit-card` "PayU" ✓; present in both system and organization settings, `#PAYMENT` header present ✓
- Field order: PayU enabled (toggle) → Live Mode (toggle) → POS ID → client_id → client_secret → second key ✓ (matches the mockup order)
- Primary actions: delete buttons per row at system level ✓ (organization rows with no value show no delete button, which is existing `<setting>` behaviour)
- Deviation: `PAYU_CLIENT_SECRET` / `PAYU_SECOND_KEY` are plain text, while the mockup shows masked dots. This was accepted in the spec as I6 and matches the other providers.
- Recommendation: optional follow-up to mask secret settings for all providers

### component:admin-payment-icon (✓ Match)
- Mockup: analysis/design-context/ascii/ui-mockups.md#admin-payment-icon
- Screenshot: verification/screenshots/20-new-admin-reservations-payu-icon.png, verification/screenshots/21-new-admin-transactions-payu-icon.png
- Layout: reservations table, Payment column shows `sl-icon name="credit-card"` followed by the method text ✓
- Copy: text is "PAYU" (raw). The mockup sketch shows "PayU", but its own note and the spec's Visual Design table say the component renders the raw value, consistent with `SAFERPAY`. Treated as a match.
- States covered: completed reservation (reservations list), confirmed payment (transactions list)
