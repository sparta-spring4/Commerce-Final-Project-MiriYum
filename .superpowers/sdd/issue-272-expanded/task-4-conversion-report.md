# Task 4 report: Waiting reservation conversion orchestration and races

## Status

- Implementation and TDD micro-cycles completed.
- Final focused unit/MySQL verification completed; commit evidence is appended below.
- No push or PR was performed.

## Production contract implemented

- Added the internal `WaitingReservationConversionService` boundary.
- `begin`: short Waiting preflight transaction, ambient caller transaction suspension around an
  independently committed Payment waiting-deposit preparation, then a short locked
  `WAITING -> RESERVATION_CONVERTING` transition.
- `fail`: exact payment/version locked `RESERVATION_CONVERTING -> WAITING`; membership retained.
- `completeVerified`: locks Waiting first and then the Payment row in the same transaction; a
  matching current PAID conversion with no refund ledger row becomes `RESERVATION_CONVERTED`,
  deletes exactly one active membership, and appends one SYSTEM audit/event.
- Exact converted replay has no Payment call or duplicate membership/audit/event effects.
- Cancellation/store-closure winners remain terminal and record/replay one deterministic durable
  compensation from the verified historical-paid Payment snapshot.
- Payment verification requires exact payment ID, consumer owner, source type
  `WAITING_RESERVATION_DEPOSIT`, source reference, historical `paidAt`, and an allowed historical
  paid status. A new conversion may complete only from current `PAID`.
- Reservation integration is scalar-only (`finalReservationId`); no Reservation entity, repository,
  service, or migration is imported or accessed.

## TDD RED -> GREEN evidence

Commands below were run from `backend` unless stated otherwise.

1. Begin boundary
   - RED: `./gradlew test --tests "...WaitingReservationConversionServiceTest"` failed compilation
     in about 3 seconds because `WaitingReservationConversionService`/`BeginCommand` did not exist.
   - GREEN: focused begin test, 1/1 passed in 8 seconds.
2. Failure boundary
   - RED: focused conversion test compilation failed in 4 seconds because `fail(long,long,String)`
     did not exist.
   - GREEN: begin/fail focused class, 2/2 passed in 7 seconds.
3. Payment waiting-deposit verifier
   - RED: focused `PaymentServiceTest` compilation failed in 4 seconds with five missing
     service/transaction query and validator symbols.
   - GREEN: `PaymentServiceTest`, 8/8 passed in 8 seconds.
4. Verified immutable snapshot
   - RED: focused compilation failed in 4 seconds because
     `VerifiedWaitingReservationDeposit` did not exist.
   - GREEN: Payment 8 plus conversion 2, 10/10 passed in 10 seconds.
5. Verified completion
   - RED: focused compilation failed in 4 seconds because `CompletionCommand` did not exist.
   - First GREEN attempt compiled and executed 15 tests, but 14 passed and one test failed with
     Mockito `InvalidUseOfMatchersException`: the CLOSED compensation assertion mixed raw arguments
     with `matches(...)`. This was a test-harness assertion error, not hidden as a product failure.
   - Corrected all arguments in that verification to Mockito matchers.
   - GREEN: `PaymentServiceTest` 8/8 plus `WaitingReservationConversionServiceTest` 7/7,
     15/15 passed in 8 seconds.
6. MySQL timestamp precision defect
   - RED actual MySQL command:
     `./gradlew integrationTest --tests "...WaitingLedgerConcurrencyIT.paidConversionAndOperatorCancelRaceCommitExactlyOneTerminalTransition"`
     failed 1/1 in 37 seconds before the race. `requireMatchingPreparation` compared a nanosecond
     command expiry to MySQL `DATETIME(6)` preparation with `Instant.equals`.
   - Added unit regression
     `beginAcceptsMySqlMicrosecondPrecisionPreparationForNanosecondCommand`; RED 1/1 in 7 seconds
     with the same `IllegalStateException`.
   - Minimal fix: null guard and truncate both instants to `ChronoUnit.MICROS` at the comparison.
   - GREEN: full conversion unit class 8/8 in 7 seconds.
   - GREEN actual MySQL rerun once: cancel race 1/1 in 34 seconds.

## Actual MySQL focused evidence

All invocations used MySQL 8.0.40 Testcontainers and a five-minute cutoff.

1. Payment verified source/owner/ref snapshot
   - Command: `./gradlew integrationTest --tests "com.miriyum.domain.payment.service.PaymentPersistenceIT.verifiesPaidWaitingDepositSnapshotAndRejectsOrdinaryDepositWithSameReference"`
   - Result: BUILD SUCCESSFUL in 43 seconds; fresh XML 1/1, 0 failures/errors/skips.
