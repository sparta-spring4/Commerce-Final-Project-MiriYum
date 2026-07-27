# 문서 변경 이력과 픽업 홀드 구현 계획

> **에이전트 작업자 필수 지침:** 이 계획은 `superpowers:subagent-driven-development` 사용을 권장하며, 같은 세션에서 직접 실행할 때는 `superpowers:executing-plans`를 사용해 작업별로 구현한다. 각 단계는 확인란(`- [ ]`)으로 추적한다.

**목표:** 기존 중앙 결정 이력에서 문서 변경을 추적할 수 있게 하고, 단독 픽업 홀드를 홀 운영 여부와 관계없이 카페·베이커리에만 제공하는 `booking` 소유 예외로 정렬한다.

**구조:** `miriyum-service-decisions.md`를 유일한 중앙 변경 이력으로 유지하고 현재 정책의 정본은 각 소유 `docs/` 파일에 둔다. 단독 픽업 홀드는 예약·메뉴 홀드 조정과 재고 불변식을 공유하지만 방문 수용량은 할당하지 않는다.

**기술 구성:** Markdown, PowerShell 텍스트 검증, Git

## 공통 제약

- 영구 작업 로그나 수명 주기 복제본을 만들지 않는다.
- 요청·결정자는 `이병우`, 수정자는 `Codex`로 기록한다.
- 카페·베이커리 자격은 매장의 홀 좌석 운영 여부에 의존하지 않는다.
- 일반 식당과 다른 업종은 단독 픽업 홀드를 활성화할 수 없다.
- 과거 기록을 보존하고 이전 결정을 오늘 내린 것처럼 다시 쓰지 않는다.

---

### 작업 1: 중앙 문서 변경 이력 형식

**파일:**
- 수정: `docs/00-index.md`
- 수정: `docs/service-policies/README.md`
- 수정: `docs/service-policies/00-policy-template.md`
- 수정: `miriyum-service-decisions.md`

**연결:**
- 입력: 기존 `miriyum-service-decisions.md`의 과거 결정 소유권
- 출력: 이후 모든 문서 변경에 사용할 하나의 날짜 카드 템플릿

- [ ] **1단계: 편집 전에 변경 이력 형식 검증 실행**

```powershell
$history = Get-Content miriyum-service-decisions.md -Raw -Encoding UTF8
@('요청·결정자: 이병우','수정 작업자: Codex','대상 문서·정책:','변경 이유:','추적 정보:') |
  ForEach-Object { if (-not $history.Contains($_)) { throw "missing: $_" } }
```

기대 결과: 새 카드 형식이 없으므로 처음 누락된 필드에서 실패한다.

- [ ] **2단계: 중앙 변경 이력 관리 연결 추가**

다음 규칙을 `docs/00-index.md`에 추가하고 정책 마스터와 템플릿에도 같은 역할 분리를 반영한다.

```markdown
- 정본 문서를 수정하면 `miriyum-service-decisions.md`의 중앙 변경 이력도 함께 갱신한다. 정책 문서의 결정 기록은 정책 의미·상태·근거를, 중앙 변경 이력은 요청·결정자·수정 작업자·대상·내용·이유를 소유한다.
```

- [ ] **3단계: 과거 이력을 삭제하지 않고 날짜 카드 템플릿 도입**

`## 7. 변경 기록` 시작 부분에 다음 내용을 추가한다.

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

기존 표와 뒤의 번호가 있는 변경 이력 절은 이 제목 아래에 그대로 보존한다.

- [ ] **4단계: 변경 이력 형식 검증 다시 실행**

1단계의 PowerShell 블록을 실행한다.

기대 결과: 종료 코드 0으로 통과한다.

- [ ] **5단계: 변경 이력 관리 규칙 커밋**

```powershell
git add docs/00-index.md docs/service-policies/README.md docs/service-policies/00-policy-template.md miriyum-service-decisions.md docs/superpowers/specs/2026-07-24-document-history-and-pickup-hold-design.md docs/superpowers/plans/2026-07-24-document-history-and-pickup-hold.md
git commit -m "docs: centralize document change history"
```

### 작업 2: 카페·베이커리 단독 픽업 자격

**파일:**
- 수정: `docs/03-domain-model.md`
- 수정: `docs/04-user-flows.md`
- 수정: `docs/05-functional-requirements.md`
- 수정: `docs/service-policies/02-store-onboarding.md`
- 수정: `docs/service-policies/06-menu-hold.md`
- 수정: `miriyum-service-decisions.md`

**연결:**
- 입력: `booking`, `HOLD-004`, `HOLD-005`, `HOLD-007`, `HOLD-010`
- 출력: 도메인·흐름·요구사항·상세 정책 문서에 일관된 하나의 자격 및 자원 할당 규칙

- [ ] **1단계: 현재 정책에 부정 검증 실행**

```powershell
$hold = Get-Content docs/service-policies/06-menu-hold.md -Raw -Encoding UTF8
$onboarding = Get-Content docs/service-policies/02-store-onboarding.md -Raw -Encoding UTF8
if (-not $hold.Contains('카페·베이커리')) { throw 'cafe/bakery eligibility missing' }
if ($hold.Contains('식당 대표자가 메뉴별로 `단독 픽업 홀드`를')) { throw 'all-store eligibility remains' }
if ($onboarding.Contains('베이커리는 방문 예약 개념을 두지 않으므로')) { throw 'bakery-only assumption remains' }
```

