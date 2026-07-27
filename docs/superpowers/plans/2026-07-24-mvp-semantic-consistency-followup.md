# MVP 의미 정합성 후속 구현 계획

> **에이전트 작업자 필수 지침:** 이 계획은 `superpowers:subagent-driven-development` 사용을 권장하며, 같은 세션에서 직접 실행할 때는 `superpowers:executing-plans`를 사용해 작업별로 구현한다. 각 단계는 확인란(`- [ ]`)으로 추적한다.

**목표:** 앞서 확인한 MVP 모순을 정렬하고, 리뷰 정책은 미래 구현 기준으로 보존하면서 리뷰 기능 전체를 1차 MVP에서 제외한다.

**구조:** 제품 비전과 명시적 사용자 결정을 현재 제품 범위 경계로 사용한다. 상세 리뷰 정책은 미래 안전 계약으로 유지하되 현재 제품·도메인·흐름·요구사항·아키텍처·인증 문서에서 리뷰 활성화를 제거하고 모든 범위 복제본을 함께 검증한다.

**기술 구성:** Markdown, PowerShell 검증, Git

## 공통 제약

- 새 제품 기능이나 정책 결정을 추가하지 않는다.
- 광고 상품·과금·광고 제거는 계속 1차 MVP에서 제외한다.
- 예약은 즉시 확정하며 승인 대기 상태를 노출하지 않는다.
- 취소 자원 자동 승계는 `TRANSFER-001`~`TRANSFER-009`가 관리하는 초기 핵심 기능으로 유지한다.
- 현재 웨이팅에는 현장과 원격 웨이팅이 모두 포함된다.
- Free 기본 운영 통계는 현재 MVP이고 Pro 비교·해석·추천·자동 리포트·내보내기는 제외한다.
- 리뷰 생성·공개·수정·삭제, 별점·태그·미디어, 신고·검수·매장 답글, 어뷰징 탐지·신뢰 점수·제재·오탐 복구는 모두 1차 MVP에서 제외한다.
- `TRUST-001`~`TRUST-012`는 확정된 미래 정책 기준으로 유지한다. 확정 상태는 현재 리뷰 기능·권한·API·UI·저장소를 활성화하지 않는다.
- 설계 허용 목록의 파일만 수정한다.

---

### 작업 1: 실패하는 의미 검증 추가

**파일:**
- 검사: `docs/04-user-flows.md`
- 검사: `docs/service-policies/02-store-onboarding.md`

**연결:**
- 입력: `main`의 현재 Markdown 텍스트
- 출력: 네 가지 모순이 남아 있는 동안 0이 아닌 종료 코드를 반환하는 재현 가능한 PowerShell 검증 명령

- [x] **1단계: 현재 문서에 검증 실행**

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

기대 결과: 종료 코드 `1`이며 문서 편집 전에는 이름이 지정된 네 검사가 모두 실패한다.

### 작업 2: 탐색·예약·승계 사용자 흐름 정렬

**파일:**
- 수정: `docs/04-user-flows.md`

**연결:**
- 입력: `docs/05-functional-requirements.md`, `docs/service-policies/04-reservation.md`, `docs/service-policies/10-waitlist-transfer.md`, `docs/service-policies/13-ad-recommendation.md`의 MVP 경계
- 출력: 일반 추천, 예약 즉시 확정과 취소 자원 자동 승계의 현재 사용자 흐름

- [x] **1단계: 탐색 흐름 교체**

다음 주요 흐름 문장을 정확히 사용한다.

```markdown
- **주요 흐름:** 서비스는 공개 가능한 매장·영업·메뉴 정보를 바탕으로 검색 결과와 일반 추천 결과를 제시한다.
```

탐색 흐름의 관련 정책 ID에서 `ADS-002`와 `ADS-006`을 제거하되 `ADS-007`과 `ADS-008`은 유지한다.

- [x] **2단계: 예약 주요 흐름 교체**

다음 문장을 정확히 사용한다.

```markdown
- **주요 흐름:** 서비스는 영업시간·예약 가능 수량·중복 예약 여부, 임시 선점 및 필요한 메뉴 홀드·결제 조건을 검증하고, 유효한 요청은 즉시 확정한다. 확정할 수 없으면 사용자에게 실패 사유를 안내한다.
```

- [x] **3단계: 취소 자원 자동 승계 흐름 추가**

결제와 취소 뒤에 다음 새 절을 삽입한다.

