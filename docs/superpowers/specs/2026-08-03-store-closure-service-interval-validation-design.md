# Store Closure and Service-Interval Validation Design

## Context

Issue #104 adds the Store-owned source data and public contract required by
Reservation Issue #89. PR #84 only answers whether a requested start time is
inside an active reservation-acceptance window. It cannot prove that the full
customer-service interval remains inside operating hours or avoids breaks and
closures.

The Store domain therefore needs two capabilities in one vertical slice:

1. operators can publish regular closures and manage temporary closures; and
2. Reservation can submit store-specific `[startAt, serviceEndAt)` intervals
   for batch validation.

Store never calculates `serviceEndAt` or `occupancyEndAt`. Turnover remains a
Reservation concern and is outside the Store validation interval.

## Scope

### Included

- immutable, versioned regular-closure schedules;
- one-off temporary closures with immediate or future start;
- operator HTTP commands and OpenAPI contracts for both closure types;
- Store-owned persistence, audit records, activation, idempotency, and
  concurrency controls;
- batch validation against store eligibility, reservation windows, operating
  hours, breaks, regular closures, and temporary closures;
- cross-midnight, IANA-zone, and DST-safe interval interpretation;
- Store unit, MockMvc, repository, MySQL integration, and public-contract tests.

### Excluded

- Reservation duration policies and end-time calculation;
- Reservation capacity, duplicate-booking, or snapshot persistence;
- Search and Reservation consumers;
- existing-reservation impact lists, changes, cancellations, notifications,
  refunds, or compensation;
- emergency shutdown;
- frontend or administrator UI.

## Architecture

The implementation remains in `com.miriyum.domain.store` and uses two focused
packages.

- `store.closure` owns closure commands, persistence, audit, activation, and
  operator HTTP endpoints.
- `store.schedule` owns the cross-domain read contract that combines all Store
  schedule sources into one service-interval decision.

The closure package does not depend on Reservation. The schedule validator
accepts value DTOs only and does not expose Store entities, repositories, or
database identifiers.

## Public Reservation Contract

```java
List<StoreServiceIntervalResult> validateServiceIntervals(
        List<StoreServiceIntervalRequest> requests);
```

```java
public record StoreServiceIntervalRequest(
        long storeId,
        Instant startAt,
        Instant serviceEndAt
) {}

public record StoreServiceIntervalResult(
        long storeId,
        StoreServiceIntervalStatus status
) {}

public enum StoreServiceIntervalStatus {
    ACCEPTING,
    NOT_ACCEPTING
}
```

The request constructor rejects non-positive store IDs, null instants, and
`startAt >= serviceEndAt`. It does not enforce service-duration bounds or slot
alignment because Reservation owns those rules.

The service rejects a null list or null elements before querying. An empty
list returns an immutable empty list without repository calls. Non-empty
results preserve input order, count, and duplicates exactly.

The result intentionally exposes no rejection reason. Search and Reservation
need a safe availability decision, while detailed Store state and closure
reasons remain Store internals.

## Regular Closures

### Meaning

A regular closure is a whole local-calendar-day rule. Two rule shapes are
supported:

- `WEEKLY`: a recurring `DayOfWeek`;
- `DATE`: one exact `LocalDate` in the store's IANA time zone.

Recurring partial-day closure is not introduced. Existing break intervals
represent recurring partial-day unavailability. A one-off partial-day closure
uses a temporary closure.

An explicitly published empty regular-closure version means that the store has
no regular closure rules. Missing active regular-closure state is not treated
as an empty schedule; interval validation fails closed until an explicit
version is published.

### Lifecycle

`RegularClosureVersion` follows the existing schedule lifecycle:

```text
DRAFT -> SCHEDULED -> ACTIVE -> RETIRED
   ^          |
   +----------+ publication cancellation

SCHEDULED -> ACTIVATION_FAILED
```

Each version stores `storeId`, monotonic `versionNumber`, `timeZoneId`, status,
effective/activation timestamps, change reason, and immutable ordered entries.
`StoreScheduleState` gains an active regular-closure version pointer and a
regular-closure version allocator.

