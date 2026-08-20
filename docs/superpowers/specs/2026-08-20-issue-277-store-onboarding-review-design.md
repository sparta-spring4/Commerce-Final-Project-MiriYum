# Issue #277 Store Onboarding Review Workflow Design

- Status: approved in brainstorming on 2026-08-20
- Issue: #277
- Parent: #269
- Prerequisites: #275, #276, #344
- Runtime activation prerequisite: #223
- Provisional migration: `V68__create_store_onboarding_review_workflow.sql`

## 1. Purpose

Every future store registration must submit a private business-registration certificate and pass the same automatic checks. A configuration switch controls only whether a Platform Operator review is added after those checks. It must not make evidence or automatic checks optional.

The workflow must survive process restarts and external-service failures, reject stale versions and concurrent decisions, prevent a duplicate business number from affecting an existing store, and keep raw evidence URLs and sensitive values out of responses, logs, analytics, and audit details.

Existing approved stores are grandfathered. This work does not retrospectively request evidence, reopen approval, or suspend an existing store.

## 2. Product decisions

### 2.1 One registration entry point

`POST /api/v1/store-operators/stores` remains the only entry point for a new store registration. The request changes to `multipart/form-data` and carries structured application data plus one business-registration certificate. It returns `202 Accepted` with the onboarding application identifier, immutable application version, and current workflow status.

A `Store` row is not created while an application is incomplete, under automatic checking, awaiting review, or awaiting supplementation. The workflow creates the `Store` only at final approval.

### 2.2 Evidence and automatic checks are unconditional

All new applications require exactly one current certificate per application version. Supported content is PDF, JPEG, or PNG, with a maximum size of 10 MB. The service verifies both the declared media type and the file signature. Encrypted PDFs, unrecognized content, and multiple files are rejected.

All new applications run the same automatic checks. The first implementation uses a provider-neutral port and deterministic mock adapter because no production National Tax Service contract has been selected. The adapter boundary must support a later real provider without changing the application state machine or HTTP contract.

The automatic checks cover at least:

- business-registration-number format and permanent platform uniqueness;
- provider-reported registration validity and business status;
- structured response matching for the submitted registration facts;
- a versioned provider-contract and automatic-check policy identifier.

A definitive invalid or mismatched result cannot be overridden by a Platform Operator. A provider outage, timeout, or rate limit is not treated as invalid; the application remains in automatic checking and is retried durably.

### 2.3 The switch controls review only

The application records `reviewRequired` from the configuration value at version creation time.

- `reviewRequired=false`: a successful automatic check leads to system approval and Store creation.
- `reviewRequired=true`: a successful automatic check creates a review-ready case, and Store creation waits for a valid Platform Operator approval.

Changing the configuration affects only application versions created afterward. It never auto-approves or re-routes an application already in progress.

### 2.4 Assignment remains operationally simple

Issue #277 reuses the #276 assignment table and guard. The application does not add a visible countdown or renewal API. One configured onboarding reviewer owns a case until it is closed or explicitly reassigned. The shared assignment schema still requires `expiresAt`; #277 supplies a long, configurable assignment duration and does not change the #276 schema or general assignment semantics.

## 3. Architecture and ownership

### 3.1 Store domain

The Store domain owns the canonical onboarding application, immutable application versions, evidence association, automatic-check jobs and results, workflow state, and final Store creation.

The Store onboarding boundary implements `StoreOnboardingApplicationOwnershipPort` so #344 evidence replacement can validate the application, version, and Store Operator owner before inspecting file metadata.

The finalization path is shared by system approval and manual approval. It rechecks the current application version, final automatic-check outcome, final decision mode, catalog data, geocoding result, and permanent business-number uniqueness before creating one approved Store. There is no second or fallback Store creation path.

### 3.2 Platform Operator domain

The Platform Operator domain owns review queue HTTP endpoints, assignment commands, permission checks, current-password reauthentication consumption, decision commands, and immutable operational audit.

It uses Store-owned public Service/DTO contracts to query and decide an onboarding application. It must not import Store onboarding entities or repositories. It reuses:

- `AdminCaseType.ONBOARDING_REVIEW`;
- `AdminCommandPurpose.ONBOARDING_DECISION`;
- `AdminTargetType.ONBOARDING_APPLICATION`;
- `PlatformOperatorPermission.ONBOARDING_REVIEW`;
- `PlatformOperatorPermission.ONBOARDING_EVIDENCE_READ`;
- `AdminCaseAssignmentManager`;
- `HighRiskCommandGuard`;
- the existing Platform Operator audit writer.

Evidence access adds a distinct reauthentication purpose so a decision approval cannot be reused to read a file and a file-read approval cannot authorize a decision.

### 3.3 Evidence and storage boundary

Issue #277 consumes and extends the #344 public evidence Service/DTO boundary. Platform Operator code never reads `FileMetadata`, object keys, storage URLs, or storage repositories directly.

The request orchestrator does not hold Store or application row locks while calling external object storage. It uses short transitions:

1. create or replay the durable application receipt;
2. store and confirm the private file through the storage boundary;
3. attach the confirmed evidence to the exact application version;
4. enqueue automatic checking only after attachment succeeds.

An uncertain or failed upload does not create a review case. A retry with the same idempotency key and identical payload resumes or returns the same application result.

Actual private S3 activation, IAM and bucket-policy validation, production secrets, and staging upload/read failure tests remain blocked on #223.

### 3.4 Automatic-check worker

A scheduled worker claims due jobs with an owner, fencing token, and bounded worker lease. External provider work happens outside long database transactions. A short result transaction applies the outcome only when the application version, job version, owner, and fencing token still match.

Retryable failures record an allowlisted failure category, attempt count, and next attempt time. The worker uses bounded exponential backoff without `sleep`. A crashed or expired claim may be reclaimed, while a stale worker result is rejected.

## 4. State model

### 4.1 Application states

The canonical states are:

```text
RECEIVED
  -> EVIDENCE_PENDING
  -> AUTO_CHECKING
  -> AUTO_APPROVED                       reviewRequired=false
  -> REVIEW_READY -> UNDER_REVIEW        reviewRequired=true
                     -> CHANGES_REQUESTED
                     -> APPROVED
                     -> REJECTED
```

`AUTO_APPROVED`, `APPROVED`, and `REJECTED` are terminal for that version. Successful approval atomically records the terminal result and resulting Store identifier. `CHANGES_REQUESTED` is terminal for the old version but permits the owner to submit a successor version.

Provider unavailability does not introduce a separate queue status. The domain remains `AUTO_CHECKING` and records the hold category, retry count, and next attempt time as metadata.

### 4.2 Ownership conflict

A business number already assigned anywhere in Store history never creates another Store and never changes the existing Store's state, sessions, owner, or visibility.

- Review disabled: the new application fails closed with a masked conflict outcome.
- Review enabled: the workflow may create one `OWNERSHIP_CONFLICT` review case for classification. The reviewer can classify the request as duplicate or suspected fraud, account-access recovery, or a future transfer/change path. This permission never transfers, suspends, or edits the existing Store.

### 4.3 Version invalidation

Supplementation always creates a new immutable positive version. The applicant submits the complete structured data and a new certificate together. The successor version does not inherit automatic-check results, review decisions, assignment validity, or evidence-read authorization from the predecessor.

Creating the successor version atomically:

- advances the application's current version;
- closes the old active review case and assignment;
- makes old decision commands stale;
- makes prior evidence-read approvals unusable;
- preserves old data, evidence metadata, checks, decisions, and audit as history.

Every decision validates both application version and review-case version. Database constraints allow at most one active case per application version. A row lock, expected-version comparison, immutable-decision uniqueness, and Store uniqueness constraint ensure that concurrent valid decisions have one winner.

## 5. HTTP contract

### 5.1 Store Operator

#### Submit a new application

`POST /api/v1/store-operators/stores`

- Content type: `multipart/form-data`
- Parts:
  - `application`: structured JSON containing the existing Store registration fields plus the legal business name, representative name, opening date, primary business category, and primary business item needed for verification;
  - `businessRegistrationEvidence`: one PDF, JPEG, or PNG up to 10 MB.
- Headers: required `Idempotency-Key`.
- Success: `202 Accepted` with `applicationId`, `applicationVersion`, `status`, `reviewRequired`, and `nextAction`.

#### Read own application

`GET /api/v1/store-operators/onboarding-applications/{applicationId}`

Returns only the owner's current status, version, masked result summary, resulting Store identifier when approved, and the next applicant action. A missing and non-owned application produce the same not-found response.

#### Submit a supplemented version

`POST /api/v1/store-operators/onboarding-applications/{applicationId}/versions`

Uses the same multipart parts and a required idempotency key. It is allowed only from `CHANGES_REQUESTED` and creates exactly one successor version.

### 5.2 Platform Operator

The active onboarding review OpenAPI defines:

- paginated review-case list with status and type filters;
- review-case detail with the current application and case versions;
- assignment and explicit reassignment;
- approve, reject, and request-supplementation commands;
- current evidence content read.

All mutations require an idempotency key, expected application version, expected case version, and `X-Admin-Reauthentication`. The endpoint constructs the required purpose, target, case, and permission server-side; client-supplied roles or permissions are ignored.

Evidence content uses a stable server endpoint rather than a returned S3 URL. The caller first obtains the existing five-minute one-time reauthentication value bound to the review case, application, version, evidence, session, and authority version. The evidence endpoint consumes it once, rechecks current assignment and `ONBOARDING_EVIDENCE_READ`, records the access outcome, and streams through the Store evidence boundary.

Reassignment, authority revocation, application version change, evidence replacement, case closure, expiry, or prior consumption rejects the value.

## 6. Privacy, audit, and errors

Raw evidence, object URLs, object keys, complete business-registration numbers, representative names, provider payloads, reauthentication values, access tokens, and session values are forbidden from application logs, analytics, response metadata, and audit JSON.