```markdown
## 7. 취소 자리·수량 자동 승계

- **시작 조건:** 자동 승계가 활성이고, 취소로 예약 수용량이나 메뉴 수량이 반환됐으며 해당 자원에 유효한 대기 등록자가 있다.
- **주요 흐름:** 서비스는 등록 순번이 가장 빠른 유효 후보에게 5분 제안을 보낸다. 사용자가 기한 안에 수락하면 자원을 10분 동안 선점하고 원 거래와 같은 예약·홀드의 검증·결제·확정 흐름으로 전환한다.
- **성공 종료:** 수락한 사용자의 예약 또는 홀드 전환이 최종 확정되고 사용하고 남은 인원·수량은 일반 가용으로 반환된다.
- **실패 종료:** 명시적 거절, 제안 만료, 자격 재검증 실패 또는 검증된 결제 실패에는 정책과 날짜 경계에 따라 다음 유효 후보 제안 또는 일반 가용 처분으로 진행한다. 결제 결과가 불명확하면 자원을 공개하거나 다음 후보로 넘기지 않고 대사·복구 상태로 유지한다.
- **관련 정책 ID:** TRANSFER-001, TRANSFER-002, TRANSFER-003, TRANSFER-004, TRANSFER-005, TRANSFER-006, TRANSFER-007, TRANSFER-008, TRANSFER-009.
```

뒤의 리뷰·알림 절 번호를 `7`, `8`에서 `8`, `9`로 다시 매긴다.

- [x] **4단계: 사용자 흐름 검증 실행**

작업 1의 명령을 실행한다.

기대 결과: 일반 추천, 승인 대기 없음, 승계 흐름 검사는 통과하고 매장 활성화 검사는 계속 실패한다.

- [x] **5단계: 사용자 흐름 정렬 커밋**

```powershell
git add -- docs/04-user-flows.md
git commit -m "docs: align current MVP user flows"
```

### 작업 3: 매장 활성화 기능 경계 정렬

**파일:**
- 수정: `docs/service-policies/02-store-onboarding.md`

**연결:**
- 입력: `docs/service-policies/05-waiting.md`, `docs/service-policies/14-analytics-report.md`, `docs/05-functional-requirements.md`의 현재 MVP 경계
- 출력: 현장·원격 웨이팅과 Free 기본 운영 통계를 포함하고 Pro 분석을 제외하는 `STORE-007` 활성화 목록

- [x] **1단계: 현재 활성화 목록 확장**

`STORE-007` 활성화 목록에서 현장 전용 웨이팅 표현을 `현장·원격 웨이팅`으로 바꾸고 `Free 기본 운영 통계`를 추가한다.

- [x] **2단계: 제외 분석 범위 축소**

넓은 `수요 분석` 제외 표현을 정확한 미래 범위인 `Pro 비교·해석·추천·자동 리포트·내보내기`로 교체한다.

- [x] **3단계: 제한 문장 정렬**

현재 지원 기능을 제한하는 문장이 수정된 `STORE-007` 목록을 가리키게 한다. 연결된 홀드·보증금 경계는 유지하되 원격 웨이팅이나 Free 기본 통계를 제외하지 않게 한다.

- [x] **4단계: 모든 의미 검증 실행**

작업 1의 명령을 실행한다.

기대 결과: 종료 코드 `0`이며 이름이 지정된 네 검사가 모두 통과한다.

- [x] **5단계: 매장 활성화 정렬 커밋**

```powershell
git add -- docs/service-policies/02-store-onboarding.md
git commit -m "docs: align store activation MVP scope"
```

### 작업 4: 범위와 문서 위생 검증

**파일:**
- 검증: `docs/04-user-flows.md`
- 검증: `docs/service-policies/02-store-onboarding.md`
- 검증: `docs/superpowers/specs/2026-07-24-mvp-semantic-consistency-followup-design.md`
- 검증: `docs/superpowers/plans/2026-07-24-mvp-semantic-consistency-followup.md`

**연결:**
- 입력: 완료된 Markdown 변경
- 출력: 정본 MVP 경계나 관련 없는 파일을 바꾸지 않고 네 모순을 해소했다는 증거

- [x] **1단계: 정본 경계 텍스트가 바뀌지 않았는지 검증**

```powershell
git diff main -- docs/05-functional-requirements.md docs/service-policies/04-reservation.md docs/service-policies/05-waiting.md docs/service-policies/10-waitlist-transfer.md docs/service-policies/13-ad-recommendation.md docs/service-policies/14-analytics-report.md
```

기대 결과: 출력이 없다.

- [x] **2단계: 형식 검증**

```powershell
git diff --check main
```

기대 결과: 출력 없이 종료 코드 `0`이다.

- [x] **3단계: 허용 목록 검증**

```powershell
git diff --name-only main
```

기대 경로:

