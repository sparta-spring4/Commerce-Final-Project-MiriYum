# MiriYum Automatic Recommendation Finalization Plan (historical completion record)

> **Historical snapshot (2026-07-22):** This completed plan records the 139-row delegated-finalization result; current policy totals and queues are maintained in `docs/service-policies/README.md`.

**Historical goal:** Write implementable detailed policies for all 139 `자동 추천 예정` rows and finalize them under the user's delegated recommendation standard, producing the then-current `확정 170 / 팀원 상의 필요 19 / TODO 28 / 자동 추천 예정 0` snapshot.

**Architecture:** Finalize recommendations inside their existing domain documents, using each policy ID as the stable unit. Every finalized row must state a concrete rule plus the relevant authorization, precondition, state transition, concurrency/idempotency, failure/recovery, notification/audit, privacy, and verification behavior; irrelevant dimensions may be explicitly marked not applicable rather than invented. After domain-level independent reviews, synchronize the master ledger and cross-cutting documents and run a whole-repository review.

**Tech Stack:** Markdown, PowerShell, ripgrep, Git read-only verification.

## Global Constraints

- Preserve the existing 31 confirmed policy rows and all confirmed sub-decisions without changing their substance, status, or importance.
- Preserve exactly 19 `팀원 상의 필요` rows and exactly 28 `TODO` rows; do not decide product direction, financial responsibility, fairness allocation, paid-provider contracts, final legal/privacy/terms questions, future expansion, or empirical numeric targets on their behalf.
- Finalize exactly the current 139 `자동 추천 예정` rows using the explicitly delegated non-core recommendation standard, and record the completed detail and confirmation decision for every row.
- A policy is not detailed merely because its status changed. Each row must contain an actionable normative recommendation, affected actor/data/state, failure and concurrency behavior where relevant, audit/notification/privacy constraints where relevant, and observable acceptance criteria.
- Use safe standard recommendations for authorization, least privilege, idempotency, concurrency, state machines, error handling, retries, notifications, auditing, privacy minimization, and multi-instance consistency.
- When a finalized row depends on one of the 19 team decisions, define a safe current default that does not pre-empt that decision and state how the finalized detail consumes the later decision. `TRANSFER-003` may only implement `TRANSFER-002`; `SUB-009` must provide no preferential allocation until `SUB-001`–`SUB-003` authorize it.
- When a row contains an existing confirmed sub-scope and unresolved broader scope, preserve the confirmed sub-scope exactly and finalize only the remaining delegated non-core scope.
- When safe baselines coexist with a TODO fragment, finalize only the safe baseline and keep the provider/legal/future/numeric fragment explicitly TODO; do not falsely mark the TODO fragment confirmed.
- Do not introduce paid services, unnecessarily complex infrastructure, additional personal-data collection, or single-instance assumptions.
- Use MiriYum naming; at the time of this plan, do not modify `miriyum-service-blueprint.md`; do not write implementation code; do not stage, commit, or push.
- Preserve all pre-existing user changes and work on the current `dev`-based `codex/` branch.

---

### Task 1: Store and Store-Operation Recommendations (10 policies)

**Files:**
- Modify: `docs/service-policies/02-store-onboarding.md`
- Modify: `docs/service-policies/03-store-operation.md`

**Interfaces:**
- Consumes: current master rows and existing confirmed store policies.
- Produces: finalized detailed policies for `STORE-014` and `OPER-002`–`OPER-010`.

- [x] Write one implementable, ID-addressable detailed policy for each of the 10 rows.
- [x] Preserve STORE ownership safeguards and make STORE-014 transitions, evidence, conflict handling, audit, and multi-instance behavior explicit.
- [x] Define operating-hours, closure, menu-version, stock/content, legal-display, lifecycle, and emergency-stop behavior without selecting TODO providers or numeric thresholds.
- [x] Add a dated confirmation record for all 10 delegated recommendations and change only these rows from automatic to confirmed in the two domain summaries.
- [x] Verify 10/10 rows have concrete rules and acceptance criteria.

### Task 2: Reservation, Waiting, and Menu-Hold Recommendations (34 policies)

**Files:**
- Modify: `docs/service-policies/04-reservation.md`
- Modify: `docs/service-policies/05-waiting.md`
- Modify: `docs/service-policies/06-menu-hold.md`

**Interfaces:**
- Consumes: preserved team decisions and TODO boundaries for reservation, waiting, and hold.
- Produces: finalized detailed policies for the 12 reservation, 11 waiting, and 11 hold automatic rows.

- [x] Write concrete rules for every automatic row, including permissions, validation, state transitions, idempotency/concurrency, failures, audit/notification, and acceptance criteria where applicable.
- [x] Keep `RES-001`, `RES-009`, `WAIT-009`, `WAIT-015`, and `HOLD-004` unresolved and avoid embedding choices that decide them indirectly.
- [x] Keep `RES-006`, `WAIT-003`, `WAIT-011`, and `HOLD-007` TODO numeric fragments unresolved while finalizing their surrounding safe baselines only where another row owns them.
- [x] Preserve WAIT-001 authenticated current scope and future nonmember exception boundary; finalize WAIT-004 safe fallback without weakening location/privacy rules.
- [x] Add confirmation records and change exactly these 34 automatic rows to confirmed in domain summaries.

### Task 3: Tasting, Payment, Check-in, and Transfer Recommendations (34 policies)

**Files:**
- Modify: `docs/service-policies/07-course-tasting.md`
- Modify: `docs/service-policies/08-payment-refund.md`
- Modify: `docs/service-policies/09-checkin-noshow.md`
- Modify: `docs/service-policies/10-waitlist-transfer.md`

