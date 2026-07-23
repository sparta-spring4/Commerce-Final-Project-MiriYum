# MiriYum 관리자-도메인 정책 정합성 보완 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 관리자 권한·승인 절차와 결제, 매장 상태, 개인정보, 입점·테이스팅 심사 정본 사이의 충돌을 없애고, 이미 확정된 정책 상태와 TODO 경계는 그대로 보존한다.

**Architecture:** `15-admin-operation.md`는 관리자 역할, 사건 큐, 제안·승인·실행 절차를 소유한다. 각 도메인 문서는 실제 업무 객체의 상태 전이와 금전·개인정보 효과를 소유한다. 관리자 문서가 도메인 규칙을 우회하지 못하도록 교차 참조와 적용 우선순위를 명시하고, 같은 개념의 이름이 다른 경우에는 도메인 상태를 바꾸지 않는 관리자 큐 투영표를 둔다.

**Tech Stack:** Markdown, PowerShell, ripgrep(`rg`), Git 읽기 전용 검증

## 1. 확정 결론

이번 보완에서 실제로 해결해야 할 문제는 다음 네 가지다.

1. `PAY-015`의 금전 영향 수동 복구 이중 승인과 `ADMIN-006`의 20만 원 이하 일반 환불·보상 1인 승인 규칙이 적용 범위 없이 연결되어 있다.
2. `OPER-009`의 운영자 폐점 권한과 `ADMIN-007`의 영구 퇴점 이중 승인 규칙 사이에 우회 가능한 표현이 남아 있다.
3. `15-admin-operation.md`의 대량 내보내기 절대 금지 문구가 `PRIV-011`의 승인된 대량 내보내기·break-glass 절차와 충돌한다.
4. `ADMIN-003`의 공통 심사 큐 상태와 입점·테이스팅 도메인 상태 사이의 대응 관계가 문서화되어 있지 않다.

다음 두 사항은 충돌 해소와 함께 정리하되 정책 상태를 바꾸지 않는다.

- `ADMIN-009`의 TODO는 아직 정해지지 않은 감사 항목별 보존기간과 최종 법률 문구에만 적용한다. `AUTH-006` 등 도메인에서 이미 확정한 보존기간을 다시 미정으로 만들지 않는다.
- `00-policy-template.md`에 고정된 과거 정책 집계는 제거하고, 동적 집계의 유일한 정본을 `docs/service-policies/README.md`로 둔다.
- 현재 집계에는 확정 정책으로 포함되지만 비표준 상태값 `개정 확정`을 사용하는 `RES-003`~`RES-005`의 현재 상태 라벨을 정식 상태값 `확정`으로 정규화한다. 예약 정책 내용과 과거 결정 기록의 `개정 확정` 표현은 바꾸지 않는다.

## 2. 변경 범위

### 반드시 수정

- `docs/service-policies/15-admin-operation.md`
- `docs/service-policies/08-payment-refund.md`
- `docs/service-policies/03-store-operation.md`
- `docs/service-policies/17-privacy-security.md`
- `docs/service-policies/00-policy-template.md`
- `docs/service-policies/04-reservation.md`의 `RES-003`~`RES-005` 현재 상태 라벨
- `docs/service-policies/README.md`의 `RES-003`~`RES-005` 상태 열

### 최소 교차 참조만 추가

- `docs/service-policies/02-store-onboarding.md`
- `docs/service-policies/07-course-tasting.md`

### 수정하지 않음

- `docs/service-policies/01-member-auth.md`
  - `AUTH-006`의 확정 보존기간은 그대로 둔다.
  - `ADMIN-009` TODO의 범위를 `15-admin-operation.md`에서 제한하면 충분하다.
- `docs/service-policies/16-notification.md`
  - `NOTI-010`의 알림 처리 계약을 변경하지 않는다.
- `docs/service-policies/18-scale-reliability.md`
  - `SCALE-013`의 RPO·RTO를 변경하지 않는다.
- 구현 코드, API, DB 스키마, 인프라

## 3. 전역 제약

