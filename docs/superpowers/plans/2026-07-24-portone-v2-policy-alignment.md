# PortOne V2 Policy Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make PortOne V2 the confirmed payment adapter while preserving PG-channel contracts, payment-method activation and restaurant settlement as separate operational boundaries.

**Architecture:** The `payment` domain owns an internal port. Production uses a PortOne V2 adapter following the pinned Agora reference; local development uses a local adapter. Browser payment success is never authoritative: server lookup and verified webhook events converge through one idempotent confirmation path.

**Tech Stack:** Markdown, PortOne V2 browser SDK/server API/webhooks, Spring payment port design, PowerShell text assertions, Git

## Global Constraints

- Reference Agora commit `bf662e2888c625fd27f751ad932022dbb6dc0e2d`.
- Do not copy Agora's V1 `window.IMP` fallback, marketplace settlement or coupon models.
- Do not confirm Toss Payments or any individual payment method.
- Keep `PAY-012` as `TODO`.
- Record the requester/decision maker as `이병우` and the editor as `Codex`.
- Keep identifiers distinct: MiriYum creates the internal payment record ID and internal order ID; the customer-assigned PortOne `paymentId` is derived one-to-one from the prepared internal order ID; PortOne assigns a separate `transactionId` to each attempt under that payment case.
- A browser confirmation sends only the prepared `paymentId` and authenticated internal payment reference as lookup selectors. Ignore browser claims of status, amount and `transactionId`; trust a `transactionId` only after authenticated server lookup or verified webhook and store it per attempt.
- Correlate and deduplicate verified webhooks using distinct `paymentId`, `transactionId`, event type and timestamp or message identifier fields.
- Store ID와 Channel Key는 프런트엔드 공개 설정이고, API Base URL은 서버 전용 비밀이 아닌 설정이며, API Secret과 Webhook Secret은 서버 비밀이다.

---

### Task 1: Payment policy and policy-index status

**Files:**
- Modify: `docs/service-policies/08-payment-refund.md`
- Modify: `docs/service-policies/README.md`
- Modify: `docs/05-functional-requirements.md`

**Interfaces:**
- Consumes: Existing `PAY-001` through `PAY-015` semantics.
- Produces: `PAY-003 = 확정`, `PAY-012 = TODO`, corrected global counts.

- [ ] **Step 1: Run the status assertions before editing**

```powershell
$policy = Get-Content docs/service-policies/08-payment-refund.md -Raw -Encoding UTF8
if ($policy.Contains('| TODO | 필수 | `PAY-003`, `PAY-012` |')) { throw 'PAY-003 still TODO' }
if (-not $policy.Contains('## PAY-003 PortOne V2 결제 연동')) { throw 'confirmed PAY-003 section missing' }
```

Expected: FAIL because `PAY-003` is still grouped under TODO.

- [ ] **Step 2: Make PAY-003 a confirmed PortOne V2 integration policy**

Create a normal `PAY-003` section before `PAY-004` with:

```markdown
## PAY-003 PortOne V2 결제 연동

> 정책 상태: 확정

- 현재 결제 연동 어댑터는 PortOne V2다. 서버가 내부 결제와 고유 주문 ID를 먼저 만들고 프론트엔드는 PortOne V2 브라우저 SDK에 Store ID, Channel Key, 내부 주문 ID 기반 `paymentId`, 주문명, 금액, 통화, 활성 결제수단, 최소 고객 정보와 복귀 URL을 전달한다.
- 클라이언트 성공 응답만으로 확정하지 않는다. 브라우저는 준비된 PortOne `paymentId`와 인증된 내부 결제 참조만 조회 선택자로 보내고, 서버는 알려진 `paymentId`를 PortOne V2 API에서 조회해 상태·정확한 금액과 통화·내부 주문 매핑을 대조한다. PortOne이 결제 시도별로 부여한 `transactionId`는 인증된 조회 또는 검증된 웹훅에서만 신뢰해 시도별로 저장하고, 웹훅도 같은 멱등 확정 경로로 처리한다.
- 실제 PG 채널과 결제수단은 PortOne 콘솔 구성, 계약·심사와 운영 환경 설정이 완료된 항목만 노출한다. PortOne V2 선택은 특정 PG·결제수단·수수료·분쟁 조건의 확정이 아니다.
```