The scheduled activation worker uses central `Instant`, chooses the earliest
due candidate per store, and locks in the established order: Store row, then
schedule-state row, then target version. Activation replaces the active
pointer and retires the prior active version atomically. Permanent authority,
state, zone, or overlap failures end in `ACTIVATION_FAILED`; transient database
failures leave state unchanged for retry.

### Commands

The operator API follows the existing schedule command shape:

- `PUT /api/v1/store-operator/stores/{storeId}/regular-closures`
  creates a new complete `DRAFT` version;
- `POST /api/v1/store-operator/stores/{storeId}/regular-closures/{version}/publication`
  performs immediate or scheduled publication;
- `POST /api/v1/store-operator/stores/{storeId}/regular-closures/{version}/publication-cancellation`
  returns a future `SCHEDULED` version to `DRAFT`.

The draft request contains unique `weeklyDays` and unique `dates`, each with at
most 366 elements. Empty lists are valid and explicit. Publication and
cancellation reuse the existing publication request meanings and require an
`Idempotency-Key`.

## Temporary Closures

### Persistence and derived state

`TemporaryClosure` stores:

- external closure ID and `storeId`;
- `startAt` and `endAt` as `Instant`;
- the Store IANA zone snapshot;
- public reason category and optional public message;
- cancellation time, change version, and audit metadata.

Allowed public reason categories are `MAINTENANCE`, `STAFFING`,
`PRIVATE_EVENT`, and `OTHER`. The public message is optional, trimmed, and at
most 200 characters. It must not be emitted by the Reservation batch contract.

State is derived from central time instead of relying on a scheduler:

```text
cancelledAt != null      -> CANCELLED
now < startAt            -> SCHEDULED
startAt <= now < endAt   -> ACTIVE
endAt <= now             -> ENDED
```

The query predicate uses the actual interval and cancellation flag, so delayed
background work cannot reopen a closed period.

### Commands

- `POST /api/v1/store-operator/stores/{storeId}/temporary-closures`
  creates an immediate or future closure;
- `PUT /api/v1/store-operator/stores/{storeId}/temporary-closures/{closureId}/end-at`
  changes only the end instant;
- `POST /api/v1/store-operator/stores/{storeId}/temporary-closures/{closureId}/cancellation`
  cancels a non-ended closure.

All commands require the authenticated managing operator and an idempotency
key. Creation requires `startAt < endAt`. An end change requires the new end
to be after both the original start and the command's central time. A
cancelled or ended closure cannot be changed or cancelled again with a new
command; an identical idempotent replay returns the stored outcome.

Creation is rejected if the interval overlaps an active regular-closure rule.
Regular-closure publication is rejected if its effective rules would overlap
an existing non-cancelled temporary closure. Both checks are repeated while
holding the Store and closure-state locks.

## Service-Interval Decision

An input is `ACCEPTING` only when all of the following are true:

1. the Store exists, is approved, is `OPEN`, and has reservations enabled;
2. Store time zone and all required active schedule pointers are present and
   internally consistent;
3. the start instant maps inside an active reservation-acceptance interval;
4. one active business interval contains the entire
   `[startAt, serviceEndAt)` interval;
5. no active break interval overlaps it;
6. no active regular-closure local day overlaps it; and
7. no non-cancelled temporary closure overlaps it.

Intervals are half-open. Touching a break or closure end is allowed; touching
its start is not. A service interval that spans multiple separate operating
intervals is rejected even if every individual instant appears somewhere in
weekly business hours.

The active reservation schedule must still reference the same active
operating version. Any missing row, wrong owner, inactive version, invalid
zone, ambiguous/nonexistent local schedule boundary, or inconsistent pointer
produces `NOT_ACCEPTING` for that input rather than an exception or fabricated
default.

### Time-zone handling

Caller instants identify unique real moments. Store converts them with its own
`ZoneId`; it never trusts a caller zone or system default.

Weekly operating and break boundaries are materialized for the owning local
date. A local boundary must have exactly one valid zone offset. Gap or overlap
boundaries fail closed for affected inputs. Cross-midnight intervals retain
their owning start day and materialize the end on the following local date.

Regular whole-day closures materialize local midnight-to-midnight. If either
boundary is not uniquely resolvable, affected inputs fail closed. Temporary
closures already use instants and need no local-time disambiguation.

