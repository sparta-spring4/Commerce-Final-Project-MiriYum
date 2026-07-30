# Store Schedule Issue #34 Design

## Goal

Implement Issue #34 so an authenticated store operator can publish complete weekly
operating hours and complete weekly reservation acceptance windows. Publishing creates
an immutable version and makes it effective immediately. Existing confirmed
reservations are never moved or cancelled by this feature.

This design is based on Issue #34, the store-search OpenAPI and specification,
OPER-002/OPER-004, and the central store authority boundary introduced by Issue #33.

## Scope

Included:

- `PUT /api/v1/store-operator/stores/{storeId}/operating-hours`
- `PUT /api/v1/store-operator/stores/{storeId}/reservation-time-slots`
- immutable operating-hours and reservation-time-slot versions
- weekly interval validation, including overnight intervals
- immediate activation, idempotency, concurrency control, and rollback
- MySQL schema, service, controller, and tests owned by the store schedule package

Excluded:

- scheduled or future-dated activation
- draft editing and activation cancellation
- regular holidays, temporary closures, and emergency stops
- reservation capacity, party-size limits, duration, or turnover rules
- changing, cancelling, or moving existing reservations
- public search and current-open calculation
- menu management from Issue #35

## Confirmed Product Decisions

### Separate version streams

Operating hours and reservation acceptance windows are independent version streams.
Each endpoint publishes only its own complete weekly configuration.

A reservation-time-slot version records the operating-hours version against which it
was validated. It does not copy or mutate that operating-hours version.

### Immediate publication

Both PUT endpoints activate the newly created version when the transaction commits.
The API does not accept an effective date or scheduled activation time in the first
MVP.

### Time zone

All input and calculations use the explicit first-MVP business time zone
`Asia/Seoul`. Code must not use the JVM or operating-system default time zone.

### Overnight ownership

Intervals are half-open `[startTime, endTime)` ranges:

- `startTime < endTime` ends on the same local date.
- `startTime > endTime` ends on the next local date.
- `startTime == endTime` is a zero-length interval and is rejected.

An overnight interval belongs to the day on which it starts. For example, Monday
`22:00-02:00` is a Monday business interval ending Tuesday at 02:00.

## Architecture

The schedule domain lives under `com.miriyum.domain.store.schedule`. It consumes
Issue #33's public `StoreService.requireManagementAuthority` method and does not access
the Store repository directly.

The main units are:

- `WeeklyIntervalPolicy`: normalizes weekly intervals and validates overlap,
  containment, overnight ownership, and weekly-boundary behavior.
- `StoreScheduleRepository`: persists immutable headers and interval rows and locks
  the per-store state row.
- `StoreScheduleService`: performs authority checks, idempotent publication,
  transaction control, version allocation, and response construction.
- `StoreScheduleController`: exposes the two PUT endpoints under the existing
  store-operator security path.

## Persistence Model

### `store_schedule_state`

One mutable coordination row per store:

- `store_id` primary key and restrictive FK to `stores`
- `active_operating_schedule_version_id`, nullable until first publication
- `active_reservation_schedule_version_id`, nullable until first publication
- `next_operating_version`
- `next_reservation_version`
- audit timestamps

The service creates this row when absent and locks it with `SELECT ... FOR UPDATE`
before allocating or activating a version. Both streams share this row so a
reservation publication cannot validate against an operating version that changes
mid-transaction.

### Operating schedule versions

`store_operating_schedule_versions` stores:

- immutable ID, store ID, and per-store version number
- publication timestamp

Child rows store the start day, range kind (`BUSINESS_HOURS` or `BREAK_TIME`), local
start time, local end time, and whether the interval ends on the next day.

Unique constraints protect `(store_id, version)` and interval identity. Historical
versions and child rows are never updated or deleted by publication.

### Reservation schedule versions

`store_reservation_schedule_versions` stores:

- immutable ID, store ID, and per-store version number
- the operating schedule version used for validation
- publication timestamp

Child rows store start day, local start and end times, and the overnight flag.
Historical versions remain immutable.

## Weekly Validation

Every request contains exactly seven distinct days. A duplicate or missing day is a
request-shape failure (`COMMON_001`). Days without intervals are represented by empty
arrays.

For interval calculations, local times are converted to minute offsets from the start
of the owning week. An overnight end receives an additional 1,440 minutes. Weekly
overlap detection also compares shifted copies across the 10,080-minute week boundary,
so Sunday overnight intervals cannot overlap Monday early-morning intervals silently.