Align `PAY-006`, `PAY-010`, `PAY-011`, `PAY-014` and `PAY-015` to name the PortOne V2 boundary where provider actions occur.

- [ ] **Step 3: Correct master status and counts**

Move `PAY-003` from TODO to confirmed/required, remove it from every current TODO list, and change the current totals:

```markdown
- 상태: `확정` 194개, `팀원 상의 필요` 8개, `자동 추천 예정` 0개, `TODO` 15개, 합계 217개
```

Keep `PAY-012` in TODO and adjust prose so only its settlement conditions remain unresolved in the payment group.

- [ ] **Step 4: Re-run status and count assertions**

Run Step 1, then:

```powershell
$master = Get-Content docs/service-policies/README.md -Raw -Encoding UTF8
if ($master -match 'TODO[^\r\n]*PAY-003') { throw 'master still classifies PAY-003 as TODO' }
if (-not $master.Contains('`확정` 194개')) { throw 'confirmed count not updated' }
if (-not $master.Contains('`TODO` 15개')) { throw 'TODO count not updated' }
```

Expected: PASS.

- [ ] **Step 5: Commit the policy status change**

```powershell
git add docs/service-policies/08-payment-refund.md docs/service-policies/README.md docs/05-functional-requirements.md docs/superpowers/specs/2026-07-24-portone-v2-policy-alignment-design.md docs/superpowers/plans/2026-07-24-portone-v2-policy-alignment.md
git commit -m "docs: confirm PortOne V2 payment adapter"
```

### Task 2: Architecture, API and user-flow alignment

**Files:**
- Create: `docs/adr/ADR-005-portone-v2-payment-adapter.md`
- Modify: `docs/06-system-architecture.md`
- Modify: `docs/07-data-and-api-contracts.md`
- Modify: `docs/04-user-flows.md`
- Modify: `miriyum-service-decisions.md`

**Interfaces:**
- Consumes: Confirmed `PAY-003`, Agora pinned reference.
- Produces: Durable adapter decision, public/secret configuration boundary and one confirmation/recovery flow.

- [ ] **Step 1: Run architecture assertions before editing**

```powershell
$architecture = Get-Content docs/06-system-architecture.md -Raw -Encoding UTF8
@('PortOne V2','PortOnePaymentClient','LocalPaymentClient','CONFIRMING') |
  ForEach-Object { if (-not $architecture.Contains($_)) { throw "missing: $_" } }
```

Expected: FAIL because the adapter architecture is absent.

- [ ] **Step 2: Record ADR-005**

The ADR must state:

```markdown
- 상태: Accepted
- 결정일: 2026-07-24
- 결정: PortOne V2 adapter behind the payment-domain port; production/Docker uses the PortOne client and local development uses a local client.
- Verification: browser response is non-authoritative; server lookup uses the prepared customer-assigned PortOne `paymentId` and checks status, exact amount/currency and internal-order mapping; the PortOne-assigned per-attempt `transactionId` is trusted only from authenticated lookup or a verified webhook; verified webhooks invoke the same path.
- Recovery: `CONFIRMING` acquisition, rollback on lookup exception, central timeout recovery.
- Configuration: Store ID/Channel Key are public frontend configuration; API Base URL is server-only non-secret configuration; API Secret/Webhook Secret are server secrets.
- Rejected: V1 `window.IMP`, direct SDK coupling in domain logic, fixed Toss Payments/payment methods, Agora marketplace settlement model.
```

- [ ] **Step 3: Align architecture and data/API contracts**

Add the exact named port implementations and configuration split to `docs/06-system-architecture.md`. Add the internal payment record ID, internal order ID, customer-assigned PortOne `paymentId`, PortOne-assigned per-attempt `transactionId`, integer amount/currency, signature/timestamp, idempotency and same-confirmation-path boundaries to `docs/07-data-and-api-contracts.md` without inventing final endpoint URLs.

