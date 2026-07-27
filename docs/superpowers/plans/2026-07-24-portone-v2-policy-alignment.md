# PortOne V2 정책 정렬 구현 계획

> **에이전트 작업자 필수 지침:** 이 계획은 `superpowers:subagent-driven-development` 사용을 권장하며, 같은 세션에서 직접 실행할 때는 `superpowers:executing-plans`를 사용해 작업별로 구현한다. 각 단계는 확인란(`- [ ]`)으로 추적한다.

**목표:** PortOne V2를 확정 결제 어댑터로 삼고 PG 채널 계약, 결제수단 활성화와 식당 정산은 별도의 운영 경계로 보존한다.

**구조:** `payment` 도메인이 내부 포트를 소유한다. 운영 환경은 고정된 Agora 참조를 따르는 PortOne V2 어댑터를 사용하고 로컬 개발은 로컬 어댑터를 사용한다. 브라우저 결제 성공은 권위 있는 결과가 아니며 서버 조회와 검증된 웹훅 사건은 하나의 멱등 확정 경로로 수렴한다.

**기술 구성:** Markdown, PortOne V2 브라우저 SDK·서버 API·웹훅, Spring 결제 포트 설계, PowerShell 텍스트 검증, Git

## 공통 제약

- Agora 커밋 `bf662e2888c625fd27f751ad932022dbb6dc0e2d`를 참조한다.
- Agora의 V1 `window.IMP` 대체 경로, 마켓플레이스 정산 또는 쿠폰 모델을 복사하지 않는다.
- Toss Payments나 개별 결제수단을 확정하지 않는다.
- `PAY-012`는 `TODO`로 유지한다.
- 요청·결정자는 `이병우`, 수정자는 `Codex`로 기록한다.
- 식별자를 구분한다. MiriYum은 내부 결제 레코드 ID와 내부 주문 ID를 만들고, 고객사가 채번하는 PortOne `paymentId`는 준비된 내부 주문 ID에서 일대일로 파생한다. PortOne은 해당 결제 건 아래의 각 시도에 별도 `transactionId`를 부여한다.
- 브라우저 확정은 준비된 `paymentId`와 인증된 내부 결제 참조만 조회 선택자로 보낸다. 브라우저가 주장하는 상태·금액·`transactionId`는 무시하고, 인증된 서버 조회나 검증된 웹훅 뒤에만 `transactionId`를 신뢰해 시도별로 저장한다.
- 검증된 웹훅은 서로 다른 `paymentId`, `transactionId`, 사건 유형과 타임스탬프 또는 메시지 식별자 필드로 상관관계를 맺고 중복을 제거한다.
- Store ID와 Channel Key는 프런트엔드 공개 설정이고, API Base URL은 서버 전용 비밀이 아닌 설정이며, API Secret과 Webhook Secret은 서버 비밀이다.
- 조회 예외에는 거래를 `결과 불명확`·`확인 대기`로 격리하고 작업 임대만 해제한다. 장기 `CONFIRMING`은 중앙 대사 작업자만 인수하며, 인증된 PortOne 조회가 `PAID`이면 같은 멱등 확정 경로를 호출하고 실패·취소·결제 없음 같은 명시적 비성공 종결을 확인한 뒤에만 결제·재시도 가능 상태를 복원한다. 대기·알 수 없음에는 새 결제 시도·청구를 노출하지 않고 자동 해소 불가 건은 `PAY-014`·`PAY-015`로 보낸다.

---

### 작업 1: 결제 정책과 정책 색인 상태

**파일:**
- 수정: `docs/service-policies/08-payment-refund.md`
- 수정: `docs/service-policies/README.md`
- 수정: `docs/05-functional-requirements.md`

**연결:**
- 입력: 기존 `PAY-001`~`PAY-015` 의미
- 출력: `PAY-003 = 확정`, `PAY-012 = TODO`, 수정된 전체 집계

- [ ] **1단계: 편집 전에 상태 검증 실행**

```powershell
$policy = Get-Content docs/service-policies/08-payment-refund.md -Raw -Encoding UTF8
if ($policy.Contains('| TODO | 필수 | `PAY-003`, `PAY-012` |')) { throw 'PAY-003 still TODO' }
if (-not $policy.Contains('## PAY-003 PortOne V2 결제 연동')) { throw 'confirmed PAY-003 section missing' }
```

기대 결과: `PAY-003`이 아직 TODO 그룹에 있으므로 실패한다.

- [ ] **2단계: PAY-003을 확정 PortOne V2 연동 정책으로 변경**

`PAY-004` 앞에 다음 내용의 일반 `PAY-003` 절을 만든다.