```text
docs/04-user-flows.md
docs/service-policies/02-store-onboarding.md
docs/superpowers/plans/2026-07-24-mvp-semantic-consistency-followup.md
docs/superpowers/specs/2026-07-24-mvp-semantic-consistency-followup-design.md
```

- [x] **4단계: 최종 변경 내역 검토**

```powershell
git diff main -- docs/04-user-flows.md docs/service-policies/02-store-onboarding.md
```

기대 결과: 승인된 네 가지 의미 보정만 있다.

### 작업 5: 1차 MVP의 리뷰 활성화 제거

**파일:**
- 수정: `docs/01-product-vision.md`
- 수정: `docs/02-users-and-permissions.md`
- 수정: `docs/03-domain-model.md`
- 수정: `docs/04-user-flows.md`
- 수정: `docs/05-functional-requirements.md`
- 수정: `docs/06-system-architecture.md`
- 수정: `docs/service-policies/01-member-auth.md`
- 수정: `docs/service-policies/12-review-trust.md`
- 수정: `docs/service-policies/README.md`
- 수정: `miriyum-service-blueprint.md`
- 수정: `miriyum-service-decisions.md`

**연결:**
- 입력: 리뷰 기능 그룹 전체를 1차 MVP에서 제외한다는 사용자 결정
- 출력: 리뷰 기능을 활성화하지 않고 리뷰 정책을 보존하는 제품·권한·도메인·흐름·요구사항·아키텍처·인증·정책 문서

- [x] **1단계: 실패하는 리뷰 범위 검증 실행**

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

기대 결과: 종료 코드 `1`이며 현재 문서가 여전히 1차 MVP에서 리뷰를 활성화한다.

- [x] **2단계: 제품·권한·도메인·흐름·요구사항·아키텍처 문서 정렬**

- 방문 후 리뷰 평가를 미래 제품 가치로 표시하고 리뷰 기능 전체를 초기 비목표에 추가한다.
- 현재 일반 사용자 권한에서 리뷰를 제거한다.
- 초기 도메인 모델에서 `review` 절과 리뷰 의존성을 제거하고 초기 도메인 수를 6개에서 5개로 바꾼다.
- 리뷰 사용자 흐름을 제거하고 알림을 8절로 다시 매기며 현재 시작 조건에서 리뷰 사건을 제거한다.
- `TRUST-001`~`TRUST-012` 기능 그룹을 `초기 핵심`에서 `비초기`로 바꾸고 전체 기능 제외 경계를 명시한다.
- 초기 백엔드 패키지 구조에서 `review/`를 제거한다.

- [x] **3단계: 인증과 리뷰 정책 경계 정렬**

- 만 14세 이상 사용자의 현재 비주류 서비스를 나열하는 `AUTH-009` 문장에서 리뷰를 제거한다.
- 모든 상세 정책 결정을 유지하되 현재 리뷰 기능·권한·API·UI·저장소를 활성화하지 않는 1차 MVP 경계를 `12-review-trust.md`에 추가한다.
- 정책 마스터의 리뷰 절 아래에도 같은 경계 문구를 추가한다.
- 사용자의 현재 리뷰 제외 결정을 `miriyum-service-decisions.md`에 기록한다.
- `miriyum-service-blueprint.md`를 현재 MVP를 활성화하거나 확장할 수 없는 역사적 입력으로 표시한다.

- [x] **4단계: 리뷰 범위 검증 실행**

1단계의 명령을 실행한다.

기대 결과: 종료 코드 `0`이며 범위 검증 10개가 모두 통과한다.

### 작업 6: 확장된 MVP 정합성 범위 재검증

**파일:**
- 확장된 설계 허용 목록의 모든 경로를 검증한다.

**연결:**
- 입력: 완료된 다섯 가지 의미 정합성 보정
- 출력: 의미 범위·정책 ID 범위·링크·인코딩·형식·정확한 변경 경로 허용 목록의 증거

- [x] **1단계: 기존 의미 검사와 작업 5의 리뷰 검증 모두 실행**

기대 결과: 모든 검사가 통과한다.

- [x] **2단계: 정책 ID·상대 링크·인코딩·변경 형식 검증**

```powershell
git diff --check main
git diff --name-only main
```

기대 결과: 종료 코드 `0`, 형식 지적 없음, 확장된 허용 목록 경로만 표시된다.

- [x] **3단계: 독립 검토 요청**

검토자는 `main..HEAD`를 비교하고 리뷰 정책 확정과 현재 MVP 활성화가 혼동되지 않았는지 확인한 뒤 치명적·중요·경미 지적을 보고해야 한다.
