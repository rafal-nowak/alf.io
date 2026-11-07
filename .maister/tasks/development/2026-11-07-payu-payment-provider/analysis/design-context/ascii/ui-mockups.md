# UI Mockups: PayU payment provider

**Generated**: 2026-11-07 · **Feature Type**: Enhancement · **Format**: ASCII

## TL;DR
- Four touchpoints, all built from components that already exist. No new visual patterns.
- Public: a new `PAYU` radio option plus an `app-payu-payment-proxy` panel that copies the Saferpay layout; the overview button reads "Pay <price>".
- Stepper: the current-step badge uses `var(--bs-primary)` instead of `#007ACC` (a one-line SCSS change).
- Legacy admin: a Bootstrap 3 PayU panel that copies the Saferpay panel, in `system.html` and `organization.html`.
- New admin: `paymentMethodIcon()` maps `PAYU` to `credit-card`, so `alfio-payment-method` shows the icon followed by "PayU".

## Key Decisions
- The PayU proxy panel copies `saferpay-payment-proxy.component.html` (an `h4.text-body-secondary` title and a description), because both are redirect-to-hosted-checkout providers.
- The icon is `['fas','credit-card']`, already registered in `app.module.ts`. A PayU brand icon would mean a new asset and a brand-usage review.
- Legacy admin panels reuse the `<setting>` directive in `ng-repeat`, so every key renders with the same input or toggle as the Saferpay keys.
- New admin: the only change is a new `case 'PAYU':` in `frontend/admin/src/service/reservation-format.ts`. The component stays as it is, which fits the style guide rule on reuse.