```markdown
## PAY-003 PortOne V2 결제 연동

> 정책 상태: 확정

- 현재 결제 연동 어댑터는 PortOne V2다. 서버가 내부 결제와 고유 주문 ID를 먼저 만들고 프론트엔드는 PortOne V2 브라우저 SDK에 Store ID, Channel Key, 내부 주문 ID 기반 `paymentId`, 주문명, 금액, 통화, 활성 결제수단, 최소 고객 정보와 복귀 URL을 전달한다.
- 클라이언트 성공 응답만으로 확정하지 않는다. 브라우저는 준비된 PortOne `paymentId`와 인증된 내부 결제 참조만 조회 선택자로 보내고, 서버는 알려진 `paymentId`를 PortOne V2 API에서 조회해 상태·정확한 금액과 통화·내부 주문 매핑을 대조한다. PortOne이 결제 시도별로 부여한 `transactionId`는 인증된 조회 또는 검증된 웹훅에서만 신뢰해 시도별로 저장하고, 웹훅도 같은 멱등 확정 경로로 처리한다.
- 조회 예외와 장기 `CONFIRMING`은 결제 가능 상태로 추정 복구하지 않는다. 거래는 `결과 불명확`·`확인 대기`로 격리하고 중앙 대사 작업자만 작업 임대를 인수한다. 인증된 조회가 `PAID`이면 같은 멱등 확정 경로를 호출하고 명시적 비성공 종결을 확인한 뒤에만 재시도를 허용하며, 그 전에는 새 결제 시도·청구를 차단하고 미해소 건을 `PAY-014`·`PAY-015`로 보낸다.
- 실제 PG 채널과 결제수단은 PortOne 콘솔 구성, 계약·심사와 운영 환경 설정이 완료된 항목만 노출한다. PortOne V2 선택은 특정 PG·결제수단·수수료·분쟁 조건의 확정이 아니다.
```

제공자 작업이 발생하는 PortOne V2 경계를 명시하도록 `PAY-006`, `PAY-010`, `PAY-011`, `PAY-014`, `PAY-015`를 정렬한다.

- [ ] **3단계: 마스터 상태와 집계 수정**

`PAY-003`을 TODO에서 확정·필수로 옮기고 모든 현재 TODO 목록에서 제거한 뒤 현재 합계를 다음과 같이 바꾼다.

```markdown
- 상태: `확정` 194개, `팀원 상의 필요` 8개, `자동 추천 예정` 0개, `TODO` 15개, 합계 217개
```

`PAY-012`는 TODO에 유지하고 결제 그룹에서 미결정인 범위가 해당 정산 조건뿐임을 문장에 반영한다.

- [ ] **4단계: 상태와 집계 검증 다시 실행**

1단계를 실행한 뒤 다음을 실행한다.

```powershell
$master = Get-Content docs/service-policies/README.md -Raw -Encoding UTF8
if ($master -match 'TODO[^\r\n]*PAY-003') { throw 'master still classifies PAY-003 as TODO' }
if (-not $master.Contains('`확정` 194개')) { throw 'confirmed count not updated' }
if (-not $master.Contains('`TODO` 15개')) { throw 'TODO count not updated' }
```

기대 결과: 통과한다.

- [ ] **5단계: 정책 상태 변경 커밋**

```powershell
git add docs/service-policies/08-payment-refund.md docs/service-policies/README.md docs/05-functional-requirements.md docs/superpowers/specs/2026-07-24-portone-v2-policy-alignment-design.md docs/superpowers/plans/2026-07-24-portone-v2-policy-alignment.md
git commit -m "docs: confirm PortOne V2 payment adapter"
```

### 작업 2: 아키텍처·API·사용자 흐름 정렬

**파일:**
- 생성: `docs/adr/ADR-005-portone-v2-payment-adapter.md`
- 수정: `docs/06-system-architecture.md`
- 수정: `docs/07-data-and-api-contracts.md`
- 수정: `docs/04-user-flows.md`
- 수정: `miriyum-service-decisions.md`

**연결:**
- 입력: 확정된 `PAY-003`, 고정된 Agora 참조
- 출력: 지속 가능한 어댑터 결정, 공개·비밀 설정 경계와 하나의 확정·복구 흐름

- [ ] **1단계: 편집 전에 아키텍처 검증 실행**

```powershell
$architecture = Get-Content docs/06-system-architecture.md -Raw -Encoding UTF8
@('PortOne V2','PortOnePaymentClient','LocalPaymentClient','CONFIRMING') |
  ForEach-Object { if (-not $architecture.Contains($_)) { throw "missing: $_" } }
```

기대 결과: 어댑터 아키텍처가 없으므로 실패한다.

- [ ] **2단계: ADR-005 기록**

ADR은 다음 내용을 명시해야 한다.