Queue responses contain a masked business number and the minimum fields needed to find and triage a case. Full comparison fields and evidence content are available only after current authority and assignment validation.

Audit records include the public application and case identifiers, versions, action, allowlisted reason code, actor and authority snapshot, policy versions, timestamp, idempotency key, correlation identifier, and outcome. Evidence-access issuance/read success, denial, and expiry are auditable without recording content or credentials. Failure audit is committed independently from a rejected business transaction so denial evidence is not lost to rollback.

Canonical error behavior:

- validation or unsupported file: 400;
- missing/non-owned Store Operator application: concealed 404;
- stale version, duplicate decision, or idempotency payload conflict: 409;
- Platform Operator permission, assignment, or reauthentication failure: 403;
- invalid Platform Operator session or authority version: 401;
- storage or automatic-check infrastructure unavailable: 503;
- automatic-check business rejection: a persisted terminal application outcome, not a fabricated infrastructure error.

## 7. Persistence

The provisional V68 migration adds focused tables:

- `store_onboarding_applications`: owner, current version, current state, final Store reference, timestamps, and optimistic row version;
- `store_onboarding_application_versions`: immutable structured snapshot, `review_required`, evidence reference, policy versions, status metadata, and unique `(application_id, application_version)`;
- `store_onboarding_auto_check_jobs`: due time, retry metadata, worker owner/token/lease, result category, and optimistic row version;
- `store_onboarding_review_cases`: public case ID, type, application/version, case version, state, active marker, timestamps, and optimistic row version;
- `store_onboarding_decisions`: immutable action, reason code, actor/authority snapshot, application/case versions, idempotency identity, and timestamp.

Existing `store_business_registration_evidences`, `admin_case_assignments`, `platform_operator_audit_events`, `idempotency_commands`, and `stores` are reused. The final migration design includes database checks for legal state/marker combinations and uniqueness for current application version, one active review case, one terminal decision per case/version, and one final Store per application.

V68 is provisional because migration ownership is time-sensitive. Immediately before implementation and again before merge, fetch `origin/dev`, inspect open PR migration ownership, and renumber if necessary. Existing migrations are never edited.

## 8. Configuration and activation

The review switch is a Store onboarding setting whose only semantic effect is the `reviewRequired` snapshot for a new version. Evidence and automatic checking cannot be disabled by this switch.

Worker delays, batch size, retry limits/backoff, worker lease, and long case-assignment duration are explicit bounded configuration with safe defaults. Tests inject a `Clock` and run workers directly; they never use sleeps.

Production activation requires all of the following:

1. #277 code, migration, Store Operator OpenAPI, Platform Operator OpenAPI, and focused tests complete;
2. #223 private S3 bucket/IAM/secrets and reconciliation activation complete;
3. staging submission, replacement, automatic-check retry, assigned one-time evidence read, access denial, and cleanup smoke tests complete;
4. logging and analytics inspection confirms no raw URL, object key, evidence content, business number, or representative name leakage;
5. rollback disables new intake without rewriting or auto-approving in-flight applications.

#277 and its PR must say that implementation completion does not mean production activation. #223 must include the #277 submission and evidence-read paths in its staging completion evidence.

## 9. Testing strategy

Local verification follows the repository's targeted-test policy.

Unit and slice tests cover:

- legal and illegal aggregate transitions;
- application-version replacement and stale-command rejection;
- request fingerprints and idempotency replay/conflict;
- mock automatic-check outcomes and retry scheduling;
- media type, signature, encrypted-PDF, count, and 10 MB boundaries;
- response masking and forbidden-field scans;
- Controller validation, ownership concealment, permission, and feature exposure;
- OpenAPI schemas, operation IDs, aggregated audiences, and runtime drift.

Focused real-MySQL integration tests cover:

- V68 constraints and migration startup;
- one active case per application version;
- duplicate application and final Store uniqueness races;
- concurrent approve/reject/supplement decisions with one winner;
- stale version after supplementation;
- worker claim fencing, expired-claim recovery, and stale-result rejection;
- assignment, authority-version, reauthentication reuse, and evidence access;
- atomic approval, decision, audit, and Store creation.

Storage tests use the existing local/test adapter. Actual S3, IAM, private-object denial, and staging smoke evidence remain pending until #223 completes. GitHub CI is the authoritative full-suite verification; local work does not run `build`, `check`, the full `integrationTest`, or all integration shards unless explicitly requested.

## 10. Delivery and scope controls

Before runtime implementation:

1. update STORE-003, STORE-005, ADMIN-003, the active store-onboarding spec, and OpenAPI so all future applications require evidence and automatic checking;
2. remove obsolete phase wording from the affected onboarding contract without retroactively changing existing Stores;
3. update #277 with the selected migration and exact production/test/document allowlist;
4. add the explicit #223 production-activation dependency and staging paths;
5. create an implementation plan with test-first tasks.

The implementation does not add OCR, a production National Tax Service adapter, a frontend review screen, retrospective evidence collection, Store ownership transfer, or S3 production activation.