## Batch Query Plan

The validator deduplicates store IDs for reads and reconstructs results from
the original request list. Repository work is bounded independently of store
count:

1. batch-load Stores;
2. batch-load `StoreScheduleState` rows;
3. fetch active operating versions with entries by ID;
4. fetch active reservation versions with entries by ID;
5. fetch active regular-closure versions with entries by ID;
6. fetch non-cancelled temporary closures for the requested stores that
   overlap the aggregate `[minimum startAt, maximum serviceEndAt)` envelope.

The service filters the envelope result against each request in memory. Empty
ID or interval collections skip their corresponding query. Hibernate
Statistics integration tests assert the fixed query ceiling and guard against
per-store repository calls.

## Transactions, Idempotency, and Audit

Closure mutation commands follow the Store lock order used by schedule
publication: Store row first, closure/schedule state second, target resource
third. They use `READ_COMMITTED`, bounded transaction timeouts, the global
idempotency executor, and command fingerprints containing the route, Store,
resource/version, and normalized request.

Known closure constraints, lock failures, and timeouts map to the existing
`STORE_006` schedule-conflict response. Ownership and Store-state failures
reuse existing Store errors. Bean validation failures use the common
validation response. No new public error code is introduced.

Append-only closure audit events record actor, Store, resource and version,
old/new state, requested/effective/processed instants, Store zone, sanitized
reason fields, idempotency key, and outcome. Audit never claims that existing
Reservation conflicts were evaluated; Issue #69 remains responsible for
impact discovery.

The public batch validator is read-only and creates no locks, state changes,
or audit records. Reservation creation must invoke current Store transaction
eligibility and re-run schedule validation in its own finalization flow; that
consumer integration is outside Issue #104.

## Database Migration

A new migration, initially numbered `V18`, creates:

- `store_regular_closure_versions`;
- `store_regular_closure_entries`;
- regular-closure fields and foreign key on `store_schedule_state`;
- `store_temporary_closures`;
- `store_closure_audit_events`;
- uniqueness, lifecycle, interval, owner, due-activation, and overlap-query
  indexes required by the command and batch paths.

If another migration reaches `dev` first, the file is renumbered before merge.
Applied migrations are never edited. Existing stores are not silently seeded
with an empty active closure version because that would assert an operator
decision that did not occur.

## Documentation Changes

`docs/specs/store-search/spec.md` adds closure management and the Store public
batch contract to the 1st-MVP Store scope. `docs/specs/store-search/openapi.yaml`
defines the six operator routes, request/response schemas, status enums, and
existing error mappings. `docs/service-policies/03-store-operation.md` is
changed only where necessary to make the approved MVP mechanics explicit; it
does not duplicate implementation detail.

The internal Java batch contract is documented in Store source and the feature
spec but has no HTTP route.

## Testing Strategy

TDD proceeds in independent red-green slices:

1. regular-closure request and entity invariants;
2. regular-closure lifecycle, idempotent commands, and activation;
3. temporary-closure interval and derived-state behavior;
4. temporary-closure commands, overlap rules, and audit;
5. public request/result DTO invariants;
6. pure interval decision policy for business, break, regular, temporary, and
   half-open boundaries;
7. batch service ordering, duplicates, fail-closed mapping, and query count;
8. MockMvc and OpenAPI contract parity;
9. MySQL clean migration, persistence, concurrency, and query-count tests.

Focused Store tests run after each slice. Final verification includes Store
closure/schedule tests, migration startup, full backend tests/build within the
available runtime limit, XML failure/error/skipped totals, `git diff --check`,
and Issue #104 allowlist inspection.

## Risks and Rollback

- The single Issue/PR is intentionally large. Package boundaries and commits
  keep closure management and interval validation independently reviewable.
- Existing Stores remain fail-closed until an operator explicitly publishes a
  regular-closure version, including an empty version. Rollout communication
  must make this activation requirement visible.
- Schedule and closure changes may affect existing reservations, but this work
  does not silently mutate them. Impact processing remains Issue #69.
- Application behavior can be reverted by commit. Schema rollback uses a new
  corrective migration rather than editing or deleting the applied migration.
