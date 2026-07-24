# Document History and Pickup Hold Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make document changes traceable through the existing central decision history and align standalone pickup holds as a `booking`-owned exception available only to cafes and bakeries regardless of dine-in operation.

**Architecture:** `miriyum-service-decisions.md` remains the only central historical record; current policy truth stays in its owning `docs/` files. Standalone pickup holds share the booking/menu-hold orchestration and inventory invariants but omit visit-capacity allocation.

**Tech Stack:** Markdown, PowerShell text assertions, Git

## Global Constraints

- Do not create a permanent work-log or lifecycle mirror.
- Record the requester/decision maker as `이병우` and the editor as `Codex`.
- Cafe/bakery eligibility does not depend on whether the store has dine-in seating.
- General restaurants and other categories cannot enable standalone pickup holds.
- Preserve historical records; do not rewrite old decisions as if they were made today.

---

### Task 1: Central document-change history format

**Files:**
- Modify: `docs/00-index.md`
- Modify: `docs/service-policies/README.md`
- Modify: `docs/service-policies/00-policy-template.md`
- Modify: `miriyum-service-decisions.md`

**Interfaces:**
- Consumes: Existing `miriyum-service-decisions.md` historical decision ownership.
- Produces: One date-card template for all later documentation changes.

- [ ] **Step 1: Run the history-format assertions before editing**

```powershell
$history = Get-Content miriyum-service-decisions.md -Raw -Encoding UTF8
@('요청·결정자: 이병우','수정 작업자: Codex','대상 문서·정책:','변경 이유:','추적 정보:') |
  ForEach-Object { if (-not $history.Contains($_)) { throw "missing: $_" } }
```

Expected: FAIL on the first missing field because the new card format is not present.

- [ ] **Step 2: Add the central-history governance links**

Add the following rule to `docs/00-index.md` and equivalent role separation to the policy master/template:

```markdown
- 정본 문서를 수정하면 `miriyum-service-decisions.md`의 중앙 변경 이력도 함께 갱신한다. 정책 문서의 결정 기록은 정책 의미·상태·근거를, 중앙 변경 이력은 요청·결정자·수정 작업자·대상·내용·이유를 소유한다.
```

- [ ] **Step 3: Introduce the date-card template without deleting history**

At the start of `## 7. 변경 기록`, add:

```markdown
### 2026-07-24 이후 기록 형식

새 문서 변경은 날짜별 카드로 기록한다. 현재 정책 내용은 관련 `docs/` 정본에서 확인하고, 이 절에는 변경의 추적 정보만 남긴다.

#### DOC-YYYYMMDD-NNN · 변경 제목

- 변경 일시: `YYYY-MM-DD HH:mm KST`
- 요청·결정자: 이병우
- 수정 작업자: 실제 문서 수정자
- 대상 문서·정책: 변경한 정본과 정책 ID
- 변경 내용: 이전 기준과 새 기준
- 변경 이유: 변경이 필요했던 제품·정책·정합성 근거
- 추적 정보: 관련 PR과 커밋

### 2026-07-23 이전 누적 기록
```

Keep the existing table and later numbered history sections below this heading.

- [ ] **Step 4: Re-run the history-format assertions**

Run the Step 1 PowerShell block.

Expected: PASS with exit code 0.

- [ ] **Step 5: Commit the history-governance change**

```powershell
git add docs/00-index.md docs/service-policies/README.md docs/service-policies/00-policy-template.md miriyum-service-decisions.md docs/superpowers/specs/2026-07-24-document-history-and-pickup-hold-design.md docs/superpowers/plans/2026-07-24-document-history-and-pickup-hold.md
git commit -m "docs: centralize document change history"
```

### Task 2: Cafe and bakery standalone pickup eligibility

**Files:**
- Modify: `docs/03-domain-model.md`
- Modify: `docs/04-user-flows.md`
- Modify: `docs/05-functional-requirements.md`
- Modify: `docs/service-policies/02-store-onboarding.md`
- Modify: `docs/service-policies/06-menu-hold.md`
- Modify: `miriyum-service-decisions.md`

**Interfaces:**
- Consumes: `booking`, `HOLD-004`, `HOLD-005`, `HOLD-007`, `HOLD-010`.
- Produces: One consistent eligibility and resource-allocation rule across domain, flow, requirement and detailed policy documents.

- [ ] **Step 1: Run negative assertions against the current policy**

```powershell
$hold = Get-Content docs/service-policies/06-menu-hold.md -Raw -Encoding UTF8
$onboarding = Get-Content docs/service-policies/02-store-onboarding.md -Raw -Encoding UTF8
if (-not $hold.Contains('카페·베이커리')) { throw 'cafe/bakery eligibility missing' }
if ($hold.Contains('식당 대표자가 메뉴별로 `단독 픽업 홀드`를')) { throw 'all-store eligibility remains' }
if ($onboarding.Contains('베이커리는 방문 예약 개념을 두지 않으므로')) { throw 'bakery-only assumption remains' }
```