## Open Questions / Risks
- **Radio id `PAYU`**: radio ids are `PaymentMethodId` values (`CREDIT_CARD`, `IDEAL`, ...), not proxy names. A `PAYU` id needs a new entry in the `PaymentMethodId` union and in the method-details map in `frontend/public/src/app/model/event.ts`, plus the label key `reservation-page.payment-method.payu`. If PayU instead registers under `CREDIT_CARD`, the option shows the existing "Credit card" label and the PayU text can only appear in the proxy panel.
- **Label is mixed Polish/English**: "PayU: BLIK, szybki przelew, karta" goes in `public.properties` (English). Either accept it as a brand string or translate it to "BLIK, fast transfer, card" and let POEditor provide `pl`.
- **organization.html**: the `#PAYMENT` page-header `ng-if` (line 135) lists every provider. Add `|| organizationConf.paymentPayu` to it, or an organization that has only PayU configured gets no section header.
- **Stepper contrast**: white check icon on `--bs-primary` (#0d6efd by default) is about 4.5:1, borderline for small glyphs. If a theme overrides `--bs-primary` with a lighter colour, the contrast drops.
- **PAYU_CLIENT_SECRET / PAYU_SECOND_KEY**: confirm these are flagged as secret config keys, so `<setting>` masks them the way it masks the other providers' secrets.

---

<a id="payment-method-selector"></a>
## Mockup 1 — screen:payment-method-selector

`frontend/public/src/app/reservation/payment-method-selector/payment-method-selector.component.html` (vertical layout, PAYU selected)

```
┌─ Overview page · payment section ─────────────────────────────────────────────┐
│ col-md-5 (radio list, EXISTING loop)    │ col-md-7 (proxy panel)               │
│                                         │                                      │
│ ( ) [fa credit-card] Credit card        │  NEW app-payu-payment-proxy          │
│ ────────────────────────────────────────│  (copy of saferpay-payment-proxy)    │
│ (•) [fa credit-card] PayU: BLIK,        │                                      │
│     szybki przelew, karta      ← NEW    │  PayU               ← h4.text-body-  │
│     input#PAYU  label#PAYU-label        │                        secondary     │
│     .fw-bold .border-top/.border-bottom │                                      │
│ ────────────────────────────────────────│  You will be redirected to PayU's    │
│ ( ) [fa money-bill] Bank transfer       │  secure checkout to complete the     │
│     .text-body-secondary (not selected) │  purchase.    ← .text-body-secondary │
├─────────────────────────────────────────┴──────────────────────────────────────┤
│                                    [ Back ]          [ Pay € 120.00 ] EXISTING │
│                                                       └ reservation-page.pay   │
└────────────────────────────────────────────────────────────────────────────────┘
```
- The option comes from the existing `@for(methodID of sortedAvailablePaymentMethodIDs)` loop. No template change is needed for the radio.
- NEW `<app-payu-payment-proxy [proxy] [method] (paymentProvider)="registerCurrentPaymentProvider($event)">` goes next to `<app-saferpay-payment-proxy>`, guarded by `*ngIf="matchProxyAndMethod"`.
- The description is a NEW key `reservation-page.payment.payu.description`, modelled on `...saferpay.description`.
- With a single active method, the existing `activePaymentsCount === 1` branch shows the `h4` with the icon and label instead of the radios.

<a id="stepper-active-badge"></a>
## Mockup 2 — component:stepper-active-badge

`frontend/public/src/app/stepper/stepper.component.html` + `stepper.component.scss:44-46`

```
  (✓)──────────────(■)──────────────( $ )──────────────( 👍 )
  green             CURRENT          grey               grey
  text-bg-success   .wizard2-current .badge
  Step 1            **Step 2** (bold, black)  Step 3     Step 4

  BEFORE: .wizard2 .wizard2-current .badge { background-color: #007ACC; }
  AFTER:  .wizard2 .wizard2-current .badge { background-color: var(--bs-primary); }  ← MODIFIED
```
- Markup does not change. The badge now follows the Bootstrap 5.3 theme in `frontend/public/src/styles.scss`, and with it any event-specific theme.

<a id="admin-payu-settings"></a>
## Mockup 3 — screen:admin-payu-settings

`src/main/webapp/alfio-admin-v1/angular-templates/admin/partials/configuration/system.html` (after the Saferpay panel, around line 304) and `organization.html` (after line 253)

```
┌ panel panel-default ─ EXISTING ────────────────────────────────┐
│ [fa credit-card] Saferpay                                      │
├────────────────────────────────────────────────────────────────┤
│ ...SAFERPAY_* settings...                                      │
└────────────────────────────────────────────────────────────────┘
┌ panel panel-default ─ NEW  data-ng-if="systemConf.paymentPayu" ┐
│ [fa credit-card] PayU                         .panel-title     │
├────────────────────────────────────────────────────────────────┤
│ PAYU_ENABLED        [ toggle      ]                 [delete]   │
│ PAYU_LIVE_MODE      [ toggle      ]                 [delete]   │
│ PAYU_POS_ID         [____________ ]                 [delete]   │
│ PAYU_CLIENT_ID      [____________ ]                 [delete]   │
│ PAYU_CLIENT_SECRET  [•••••••••••• ]                 [delete]   │
│ PAYU_SECOND_KEY     [•••••••••••• ]                 [delete]   │
│  └ <setting data-obj="setting" data-display-delete-if-needed   │
│     ng-repeat="setting in systemConf.paymentPayu.settings">    │
└────────────────────────────────────────────────────────────────┘
```
- Organization variant: same markup, with `organizationConf.paymentPayu` and `organizationConf.delete(config)`. Add `paymentPayu` to the `#PAYMENT` header condition.
- Each row is rendered by the existing `<setting>` directive, so labels and descriptions come from the backend config key metadata.

<a id="admin-payment-icon"></a>
## Mockup 4 — component:admin-payment-icon

`frontend/admin/src/components/payment-method.ts` (`<alfio-payment-method method="PAYU">`) in a reservation row

```
┌ Reservations table (new admin) ───────────────────────────────────────────┐
│ ID       │ Name          │ Amount    │ Payment          │ Status          │
├──────────┼───────────────┼───────────┼──────────────────┼─────────────────┤
│ 3f2a…    │ Jan Kowalski  │ 120,00 zł │ [▭] SAFERPAY     │ COMPLETE        │  EXISTING
│ 9c1e…    │ Anna Nowak    │  80,00 zł │ [▭] PayU         │ COMPLETE        │  NEW
│          │               │           │  └ sl-icon name="credit-card"       │
│          │               │           │    gray-500 icon, gray-600 small text│
└──────────┴───────────────┴───────────┴──────────────────┴─────────────────┘
```
- MODIFIED `paymentMethodIcon()` in `frontend/admin/src/service/reservation-format.ts`: add `case 'PAYU':` to the `credit-card` group.
- The component renders `this.method` verbatim. For it to show "PayU" instead of "PAYU", the backend value must be `PayU`, or a display-name map must be added. Today the existing rows show raw proxy names (e.g. `SAFERPAY`), so "PAYU" would be the consistent choice. Confirm which one is wanted.

---
*Generated by ascii-mockup-generator subagent*
