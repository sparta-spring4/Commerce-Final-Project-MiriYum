# MiriYum Policy Decisions Documentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reflect the user-approved operating numbers, waiting fairness, payment/settlement target architecture, and user/store subscription candidates across the MiriYum policy corpus, then publish the verified documentation on the dedicated branch.

**Architecture:** `docs/service-policies/README.md` remains the canonical row-level state and importance index. Domain documents own implementable details and dated decision records; service definition, technical architecture, the decision ledger, and policy operating rules summarize those facts without resolving external contracts or unanswered launch decisions. Exactly 13 numeric `TODO` rows and `WAIT-009` move to `확정`; conditional payment and subscription scopes remain unresolved at row level.

**Tech Stack:** Markdown policy documents, PowerShell verification, Git, independent subagent reviews.

## Global Constraints

- Work only on `codex/miriyum-policy-decisions`.
- Do not write implementation code; modify Markdown documentation only.
- Preserve all previously confirmed policy content and unrelated user changes.
- Final master state must be `확정 184 / 팀원 상의 필요 18 / 자동 추천 예정 0 / TODO 15`, total 217.
- Importance must remain `핵심 19 / 필수 181 / 권장 17`.
- Move only `RES-006`, `WAIT-003`, `WAIT-009`, `WAIT-011`, `HOLD-007`, `CHECK-005`, `TRANSFER-004`, `ADMIN-004`, `ADMIN-006`, `SCALE-001`, `SCALE-002`, `SCALE-003`, `SCALE-005`, and `SCALE-013` to `확정`.
- Keep `PAY-003`, `PAY-012`, `SUB-004`, and `SUB-006` unresolved while recording their approved target subscopes and remaining external conditions.
- Keep `SUB-001`, `SUB-002`, `SUB-003`, and `SUB-007` at `팀원 상의 필요`; user subscription is not launching initially and the store Pro launch time is unanswered.
- Use `MiriYum` for brand copy. Keep the lowercase former-name token only where it is part of an existing filename or link.
- Preserve multi-server/multi-instance consistency, no new personal data, and no uncontracted paid provider as an active production dependency.

---

### Task 1: Reservation, Waiting, Hold, Check-in, and Transfer Decisions

**Files:**
- Modify: `docs/service-policies/04-reservation.md`
- Modify: `docs/service-policies/05-waiting.md`
- Modify: `docs/service-policies/06-menu-hold.md`
- Modify: `docs/service-policies/09-checkin-noshow.md`
- Modify: `docs/service-policies/10-waitlist-transfer.md`

**Interfaces:**
- Consumes: Sections 2 and 3 of `docs/superpowers/specs/2026-07-22-miriyum-policy-decisions-design.md`.
- Produces: Implementable confirmed detail, observable acceptance criteria, domain classification rows, and dated decision records for seven affected policy IDs.

- [x] Update `RES-006` to 10 minutes with a two-minute warning, central time, no user extension, exact-once release, and payment-result-unknown recovery.
- [x] Update `WAIT-003` to a 500m default and store-configurable 300m–1km range with poor-GPS retry/fallback behavior.
- [x] Update `WAIT-011` to two calls of three minutes each, separating delivery retry from call count and routing final nonresponse to store-confirmed `호출 실패` handling.
- [x] Resolve `WAIT-009` with store-selected vacant table, system-filtered first compatible FIFO team, no commercial priority, and one pre-call defer by five compatible teams.
- [x] Align `WAIT-012`, `WAIT-013`, `WAIT-016`, and `WAIT-017` wording and state transitions with the new versioned `WAIT-009` and `WAIT-011` decisions without creating a second ordering rule.
- [x] Update `HOLD-007` to a 10-minute hold and one shared expiry for combined seat/menu resources with all-or-nothing confirmation or release.
- [x] Update `CHECK-005` to default 10 minutes, store range 3–15 minutes, reservation-time snapshot, and handoff to `CHECK-006` rather than automatic no-show.
- [x] Update `TRANSFER-004` to five minutes for response and ten minutes after acceptance for completion, with conditional transitions for response/payment races.
- [x] Add one 2026-07-22 direct-user decision record per affected ID and observable acceptance criteria that exercise boundary times and multi-instance races.
- [x] Recompute the five domain summaries and run `git diff --check` on the five files; expected result is exit 0.

