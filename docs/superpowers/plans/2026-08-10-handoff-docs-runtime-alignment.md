# MiriYum Frontend Handoff and Scope-Boundary Documents Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite and cross-review six MiriYum frontend handoff documents and make the boundary between whole-service policy/architecture and the current MVP1 ownership/spec/OpenAPI sources unambiguous.

**Architecture:** Each user role owns one design document and one fullstack connection document. Design documents contain the complete approved roadmap without repository dependencies; fullstack documents use `mvp1-common/ownership.md`, linked domain specs/OpenAPI, actual Controller/Service evidence, and release approval for current activation. Whole-service definition, service policies, and technical architecture remain valid long-term references but are labeled so they cannot be mistaken for the current MVP1 screen/API scope.

**Tech Stack:** Markdown, Git, ripgrep, PowerShell, repository OpenAPI and Spring Controller inspection.

## Global Constraints

- Base branch is `origin/dev`; isolated branch is `codex/handoff-docs-runtime-alignment`.
- Approved design is `docs/superpowers/specs/2026-08-10-handoff-docs-runtime-alignment-design.md`.
- Original attached files are read-only inputs under `C:/Users/lbw01/GitHub/Commerce-Final-Project-MiriYum/handoff/design/` and `C:/Users/lbw01/GitHub/Commerce-Final-Project-MiriYum/handoff/fullstack/`.
- Target files are the same six relative paths inside the isolated worktree.
- Design documents must be standalone and must not contain local paths, repository-reading instructions, API routes, DTOs, database designs, or implementation guesses.
- Fullstack documents must inspect the current Issue, routing rules, active specs, OpenAPI, actual Controller and Service implementation, tests, and release scope before enabling a feature.
- Product phase labels describe the roadmap; phase labels are never runtime feature flags.
- A feature is enabled only when approved contract, actual Controller and Service implementation, verification evidence, and current release scope intersect.
- Unsupported features must be removed together from navigation, route registration, tabs, buttons, data requests, actions, and production bundles; disabled controls, coming-soon screens, fake successes, and production mocks are forbidden.
- Pickup is available to every business type when the store enables pickup; no `CAFE` or `BAKERY` restriction remains.
- General-reservation menu-hold inventory and pickup inventory are independent service inventories. Consumption, sold-out state, cancellation, and restoration never cross service boundaries.
- Cross-service inventory transfer is outside the confirmed scope. Do not define transfer controls, routes, states, limits, audit behavior, or placeholder UI.
- Regions are exactly Seoul, Busan, Daegu, Daejeon, and Gwangju in user-facing Korean; fullstack documents use current server codes only after reading OpenAPI.
- Platform operator functionality is enhancement-only and absent from first and second MVP runtime shells.
- Second MVP recommendation features are consumer-centered; do not invent store-operator recommendation administration.
- Whole-service policy decisions are not being broadly rewritten. Policy status `확정` or importance `필수` never means `1차 MVP`.
- Current MVP1 scope priority is `docs/specs/mvp1-common/ownership.md` → linked domain spec/OpenAPI → actual Controller/Service and verification → release approval.
- `docs/service-definition.md` and `docs/service-policies/` describe whole-service policy/long-term goals; `docs/technical-architecture.md` describes target architecture. Their existence is not runtime evidence.
- First-MVP store UI flow is operator signup → login → store registration → immediate management of the returned store. Do not create onboarding application, approval-wait, supplement/rejection, or platform-review UI, and do not invent an auto-approval API.
- Current OpenAPI fields such as `APPROVED` remain implementation truth; their existence does not authorize a platform-approval screen.
- Do not modify frontend code, backend code, OpenAPI behavior, migrations, or tests in this PR.
- Use `apply_patch` for document creation and edits. Preserve unrelated files and stage only paths listed by the active task.
- Every task must run `git diff --check` and its task-specific `rg` checks before committing.

---

### Task 1: General Consumer Handoff Pair

**Files:**
- Create: `handoff/design/01-consumer-design.md`
- Create: `handoff/fullstack/01-consumer-fullstack.md`

**Interfaces:**
- Consumes: approved design sections 2–6 and the current `auth-account`, `store-search`, `reservation`, and `menu-hold-pickup` contracts and Controllers.
- Produces: the authoritative consumer design brief and consumer runtime-connection brief used by the final cross-document audit.

- [ ] **Step 1: Read the two attached consumer inputs and current runtime evidence**