- 기존 정책 ID 217개와 상태 집계 `확정 191 / 팀원 상의 필요 11 / 자동 추천 예정 0 / TODO 15`를 유지한다.
- 마스터 표와 정책 본문의 현재 상태에는 `확정`, `팀원 상의 필요`, `자동 추천 예정`, `TODO` 네 값만 사용한다. 결정 기록의 `대체됨`, `폐기`, `개정 확정` 같은 이력 표현은 이 제약의 대상이 아니다.
- `ADMIN-009`, 개인정보·법률 최종 검토, 운영 계약 등 기존 TODO를 해소한 것처럼 표현하지 않는다.
- 일반 환불, 별도 보상, 금전 영향 수동 복구를 같은 작업으로 합치지 않는다.
- 정상 폐점, 긴급·기간 운영 중지, 영구 제재 퇴점을 같은 상태 전이로 합치지 않는다.
- 입점·테이스팅의 도메인 상태명을 `ADMIN-003`의 관리자 큐 상태명으로 교체하지 않는다.
- 자유 SQL, 무승인 대량 내보내기, 요청자 자신의 승인, 원장 직접 편집을 허용하지 않는다.
- 사용자 원본 개인정보와 결제수단 원문을 관리자 화면, 감사 로그, 지표에 추가하지 않는다.
- 현재 작업 트리에 이미 존재하는 `docs/service-policies/01-member-auth.md`와 `docs/service-policies/17-privacy-security.md`의 사용자 변경을 덮어쓰지 않는다.
- 기존 미추적 파일 `docs/superpowers/plans/2026-07-22-miriyum-repository-architecture-restructure.md`를 수정·스테이징·커밋하지 않는다.
- 별도 요청 전에는 계획 실행 결과를 커밋하거나 푸시하지 않는다.

---

### Task 1: 실행 기준선과 문서 소유권을 고정한다

**Files:**

- Modify: `docs/service-policies/15-admin-operation.md`
- Preserve: `docs/service-policies/01-member-auth.md`
- Preserve: `docs/service-policies/17-privacy-security.md`의 기존 사용자 변경
- Preserve: `docs/superpowers/plans/2026-07-22-miriyum-repository-architecture-restructure.md`

**정본 규칙:**

- 관리자 문서: 행위자, 역할, 업무 권한, 사건 배정, 제안·승인·실행 분리, 관리자 감사 절차
- 도메인 문서: 업무 객체, 상태 전이, 금전 효과, 개인정보 처리, 실패 시 복구 불변식
- 두 문서가 같은 행위를 규정하면 도메인의 업무 효과와 더 엄격한 승인 조건을 함께 충족해야 한다.

- [ ] **Step 1: 기존 변경을 기록하고 보호한다**

  Run:

  ```powershell
  git status --short --branch
  git diff -- docs/service-policies/01-member-auth.md
  git diff -- docs/service-policies/17-privacy-security.md
  git diff -- docs/superpowers/plans/2026-07-22-miriyum-repository-architecture-restructure.md
  ```

  Expected:

  - 현재 브랜치와 기존 변경이 확인된다.
  - `01-member-auth.md`와 `17-privacy-security.md`의 기존 변경 내용이 식별된다.
  - 미추적 계획 파일은 diff 대상이나 이후 스테이징 대상에 포함되지 않는다.
  - `17-privacy-security.md`를 수정할 때는 기존 사용자 변경을 기준선으로 보존한다.

- [ ] **Step 2: `운영자 공통 불변식`에 정책 소유권과 적용 우선순위를 추가한다**

  Add the following rules in substance:

  - `15-admin-operation.md`는 관리자 권한과 승인 절차의 정본이다.
  - 업무 객체의 상태와 효과는 해당 도메인 정책이 정본이다.
  - 관리자 작업은 도메인이 허용한 명령만 호출하며 원장을 직접 편집하지 않는다.
  - 승인 조건이 겹치면 더 엄격한 조건을 적용한다.
  - 교차 참조가 누락되거나 상태가 불명확하면 실행하지 않고 사건을 보류한다.

  Expected: 관리자 권한 보유만으로 도메인 전이나 금전·개인정보 규칙을 우회할 수 없다는 문장이 명시된다.

- [ ] **Step 3: 결정 기록을 추가한다**

  Add one `ADMIN-003·ADMIN-005·ADMIN-006·ADMIN-007·ADMIN-009·ADMIN-010·ADMIN-011` cross-domain alignment row dated `2026-07-23`.

  Expected: 상태는 기존과 같고, 변경 사유는 “정본 소유권과 적용 우선순위 명시”로 기록된다.

### Task 2: 일반 환불·보상과 금전 영향 수동 복구의 승인 경계를 분리한다

**Files:**

- Modify: `docs/service-policies/08-payment-refund.md` under `PAY-015 운영자 수동 복구와 승인`
- Modify: `docs/service-policies/15-admin-operation.md` under `ADMIN-005` and `ADMIN-006`

**최종 승인 매트릭스:**