### Task 2: Administration and Scale Targets

**Files:**
- Modify: `docs/service-policies/15-admin-operation.md`
- Modify: `docs/service-policies/18-scale-reliability.md`
- Modify: `docs/technical-architecture.md`

**Interfaces:**
- Consumes: Section 2 of the approved design spec.
- Produces: Confirmed operational SLAs, approval thresholds, capacity/SLO/rate-limit/DR targets and architecture references.

- [x] Update `ADMIN-004` with 1-business-day acknowledgment, 3-business-day general resolution, 7-business-day payment/refund resolution, and a maximum 14-business-day complex-event target with delay notice.
- [x] Update `ADMIN-006` so a payment manager approves at or below KRW 200,000, a super administrator adds approval above KRW 200,000, and any compensation above original payment requires super-administrator approval.
- [x] Require an audit record for every amount containing the approved minimal fields while excluding raw payment methods and unnecessary personal data.
- [x] Update `SCALE-001`, `SCALE-002`, and `SCALE-003` with 100,000 MAU, 5,000 stores, 50,000 daily core transactions, 200 normal RPS, 1,000 RPS for ten minutes, 99.9% monthly availability, read p95 500ms, write p95 1s, and critical p99 2s.
- [x] Update `SCALE-005` with the exact five rate-limit classes from the spec, shared multi-instance enforcement, `429`, and retry timing.
- [x] Update `SCALE-013` with transaction RPO 5m/RTO 1h, config/document RPO 24h/RTO 4h, analytics RPO/RTO 24h, plus restore verification and failure reporting.
- [x] Synchronize architecture capacity, SLO, rate-limit, backup, and approval examples without introducing a new paid infrastructure selection.
- [x] Add dated direct-user decision records and observable acceptance tests for thresholds, boundary values, failover, and concurrent approval/recovery.
- [x] Run `git diff --check` on the three files; expected result is exit 0.

### Task 3: Payment, Settlement, and Subscription Candidate Boundaries

**Files:**
- Modify: `docs/service-policies/08-payment-refund.md`
- Modify: `docs/service-policies/11-subscription.md`
- Modify: `docs/service-policies/13-ad-recommendation.md`
- Modify: `docs/service-policies/14-analytics-report.md`
- Modify: `docs/technical-architecture.md`

**Interfaces:**
- Consumes: Sections 4, 5, and 6 of the approved design spec.
- Produces: Confirmed sub-scope records and explicit unresolved contract/launch boundaries without changing the affected row states.

- [x] Record PortOne V2 plus Toss Payments as the selected target adapter/channel and cards plus Toss Pay, Kakao Pay, and Naver Pay as initial target methods.
- [x] Keep `PAY-003` at `TODO` until PG contracts, payment-method review, fees, and dispute/cancellation terms are complete; distinguish test adapters from production activation.
- [x] Record platform settlement with a Monday–Sunday cycle paid the following Wednesday or next business day, holding only disputed transaction amounts.
- [x] Keep `PAY-012` at `TODO` until payout agency, tax, legal, and actual fund-flow approvals are complete.
- [x] Document the single user subscription candidate: KRW 3,900 monthly/KRW 39,000 annually including VAT, five free alerts versus subscriber unlimited app/web alerts, enhanced recommendations/calendar, subscriber-only events, 40% maximum 24-hour early event allocation, ad removal, three-day failed-payment grace, and period-end cancellation.
- [x] Explicitly prohibit user-subscription priority for ordinary reservations, general waiting, and cancellation-seat allocation; keep the product initially unlaunched.
- [x] Document store Free and Pro: core reservation/waiting management free, actual aggregates free, derived reports/rule-based recommendations and menu/event automation in Pro, per-store pricing of KRW 29,000 monthly or KRW 290,000 annually before VAT, seven-day/three-retry grace, and period-end cancellation.
- [x] Preserve statutory withdrawal, duplicate/wrong payment, non-provision, and MiriYum-fault exceptions despite the no-voluntary-prorated-refund disclosure.
- [x] Keep `SUB-001`–`SUB-003` and `SUB-007` at `팀원 상의 필요` and `SUB-004`/`SUB-006` at `TODO` because user subscription is deferred and store Pro launch time/production recurring-payment contract remain unresolved.
- [x] Align advertising and analytics entitlements so ad removal and Pro reports activate only if their product plans launch; do not grant current report access or suppress non-existent ads.
- [x] Add dated records that distinguish approved candidate design from unresolved launch/contract conditions and run `git diff --check` on all five files; expected result is exit 0.

