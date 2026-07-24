# MVP Semantic Consistency Follow-up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align the four remaining important MVP semantic contradictions in the user-flow and store-onboarding documents with the approved functional requirements and detailed policies.

**Architecture:** Keep `docs/05-functional-requirements.md` and the detailed service policies as canonical boundaries. Change only the two downstream documents that currently misstate those boundaries, then verify the exact semantic invariants and repository hygiene.

**Tech Stack:** Markdown, PowerShell assertions, Git

## Global Constraints

- Do not add a new product feature or policy decision.
- Advertising products, billing, and ad removal remain outside the first MVP.
- Reservations confirm immediately and do not expose an approval-pending state.
- Cancellation-resource transfer remains an initial core feature governed by `TRANSFER-001` through `TRANSFER-009`.
- Current waiting includes both onsite and remote waiting.
- Free basic operating statistics are current MVP; Pro comparison, interpretation, recommendation, and automatic reports remain excluded.
- Modify only the files in the design allowlist.

---

### Task 1: Add failing semantic assertions

**Files:**
- Inspect: `docs/04-user-flows.md`
- Inspect: `docs/service-policies/02-store-onboarding.md`

**Interfaces:**
- Consumes: Current Markdown text on `main`.
- Produces: A reproducible PowerShell assertion command that exits nonzero while the four contradictions remain.

- [ ] **Step 1: Run assertions against the current documents**

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

- [ ] **Step 1: Replace the discovery flow**

Use this exact main-flow sentence:

```markdown
- **주요 흐름:** 서비스는 공개 가능한 매장·영업·메뉴 정보를 바탕으로 검색 결과와 일반 추천 결과를 제시한다.
```

Remove `ADS-002` and `ADS-006` from the discovery flow's related policy IDs while preserving `ADS-007` and `ADS-008`.

- [ ] **Step 2: Replace the reservation main flow**

Use this exact sentence:

```markdown
- **주요 흐름:** 서비스는 영업시간·예약 가능 수량·중복 예약 여부를 검증하고, 유효한 요청은 즉시 확정한다. 확정할 수 없으면 사용자에게 실패 사유를 안내한다.
```

- [ ] **Step 3: Add the cancellation-resource transfer flow**

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

- [ ] **Step 4: Run the user-flow assertions**

Run the Task 1 command.

Expected: the general recommendation, no approval pending, and transfer flow checks pass; the store activation check still fails.

- [ ] **Step 5: Commit the user-flow alignment**

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

- [ ] **Step 1: Expand the current activation list**

In the STORE-007 activation list, replace the onsite-only waiting wording with `현장·원격 웨이팅` and add `Free 기본 운영 통계`.

- [ ] **Step 2: Narrow the excluded analytics scope**

Replace the broad exclusion of `수요 분석` with the exact future scope `Pro 비교·해석·추천·자동 리포트`.

- [ ] **Step 3: Align the limiting sentence**

Update the sentence that limits currently supported functions so it refers to the corrected STORE-007 list and keeps linked hold/deposit boundaries without excluding remote waiting or Free basic statistics.

- [ ] **Step 4: Run all semantic assertions**

Run the Task 1 command.

Expected: exit code `0`; all four named checks pass.

- [ ] **Step 5: Commit the store activation alignment**

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

- [ ] **Step 1: Verify the canonical boundary text remains unchanged**

```powershell
git diff main -- docs/05-functional-requirements.md docs/service-policies/04-reservation.md docs/service-policies/05-waiting.md docs/service-policies/10-waitlist-transfer.md docs/service-policies/13-ad-recommendation.md docs/service-policies/14-analytics-report.md
```

Expected: no output.

- [ ] **Step 2: Verify formatting**

```powershell
git diff --check main
```

Expected: exit code `0` with no output.

- [ ] **Step 3: Verify the allowlist**

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

- [ ] **Step 4: Review the final diff**

```powershell
git diff main -- docs/04-user-flows.md docs/service-policies/02-store-onboarding.md
```

Expected: only the four approved semantic corrections.