| 작업 유형 | 예시 | 승인 조건 | 정본 |
|---|---|---|---|
| 정책에 따른 일반 환불 | 정상 취소·정책 환불 | 원 결제금액 이하이고 건당 20만 원 이하면 결제 관리자 1명, 20만 원 초과면 요청자와 다른 슈퍼관리자 추가 승인 | `ADMIN-006`, 업무 결과는 `08-payment-refund.md` |
| 별도 보상 | 원 환불 외 추가 보상 | 원 결제금액을 1원이라도 초과하면 금액과 관계없이 요청자와 다른 슈퍼관리자 승인 | `ADMIN-006` |
| 금전 영향 수동 복구 | 원장 불일치, 부분 실패, 수동 보정 | 금액과 관계없이 제안자와 다른 `복구 승인` 권한자 승인 | `PAY-015`, `ADMIN-005` |
| 금전 영향 없는 재조회·재연결 | 최신 외부 상태 재조회, 멱등 재연결 | 해당 복구 권한자가 실행하되 허용 명령·사건 버전·멱등 조건 검증 | `PAY-015`, `ADMIN-005` |

- [ ] **Step 1: `PAY-015`에 작업 분류와 승인 우선순위를 명시한다**

  Add:

  - 수동 복구는 `PAY-014` 대사로 해결되지 않은 불일치·부분 실패·수동 보정 사건에만 적용한다.
  - 금전 효과가 생기는 수동 복구에는 `ADMIN-006`의 20만 원 이하 1인 승인 예외를 적용하지 않는다.
  - 제안자와 승인자는 서로 다른 계정이어야 한다.
  - 원 결제금액 초과 별도 보상은 `ADMIN-006`의 슈퍼관리자 승인까지 함께 충족한다.
  - 같은 사건의 환불·보상·보정 명령은 멱등하게 한 번만 실행한다.

  Expected: `PAY-015`만 읽어도 10만 원 수동 보정은 2인 승인이며, 10만 원 정상 정책 환불은 조건 충족 시 1인 승인임을 구분할 수 있다.

- [ ] **Step 2: `ADMIN-005`에서 잘못된 금액 한도 상속 표현을 제거한다**

  Replace the ambiguous statement that financial recovery “applies the `ADMIN-006` amount threshold” with:

  - 금전 효과 수동 복구는 금액과 관계없이 이중 승인한다.
  - `ADMIN-006`은 일반 환불·별도 보상의 금액 경계를 제공한다.
  - 수동 복구가 원 결제금액 초과 별도 보상까지 포함하면 두 정책의 승인 조건을 모두 충족한다.

  Expected: 20만 원 이하라는 이유로 금전 영향 수동 복구를 한 명이 제안·승인할 수 없다.

- [ ] **Step 3: `ADMIN-006`의 적용 범위를 일반 환불과 별도 보상으로 한정한다**

  Add an opening scope sentence and an explicit `PAY-015` exception:

  - 이 한도는 정상 정책 흐름의 환불과 별도 보상 승인에 적용한다.
  - 원장 불일치·부분 실패·수동 보정은 `PAY-015`와 `ADMIN-005`의 이중 승인 규칙을 우선한다.

- [ ] **Step 4: 양쪽 문서에 같은 인수 예시를 추가한다**

  Required cases:

  1. 200,000원 정상 정책 환불 → 결제 관리자 1명 승인 가능
  2. 200,001원 정상 정책 환불 → 요청자와 다른 슈퍼관리자 추가 승인
  3. 100,000원 금전 영향 수동 복구 → 금액과 관계없이 제안자와 다른 복구 승인자 필요
  4. 원 결제금액보다 1원 많은 별도 보상 → 요청자와 다른 슈퍼관리자 승인 필요
  5. 중복·재시도 → 외부 환불·보상·보정 명령은 한 번만 실행

- [ ] **Step 5: 결정 기록을 동기화한다**

  Add `2026-07-23` rows to both files. Do not change `PAY-015`, `ADMIN-005`, or `ADMIN-006` status.

- [ ] **Step 6: 결제 경계를 검증한다**

  Run:

  ```powershell
  rg -n "PAY-015|ADMIN-005|ADMIN-006|200,000|200,001|금액과 관계없이|제안자와 다른|원 결제금액" docs/service-policies/08-payment-refund.md docs/service-policies/15-admin-operation.md
  ```

  Expected: 일반 환불의 금액 기준과 금전 영향 수동 복구의 무조건 이중 승인이 두 문서에서 같은 의미로 나타난다.

### Task 3: 정상 폐점·임시 제재·영구 퇴점의 전이를 분리한다

**Files:**

- Modify: `docs/service-policies/03-store-operation.md` under `OPER-009`
- Modify: `docs/service-policies/15-admin-operation.md` under `ADMIN-007`

**최종 상태 경계:**