### Task 4: Master, Cross-Document State, and Brand Synchronization

**Files:**
- Modify: `docs/service-policies/README.md`
- Modify: `docs/service-policies/00-policy-template.md`
- Modify: `docs/service-definition.md`
- Modify: `docs/technical-architecture.md`
- Modify: `miriyum-service-decisions.md`
- Modify: `docs/superpowers/specs/2026-07-21-miriyum-policy-documentation-design.md`
- Modify: `miriyum-service-blueprint.md`
- Modify: `docs/superpowers/plans/2026-07-22-miriyum-policy-reclassification.md`
- Modify: `docs/superpowers/plans/2026-07-22-miriyum-auto-recommendation-finalization.md`

**Interfaces:**
- Consumes: Approved Task 1–3 domain details and status transitions.
- Produces: One consistent 217-row master, current summary, queue, history, next steps, and zero old brand-copy occurrences.

- [x] Change exactly the 14 IDs in Global Constraints to `확정`, preserving importance and all other row states.
- [x] Recompute final master and domain totals to `확정 184 / 팀원 상의 필요 18 / 자동 추천 예정 0 / TODO 15`; keep importance at `핵심 19 / 필수 181 / 권장 17`.
- [x] List the remaining 18 team IDs and 15 TODO IDs exactly, and separate selected-but-contract-pending payment scopes from entirely unanswered decisions.
- [x] Synchronize service definition, architecture, policy operating rules, decision history, core queue, TODO queue, and next-step wording to the new current state while labeling older totals as historical snapshots.
- [x] Use `MiriYum` for all brand copy, including the four approved blueprint substitutions; keep lowercase filename/link references intact.
- [x] Verify case-sensitive old brand-copy count is zero across all Markdown and that the lowercase former-name token occurs only in actual filenames or links.
- [x] Run a mechanical master/domain comparison for 217 unique IDs, state, and importance; expected mismatches are zero.
- [x] Run `git diff --check`; expected result is exit 0.

### Task 5: Independent Review, Commit, and GitHub Publication

**Files:**
- Review: all modified and newly added Markdown files

**Interfaces:**
- Consumes: Completed Tasks 1–4.
- Produces: Reviewed commits and a pushed `codex/miriyum-policy-decisions` branch.

- [x] Dispatch an independent whole-work reviewer to verify spec coverage, exact state sets, mixed-scope boundaries, monetary/fairness decisions, multi-instance behavior, brand count, and absence of implementation-code changes.
- [x] Resolve every reviewer finding and repeat review until approved.
- [x] Run fresh final checks: `git diff --check`, 217 unique master rows, `184/18/0/15`, `19/181/17`, 18 domain/master agreement, zero staged surprises, and zero old brand-copy occurrences.
- [x] Stage only the reviewed documentation files, inspect `git diff --cached --name-only` and `git diff --cached --check`, then commit with an intentional documentation message.
- [x] Push `codex/miriyum-policy-decisions` to `origin` and report the remote branch without creating a pull request unless requested.
