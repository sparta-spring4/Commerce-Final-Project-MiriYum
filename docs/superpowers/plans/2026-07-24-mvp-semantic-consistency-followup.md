# MVP Semantic Consistency Follow-up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align the previously identified MVP contradictions and remove the entire review feature set from the first MVP while preserving its policies as future implementation criteria.

**Architecture:** Use the product vision and explicit user decisions as the current product-scope boundary. Keep detailed review policies as future safety contracts, remove review activation from current product, domain, flow, requirement, architecture, and authentication documents, then verify all scope mirrors together.

**Tech Stack:** Markdown, PowerShell assertions, Git

## Global Constraints

- Do not add a new product feature or policy decision.
- Advertising products, billing, and ad removal remain outside the first MVP.
- Reservations confirm immediately and do not expose an approval-pending state.
- Cancellation-resource transfer remains an initial core feature governed by `TRANSFER-001` through `TRANSFER-009`.
- Current waiting includes both onsite and remote waiting.
- Free basic operating statistics are current MVP; Pro comparison, interpretation, recommendation, automatic reports, and export remain excluded.
- Review creation, publication, editing, deletion, ratings, tags, media, reporting, moderation, store replies, abuse detection, trust scoring, sanctions, and false-positive recovery are all outside the first MVP.
- `TRUST-001` through `TRUST-012` remain confirmed future policy criteria; confirmation does not activate current review functionality, permissions, APIs, UI, or storage.
- Modify only the files in the design allowlist.

---

### Task 1: Add failing semantic assertions

**Files:**
- Inspect: `docs/04-user-flows.md`
- Inspect: `docs/service-policies/02-store-onboarding.md`

**Interfaces:**
- Consumes: Current Markdown text on `main`.
- Produces: A reproducible PowerShell assertion command that exits nonzero while the four contradictions remain.

- [x] **Step 1: Run assertions against the current documents**

```powershell
$flow = Get-Content -LiteralPath 'docs\04-user-flows.md' -Raw -Encoding UTF8
$store = Get-Content -LiteralPath 'docs\service-policies\02-store-onboarding.md' -Raw -Encoding UTF8
$checks = @(
  @{ Name = 'general recommendation only'; Pass = $flow.Contains('검색 결과와 일반 추천 결과를 제시한다') -and -not $flow.Contains('추천 또는 광고는 구분해 표시한다') },
  @{ Name = 'no approval pending'; Pass = $flow.Contains('유효한 요청은 즉시 확정한다') -and -not $flow.Contains('승인 대기 상태로 만든다') },
  @{ Name = 'transfer flow'; Pass = $flow.Contains('TRANSFER-001, TRANSFER-002, TRANSFER-003, TRANSFER-004, TRANSFER-005, TRANSFER-006, TRANSFER-007, TRANSFER-008, TRANSFER-009') -and $flow.Contains('5분') -and $flow.Contains('10분') -and $flow.Contains('결제 결과가 불명확하면') },
  @{ Name = 'remote waiting and free statistics'; Pass = $store.Contains('현장·원격 웨이팅') -and $store.Contains('Free 기본 운영 통계') }
)
$failed = @($checks | Where-Object { -not $_.Pass })
if ($failed.Count -gt 0) {
  $failed | ForEach-Object { Write-Error "FAIL: $($_.Name)" }
  exit 1
}
```

Expected: exit code `1`; all four named checks fail before the document edits.

### Task 2: Align discovery, reservation, and transfer user flows

**Files:**
- Modify: `docs/04-user-flows.md`

**Interfaces:**
- Consumes: MVP boundaries from `docs/05-functional-requirements.md`, `docs/service-policies/04-reservation.md`, `docs/service-policies/10-waitlist-transfer.md`, and `docs/service-policies/13-ad-recommendation.md`.
- Produces: Current user flows for general recommendation, immediate reservation confirmation, and cancellation-resource transfer.

- [x] **Step 1: Replace the discovery flow**

Use this exact main-flow sentence:

```markdown
- **주요 흐름:** 서비스는 공개 가능한 매장·영업·메뉴 정보를 바탕으로 검색 결과와 일반 추천 결과를 제시한다.
```

Remove `ADS-002` and `ADS-006` from the discovery flow's related policy IDs while preserving `ADS-007` and `ADS-008`.

- [x] **Step 2: Replace the reservation main flow**

Use this exact sentence:

```markdown
- **주요 흐름:** 서비스는 영업시간·예약 가능 수량·중복 예약 여부, 임시 선점 및 필요한 메뉴 홀드·결제 조건을 검증하고, 유효한 요청은 즉시 확정한다. 확정할 수 없으면 사용자에게 실패 사유를 안내한다.
```

- [x] **Step 3: Add the cancellation-resource transfer flow**

Insert a new section after payment and cancellation:

```markdown
## 7. 취소 자리·수량 자동 승계

- **시작 조건:** 자동 승계가 활성이고, 취소로 예약 수용량이나 메뉴 수량이 반환됐으며 해당 자원에 유효한 대기 등록자가 있다.
- **주요 흐름:** 서비스는 등록 순번이 가장 빠른 유효 후보에게 5분 제안을 보낸다. 사용자가 기한 안에 수락하면 자원을 10분 동안 선점하고 원 거래와 같은 예약·홀드의 검증·결제·확정 흐름으로 전환한다.
- **성공 종료:** 수락한 사용자의 예약 또는 홀드 전환이 최종 확정되고 사용하고 남은 인원·수량은 일반 가용으로 반환된다.
- **실패 종료:** 명시적 거절, 제안 만료, 자격 재검증 실패 또는 검증된 결제 실패에는 정책과 날짜 경계에 따라 다음 유효 후보 제안 또는 일반 가용 처분으로 진행한다. 결제 결과가 불명확하면 자원을 공개하거나 다음 후보로 넘기지 않고 대사·복구 상태로 유지한다.
- **관련 정책 ID:** TRANSFER-001, TRANSFER-002, TRANSFER-003, TRANSFER-004, TRANSFER-005, TRANSFER-006, TRANSFER-007, TRANSFER-008, TRANSFER-009.
```

Renumber the following review and notification sections from `7`, `8` to `8`, `9`.

- [x] **Step 4: Run the user-flow assertions**

Run the Task 1 command.

Expected: the general recommendation, no approval pending, and transfer flow checks pass; the store activation check still fails.

- [x] **Step 5: Commit the user-flow alignment**

```powershell
git add -- docs/04-user-flows.md
git commit -m "docs: align current MVP user flows"
```

### Task 3: Align the store activation feature boundary

**Files:**
- Modify: `docs/service-policies/02-store-onboarding.md`

**Interfaces:**
- Consumes: Current MVP boundaries from `docs/service-policies/05-waiting.md`, `docs/service-policies/14-analytics-report.md`, and `docs/05-functional-requirements.md`.
- Produces: A STORE-007 activation inventory that includes onsite and remote waiting plus Free basic operating statistics while excluding Pro analytics.

- [x] **Step 1: Expand the current activation list**

In the STORE-007 activation list, replace the onsite-only waiting wording with `현장·원격 웨이팅` and add `Free 기본 운영 통계`.

- [x] **Step 2: Narrow the excluded analytics scope**

Replace the broad exclusion of `수요 분석` with the exact future scope `Pro 비교·해석·추천·자동 리포트·내보내기`.

- [x] **Step 3: Align the limiting sentence**

Update the sentence that limits currently supported functions so it refers to the corrected STORE-007 list and keeps linked hold/deposit boundaries without excluding remote waiting or Free basic statistics.

- [x] **Step 4: Run all semantic assertions**

Run the Task 1 command.

Expected: exit code `0`; all four named checks pass.

- [x] **Step 5: Commit the store activation alignment**

```powershell
git add -- docs/service-policies/02-store-onboarding.md
git commit -m "docs: align store activation MVP scope"
```

### Task 4: Verify scope and document hygiene

**Files:**
- Verify: `docs/04-user-flows.md`
- Verify: `docs/service-policies/02-store-onboarding.md`
- Verify: `docs/superpowers/specs/2026-07-24-mvp-semantic-consistency-followup-design.md`
- Verify: `docs/superpowers/plans/2026-07-24-mvp-semantic-consistency-followup.md`

**Interfaces:**
- Consumes: The completed Markdown changes.
- Produces: Evidence that the four contradictions are resolved without changing canonical MVP boundaries or unrelated files.

- [x] **Step 1: Verify the canonical boundary text remains unchanged**

```powershell
git diff main -- docs/05-functional-requirements.md docs/service-policies/04-reservation.md docs/service-policies/05-waiting.md docs/service-policies/10-waitlist-transfer.md docs/service-policies/13-ad-recommendation.md docs/service-policies/14-analytics-report.md
```

Expected: no output.

- [x] **Step 2: Verify formatting**

```powershell
git diff --check main
```

Expected: exit code `0` with no output.

- [x] **Step 3: Verify the allowlist**

```powershell
git diff --name-only main
```

Expected paths:

```text
docs/04-user-flows.md
docs/service-policies/02-store-onboarding.md
docs/superpowers/plans/2026-07-24-mvp-semantic-consistency-followup.md
docs/superpowers/specs/2026-07-24-mvp-semantic-consistency-followup-design.md
```

- [x] **Step 4: Review the final diff**

```powershell
git diff main -- docs/04-user-flows.md docs/service-policies/02-store-onboarding.md
```

Expected: only the four approved semantic corrections.

### Task 5: Remove review activation from the first MVP

**Files:**
- Modify: `docs/01-product-vision.md`
- Modify: `docs/02-users-and-permissions.md`
- Modify: `docs/03-domain-model.md`
- Modify: `docs/04-user-flows.md`
- Modify: `docs/05-functional-requirements.md`
- Modify: `docs/06-system-architecture.md`
- Modify: `docs/service-policies/01-member-auth.md`
- Modify: `docs/service-policies/12-review-trust.md`
- Modify: `docs/service-policies/README.md`
- Modify: `miriyum-service-blueprint.md`
- Modify: `miriyum-service-decisions.md`