| 상황 | 승인·행위자 | 허용 전이 |
|---|---|---|
| 대표자의 정상 휴점·폐점 | 유효한 대표자 요청과 사전 조건 검증 | `OPER-009`에 따른 휴점·폐점 |
| 명백한 폐업·소유권 변경 | `STORE-014`의 검증된 사건 | `OPER-009`가 상태 전이 실행 |
| 긴급한 정책 위반 차단 | 매장 제재 권한 관리자 1명 | 범위가 제한된 기능 제한 또는 기간 운영 중지 |
| 영구 제재 | 매장 제재 관리자 제안 + 제안자와 다른 슈퍼관리자 승인 | 승인 후에만 영구 퇴점 및 `OPER-009` 폐점 전이 |

- [ ] **Step 1: `OPER-009`에서 운영자의 직접 영구 제재 우회 가능성을 제거한다**

  Replace the broad rule allowing an authorized operator to apply closure for a policy violation with:

  - 운영자는 정책 위반을 이유로 기능 제한·기간 운영 중지를 적용할 수 있다.
  - 영구 퇴점은 `ADMIN-007`의 제안·추가 승인이 확정된 사건만 실행한다.
  - 폐점 상태 명령에는 정상 폐점, `STORE-014`, 또는 승인된 `ADMIN-007` 사건 ID와 정책 버전이 필요하다.
  - 승인 전 영구 폐점 요청은 거부하며 긴급하면 기간 운영 중지를 사용한다.

- [ ] **Step 2: `ADMIN-007`에 도메인 실행 경계를 추가한다**

  Add:

  - 영구 퇴점 승인은 제재 결정을 확정할 뿐, 매장 원장을 직접 편집하지 않는다.
  - 승인 후 `OPER-009`의 조건부 전이로 폐점을 실행한다.
  - 기존 예약·결제·환불은 각 도메인 정책으로 별도 처리한다.
  - 긴급 기간 운영 중지는 영구 퇴점으로 자동 승격되지 않는다.

- [ ] **Step 3: 인수 조건을 추가한다**

  Required cases:

  1. 대표자 정상 폐점은 `ADMIN-007` 제재 승인을 요구하지 않는다.
  2. 정책 위반 영구 퇴점은 슈퍼관리자 승인 전 실행되지 않는다.
  3. 긴급 기간 운영 중지는 관리자 1명이 적용할 수 있으나 영구 퇴점으로 자동 변경되지 않는다.
  4. 승인된 영구 퇴점도 기존 거래를 자동 취소·환불하지 않는다.
  5. 같은 제재 사건의 중복 실행은 매장 상태를 한 번만 전이한다.

- [ ] **Step 4: 결정 기록을 동기화하고 검증한다**

  Run:

  ```powershell
  rg -n "OPER-009|ADMIN-007|정상 폐점|기간 운영 중지|영구 퇴점|제안자와 다른|기존 예약|기존 거래" docs/service-policies/03-store-operation.md docs/service-policies/15-admin-operation.md
  ```

  Expected: `OPER-009`만으로 정책 위반 영구 퇴점을 실행할 수 있다는 문장이 남지 않는다.

### Task 4: 개인정보 내보내기·break-glass·감사 TODO 경계를 일치시킨다

**Files:**

- Modify: `docs/service-policies/15-admin-operation.md` under `운영자 공통 불변식`, `ADMIN-009`, `ADMIN-010`, `ADMIN-011`
- Modify: `docs/service-policies/17-privacy-security.md` under `PRIV-011`
- Preserve: `docs/service-policies/17-privacy-security.md`의 기존 사용자 변경

**최종 허용 경계:**

- 무승인·자유 형식 대량 내보내기와 자유 SQL은 금지한다.
- 업무상 필요한 개인정보 대량 내보내기는 `PRIV-011`의 목적·필드 최소화와 `ADMIN-010`의 불변 계획·서로 다른 승인자·만료·감사를 모두 충족할 때만 허용한다.
- 개인정보 조회가 포함된 break-glass는 사건, 목적, 범위, 만료, 현재 재인증과 요청자와 다른 승인자를 요구한다.
- 승인 서비스 장애, 권한 버전 불일치, 계획 변조, 결과 불명확 상태에서는 실행하지 않는다.
- `ADMIN-009` TODO는 미확정 보존기간과 법률 문구만 남기며, 이미 확정된 도메인 보존기간을 덮어쓰지 않는다.

- [ ] **Step 1: 절대 금지 문구를 승인 없는 작업 금지로 좁힌다**

  In `운영자 공통 불변식`, replace “대량 내보내기를 허용하지 않는다” in substance with:

  - 무승인·자유 형식 대량 내보내기를 허용하지 않는다.
  - 승인된 대량 내보내기는 `PRIV-011`과 `ADMIN-010`을 모두 따라야 한다.
  - 비밀 조회, 직접 DB 수정, 공용 계정 사용은 계속 금지한다.

