# TDD Red Gate

## TL;DR
`src/test/java/alfio/manager/payment/PayUManagerLifecycleTest.java` reproduces the draft's lifecycle defects. The sanity test (order created when no transaction exists) passes, and all 6 defect tests FAIL against commit 2259c9313 (the rebased WIP draft).

## Failing tests (expected to pass after implementation)
| Test | Defect |
|------|--------|
| doPaymentMustNotCreateSecondOrderWhileFirstAwaitsConfirmation | B1 double charge (WAITING_FOR_CONFIRMATION) |
| doPaymentMustNotCreateSecondOrderWhileFirstIsBeingPaid | B1 (PENDING) |
| doPaymentCancelsStillNewOrderBeforeCreatingAnotherOne | B1/B3 old NEW order stays payable |
| newOrderIsNotReportedAsPaymentInProgress | M2 NEW mapped to TRANSACTION_INITIATED |
| signedRefundNotificationIsAcknowledged | B5 refund notification rejected → endless retries |
| signedNotificationWithoutExtOrderIdDoesNotThrow | B5 NPE on missing extOrderId |

Passing control: fixtureCreatesOrderWhenNoTransactionExists. It checks that the fixture is valid, so the failures above are not artefacts of the setup.

Command: `./gradlew test --tests 'alfio.manager.payment.PayUManagerLifecycleTest'` → 7 tests, 6 failed.