기대 결과: 카페·베이커리 자격이 누락되어 실패한다.

- [ ] **2단계: 상세 정책 정렬**

다음 정확한 정책 경계를 `HOLD-005`에 사용하고 `STORE-007`에 요약한다.

```markdown
- 카페·베이커리 업종으로 승인된 매장은 홀 운영 여부와 관계없이 메뉴별 단독 픽업 홀드를 활성화할 수 있다. 일반 식당과 다른 업종은 활성화할 수 없다.
- 홀을 운영하는 카페·베이커리도 테이크아웃 요청에는 단독 픽업 홀드를 사용할 수 있으며, 방문 예약 요청에는 `HOLD-004`의 예약 수용량·메뉴 수량 결합 선점을 적용한다.
- 이 예외는 카페·베이커리에 홀 없이 테이크아웃 중심으로 운영하는 매장이 많기 때문에 제공한다. 홀 없는 매장만 허용한다는 조건은 아니다.
- 단독 픽업 홀드는 `booking` 내부에서 메뉴 수량과 픽업 제공 구간만 선점하며 방문 예약 레코드·좌석·인원 수용량을 만들지 않는다.
```

“독립 흐름·도메인”이라는 표현을 “`booking` 내부의 방문 수용량 비의존 경로”로 교체한다.

- [ ] **3단계: 도메인·기능 요구사항·사용자 흐름 정렬**

다음 사용자 흐름을 추가한다.

```markdown
## 5. 카페·베이커리 단독 픽업 홀드

- **시작 조건:** 로그인 사용자가 단독 픽업 홀드를 활성화한 카페·베이커리의 메뉴와 픽업 구간을 선택한다.
- **주요 흐름:** `booking`은 업종 자격, 메뉴 수량, 영업·픽업 구간과 사용자 제한을 검증하고 메뉴 수량만 임시 선점한 뒤 픽업 홀드를 확정한다. 홀 운영 여부는 자격 조건이 아니며 방문 예약 수용량은 만들거나 선점하지 않는다.
- **성공 종료:** 사용자는 확정 수량·픽업 구간·수령 조건을 확인하고 매장은 같은 메뉴 수량 원장에서 준비 상태를 관리한다.
- **실패 종료:** 업종 부적격, 기능 미활성, 비영업, 무재고, 선점 만료 또는 상태 불일치에는 확정하지 않고 수량을 안전하게 반환한다.
- **관련 정책 ID:** HOLD-001, HOLD-003, HOLD-005, HOLD-007, HOLD-009, HOLD-010, HOLD-013.
```

뒤의 사용자 흐름 번호를 다시 매긴다. 도메인과 기능 요구사항 문서에는 같은 소유권·자격을 한 문장으로 요약해 추가한다.

- [ ] **4단계: 결정 기록과 중앙 변경 카드 추가**

직접 결정자를 `이병우`로 명시한 2026-07-24 `HOLD-005` 결정 행을 추가하고 다음 카드를 추가한다.

```markdown
#### DOC-20260724-002 · 카페·베이커리 단독 픽업 홀드 범위 정정

- 변경 일시: `2026-07-24 22:27 KST`
- 요청·결정자: 이병우
- 수정 작업자: Codex
- 대상 문서·정책: `docs/03-domain-model.md`, `docs/04-user-flows.md`, `docs/05-functional-requirements.md`, `STORE-007`, `HOLD-005`
- 변경 내용: 카페·베이커리는 홀 운영 여부와 관계없이 단독 픽업 홀드를 사용할 수 있고 일반 식당·다른 업종은 사용할 수 없도록 정렬했다. 기능 소유권은 `booking`으로 유지하고 방문 예약 수용량만 할당하지 않는다.
- 변경 이유: 카페·베이커리에 테이크아웃 중심 매장이 많아 일반 식당과 구분되는 픽업 흐름이 필요하며, 이를 홀 없는 매장만의 기능으로 제한하려는 결정은 아니기 때문이다.
- 추적 정보: PR `#23`, 커밋은 반영 커밋을 참조한다.
```

- [ ] **5단계: 픽업 정합성 검증 실행**

1단계 블록을 실행한 뒤 다음을 실행한다.

```powershell
$all = Get-Content docs/03-domain-model.md,docs/04-user-flows.md,docs/05-functional-requirements.md,docs/service-policies/02-store-onboarding.md,docs/service-policies/06-menu-hold.md -Raw -Encoding UTF8
@('카페','베이커리','홀 운영 여부','booking','일반 식당') |
  ForEach-Object { if (-not $all.Contains($_)) { throw "missing: $_" } }
```

기대 결과: 두 명령이 모두 통과한다.

- [ ] **6단계: 픽업 정책 정렬 커밋**

```powershell
git add docs/03-domain-model.md docs/04-user-flows.md docs/05-functional-requirements.md docs/service-policies/02-store-onboarding.md docs/service-policies/06-menu-hold.md miriyum-service-decisions.md
git commit -m "docs: align cafe bakery pickup holds"
```