```markdown
- 상태: Accepted
- 결정일: 2026-07-24
- 결정: 결제 도메인 포트 뒤에 PortOne V2 어댑터를 둔다. 운영·Docker 환경은 PortOne 클라이언트를 사용하고 로컬 개발은 로컬 클라이언트를 사용한다.
- 검증: 브라우저 응답은 권위가 없다. 서버 조회는 준비된 고객사 채번 PortOne `paymentId`로 상태, 정확한 금액·통화와 내부 주문 매핑을 확인한다. PortOne이 시도별로 부여한 `transactionId`는 인증된 조회나 검증된 웹훅에서만 신뢰하며, 검증된 웹훅도 같은 경로를 호출한다.
- 복구: 조회 예외와 오래된 `CONFIRMING`은 격리한 채 대사 작업자 임대만 해제하거나 인수한다. `PAID`는 같은 멱등 확정 경로를 사용하고, 인증된 조회가 명시적 비성공 종결을 입증한 뒤에만 결제를 다시 허용한다.
- 설정: Store ID와 Channel Key는 프런트엔드 공개 설정이다. API Base URL은 서버 전용 비밀이 아닌 설정이고 API Secret과 Webhook Secret은 서버 비밀이다.
- 미채택: V1 `window.IMP`, 도메인 로직의 직접 SDK 결합, 고정 Toss Payments·결제수단, Agora 마켓플레이스 정산 모델
```

- [ ] **3단계: 아키텍처와 데이터·API 계약 정렬**

정확한 포트 구현 이름과 설정 분리를 `docs/06-system-architecture.md`에 추가한다. 최종 엔드포인트 URL을 만들지 않고 내부 결제 레코드 ID, 내부 주문 ID, 고객사 채번 PortOne `paymentId`, PortOne 시도별 `transactionId`, 정수 금액·통화, 서명·타임스탬프, 멱등성과 동일 확정 경로 경계를 `docs/07-data-and-api-contracts.md`에 추가한다.

- [ ] **4단계: 결제 사용자 흐름 확장**

다음 순서를 사용한다.

```markdown
내부 결제·주문과 PortOne `paymentId` 준비 → PortOne V2 SDK 결제 요청 → 서버의 알려진 `paymentId` 조회·상태·정확한 금액/통화·내부 주문 매핑과 시도별 `transactionId` 검증 → 동일 멱등 확정 경로로 예약 반영
```

검증된 웹훅이 같은 경로를 호출한다고 명시한다. 조회 예외와 모호하거나 오래 지속된 `CONFIRMING`은 작업자 임대만 해제하고 중앙 대사를 위해 격리 상태로 유지한다. 인증된 조회가 명시적 비성공 종결을 입증할 때까지 새 청구를 노출하지 않고, 해소되지 않은 모호성은 `PAY-014`·`PAY-015`로 보낸다.

- [ ] **5단계: 중앙 변경 카드와 현재 결정 요약 추가**

다음을 추가한다.

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

과거 스냅샷을 보존하면서 결정 이력의 현재 집계와 목록을 갱신한다.

- [ ] **6단계: 아키텍처와 제공자 경계 검증 실행**

1단계를 실행한 뒤 다음을 실행한다.

```powershell
$current = Get-Content docs/service-policies/08-payment-refund.md,docs/service-policies/README.md,docs/05-functional-requirements.md,docs/06-system-architecture.md,docs/07-data-and-api-contracts.md,docs/04-user-flows.md,miriyum-service-decisions.md -Raw -Encoding UTF8
if ($current -match '현재[^\r\n]*(PortOne V2와 Toss Payments|PortOne V2·Toss Payments)') { throw 'candidate wording remains current' }
if (-not $current.Contains('PAY-012')) { throw 'settlement TODO lost' }
if ($current.Contains('window.IMP')) { throw 'V1 fallback leaked into current docs' }
```

기대 결과: 통과한다.

- [ ] **7단계: 아키텍처와 결정 정렬 커밋**

```powershell
git add docs/adr/ADR-005-portone-v2-payment-adapter.md docs/06-system-architecture.md docs/07-data-and-api-contracts.md docs/04-user-flows.md miriyum-service-decisions.md
git commit -m "docs: align PortOne V2 payment flow"
```

### 작업 3: 저장소 전체 문서 검증

**파일:**
- 작업 1과 작업 2에서 변경한 모든 파일을 검증한다.

**연결:**
- 입력: 완료된 정책·아키텍처 정렬
- 출력: 검토 가능한 변경 내역과 정확한 검증 증거

- [ ] **1단계: 형식과 오래된 표현 검사 실행**

```powershell
git diff --check origin/main...HEAD
rg -n "PAY-003.*TODO|TODO.*PAY-003|PortOne V2와 Toss Payments|PortOne V2·Toss Payments|window\\.IMP" docs miriyum-service-decisions.md
```

기대 결과: `git diff --check`는 종료 코드 0이다. `rg`는 명시적으로 표시된 과거 스냅샷이나 설계·계획의 금지 패턴 검사에서만 결과가 나올 수 있으며 현재 정책 문장과 일치해서는 안 된다.

- [ ] **2단계: 저장소 검증 경로로 링크와 인코딩 검사**

`ai/verification-and-completion.md`를 읽고 구성된 문서 검사만 선택해 등록된 그대로 실행한다. 문서 실행 환경이 구성되지 않았다면 성공을 주장하지 말고 `NOT CONFIGURED`로 기록한다.

- [ ] **3단계: 범위 검사**

```powershell
git status --short
git diff --stat origin/main...HEAD
git diff --name-only origin/main...HEAD
```

기대 결과: 계획된 문서 경로와 기존 PR #23 문서 변경만 표시된다.
