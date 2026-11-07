# Phase 1 Clarifications

## TL;DR
Four decisions resolved with the owner on 2026-11-07: PL translations go on a separate fork-only branch, the stepper badge uses `var(--bs-primary)`, e2e confirms the payment via return + force-check (no tunnel), and customerIp becomes a new optional field in `PaymentSpecification`.

## Key Decisions
- PL translations: separate fork branch (e.g. `fix/pl-translations`) with the missing PL keys from the purchase path (Confirm, from/to, payment flow, PayU), merged into `plo-pl`. Upstream gets them via POEditor, not via PR. PayU EN keys stay on `feature/payu`. — keeps PR branches compliant with AGENTS.md
- Stepper active badge: replace hard-coded `#007ACC` with `var(--bs-primary)` — generic fix, upstream PR candidate; brand colour can come from event custom CSS
- E2E on public PayU sandbox: return from PayU + callback/force-check (GET orders/{id}) confirms the reservation; webhook path covered by integration test (WireMock + signature) — notifications cannot reach localhost
- customerIp: new optional field in `PaymentSpecification`, set in `ReservationApiV2Controller` from `HttpServletRequest` (X-Forwarded-For aware) — owner chose explicit data flow over RequestContextHolder

## Q&A
1. PL translations vs AGENTS.md "EN only" → "Osobna gałąź forka"
2. Stepper active colour → "var(--bs-primary)"
3. E2E notification delivery → "Powrót + force-check"
4. customerIp source → "Pole w PaymentSpecification"

Not asked (sensible default): PayU base URL becomes overridable for tests (package-private/constructor hook or config), so WireMock integration tests can run.
