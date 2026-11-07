# Scope Clarifications (Phase 2)

## TL;DR
All gap-analysis decisions resolved by taking the recommended option. At 2026-11-07T13:19Z the owner asked for autonomous execution following recommendations, so they are recorded as auto-approved per user instruction. Scope does not expand.

## Key Decisions
- C1: earlier PayU order still NEW on re-confirm → cancel remotely (DELETE /api/v2_1/orders/{id}) and create a new order; same DELETE implements `ServerInitiatedTransaction.discardTransaction` (fixes B3)
- C2: hardening M4–M8 all on feature/payu with unit tests (customerIp, token cache keyed by clientId+secret hash with 401 invalidation, per-request HTTP timeout, amount/currency/extOrderId validation, extRefundId + refundedAmount)
- I1: rebase feature/payu onto main, drop the Mollie commit (stays on fix/mollie-ignore-unsupported-methods), split WIP into meaningful commits; keep backup ref `backup/feature-payu-wip`; force-push required
- I2: add GET `PayUCallbackController` as continueUrl (error=501 → cancel order + back to overview with error; else force-check → /book)
- I3: offer PayU only for PayU-supported currencies (check in accept/isActive)
- I4: generic `fas credit-card` icon (public) / `credit-card` (new admin); no PayU logo asset
- I5: Docker build command documented in README.md on plo-pl only
- I6: PAYU_CLIENT_SECRET / PAYU_SECOND_KEY keep plain TEXT component type, like other providers

## Open Questions / Risks
- Force-push of feature/payu rewrites a branch already on origin (backup ref kept)
- descriptor.json must be regenerated on feature/payu and again on plo-pl after merges