**Interfaces:**
- Consumes: preserved financial/fairness team decisions and provider/numeric TODO boundaries.
- Produces: finalized detailed policies for 9 tasting, 10 payment, 8 check-in, and 7 transfer automatic rows.

- [x] Write implementable rules for all 34 rows, emphasizing monetary integrity, webhook verification, idempotency, state reconciliation, evidence, notification, and multi-instance concurrency.
- [x] Preserve confirmed TASTE-001/TASTE-005 sub-scopes while completing only remaining delegated details.
- [x] Do not decide TASTE/PAY/CHECK/TRANSFER team rows or PAY-003/PAY-012/CHECK-005/TRANSFER-004 TODO fragments.
- [x] Make TRANSFER-003 consume only the future TRANSFER-002 priority policy and never create an independent subscriber/lottery advantage.
- [x] Add confirmation records and change exactly these 34 automatic rows to confirmed in domain summaries.

### Task 4: Subscription, Trust, Advertising, and Analytics Recommendations (28 policies)

**Files:**
- Modify: `docs/service-policies/11-subscription.md`
- Modify: `docs/service-policies/12-review-trust.md`
- Modify: `docs/service-policies/13-ad-recommendation.md`
- Modify: `docs/service-policies/14-analytics-report.md`

**Interfaces:**
- Consumes: preserved subscription/advertising team decisions and contract/privacy TODO fragments.
- Produces: finalized detailed policies for 4 subscription, 12 trust, 3 advertising, and 9 analytics automatic rows.

- [x] Write implementable rules for all 28 rows, including entitlements, snapshotting, fraud/abuse recovery, moderation evidence, ranking transparency, aggregation correctness, privacy, and reprocessing.
- [x] Keep SUB-001–003/SUB-007 and ADS-001 unresolved; keep SUB-004/SUB-006 and ADS-003–005 TODO boundaries intact.
- [x] Set SUB-009 to no preferential inventory or early access until the subscription team decisions expressly authorize it.
- [x] Preserve ADS-004/ADS-005 confirmed youth-alcohol safeguards without treating their broader legal/provider TODO scope as confirmed.
- [x] Add confirmation records and change exactly these 28 automatic rows to confirmed in domain summaries.

### Task 5: Administration, Notification, Privacy, and Scale Recommendations (33 policies)

**Files:**
- Modify: `docs/service-policies/15-admin-operation.md`
- Modify: `docs/service-policies/16-notification.md`
- Modify: `docs/service-policies/17-privacy-security.md`
- Modify: `docs/service-policies/18-scale-reliability.md`

**Interfaces:**
- Consumes: existing confirmed security sub-scopes and explicit legal/provider/numeric TODO fragments.
- Produces: finalized detailed policies for 8 administration, 9 notification, 9 privacy, and 7 scale automatic rows.

- [x] Write implementable safe-baseline rules for all 33 rows, including least privilege, dual control, durable audit, retries/deduplication, consent/privacy minimization, encryption, deletion propagation, distributed consistency, isolation, observability, and deployment compatibility.
- [x] Preserve confirmed ADMIN-001/007/012 and PRIV-001/005/006/008/011 sub-scopes while completing only remaining delegated scope.
- [x] Keep ADMIN-002/004/006/009, NOTI-009, PRIV-005/006/010/012, and SCALE-001/002/003/005/013 TODO fragments unresolved.
- [x] Finalize the safe baselines that coexist with ADMIN-002/009, PRIV-012, and SCALE-013 without claiming their WebAuthn rollout, retention/legal notice, or RPO/RTO numbers are confirmed.
- [x] Add confirmation records and change exactly these 33 automatic rows to confirmed in domain summaries.

### Task 6: Master and Cross-Cutting Synchronization

**Files:**
- Modify: `docs/service-policies/README.md`
- Modify: `docs/service-policies/00-policy-template.md`
- Modify: `docs/service-definition.md`
- Modify: `docs/technical-architecture.md`
- Modify: `miriyum-service-decisions.md`
- Modify if needed: `docs/superpowers/specs/2026-07-21-miriyum-policy-documentation-design.md`

**Interfaces:**
- Consumes: all independently approved domain details and confirmation records.
- Produces: canonical totals `170/19/0/28`, consistent status semantics, decision history, and next actions.

- [x] Change exactly the 139 completed rows from `자동 추천 예정` to `확정`, retaining their existing importance values.
- [x] Keep all 31 prior confirmed, 19 team, and 28 TODO rows unchanged.
- [x] Record that the user's explicit delegated recommendation standard was applied only after detailed policy completion and independent review.
- [x] Update counts, summaries, queues, service definition, architecture references, template guidance, and decision history without representing TODO fragments as confirmed.
- [x] Verify the master has 217 unique rows with `확정 170 / 팀원 상의 필요 19 / TODO 28 / 자동 추천 예정 0` and domain summaries match exactly.

### Task 7: Final Integrity Review

**Files:**
- Verify: every file above
- Verify unchanged: `miriyum-service-blueprint.md`

**Interfaces:**
- Consumes: the complete finalized policy corpus.
- Produces: completion evidence and an independent whole-work verdict.

- [x] Verify every one of the original 139 automatic IDs has ID-addressable detailed policy content and a confirmation record.
- [x] Verify the 31 original confirmed policies, 19 team decisions, and 28 TODO rows and fragments remain preserved.
- [x] Recompute all state/importance totals and domain/master consistency.
- [x] Scan for stale `자동 추천 예정` claims, placeholder-only policies, product/fairness/financial decisions embedded in confirmed recommendations, and paid/complex/extra-PII defaults.
- [x] Run `git diff --check`, confirm the branch and staged state, verify blueprint has no task-caused diff, and conduct a final independent review.