Operating publication rules:

- business intervals must be non-zero and must not overlap in real time
- every break must be fully contained in one business interval
- breaks must be non-zero and must not overlap
- a break uses the same start-day ownership convention as business intervals

Reservation publication rules:

- an active operating schedule must already exist
- every reservation interval must be fully contained in an active business interval
- reservation intervals must not overlap one another
- reservation intervals must not intersect any active break interval
- validation records the exact active operating version ID

Semantic interval failures use `STORE_006`.

## Authority and Store State

Before each operation, the service calls
`StoreService.requireManagementAuthority(operatorAccountId, storeId)`.

- missing store: `STORE_001`
- different owner: `STORE_003`
- inactive or missing operator account: existing authentication account error
- `CLOSED` store: `STORE_005`
- `TEMPORARILY_CLOSED` store: schedule editing is allowed, but publication does not
  reopen the store or make reservations executable
- verification must remain `APPROVED`; otherwise publication fails with `STORE_007`

Schedule publication never changes the Store aggregate's operation status.

## Idempotency and Transaction Boundary

Command types:

- `STORE_OPERATING_HOURS_REPLACE`
- `STORE_RESERVATION_TIME_SLOTS_REPLACE`

The request fingerprint includes the route identity, store ID, all seven days, and
normalized sorted intervals. Equivalent weekly content produces the same fingerprint
even if request arrays arrive in a different order.

The authority check, idempotency execution, state-row lock, validation, immutable
version insertion, active-pointer update, and stored success response participate in
one `READ_COMMITTED` transaction with a bounded timeout.

The same key and fingerprint replays the stored version without creating another
version. Reusing the key for different content returns `COMMON_007`.

Different keys racing for the same stream serialize on `store_schedule_state`. One
request publishes first; the next request validates and publishes from the resulting
latest state rather than overwriting it with a duplicated version number. Constraint
or lock conflicts that cannot be completed safely return `STORE_006`.

Any failure rolls back the version header, interval rows, pointer update, and
idempotency record together.

## HTTP Contract

The existing OpenAPI contract remains unchanged.

Operating hours request:

- exactly seven `DailySchedule` entries
- each entry contains `businessHours` and `breakTimes`

Reservation time slots request:

- exactly seven `DailyTimeSlots` entries
- each entry contains `slots`

Success responses return HTTP 200 with the common success envelope, the newly active
version number, and a canonical Monday-to-Sunday representation of the published
weekly data.

Errors:

- `COMMON_001`: structural validation, duplicate/missing days
- `COMMON_003`, `COMMON_004`, `COMMON_007`: idempotency key errors
- `STORE_001`: store missing
- `STORE_003`: store belongs to another operator
- `STORE_005`: closed store or unsafe concurrent state change
- `STORE_006`: interval overlap, containment failure, or schedule publication conflict
- `STORE_007`: invalid verification state

## Testing Strategy

Unit tests:

- same-day and overnight normalization
- zero-length rejection
- adjacent half-open ranges
- same-day, cross-midnight, and Sunday-to-Monday overlap
- break containment and overlap
- reservation containment and break intersection
- seven-day completeness and canonical ordering
- stable request fingerprints

Service tests:

- central Issue #33 authority invocation
- closed and temporarily closed behavior
- independent version increments and operating-version reference
- whole-week replacement rather than partial merge
- idempotent replay and response conversion

MockMvc tests:

- store-operator authentication and namespace mismatch
- required idempotency header
- request validation and common envelope
- `STORE_001`, `STORE_003`, `STORE_005`, and `STORE_006`
- successful operating and reservation publication responses

Testcontainers MySQL tests:

- Flyway/JPA mapping
- immutable historical versions
- active pointer and monotonic version allocation
- concurrent publications
- atomic rollback of schedule data and idempotency record
- restrictive store FK behavior

Final verification runs the focused schedule suite and the complete backend
`clean build`, then checks the Issue #34 diff against its allowed paths.

## Branch and Integration Strategy

Issue #34 is implemented on `codex/34-store-schedule` in its own worktree, based on
`codex/33-store-core`. Its initial pull request is stacked on the Issue #33 branch.
After Issue #33 merges, Issue #34 is rebased onto `dev` and retargeted to `dev`.

Issue #35 is not implemented in this branch. It receives a separate worktree, design,
plan, branch, and pull request based on the same Issue #33 boundary.