Read both original consumer files, the approved design, relevant OpenAPI paths, and current Controller mappings. Record the current gaps in the task report: public menu-hold availability, pickup APIs, and any public bookable-slot construction gap must not be described as implemented.

- [ ] **Step 2: Write the standalone consumer design brief**

Create the target design document with these explicit sections:

- purpose and consumer-only role boundary;
- full stage matrix for first MVP, second MVP, and enhancement;
- consumer signup and login, including first-MVP trusted contact and age self-confirmation without claiming email or phone ownership verification;
- store search inputs for keyword, five regions, catalog category, service date, start time, party size, infant inclusion, and complete-condition availability;
- store detail, public schedules, representative menus, and independent reservation and pickup entry points;
- reservation, optional representative-menu selection, all-or-nothing inventory conflict handling, skip path, completion/detail/cancellation, and history;
- all-business-type pickup with pickup-specific date, time, menu, quantity, status, cancellation, and history;
- second-MVP natural-language, preference, substitute-menu, 3 km nearby-store, availability-aware ranking, and Kakao Map screens;
- enhancement payment/refund, notifications, waiting, check-in/no-show, social login/session, and history modules;
- loading, empty, validation, concurrency-conflict, permission, network, stale-state, and retry states;
- future modules removable without leaving empty layout regions.

Do not mention source files, repositories, backend types, endpoint paths, or API implementation status.

- [ ] **Step 3: Write the consumer fullstack connection brief**

Create the target fullstack document with:

- authoritative read order and the contract-versus-runtime activation formula;
- separate consumer/store-operator authentication namespaces and memory/cookie boundaries from the current contract;
- exact currently supported consumer account/search/reservation-history capabilities after Controller verification;
- independent gates for reservation creation, menu-hold availability and selection, pickup availability and pickup transactions;
- a rule that reservation creation accepting `menuSelections` does not by itself prove that selectable hold quantities can be exposed;
- current pickup hiding because public and transaction Controllers are absent, while retaining all-business-type product policy for later activation;
- independent general-reservation menu-hold and pickup inventory contracts; reject the current shared-pool contract as proof of separate pickup inventory support;
- second-MVP and enhancement modules enabled only by runtime evidence, not phase names;
- route, request, idempotency, HTTP/error-code, stale-query, and namespace verification requirements.

- [ ] **Step 4: Run consumer-specific verification**

Run:

```powershell
git diff --check
rg -n "1차 MVP|2차 MVP|고도화|서울|부산|대구|대전|광주|자연어|3km|카카오맵|웨이팅|결제|노쇼" handoff/design/01-consumer-design.md
rg -n "Controller|OpenAPI|라우트|데이터 요청|실제 구현|메뉴 홀드|픽업|독립" handoff/fullstack/01-consumer-fullstack.md
rg -n "카페.*베이커리|베이커리.*카페|CAFE.*BAKERY|BAKERY.*CAFE|준비 중|비활성 버튼" handoff/design/01-consumer-design.md handoff/fullstack/01-consumer-fullstack.md
```

Expected: the first two searches find all required concepts; the third finds only explicit prohibition text, never an industry restriction or unsupported placeholder instruction.

- [ ] **Step 5: Commit the consumer pair**

```powershell
git add -- handoff/design/01-consumer-design.md handoff/fullstack/01-consumer-fullstack.md
git commit -m "docs: align consumer frontend handoff"
```

### Task 2: Store Operator Handoff Pair

**Files:**
- Create: `handoff/design/02-store-operator-design.md`
- Create: `handoff/fullstack/02-store-operator-fullstack.md`

**Interfaces:**
- Consumes: approved design sections 2–6 and current account, store, schedule, closure, reservation-time-policy, menu, capacity, inventory, reservation, and pickup contracts and Controllers.
- Produces: the authoritative store-operator design and runtime-connection briefs used by the final audit.

- [ ] **Step 1: Read the two attached operator inputs and current runtime evidence**

Read both originals, approved design, current OpenAPI, and Controller mappings. Confirm implemented schedule lifecycle, regular/temporary closure, reservation time policy, menu lifecycle, capacity, reservation query/cancel, and inventory administration. Confirm missing managed-store bootstrap/discovery, reservation fulfillment Controller, pickup Controllers, and separate pickup-inventory contract.

- [ ] **Step 2: Write the standalone store-operator design brief**

Create the target design document with:

- operator-only account, signup/login, and own-profile screens;
- immediate store registration without a separate onboarding-application or approval-wait screen;
- store information, business self-attestation, required onboarding agreement, time zone, catalog fields, modes, and edit boundaries expressed as user-facing inputs without backend names;
- operating-hours and reservation-acceptance schedules with draft, immediate/scheduled publication, cancellation before effect, active/failed states, conflicts, and unsaved changes;
- regular and temporary closure management;
- reservation slot interval, service duration, turnover duration, draft/publication lifecycle, and safe conflict messaging;
- menu create/edit/publication/visibility/selling/retirement, representative marker, structured allergen/origin/alcohol information, and independent business statuses;
- capacity management with people, teams, party-size limits, infant policy, current use, remaining capacity, and non-retroactive changes;
- two clearly separate inventory areas: reservation menu-hold inventory and pickup inventory, each with its own quantities, sold-out control, used/available values, cancellation restoration, and no transfer control;
- reservation list/detail/store cancellation; visit completion designed but modular;
- all-business-type pickup list/detail/cancellation/collection completion designed but modular;
- no invented second-MVP store recommendation administration;
- enhancement dashboard, waiting, payment/refund, notifications, images, analytics, check-in/no-show, and representative/recommended-menu 3–5 management.

- [ ] **Step 3: Write the store-operator fullstack connection brief**

Create the target fullstack document with:

- runtime activation intersection independent of product phase labels;
- operator authentication and own-account APIs;
- a bootstrap guard explaining that managed routes cannot depend on guessed or Web Storage store IDs while no current server-owned discovery path exists;
- exact store creation fields and immediate-management semantics from current OpenAPI, without a separate approval API;
- current schedule draft/publication/cancellation, closure, reservation time policy, menu lifecycle, capacity, reservation query/cancel, and inventory admin capabilities;
- explicit independent gates for visit fulfillment, public pickup, operator pickup, and separate pickup inventory;
- all-business-type pickup product rule without claiming current Controller support;
- current menu inventory connected only as the contract it actually implements; do not relabel a shared online pool as separate pickup inventory;
- no store-switch UI in the one-representative-store first-MVP model;
- second-MVP recommendation-management exclusion and enhancement hiding rules.

- [ ] **Step 4: Run store-operator-specific verification**

Run:

```powershell
git diff --check
rg -n "운영시간|예약 접수|휴무|예약 시간 정책|메뉴 홀드 재고|픽업 재고|방문 완료|수령 완료|3~5" handoff/design/02-store-operator-design.md
rg -n "Controller|OpenAPI|bootstrap|발견|방문 완료|픽업|독립 재고|실제 구현" handoff/fullstack/02-store-operator-fullstack.md
rg -n "카페.*베이커리|베이커리.*카페|CAFE.*BAKERY|BAKERY.*CAFE|재고 이전|준비 중|비활성 버튼" handoff/design/02-store-operator-design.md handoff/fullstack/02-store-operator-fullstack.md
```

Expected: required concepts are present; inventory transfer appears only as an explicit out-of-scope prohibition; no pickup industry restriction or placeholder exposure appears.

- [ ] **Step 5: Commit the operator pair**

```powershell
git add -- handoff/design/02-store-operator-design.md handoff/fullstack/02-store-operator-fullstack.md
git commit -m "docs: align store operator frontend handoff"
```

### Task 3: Platform Operator Handoff Pair

**Files:**
- Create: `handoff/design/03-platform-operator-design.md`
- Create: `handoff/fullstack/03-platform-operator-fullstack.md`

**Interfaces:**
- Consumes: approved design sections 2–4 and the verified absence of current platform-operator account, authentication, permission, and management Controllers.
- Produces: the enhancement-only platform design and the full-shell runtime gate used by the final audit.

- [ ] **Step 1: Read the two attached platform inputs and verify current absence**

Read both originals and search current OpenAPI and Controllers for platform-operator endpoints, roles, and principals. The report must distinguish “future design exists” from “runtime capability exists.”

- [ ] **Step 2: Write the standalone platform-operator design brief**

Create the target design document with:

- enhancement-only stage declaration and total first/second-MVP hiding;
- separate login, shell, navigation, permission context, and audit-oriented high-risk confirmations;
- member management;
- onboarding application document review and approval/rejection;
- store information correction, deactivation, and operation suspension;
- reservation and waiting incident management without replacing routine store operations;
- representative/recommended-menu operational oversight at the platform boundary without editing store service inventory;
- superadministrator-only platform-operator issuance, suspension, and permission management;
- privacy minimization, pagination, concurrent-action, already-processed, permission, partial-failure, and audit states;
- no invented exact enum values, API paths, permission names, or undecided operational averages.