2. Verified completion vs operator cancel
   - Initial RED and precision fix are recorded above.
   - Final focused result: BUILD SUCCESSFUL in 34 seconds; fresh XML 1/1.
3. Cancel first -> paid callback replay -> compensation -> real refund convergence
   - Command: `./gradlew integrationTest --tests "...WaitingLedgerConcurrencyIT.cancelledPaidConversionCallbackConvergesOneCompensationAndRealRefundLedger"`
   - Result: BUILD SUCCESSFUL in 1 minute 10 seconds; fresh XML 1/1. Verified one deterministic
     compensation, one completed refund and `REFUND_COMPLETED` ledger, Payment `REFUNDED`, and
     compensation `COMPLETED`.
4. Verified completion vs claimed closure, and closure-first callback replay
   - One invocation with two `--tests` method filters.
   - Result: BUILD SUCCESSFUL in 1 minute 8 seconds; fresh XML 2/2. Verified one terminal
     conversion/closure transition and deterministic one-row CLOSED compensation replay.

The last two context shutdowns logged unrelated reservation-hold/store-schedule scheduled jobs
attempting JDBC access after the Testcontainer had stopped (`connection refused`). The focused test
XML and Gradle result remained green. Waiting closure and compensation runner initial delays were
set to 600000 ms; the warning came from unrelated schedulers and is retained here as a concern.

## Files and allowlist audit

Task 4 tracked paths:

- `backend/src/main/java/com/miriyum/domain/payment/dto/PaymentContracts.java`
- `backend/src/main/java/com/miriyum/domain/payment/service/PaymentService.java`
- `backend/src/main/java/com/miriyum/domain/payment/service/PaymentTransactionService.java`
- `backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingReservationConversionService.java`
- `backend/src/test/java/com/miriyum/domain/payment/service/PaymentServiceTest.java`
- `backend/src/test/java/com/miriyum/domain/payment/service/PaymentPersistenceIT.java`
- `backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingReservationConversionServiceTest.java`
- `backend/src/test/java/com/miriyum/domain/reservation/waiting/WaitingLedgerConcurrencyIT.java`
- `docs/specs/waiting/spec.md`
- `docs/specs/payment/spec.md`

`PaymentPersistenceIT` is included by the latest Issue/parent exact Task 4 instruction even though
the local brief's copied list omits that line. No other tracked path is outside the allowlist.

Immutable/no-access checks:

- V36, V40, and V41 are unchanged from Task 4 HEAD.
- No migration was created or edited in Task 4.
- No Reservation entity, repository, service, DTO, controller, or test path was edited.
- Existing Waiting ledger/closure production code and Task 3 compensation production code were not
  changed; they coordinate through their existing team-row locks and compensation API.

## Final verification

Fresh unit command:

`./gradlew test --tests "com.miriyum.domain.payment.service.PaymentServiceTest" --tests "com.miriyum.domain.reservation.waiting.service.WaitingReservationConversionServiceTest"`

- BUILD SUCCESSFUL in 4 seconds.
- Fresh XML: Payment 8/8 plus Waiting conversion 8/8 = 16/16; zero
  failures/errors/skips.

Fresh actual MySQL command (one invocation):

`./gradlew integrationTest --tests "com.miriyum.domain.payment.service.PaymentPersistenceIT.verifiesPaidWaitingDepositSnapshotAndRejectsOrdinaryDepositWithSameReference" --tests "com.miriyum.domain.reservation.waiting.service.WaitingLedgerConcurrencyIT.paidConversionAndOperatorCancelRaceCommitExactlyOneTerminalTransition" --tests "com.miriyum.domain.reservation.waiting.service.WaitingLedgerConcurrencyIT.cancelledPaidConversionCallbackConvergesOneCompensationAndRealRefundLedger" --tests "com.miriyum.domain.reservation.waiting.service.WaitingLedgerConcurrencyIT.paidConversionAndClaimedClosureRaceCommitExactlyOneTerminalTransition" --tests "com.miriyum.domain.reservation.waiting.service.WaitingLedgerConcurrencyIT.closedPaidConversionCallbackReplayPreservesTerminalAndOneCompensation"`

- BUILD SUCCESSFUL in 1 minute 46 seconds, below the five-minute cutoff.
- Fresh XML: Payment persistence 1/1 plus Waiting MySQL 4/4 = 5/5; zero
  failures/errors/skips.
- The same unrelated shutdown scheduler/closed-connection warning appeared after the test methods;
  Gradle exit was zero and all fresh XML was green.

Final static checks:

- `git diff --check`: exit 0 (only line-ending conversion warnings).
- Exact allowlist: 10 changed tracked paths, 0 outside allowlist.
- V36/V40/V41 diff from Task 4 HEAD: exit 0/no diff.
- Reservation entity/repository diff: 0 paths.
- Task 4 commit: `9fb1cce2` (`feat(waiting): orchestrate reservation conversion`).

