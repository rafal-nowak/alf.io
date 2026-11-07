# Visual Coverage Matrix

## TL;DR
All 4 screen and component IDs in `analysis/design-context/INDEX.md` are covered by at least one task group (4/4).
- Group 10 covers the public selector.
- Group 11 covers both admin touchpoints.
- Group 17 covers the stepper badge, on its own branch.
- Group 15 adds runtime E2E coverage of the public selector.

## Key Decisions
- **`screen:admin-payu-settings`**: the secret inputs stay plain text (I6). This deviation from the mockup's masked dots is accepted, and the Group 11 acceptance criteria record it.
- **`component:admin-payment-icon`**: shows the raw `PAYU`, consistent with `SAFERPAY`, as the spec decided. The mockup's "PayU" display variant is not built.

Source: `analysis/design-context/INDEX.md`

| Screen/Component ID | Covered By Task Group(s) | Status |
|---------------------|--------------------------|--------|
| screen:payment-method-selector | Group 10 (Public frontend PayU option and cancellation notice); runtime check in Group 15 (Sandbox E2E) | ✅ |
| component:stepper-active-badge | Group 17 (Stepper active badge colour on `fix/stepper-active-color`) | ✅ |
| screen:admin-payu-settings | Group 11 (Admin consoles: PayU label, settings panels and icon) | ✅ |
| component:admin-payment-icon | Group 11 (Admin consoles: PayU label, settings panels and icon) | ✅ |

## Uncovered Items

All screens are covered.
