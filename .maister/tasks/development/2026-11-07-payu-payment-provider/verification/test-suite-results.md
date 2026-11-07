# Test Suite Results: PayU payment provider (after verification fix batch 1)

## TL;DR
- Status: **passed**. `./gradlew build` was green on HEAD 938d5ca3d (BUILD SUCCESSFUL in 3m 29s).
- 1064 tests: 1064 passing, 0 failing, 0 errors, 1 skipped (`MigrationValidatorTest.testMigration`, the same skip as the baseline). Pass rate 100%.
- No regressions. 17 more tests than the 1047 baseline, all from new PayU tests in fix batch 1. 106 tests in total cover PayU.
- The first attempt was invalid because of outside interference, not a code problem. See Open Questions / Risks.

## Open Questions / Risks
- **Concurrent Gradle runs clobber each other.** The first full build (17:10:55) failed in `:test` with `NoSuchFileException: build/test-results/test/binary/in-progress-results-generic.bin`. A second Gradle daemon (pid 40524) started at 17:12:36 and ran only the PayU test classes in the same worktree, which wiped `build/test-results/test` mid-run. No test failed. I re-ran the build once, nothing else was running, and it passed. The orchestrator should not run targeted `./gradlew test` calls while a full build is running in the same worktree.
- In the rerun, compile, forbiddenApis, forbiddenApisTest and license were UP-TO-DATE ("3 executed, 19 up-to-date"). They had already executed and passed against the same commit in the first attempt, so all checks are still covered for HEAD 938d5ca3d.

## Test command
```
./gradlew build -Dpgsql.version=16 --console=plain > /private/tmp/claude-501/-Users-rafalnowak-Dev-alf-io/0a1e2fd7-65c8-4767-83f8-30bde7ad2471/scratchpad/full-build-reverify.log 2>&1
```
Valid run: 17:14:59 to 17:18:28 CET, 2026-11-07, branch feature/payu, HEAD 938d5ca3d, clean working tree.

## Metrics
I counted these from `build/test-results/test/*.xml`: 162 suite files, all timestamped 17:18 (from this run).

| Metric | Value |
|---|---|
| Total | 1064 |
| Passing | 1063 |
| Failing | 0 |
| Errors | 0 |
| Skipped | 1 |
| Pass rate | 100% (of executed tests) |
| Baseline | 1047 total, 0 failures, 1 skipped |
| Delta | +17 tests (new PayU tests) |

The table counts the skipped test in Total but not in Passing, so Passing is 1063 here. The TL;DR and the handback count it as passing (1064 passing).

Other gates that passed: license header check, forbiddenApis / forbiddenApisTest, admin and public frontend builds, `TestCheckRestApiStability`.

### PayU test classes (106 tests)
| Class | Tests |
|---|---|
| alfio.manager.payment.PayUManagerTest | 68 |
| alfio.controller.payment.api.payu.PayUPaymentWebhookControllerTest | 11 |
| alfio.controller.payment.PayUCallbackControllerTest | 8 |
| alfio.manager.payment.PayUManagerLifecycleTest | 7 |
| alfio.controller.api.v2.user.reservation.PayUReservationFlowIntegrationTest | 7 |
| alfio.manager.payment.payu.PayUSignatureTest | 5 |

## Failures
None.

## Regressions
None (0).

## Skipped
- `alfio.MigrationValidatorTest.testMigration()`: also skipped in the baseline. Pre-existing, unrelated to the change.

## Issues
| Severity | Description | Fixable |
|---|---|---|
| info | The first build attempt was invalidated by a concurrent Gradle test run in the same worktree. This is a process issue, not a code issue. | n/a |

Issue counts: critical 0, warning 0, info 1.