- [ ] **Step 2: `ADMIN-010`에 개인정보 대량 작업 조건을 명시한다**

  Add:

  - 대상 질의와 출력 필드는 승인 후 변경할 수 없는 계획에 고정한다.
  - 개인정보 필드는 목적에 필요한 최소 범위로 제한한다.
  - 요청자와 승인자는 다른 계정이어야 한다.
  - 결과 파일은 만료, 접근 통제, 다운로드 감사를 가진다.
  - 감사 로그에는 대상 개인정보 원문을 복제하지 않는다.

- [ ] **Step 3: `ADMIN-011`의 개인정보 break-glass에 다른 승인자를 요구한다**

  Add:

  - 단순 장애 완화 권한과 개인정보 조회 권한을 구분한다.
  - 개인정보 원문 조회·내보내기가 포함되면 요청자와 다른 승인자가 필요하다.
  - 권한은 사건 종료 또는 명시된 만료 시점 중 빠른 때 자동 회수한다.
  - 사후 검토는 사전 승인을 대체하지 않는다.

- [ ] **Step 4: `ADMIN-009` TODO의 범위를 제한한다**

  Add an explicit boundary:

  - TODO는 아직 보존기간이 정해지지 않은 감사 유형과 최종 법률 문구에 한정한다.
  - `AUTH-006` 등 도메인 문서에서 이미 확정한 보존기간을 재개방하거나 덮어쓰지 않는다.
  - 변경 불가능한 감사, 최소 권한 조회, 조회 자체 감사는 확정 안전 기본선이다.

- [ ] **Step 5: `PRIV-011`을 같은 조건으로 맞춘다**

  Preserve the privacy domain as owner and add cross-references:

  - 대량 조회·내보내기와 개인정보 break-glass는 `ADMIN-010` 또는 `ADMIN-011` 사건을 가져야 한다.
  - 다른 승인자, 불변 계획, 만료, 접근 감사가 없으면 실행하지 않는다.
  - 승인·내보내기·break-glass 감사의 저장 구조는 확정하되 항목별 보존기간과 최종 법률 문구는 `ADMIN-009` TODO를 따른다.
  - 이미 확정된 도메인 보존기간은 유지한다.

- [ ] **Step 6: 양쪽 결정 기록을 동기화한다**

  Add `2026-07-23` rows without changing `ADMIN-009` from TODO or `PRIV-011` from confirmed.

- [ ] **Step 7: 개인정보 경계를 검증한다**

  Run:

  ```powershell
  rg -n "무승인|대량 내보내기|ADMIN-010|break-glass|제안자와 다른|요청자와 다른|보존기간|AUTH-006|TODO" docs/service-policies/15-admin-operation.md docs/service-policies/17-privacy-security.md
  ```

  Expected:

  - 모든 대량 내보내기를 절대 금지하는 문구는 없다.
  - 승인 없는 대량 내보내기는 금지되어 있다.
  - 개인정보 break-glass의 다른 승인자와 만료 조건이 양쪽 문서에서 일치한다.
  - `ADMIN-009`는 계속 TODO다.

### Task 5: 관리자 심사 큐와 도메인 심사 상태의 투영 관계를 명시한다

**Files:**

- Modify: `docs/service-policies/15-admin-operation.md` under `ADMIN-003`
- Modify minimally: `docs/service-policies/02-store-onboarding.md` under `STORE-005` and the common review-state explanation
- Modify minimally: `docs/service-policies/07-course-tasting.md` under `TASTE-001`

**상태 소유권:**

- 입점·테이스팅 도메인의 상태가 사용자·매장 화면과 업무 규칙의 정본이다.
- `ADMIN-003` 상태는 여러 도메인의 사건을 한 관리자 큐에서 처리하기 위한 투영 상태다.
- 투영은 원본 도메인 상태를 변경하거나 새 업무 권한을 만들지 않는다.

**고정 매핑:**

| 도메인 | 도메인 상태·사건 | `ADMIN-003` 관리자 큐 상태 |
|---|---|---|
| 입점 | 신청 접수 | `접수` |
| 입점 | 자동 검사 진행 | `자동 검사 중` |
| 입점 | `확인 보류` | `자동 검사 중`에 머물며 보류 사유 표시 |
| 입점 | 관리자 심사 가능 | `배정 가능` |
| 입점 | `보완 요청` | `보완 요청` |
| 입점 | 보완 자료 제출 | `재접수` |
| 입점 | 승인·거절·취소 | 같은 이름의 종결 상태 |
| 테이스팅 | `심사 대기` | 자동 검사 전이면 `접수`, 검사 완료면 `배정 가능` |
| 테이스팅 | 심사자 배정 | `배정` |
| 테이스팅 | `수정 요청` | `보완 요청` |
| 테이스팅 | 수정본 재제출 | `재접수` |
| 테이스팅 | 승인·거절 | 같은 이름의 종결 상태 |
| 테이스팅 기능 | `일시 중지` | `ADMIN-003` 심사 상태가 아니라 기능 운영 상태 |