**Interfaces:**
- Consumes: The user's decision that the entire review feature group is outside the first MVP.
- Produces: Product, permission, domain, flow, requirement, architecture, authentication, and policy documents that preserve review policies without activating review functionality.

- [x] **Step 1: Run the failing review-scope assertions**

```powershell
$vision = Get-Content -LiteralPath 'docs\01-product-vision.md' -Raw -Encoding UTF8
$permissions = Get-Content -LiteralPath 'docs\02-users-and-permissions.md' -Raw -Encoding UTF8
$domain = Get-Content -LiteralPath 'docs\03-domain-model.md' -Raw -Encoding UTF8
$flow = Get-Content -LiteralPath 'docs\04-user-flows.md' -Raw -Encoding UTF8
$requirements = Get-Content -LiteralPath 'docs\05-functional-requirements.md' -Raw -Encoding UTF8
$architecture = Get-Content -LiteralPath 'docs\06-system-architecture.md' -Raw -Encoding UTF8
$auth = Get-Content -LiteralPath 'docs\service-policies\01-member-auth.md' -Raw -Encoding UTF8
$reviewPolicy = Get-Content -LiteralPath 'docs\service-policies\12-review-trust.md' -Raw -Encoding UTF8
$master = Get-Content -LiteralPath 'docs\service-policies\README.md' -Raw -Encoding UTF8
$blueprint = Get-Content -LiteralPath 'miriyum-service-blueprint.md' -Raw -Encoding UTF8
$checks = @(
  $vision.Contains('리뷰 작성·공개·상호작용 기능은 초기 제공 범위에 포함하지 않는다'),
  -not $permissions.Contains('결제와 리뷰 등 이용자 경험을 관리'),
  -not $domain.Contains('## `review`'),
  -not $flow.Contains('## 8. 리뷰 작성'),
  $requirements.Contains('| 리뷰·신뢰·어뷰징 | TRUST-001') -and $requirements.Contains('| 비초기 | review |'),
  -not $architecture.Contains('├─ review/'),
  -not $auth.Contains('일반 식당 탐색·예약·웨이팅·리뷰를 이용할 수 있으나'),
  $reviewPolicy.Contains('현재 리뷰 기능·권한·API·UI·저장소를 활성화하지 않는다'),
  $master.Contains('리뷰 정책은 확정했지만 현재 1차 MVP 구현 범위에는 포함하지 않는다'),
  $blueprint.Contains('역사적 입력(Draft v0.1) — 현재 기준 아님')
)
if (@($checks | Where-Object { -not $_ }).Count -gt 0) { exit 1 }
```

Expected: exit code `1`; the current documents still activate review in the first MVP.

- [x] **Step 2: Align product, permission, domain, flow, requirement, and architecture documents**

- Mark post-visit review evaluation as a future product value and add the full review feature group to initial non-goals.
- Remove review from current general-user permissions.
- Remove the `review` section and review dependencies from the initial domain model, changing the initial domain count from six to five.
- Remove the review user flow, renumber notification to section 8, and remove review events from its current start condition.
- Change the `TRUST-001` through `TRUST-012` functional group from `초기 핵심` to `비초기` with an explicit full-feature exclusion boundary.
- Remove `review/` from the initial backend package structure.

- [x] **Step 3: Align authentication and review policy boundaries**

- Remove review from the AUTH-009 sentence that lists current non-alcohol services for users aged 14 or older.
- Add a first-MVP boundary to `12-review-trust.md` that keeps all detailed policy decisions but activates no current review functionality, permissions, API, UI, or storage.
- Add the same boundary note below the review section in the policy master.
- Record the user's current review exclusion decision in `miriyum-service-decisions.md`.
- Mark `miriyum-service-blueprint.md` as a historical input that cannot activate or expand the current MVP.

- [x] **Step 4: Run the review-scope assertions**

Run the Step 1 command.

Expected: exit code `0`; all ten scope assertions pass.

### Task 6: Reverify the expanded MVP consistency scope

**Files:**
- Verify every path in the expanded design allowlist.

**Interfaces:**
- Consumes: The completed five semantic consistency corrections.
- Produces: Evidence for semantic scope, policy ID coverage, links, encoding, formatting, and exact changed-path allowlist.

- [x] **Step 1: Run all existing semantic checks and the Task 5 review assertions**

Expected: all checks pass.

- [x] **Step 2: Verify policy IDs, relative links, encoding, and diff formatting**

```powershell
git diff --check main
git diff --name-only main
```

Expected: exit code `0`, no formatting findings, and only expanded allowlist paths.

- [ ] **Step 3: Request independent review**

The reviewer must compare `main..HEAD`, verify that review policy confirmation is not confused with current MVP activation, and report Critical, Important, and Minor findings.