## Review fixes: ambient transaction and in-flight refund serialization

### Important 1: ambient caller rollback

- RED actual MySQL command:
  `./gradlew integrationTest --tests "com.miriyum.domain.reservation.waiting.WaitingLedgerConcurrencyIT.ambientRollbackDoesNotOrphanCommittedWaitingConversionPayment"`
- RED result: 1/1 failed in 39 seconds. The outer `TransactionTemplate` rolled back after
  `conversion.begin`; the independently committed Waiting team was `RESERVATION_CONVERTING`, but
  the Payment row had joined and rolled back with the ambient transaction.
- Fix: `begin` wraps `PaymentService.prepareWaitingReservationDeposit` in a
  `PROPAGATION_NOT_SUPPORTED` template. The ambient transaction is suspended, so the internal
  Payment `REQUIRED` transaction commits independently before Waiting starts conversion.
- GREEN focused unit: `WaitingReservationConversionServiceTest`, 8/8 in 8 seconds.
- GREEN actual MySQL rerun: 1/1 in 35 seconds.

### Important 2: stale PAID snapshot while refund is in flight

- RED actual MySQL command:
  `./gradlew integrationTest --tests "com.miriyum.domain.reservation.waiting.WaitingLedgerConcurrencyIT.processingRefundBlocksStalePaidSnapshotFromCompletingConversion"`
- RED result: 1/1 failed in 1 minute 22 seconds. The refund claim had committed a `PROCESSING`
  refund and the provider mock was blocked, yet the old pre-read PAID snapshot allowed conversion.
  The latch was released in `finally`; the refund future completed and there was no deadlock.
- RED focused unit compilation: Payment/conversion test invocation failed in 12 seconds with seven
  missing completable-verifier symbols.
- Fix: completion now locks Waiting first, then calls a Payment verifier in the same transaction.
  Payment locks its row with the existing `findByPaymentIdForUpdate`; new conversion requires current
  `PAID` and zero refund rows, including `PROCESSING`. Exact converted replay bypasses Payment.
  Cancellation/closure winners use the locked historical-paid verifier for compensation replay.
- GREEN focused unit: `PaymentServiceTest` 9/9 plus
  `WaitingReservationConversionServiceTest` 8/8 = 17/17 in 14 seconds.
- GREEN actual MySQL rerun: 1/1 in 1 minute 14 seconds.
- Both review MySQL runs retained the known unrelated scheduler shutdown connection-refused warning;
  Gradle and fresh XML were green.

### Review-fix final focused verification

- Unit command:
  `./gradlew test --rerun-tasks --tests "com.miriyum.domain.payment.service.PaymentServiceTest" --tests "com.miriyum.domain.reservation.waiting.service.WaitingReservationConversionServiceTest"`
- Result: BUILD SUCCESSFUL in 43 seconds; fresh XML Payment 9/9 plus Waiting conversion 8/8 =
  17/17, zero failures/errors/skips. A preceding identical command without `--rerun-tasks`
  completed `UP-TO-DATE` in 1 second and was not counted as fresh execution evidence.
- Actual MySQL command, one invocation:
  `./gradlew integrationTest --rerun-tasks --tests "com.miriyum.domain.reservation.waiting.service.WaitingLedgerConcurrencyIT.ambientRollbackDoesNotOrphanCommittedWaitingConversionPayment" --tests "com.miriyum.domain.reservation.waiting.service.WaitingLedgerConcurrencyIT.processingRefundBlocksStalePaidSnapshotFromCompletingConversion"`
- Result: BUILD SUCCESSFUL in 1 minute 40 seconds, below the five-minute cutoff; fresh XML 2/2,
  zero failures/errors/skips. The known unrelated store-schedule shutdown connection-refused warning
  appeared after the test methods without changing Gradle/XML success.
- Before the successful MySQL invocation, sandbox shell startup/cache attempts did not execute any
  test: two WindowsApps process-spawn failures, two wrapper distribution-download failures, one
  cached-Gradle plugin-resolution failure, and one mistyped package filter that reported
  `No tests found`. The exact package-filtered actual MySQL invocation above was then run once.

Review-fix static checks:

- `git diff --check`: exit 0; only Git LF-to-CRLF informational warnings.
- Eight tracked product/spec/test paths changed from Task 4 HEAD; all eight are in the exact Task 4
  allowlist. This ignored evidence report is force-added separately as requested.
- V36, V40, and V41 diff from Task 4 HEAD: exit 0/no diff.
- Reservation non-Waiting production/test diff: zero paths; Reservation entity, repository, and
  migrations remain untouched.