- [ ] **Step 3: Write the platform-operator fullstack connection brief**

Create the target fullstack document with:

- explicit current conclusion that the entire platform shell is absent and hidden;
- prohibition of `ADMIN` shortcuts, role injection, reused consumer/operator login, fake endpoints, hard-coded accounts, and production mocks;
- activation prerequisites for account/auth namespace, permissions, superadministrator boundary, member/onboarding/store/reservation/waiting/recommendation management contracts, state transitions, reason/audit, privacy, and actual backend verification;
- independent future shell and route guards;
- feature-level contract boundaries and final verification checklist;
- product phase labels used only for roadmap communication, never as the runtime activation decision.

- [ ] **Step 4: Run platform-specific verification**

Run:

```powershell
git diff --check
rg -n "고도화|회원 관리|입점|매장 관리|예약 관리|웨이팅 관리|추천|슈퍼관리자|운영자 관리" handoff/design/03-platform-operator-design.md
rg -n "전체.*숨|Controller|OpenAPI|실제 구현|ADMIN|가짜|mock|슈퍼관리자" handoff/fullstack/03-platform-operator-fullstack.md
rg -n "1차 MVP.*활성|2차 MVP.*활성|준비 중|비활성 버튼" handoff/design/03-platform-operator-design.md handoff/fullstack/03-platform-operator-fullstack.md
```

Expected: all enhancement scope is present; no first/second-MVP activation or placeholder exposure appears.

- [ ] **Step 5: Commit the platform pair**

```powershell
git add -- handoff/design/03-platform-operator-design.md handoff/fullstack/03-platform-operator-fullstack.md
git commit -m "docs: align platform operator frontend handoff"
```

### Task 4: Six-Document Cross-Role Audit and Corrections

**Files:**
- Modify if required: `handoff/design/01-consumer-design.md`
- Modify if required: `handoff/design/02-store-operator-design.md`
- Modify if required: `handoff/design/03-platform-operator-design.md`
- Modify if required: `handoff/fullstack/01-consumer-fullstack.md`
- Modify if required: `handoff/fullstack/02-store-operator-fullstack.md`
- Modify if required: `handoff/fullstack/03-platform-operator-fullstack.md`

**Interfaces:**
- Consumes: all three reviewed role pairs and the approved design.
- Produces: one mutually consistent six-document set and the evidence used in the final user-facing audit report.

- [ ] **Step 1: Build a cross-document requirements matrix**

In the task report, map every confirmed feature to role, product phase, design coverage, fullstack activation rule, and current runtime status. Include authentication, search, profile, reservation, reservation menu hold, pickup, service-specific inventories, recommendation, map, payment, notification, waiting, files, check-in/no-show, analytics, representative/recommended menus, and platform administration.

- [ ] **Step 2: Correct cross-document conflicts**

Use `apply_patch` to fix every mismatch found. Pay particular attention to:

- all-business-type pickup wording;
- independent reservation-menu-hold and pickup inventory;
- no transfer UI;
- stage taxonomy versus runtime activation;
- consumer/store/platform role leakage;
- platform enhancement-only visibility;
- second-MVP consumer focus;
- approval-free first-MVP store registration;
- design documents remaining repository-independent;
- fullstack documents re-checking current runtime rather than freezing the `3b619e3` support snapshot as permanent truth.

- [ ] **Step 3: Run the complete document gate**

Run:

```powershell
git diff --check origin/dev...HEAD
rg -n "카페.*베이커리|베이커리.*카페|CAFE.*BAKERY|BAKERY.*CAFE" handoff
rg -n "메뉴 홀드 재고|픽업 재고|독립|재고 이전" handoff
rg -n "준비 중|비활성 버튼|가짜 성공|production mock" handoff
rg -n "플랫폼 운영자" handoff/design/01-consumer-design.md handoff/design/02-store-operator-design.md handoff/fullstack/01-consumer-fullstack.md handoff/fullstack/02-store-operator-fullstack.md
rg -n "C:\\|/Users/|/home/|docs/specs|openapi.yaml|AGENTS.md" handoff/design
rg -n "1차 MVP|2차 MVP|고도화" handoff/design/*.md
rg -n "Controller|OpenAPI|실제 구현|라우트|데이터 요청" handoff/fullstack/*.md
```

Expected:

- no pickup industry restriction;
- independent inventory wording in consumer and operator pairs;
- inventory transfer only in prohibition/out-of-scope text;
- placeholder terms only in prohibition text;
- platform mentions in consumer/operator files only as explicit non-exposure boundaries;
- no local paths or repository instructions in design files;
- all design files carry stage coverage and all fullstack files carry runtime gates.

- [ ] **Step 4: Verify exact changed-file scope**

Run:

```powershell
git status --short
git diff --name-only origin/dev...HEAD
```

Expected changed paths: the six handoff files plus this plan and the approved design document. No frontend, backend, OpenAPI, migration, or active product-spec file may appear.

- [ ] **Step 5: Commit cross-document corrections if any**

If Step 2 changed files, run:

```powershell
git add -- handoff/design/01-consumer-design.md handoff/design/02-store-operator-design.md handoff/design/03-platform-operator-design.md handoff/fullstack/01-consumer-fullstack.md handoff/fullstack/02-store-operator-fullstack.md handoff/fullstack/03-platform-operator-fullstack.md
git commit -m "docs: reconcile frontend handoff policies"
```

If no correction was required, record that fact with the complete gate output in the task report and do not create an empty commit.

---

## Expanded scope-boundary remediation

Tasks 1–4 above record the completed six-handoff work. The following tasks implement the user's correction that whole-service documents and current MVP1 sources were being consumed at the same priority.

### Task 5: Whole-Service Definition and Target Architecture Labels

**Files:**
- Create from read-only input and correct: `docs/service-definition.md`
- Create from read-only input and correct: `docs/technical-architecture.md`
- Modify: `docs/00-index.md`
- Modify: `ai/document-routing.md`

**Read-only inputs:**
- `C:/Users/lbw01/GitHub/Commerce-Final-Project-MiriYum/docs/service-definition.md`
- `C:/Users/lbw01/GitHub/Commerce-Final-Project-MiriYum/docs/technical-architecture.md`

**Interfaces:**
- Consumes: the user's line-specific review and approved design section 9.
- Produces: self-describing whole-service reference documents and routing/index rules used by Task 6.

- [ ] **Step 1: Import the two user-owned source documents without losing unrelated content**

Read each source completely. Create the worktree copies with the original content preserved except for the scoped corrections below. Do not edit or stage the originals in the main checkout.

- [ ] **Step 2: Correct `service-definition.md` scope and phase presentation**

Add a prominent scope banner stating that this is the whole-service/long-term target and not the current MVP1 screen/API source. Correct the cited consumer line so waiting is enhancement. Split the combined inclusion list into first MVP, second MVP, and enhancement. Limit image review, platform approval, and pre-approval access blocking to the enhancement flow. State that current MVP1 uses operator signup → login → store registration → immediate management with no approval-wait or platform-review UI and no invented auto-approval API. Mark Free operating statistics/waiting/no-show dashboard as enhancement.

- [ ] **Step 3: Correct `technical-architecture.md` interpretation**

Add a prominent target-architecture banner. State that waiting, payment/refund, statistics, platform operation, business-certificate review, notification, Kakao Map, and S3 sections describe target modules and do not prove current API implementation or first-MVP UI scope. Add the current-activation read order: ownership → linked spec/OpenAPI → Controller/Service/test evidence → release approval.

- [ ] **Step 4: Route and index both documents**

In `docs/00-index.md` and `ai/document-routing.md`, register the two documents as whole-service/target-architecture references and explicitly give current MVP1 ownership/spec/OpenAPI priority for frontend/API activation decisions.

- [ ] **Step 5: Verify and commit Task 5**

Run:

```powershell
git diff --check
rg -n "전체 서비스|장기 목표|현재 1차 MVP|ownership.md|spec|OpenAPI|Controller|Service|릴리스" docs/service-definition.md docs/technical-architecture.md docs/00-index.md ai/document-routing.md
rg -n "1차 MVP|2차 MVP|고도화|웨이팅|결제|환불|체크인|노쇼|운영 통계|플랫폼 운영자|승인 대기|자동 승인 API" docs/service-definition.md docs/technical-architecture.md
```

Expected: both documents identify their reference scope; phase-sensitive functions are not presented as one current-MVP inclusion list; approval terminology cannot create a first-MVP platform workflow.

Commit only Task 5 files with message `docs: distinguish target service documents from MVP1 scope`.

### Task 6: Policy Status, Ownership, and Fullstack Read Order