Expected: FAIL because cafe/bakery eligibility is missing.

- [ ] **Step 2: Align the detailed policies**

Use this exact policy boundary in `HOLD-005` and summarize it in `STORE-007`:

```markdown
- 카페·베이커리 업종으로 승인된 매장은 홀 운영 여부와 관계없이 메뉴별 단독 픽업 홀드를 활성화할 수 있다. 일반 식당과 다른 업종은 활성화할 수 없다.
- 홀을 운영하는 카페·베이커리도 테이크아웃 요청에는 단독 픽업 홀드를 사용할 수 있으며, 방문 예약 요청에는 `HOLD-004`의 예약 수용량·메뉴 수량 결합 선점을 적용한다.
- 이 예외는 카페·베이커리에 홀 없이 테이크아웃 중심으로 운영하는 매장이 많기 때문에 제공한다. 홀 없는 매장만 허용한다는 조건은 아니다.
- 단독 픽업 홀드는 `booking` 내부에서 메뉴 수량과 픽업 제공 구간만 선점하며 방문 예약 레코드·좌석·인원 수용량을 만들지 않는다.
```

Replace “independent flow/domain” wording with “visit-capacity-independent path inside booking”.

- [ ] **Step 3: Align domain, functional requirement and user flow**

Add a user flow with:

```markdown
## 5. 카페·베이커리 단독 픽업 홀드

- **시작 조건:** 로그인 사용자가 단독 픽업 홀드를 활성화한 카페·베이커리의 메뉴와 픽업 구간을 선택한다.
- **주요 흐름:** `booking`은 업종 자격, 메뉴 수량, 영업·픽업 구간과 사용자 제한을 검증하고 메뉴 수량만 임시 선점한 뒤 픽업 홀드를 확정한다. 홀 운영 여부는 자격 조건이 아니며 방문 예약 수용량은 만들거나 선점하지 않는다.
- **성공 종료:** 사용자는 확정 수량·픽업 구간·수령 조건을 확인하고 매장은 같은 메뉴 수량 원장에서 준비 상태를 관리한다.
- **실패 종료:** 업종 부적격, 기능 미활성, 비영업, 무재고, 선점 만료 또는 상태 불일치에는 확정하지 않고 수량을 안전하게 반환한다.
- **관련 정책 ID:** HOLD-001, HOLD-003, HOLD-005, HOLD-007, HOLD-009, HOLD-010, HOLD-013.
```

Renumber later user flows. Add matching one-sentence ownership/eligibility summaries to the domain and functional-requirements documents.

- [ ] **Step 4: Add the decision record and central change card**

Add a 2026-07-24 `HOLD-005` decision row naming `이병우` as the direct decision source. Add:

```markdown
#### DOC-20260724-002 · 카페·베이커리 단독 픽업 홀드 범위 정정

- 변경 일시: `2026-07-24 KST`
- 요청·결정자: 이병우
- 수정 작업자: Codex
- 대상 문서·정책: `docs/03-domain-model.md`, `docs/04-user-flows.md`, `docs/05-functional-requirements.md`, `STORE-007`, `HOLD-005`
- 변경 내용: 카페·베이커리는 홀 운영 여부와 관계없이 단독 픽업 홀드를 사용할 수 있고 일반 식당·다른 업종은 사용할 수 없도록 정렬했다. 기능 소유권은 `booking`으로 유지하고 방문 예약 수용량만 할당하지 않는다.
- 변경 이유: 카페·베이커리에 테이크아웃 중심 매장이 많아 일반 식당과 구분되는 픽업 흐름이 필요하며, 이를 홀 없는 매장만의 기능으로 제한하려는 결정은 아니기 때문이다.
- 추적 정보: PR `#23`, 커밋은 반영 커밋을 참조한다.
```

- [ ] **Step 5: Run pickup consistency assertions**

Run the Step 1 block, then:

```powershell
$all = Get-Content docs/03-domain-model.md,docs/04-user-flows.md,docs/05-functional-requirements.md,docs/service-policies/02-store-onboarding.md,docs/service-policies/06-menu-hold.md -Raw -Encoding UTF8
@('카페','베이커리','홀 운영 여부','booking','일반 식당') |
  ForEach-Object { if (-not $all.Contains($_)) { throw "missing: $_" } }
```

Expected: both commands PASS.

- [ ] **Step 6: Commit the pickup policy alignment**

```powershell
git add docs/03-domain-model.md docs/04-user-flows.md docs/05-functional-requirements.md docs/service-policies/02-store-onboarding.md docs/service-policies/06-menu-hold.md miriyum-service-decisions.md
git commit -m "docs: align cafe bakery pickup holds"
```
