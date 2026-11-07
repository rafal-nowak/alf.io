# TDD Green Gate

## TL;DR
All 7 tests in `PayUManagerLifecycleTest` pass on feature/payu HEAD 6475c6ecb: the 6 former defect tests and the fixture control. They failed on the WIP draft (see tdd-red-gate.md). The full build is also green (1047 tests; plo-pl 1050).

## Results (2026-11-07T15:50:32Z)
| Test | Red (draft) | Green (HEAD) |
|------|-------------|--------------|
| doPaymentMustNotCreateSecondOrderWhileFirstAwaitsConfirmation | FAIL | ok |
| doPaymentMustNotCreateSecondOrderWhileFirstIsBeingPaid | FAIL | ok |
| doPaymentCancelsStillNewOrderBeforeCreatingAnotherOne | FAIL | ok |
| newOrderIsNotReportedAsPaymentInProgress | FAIL | ok |
| signedRefundNotificationIsAcknowledged | FAIL | ok |
| signedNotificationWithoutExtOrderIdDoesNotThrow | FAIL | ok |
| fixtureCreatesOrderWhenNoTransactionExists (control) | ok | ok |

Command: `./gradlew test --tests 'alfio.manager.payment.PayUManagerLifecycleTest'` → BUILD SUCCESSFUL, 7 tests, 0 failures.