**Files:**
- Modify: `docs/service-policies/README.md`
- Modify: `docs/service-policies/02-store-onboarding.md`
- Modify: `docs/specs/mvp1-common/ownership.md`
- Modify: `docs/specs/README.md`
- Modify: `handoff/fullstack/01-consumer-fullstack.md`
- Modify: `handoff/fullstack/02-store-operator-fullstack.md`
- Modify if needed for the same read-order boundary: `handoff/fullstack/03-platform-operator-fullstack.md`

**Interfaces:**
- Consumes: Task 5 document roles, current domain specs/OpenAPI, and the verified first-MVP registration flow.
- Produces: one unambiguous MVP1 precedence rule and no accidental platform-approval frontend scope.

- [ ] **Step 1: Clarify policy status without changing policy decisions**

In the policy master, prominently state that `확정` and `필수` describe decision status/importance, not the current development stage. Link waiting, payment/refund/settlement, check-in/no-show, onboarding approval/rejection, and platform operation to enhancement for screen/API scope.

- [ ] **Step 2: Add the Store policy interpretation boundary**

Preserve STORE-005 and STORE-007 policy content. Add a first-MVP frontend/runtime note: no onboarding application, approval wait, supplement/rejection result, or platform-review UI. Explain that STORE-007 is the post-approval general-feature activation policy for the whole service, not an automatic store-approval API. State the current screen flow as signup → login → store registration → immediate management of the returned store.

- [ ] **Step 3: Correct O-007 and current MVP1 ownership guidance**

In `ownership.md`, explain that approval/operating-state validation refers to the current server contract and does not authorize a platform-operator review screen. Keep operator account signup and store registration as separate steps but state that current MVP1 has no approval-wait step.

- [ ] **Step 4: Put the same priority in specs and fullstack handoffs**

Add the read order `ownership.md` → linked domain spec/OpenAPI → actual Controller/Service/test → release approval. State that service definition/policies/technical architecture inform whole-service design but are not current API activation evidence. Unsupported capabilities remain absent from navigation, routes, tabs, controls, requests, actions, production bundles, and production mocks.

- [ ] **Step 5: Verify and commit Task 6**

Run:

```powershell
git diff --check
rg -n "확정|필수|개발 단계|1차 MVP|고도화|입점 승인|플랫폼 운영자" docs/service-policies/README.md docs/service-policies/02-store-onboarding.md
rg -n "O-007|승인|가입|로그인|매장 등록|바로 관리|승인 대기|ownership.md|OpenAPI|Controller" docs/specs/mvp1-common/ownership.md docs/specs/README.md handoff/fullstack/01-consumer-fullstack.md handoff/fullstack/02-store-operator-fullstack.md handoff/fullstack/03-platform-operator-fullstack.md
```

Expected: the policy content remains valid, but current MVP1 scope and runtime activation cannot be inferred from policy status or target architecture.

Commit only Task 6 files with message `docs: clarify MVP1 scope precedence and onboarding flow`.

### Task 7: Final Cross-Document Audit and PR Update

**Files:**
- Modify if required: the six handoff files and Task 5–6 files, limited to review findings.

**Interfaces:**
- Consumes: all role handoffs, whole-service references, policy notes, ownership/spec/OpenAPI, and actual runtime evidence.
- Produces: the final file-by-file review report and Korean Draft PR description.

- [ ] **Step 1: Audit the eight user criteria file by file**

For every handoff and scope-boundary document, record role fit, required screens/states, stage classification, design/fullstack responsibility, design-versus-runtime distinction, terminology conflicts, invented features/states, and standalone sufficiency. Report passes with evidence, not only `통과`.

- [ ] **Step 2: Correct review findings**

Use only scoped document edits. Do not rewrite long-term service policy decisions. Preserve all-business-type pickup, the target service-specific inventory split and current shared-inventory runtime block, undecided cross-service inventory transfer, general-cancellation-only vacancy succession, and platform-admin enhancement-only boundaries.

- [ ] **Step 3: Run fresh final verification**

Run backend tests, frontend tests/build, repository document and link searches, exact changed-file review, and `git diff --check origin/dev...HEAD`. Verify the branch still contains no product-code or source-OpenAPI behavior changes.

- [ ] **Step 4: Push and update the existing Draft PR in Korean**

Update the title/body to describe the handoff corrections and the scope-precedence fix. Include exact changed documents, review result, verification evidence, and remaining runtime blockers without presenting whole-service policy as current MVP1 implementation.