- [ ] **Step 1: `ADMIN-003`에 투영표와 불변식을 추가한다**

  Add the mapping table above and state:

  - 큐 상태는 도메인 상태에서 결정적으로 계산한다.
  - `확인 보류`는 자동 검사 실패를 승인 가능 상태로 올리지 않는다.
  - `심사 대기`는 자동 검사 완료 여부에 따라 `접수` 또는 `배정 가능`으로 나뉜다.
  - `일시 중지`는 심사 사건 상태로 취급하지 않는다.
  - 이전 버전의 큐 상태로 승인할 수 없다.

- [ ] **Step 2: `02-store-onboarding.md`에는 역참조 한 문단만 추가한다**

  Add:

  - `확인 보류`, `보완 요청`, 재접수, 승인·거절 등 입점 도메인 상태가 정본이다.
  - 관리자 큐 표시는 `ADMIN-003` 투영표를 따른다.
  - 투영은 입점 상태 전이나 승인 요건을 바꾸지 않는다.

  Do not duplicate the full mapping table or rename existing states.

- [ ] **Step 3: `07-course-tasting.md`에는 역참조 한 문단만 추가한다**

  Add:

  - `심사 대기`, `수정 요청`, 승인·거절은 테이스팅 심사 상태다.
  - `일시 중지`는 테이스팅 기능 운영 상태다.
  - 관리자 큐 표시는 `ADMIN-003` 투영표를 따르며 도메인 상태를 대체하지 않는다.

  Do not expand course/tasting product policy or MVP scope.

- [ ] **Step 4: 세 문서의 결정 기록을 동기화한다**

  Add `2026-07-23` rows that describe only the state projection and ownership clarification. Keep all policy statuses unchanged.

- [ ] **Step 5: 상태 매핑을 검증한다**

  Run:

  ```powershell
  rg -n "ADMIN-003|확인 보류|보완 요청|재접수|심사 대기|수정 요청|일시 중지|투영|정본" docs/service-policies/15-admin-operation.md docs/service-policies/02-store-onboarding.md docs/service-policies/07-course-tasting.md
  ```

  Expected:

  - `수정 요청`과 `보완 요청`의 관계가 명시된다.
  - `일시 중지`는 심사 큐 상태로 오해되지 않는다.
  - 도메인 상태명은 기존 이름을 유지한다.

### Task 6: 정책 템플릿의 고정 집계를 제거한다

**Files:**

- Modify: `docs/service-policies/00-policy-template.md`
- Verify only: `docs/service-policies/README.md`

- [ ] **Step 1: 과거 집계 문장을 정본 참조 문장으로 교체한다**

  Replace the sentence containing the hard-coded `확정 184 / 팀원 상의 필요 18 / 자동 추천 예정 0 / TODO 15` counts with:

  > 2026-07-22의 위임 추천 확정은 당시 자동 추천 후보 139개에만 적용되었다. 현재 정책 ID별 상태·중요도와 집계의 유일한 정본은 `docs/service-policies/README.md`이며, 이 템플릿에는 특정 시점의 개수를 고정하지 않는다. 미래 비핵심 후보가 생기면 `자동 추천 예정` 상태를 다시 사용할 수 있다.

  Expected: 템플릿의 절차 설명은 유지되고 현재 집계 숫자는 README만 소유한다.

- [ ] **Step 2: 템플릿과 README를 검증한다**

  Run:

  ```powershell
  rg -n "확정.*184|팀원 상의 필요.*18" docs/service-policies/00-policy-template.md
  rg -n "상태:.*확정.*191.*팀원 상의 필요.*11.*자동 추천 예정.*0.*TODO.*15.*217" docs/service-policies/README.md
  ```

  Expected:

  - 첫 명령은 결과가 없다.
  - 두 번째 명령은 README 집계 한 줄을 찾는다.
  - Task 6에서는 README를 수정하지 않는다.

### Task 7: 비표준 현재 상태값 `개정 확정`을 정규화한다

**Files:**

- Modify: `docs/service-policies/04-reservation.md` at the current status lines for `RES-003`, `RES-004`, `RES-005`
- Modify: `docs/service-policies/README.md` at the master rows for `RES-003`, `RES-004`, `RES-005`
- Preserve: `docs/service-policies/04-reservation.md`의 결정 기록