- [ ] **Step 4: Expand the payment user flow**

Use this sequence:

```markdown
내부 결제·주문과 PortOne `paymentId` 준비 → PortOne V2 SDK 결제 요청 → 서버의 알려진 `paymentId` 조회·상태·정확한 금액/통화·내부 주문 매핑과 시도별 `transactionId` 검증 → 동일 멱등 확정 경로로 예약 반영
```

State that verified webhooks call the same path and ambiguous/long-running `CONFIRMING` results remain isolated for lookup/recovery.

- [ ] **Step 5: Add the central change card and current decision summary**

Add:

```markdown
#### DOC-20260724-001 · PortOne V2 결제 연동 확정

- 변경 일시: `2026-07-24 22:56 KST`
- 요청·결정자: 이병우
- 수정 작업자: Codex
- 대상 문서·정책: `PAY-003`, 결제 사용자 흐름, 시스템 아키텍처, 데이터·API 계약, `ADR-005`
- 변경 내용: PortOne V2를 현재 결제 어댑터로 확정하고 Agora 참조 구조의 SDK 요청, 서버 재검증, 웹훅 검증, 동일 확정 경로와 `CONFIRMING` 복구를 문서화했다.
- 변경 이유: 선택된 결제 연동 방식이 후보·TODO로 남아 있던 모순을 제거하고, 어댑터 선택과 실제 PG·결제수단 계약 및 식당 정산을 분리하기 위해서다.
- 추적 정보: PR `#23`, Agora 기준 커밋 `bf662e2888c625fd27f751ad932022dbb6dc0e2d`, 반영 커밋은 Git 이력을 참조한다.
```

Update current counts/lists in the decision history, preserving older snapshots.

- [ ] **Step 6: Run architecture and provider-boundary assertions**

Run Step 1, then:

```powershell
$current = Get-Content docs/service-policies/08-payment-refund.md,docs/service-policies/README.md,docs/05-functional-requirements.md,docs/06-system-architecture.md,docs/07-data-and-api-contracts.md,docs/04-user-flows.md,miriyum-service-decisions.md -Raw -Encoding UTF8
if ($current -match '현재[^\r\n]*(PortOne V2와 Toss Payments|PortOne V2·Toss Payments)') { throw 'candidate wording remains current' }
if (-not $current.Contains('PAY-012')) { throw 'settlement TODO lost' }
if ($current.Contains('window.IMP')) { throw 'V1 fallback leaked into current docs' }
```

Expected: PASS.

- [ ] **Step 7: Commit architecture and decision alignment**

```powershell
git add docs/adr/ADR-005-portone-v2-payment-adapter.md docs/06-system-architecture.md docs/07-data-and-api-contracts.md docs/04-user-flows.md miriyum-service-decisions.md
git commit -m "docs: align PortOne V2 payment flow"
```

### Task 3: Repository-wide documentation verification

**Files:**
- Verify all files changed by Tasks 1 and 2.

**Interfaces:**
- Consumes: Completed policy and architecture alignment.
- Produces: Reviewable diff and exact verification evidence.

- [ ] **Step 1: Run formatting and stale-wording checks**

```powershell
git diff --check origin/main...HEAD
rg -n "PAY-003.*TODO|TODO.*PAY-003|PortOne V2와 Toss Payments|PortOne V2·Toss Payments|window\\.IMP" docs miriyum-service-decisions.md
```

Expected: `git diff --check` exits 0. `rg` may only find explicitly labeled historical snapshots or the design/plan's prohibited-pattern tests; no current policy statement may match.

- [ ] **Step 2: Check links and encoding using the repository verification route**

Read `ai/verification-and-completion.md`, select only configured documentation checks, and run them exactly as registered. If no documentation runtime is configured, record `NOT CONFIGURED` rather than claiming success.

- [ ] **Step 3: Inspect scope**

```powershell
git status --short
git diff --stat origin/main...HEAD
git diff --name-only origin/main...HEAD
```

Expected: only the planned documentation paths and pre-existing PR #23 documentation changes appear.