- [ ] **Step 1: 정책 본문의 현재 상태 라벨 세 개를 바꾼다**

  In `04-reservation.md`, change only:

  ```text
  RES-003: 정책 상태: 개정 확정 → 정책 상태: 확정
  RES-004: 정책 상태: 개정 확정 → 정책 상태: 확정
  RES-005: 정책 상태: 개정 확정 → 정책 상태: 확정
  ```

  Do not change:

  - the policy contents;
  - the superseded historical rows;
  - the `2026-07-22` decision record whose decision status is `개정 확정`.

- [ ] **Step 2: README 마스터 표의 상태 열 세 개를 바꾼다**

  Change only the status column of `RES-003`, `RES-004`, and `RES-005` from `개정 확정` to `확정`.

  Expected: summary count remains 191 confirmed because the three policies were already included in that total; only the machine-readable status vocabulary becomes consistent.

- [ ] **Step 3: 상태 라벨과 변경 범위를 검증한다**

  Run:

  ```powershell
  rg -n "^\| RES-00[3-5] \|" docs/service-policies/README.md
  rg -n -A 2 "^## RES-00[3-5] " docs/service-policies/04-reservation.md
  rg -n "개정 확정" docs/service-policies/04-reservation.md docs/service-policies/README.md
  git diff -U0 -- docs/service-policies/04-reservation.md docs/service-policies/README.md
  ```

  Expected:

  - the three master rows and three policy headings use current status `확정`;
  - `개정 확정` remains only in the historical decision record;
  - the diff changes no reservation rule, count, importance, or decision history.

### Task 8: 전체 정책·상태·범위 정합성을 검증한다

**Files:**

- Verify: all files under `docs/service-policies/`
- Verify: working tree and changed-file allowlist

- [ ] **Step 1: 정책 ID 217개와 상태 집계를 기계적으로 확인한다**

  Run:

  ```powershell
  $rows = Get-Content -Encoding utf8 docs/service-policies/README.md |
    Where-Object { $_ -match '^\|\s*([A-Z]+-\d{3})\s*\|' }

  $parsed = $rows | ForEach-Object {
    $columns = $_.Split('|') | ForEach-Object { $_.Trim() }
    [pscustomobject]@{
      Id = $columns[1]
      Status = $columns[3]
    }
  }

  $parsed.Count
  $parsed.Id | Group-Object | Where-Object Count -ne 1
  $allowedStatuses = @('확정', '팀원 상의 필요', '자동 추천 예정', 'TODO')
  $parsed | Where-Object Status -notin $allowedStatuses
  foreach ($status in $allowedStatuses) {
    [pscustomobject]@{
      Status = $status
      Count = @($parsed | Where-Object Status -eq $status).Count
    }
  }
  ```

  Expected:

  - policy row count: `217`
  - duplicate policy ID output: none
  - unexpected current status output: none
  - `확정 191`, `팀원 상의 필요 11`, `자동 추천 예정 0`, `TODO 15`

- [ ] **Step 2: 대상 정책의 상태가 바뀌지 않았는지 확인한다**

  Run:

  ```powershell
  rg -n "^\| (PAY-015|OPER-009|PRIV-011|ADMIN-003|ADMIN-005|ADMIN-006|ADMIN-007|ADMIN-009|ADMIN-010|ADMIN-011) \|" docs/service-policies/README.md
  rg -n "^> 정책 상태:" docs/service-policies/03-store-operation.md docs/service-policies/08-payment-refund.md docs/service-policies/15-admin-operation.md docs/service-policies/17-privacy-security.md
  ```

  Expected:

  - `ADMIN-009`만 기존과 같이 TODO다.
  - 나머지 대상 정책은 기존과 같이 확정이다.
  - 새 정책 ID가 생기지 않는다.

- [ ] **Step 3: TODO 경계를 확인한다**

  Run:

  ```powershell
  rg -n "ADMIN-009|항목별 보존기간|최종 법률 문구|TODO" docs/service-policies/15-admin-operation.md docs/service-policies/17-privacy-security.md docs/service-policies/README.md
  ```

  Expected:

  - `ADMIN-009` TODO는 보존기간·법률 문구에 남아 있다.
  - 감사 불변성·최소 권한·조회 감사 같은 확정 안전 기본선과 TODO 조각이 구분된다.
  - 기존 개인정보 법률 검토 TODO가 해소된 표현이 없다.

- [ ] **Step 4: 비수정 문서를 확인한다**

  Run:

  ```powershell
  git diff -- docs/service-policies/01-member-auth.md
  git diff -- docs/service-policies/16-notification.md
  git diff -- docs/service-policies/18-scale-reliability.md
  ```

  Expected:

  - `01-member-auth.md`에는 작업 시작 전부터 있던 사용자 변경 외에 새 변경이 없다.
  - `16-notification.md`, `18-scale-reliability.md`에는 이번 작업의 새 diff가 없다.
  - `README.md`의 변경은 Task 7의 세 상태 셀에만 한정된다.

- [ ] **Step 5: 변경 파일 허용 목록을 확인한다**

  Run:

  ```powershell
  git status --short
  git diff --name-only
  git diff --cached --name-only
  ```

  Allowed implementation files:

  ```text
  docs/service-policies/00-policy-template.md
  docs/service-policies/02-store-onboarding.md
  docs/service-policies/03-store-operation.md
  docs/service-policies/04-reservation.md
  docs/service-policies/07-course-tasting.md
  docs/service-policies/08-payment-refund.md
  docs/service-policies/15-admin-operation.md
  docs/service-policies/17-privacy-security.md
  docs/service-policies/README.md
  ```

  Expected:

  - 허용 목록 밖의 새 변경이 없다.
  - 기존 사용자 변경과 이 계획 파일은 별도로 식별된다.
  - `docs/superpowers/plans/2026-07-22-miriyum-repository-architecture-restructure.md`는 여전히 미추적 상태이며 스테이징되지 않는다.
  - staged output is empty unless the user separately requested staging.

- [ ] **Step 6: 문서 형식과 충돌 문구를 최종 점검한다**

  Run:

  ```powershell
  git diff --check
  rg -n "금액 한도는.*ADMIN-006.*적용|정책 위반.*폐점.*적용|대량 내보내기.*허용하지 않는다" docs/service-policies/03-store-operation.md docs/service-policies/15-admin-operation.md
  ```

  Expected:

  - `git diff --check` succeeds.
  - 두 번째 검색에서 과거의 모호한 충돌 문구가 발견되지 않는다.
  - 승인된 예외를 포함한 새 문구는 “무승인·자유 형식 대량 내보내기 금지”로 구체화되어 있다.

## 4. 완료 기준

다음 조건을 모두 만족해야 보완 완료로 보고한다.

1. 결제 승인 매트릭스가 `08-payment-refund.md`와 `15-admin-operation.md`에서 동일하다.
2. 정책 위반 영구 퇴점은 `ADMIN-007` 승인 없이 `OPER-009`로 우회할 수 없다.
3. 개인정보 대량 내보내기는 절대 금지와 무제한 허용 사이가 아니라, 승인된 계획에 한정된 허용으로 정리된다.
4. 개인정보 break-glass의 다른 승인자, 만료, 감사 조건이 `15`와 `17`에서 일치한다.
5. 입점·테이스팅 상태는 각 도메인의 정본으로 유지되고 `ADMIN-003`에는 투영 관계만 추가된다.
6. `ADMIN-009`와 개인정보·법률 TODO 경계가 그대로 남는다.
7. 정책 수와 상태 집계는 `217`, `191 / 11 / 0 / 15`로 유지된다.
8. `RES-003`~`RES-005`의 현재 상태는 본문과 README에서 `확정`이며, 역사 결정 기록의 `개정 확정`은 보존된다.
9. `01-member-auth.md`, `16-notification.md`, `18-scale-reliability.md`에 이번 작업의 새 변경이 없다.
10. 기존 미추적 계획 파일이 수정·스테이징되지 않는다.
11. 커밋과 푸시는 수행하지 않는다.

## 5. 실행 시 주의할 피드백 해석

- “`01-member-auth.md`가 `ADMIN-009`를 직접 참조하므로 반드시 수정해야 한다”는 설명은 정확하지 않다. 실제 필요한 조치는 `ADMIN-009` TODO가 이미 확정된 `AUTH-006` 보존기간을 덮지 않는다고 관리자 문서에 쓰는 것이다.
- `02-store-onboarding.md`와 `07-course-tasting.md`는 관리자 큐의 원본이 아니다. 상태를 통합하거나 이름을 바꾸지 말고 `ADMIN-003` 투영표에 대한 최소 역참조만 추가한다.
- `00-policy-template.md`의 고정 집계 문제는 승인·상태 충돌과 직접 관련되지는 않지만 현재 README와 이미 어긋나므로 같은 문서 정합성 작업에서 제거한다.
- `16-notification.md`와 `18-scale-reliability.md`는 참조 대상일 뿐, 알림 계약이나 RPO·RTO를 바꾸지 않는 이번 작업에서는 수정하지 않는다.
