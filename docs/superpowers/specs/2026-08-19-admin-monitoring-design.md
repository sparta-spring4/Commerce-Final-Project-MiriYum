# #280 예약·웨이팅 통합 모니터링 설계

작성일: 2026-08-19

대상 단계: 고도화

소유 Issue: [#280](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/280)

상태: 사용자 승인 설계 기록

> 이 파일은 brainstorming 결과를 보존하는 설계 기록이다. 구현의 활성 정본은 #280의 exact allowlist에 따라 생성하는 `docs/specs/admin-monitoring/spec.md`와 `docs/specs/admin-monitoring/openapi.yaml`이다.

## 1. 목적과 성공 기준

플랫폼 운영자가 예약과 웨이팅 사건을 한 목록에서 찾고, 한 사건에 연결된 ReservationHold, MenuHold, Payment, 체크인, 노쇼 상태를 상세에서 확인한다. 통합 계층은 각 원장의 공개 조회 계약만 소비하며 원 도메인 Entity 또는 Repository에 직접 접근하지 않는다.

성공 기준은 다음과 같다.

- 목록의 한 행은 사람이나 상태 변경 동작이 아니라 하나의 예약 또는 웨이팅 사건이다.
- 같은 사건의 동작은 목록 행을 추가하지 않고 상세 상태 이력에 나타난다.
- 모든 원장 조회가 한 요청에서 확정한 같은 `asOf`를 사용한다.
- 각 원장의 출처, `dataThrough`, `statusVersion`, 완전성, 대사 상태를 독립적으로 보존한다.
- 지연되거나 확인 불가능한 값을 최신 확정 상태로 표현하지 않는다.
- 한 원장의 실패나 대사 상태가 다른 원장의 lifecycle 상태를 덮어쓰지 않는다.
- 권한이나 사건 배정 없이 민감한 상세를 볼 수 없고, 배정된 운영자에게도 최소 마스킹 정보만 제공한다.
- 부분 실패를 정상 0건으로 위장하거나 누락된 결과 이후로 cursor를 진행하지 않는다.

## 2. 선행 계약과 작업 순서

선행 Issue #236~#242, #272, #275, #276은 2026-08-19 기준 완료됐다. 그러나 최신 `origin/dev`의 Reservation, MenuHold, Payment, Waiting 공개 계약은 플랫폼 통합 모니터링에 필요한 사건 목록·상세, 동일 `asOf`, 상태 version, 원장 완전성, 대사 상태를 모두 제공하지 않는다.

작업은 두 PR로 분리한다.

1. 선행 공개 계약 PR
   - `feature/280-admin-monitoring-contracts`
   - 활성 spec과 contract-only OpenAPI를 먼저 확정한다.
   - 각 원 도메인이 소유하는 최소 공개 조회 Service, scalar DTO, 오류 의미, contract·service 테스트를 추가한다.
   - 관리자 HTTP runtime과 cross-domain 조합은 추가하지 않는다.
2. #280 runtime PR
   - 선행 PR이 `dev`에 병합된 뒤 최신 `origin/dev`에서 `feature/280-admin-monitoring`을 새로 만든다.
   - contract-only OpenAPI를 활성 runtime 계약으로 전환한다.
   - 플랫폼 운영자 계층은 선행 공개 Service/DTO만 조합한다.

선행 계약을 실제 저장 상태로 구현할 수 없거나 기존 이력만으로 요구된 시점 경계를 지킬 수 없다면 production dummy, 현재 상태 대체, `null`, 빈 성공 응답을 만들지 않고 중단한다. 부족한 저장·오류 계약을 #280에 구체적으로 보고한 뒤 별도 승인을 받는다.

## 3. 선택한 아키텍처

### 3.1 실시간 원장 조합

관리자 전용 snapshot 또는 projection 테이블을 만들지 않는다. 통합 Service는 요청마다 원 도메인 공개 조회 Service를 독립 호출하고 결과를 사건 중심 읽기 모델로 조합한다.

```text
AdminMonitoringController
        |
        v
AdminMonitoringAuthorizationService ---- OperatorAuthorityReader
        |                                AdminCaseAssignmentVerifier
        v
AdminMonitoringQueryService
   |          |          |          |
   v          v          v          v
Reservation  MenuHold   Payment    Waiting
public query public     public     public query
contract     query      query      contract
             contract   contract
```

원 도메인은 자기 공개 Service 구현 내부에서 자기 Entity와 Repository를 사용할 수 있다. 플랫폼 운영자 `adminmonitoring` 패키지는 원 도메인의 `service`와 `dto` 공개 계약 외에는 import하지 않는다. `DomainPackageArchitectureTest`가 이 경계를 고정한다.

### 3.2 대안과 기각 이유

- 관리자 snapshot 테이블: 목록 조회는 단순하지만 복제 지연과 누락 대사 책임이 새로 생기며 지연 상태를 최신으로 오인할 위험이 있다.
- 목록 snapshot·상세 실시간 혼합: 같은 화면의 목록과 상세가 다른 상태를 보여줄 수 있고 운영 복잡도가 가장 크다.

실시간 조합은 원장이 MySQL 단일 진실 원천이라는 저장소 계약과 맞고, 이 기능이 새로운 상태 원본이 되는 것을 막는다.

### 3.3 migration 결정

신규 Flyway migration은 없다. 2026-08-19 기준 열린 PR #414와 #448이 각각 V60과 V61을 점유하지만 #280은 번호를 예약하지 않는다. 읽기 구현 중 기존 append-only 이력으로 승인된 `asOf` 의미를 제공할 수 없음이 확인되면 임의 migration을 추가하지 않고 저장 계약 누락으로 중단한다.

## 4. 사건 중심 통합 모델

### 4.1 목록 행

`MonitoringCase` 한 행은 `RESERVATION` 또는 `WAITING` 사건 하나를 나타낸다.

```text
MonitoringCase
├─ caseType: RESERVATION | WAITING
├─ caseId: string
├─ storeId: string
├─ lifecycleStatus
├─ sourceStatus
├─ statusChangedAt
├─ statusVersion
├─ asOf
├─ dataThrough
├─ completeness
├─ reconciliationStatus
└─ linkedLedgerSummary
   ├─ reservation
   ├─ reservationHold
   ├─ menuHold
   ├─ payment
   └─ waiting
```

한 예약이 생성, 메뉴 홀드, 결제, 체크인, 이용 완료로 변해도 목록에는 예약 한 행만 존재한다. 같은 사람이 별도의 웨이팅이나 새 예약을 만들면 서로 다른 사건이므로 별도 행이다. 사람을 식별하거나 여러 사건을 한 사람 기준으로 합치지 않는다.

목록의 연결 원장 요약은 존재 여부와 주의 상태만 제공한다. 상세는 각 원장의 상태, version, 시각, 최소 마스킹 정보와 상태 이력을 제공한다.

사건의 `statusChangedAt`은 동일 `asOf`에서 확인된 연결 원장들의 상태 변경 시각 중 최댓값이다. Reservation 또는 Waiting lifecycle이 그대로여도 연결 Payment나 MenuHold 상태가 바뀌면 같은 사건 행의 정렬 시각이 갱신된다. 연결 source가 실패하면 최댓값과 순서를 확정할 수 없으므로 응답은 `PARTIAL`이고 cursor를 발급하지 않는다.

### 4.2 상태의 두 축

`lifecycleStatus`와 `reconciliationStatus`를 분리한다.

- `lifecycleStatus`: 고객 흐름의 현재 단계
- `reconciliationStatus`: `MATCHED`, `REQUIRED`, `UNKNOWN`

결제가 `REQUIRED`여도 예약의 `CONFIRMED` lifecycle을 바꾸지 않는다. 원장 호출 실패는 `completeness=UNAVAILABLE`로 표현하며 다른 원장의 상태를 합성하지 않는다.

공통 lifecycle 매핑은 다음과 같다.

| 원장 | 원본 상태 | 공통 lifecycleStatus |
|---|---|---|
| Reservation | `CONFIRMED` | `CONFIRMED` |
| Reservation | `FULFILLED` | `COMPLETED` |
| Reservation | `CANCELLED` | `CANCELLED` |
| Reservation | `NO_SHOW` | `NO_SHOW` |
| Waiting | `WAITING`, `CALLED`, `ARRIVED` | `PENDING` |
| Waiting | `CHECKED_IN` | `CHECKED_IN` |
| Waiting | `RESERVATION_CONVERTING` | `PENDING` |
| Waiting | `RESERVATION_CONVERTED` | `CONFIRMED` |
| Waiting | `CANCELLED`, `CLOSED_BY_STORE` | `CANCELLED` |
| Waiting | `NO_SHOW` | `NO_SHOW` |

응답은 공통 상태와 함께 원본 `sourceStatus`를 항상 보존한다. 새 원본 상태가 추가됐는데 명시적 매핑이 없으면 임의 상태로 내리지 않고 해당 원장을 `PARTIAL` 또는 `UNAVAILABLE`로 처리하며 오류 코드를 남긴다.

### 4.3 원장 메타데이터

각 원장 cell은 다음 필드를 독립적으로 갖는다.

- `source`: `RESERVATION`, `RESERVATION_HOLD`, `MENU_HOLD`, `PAYMENT`, `WAITING`
- `sourceStatus`: 공개 계약의 원본 상태 문자열 또는 공개 enum
- `statusVersion`: 원장 상태의 단조 증가 version
- `asOf`: 통합 요청이 확정한 기준 시각
- `dataThrough`: 원장이 확정적으로 반영한 마지막 시각
- `completeness`: `COMPLETE`, `DELAYED`, `PARTIAL`, `UNAVAILABLE`
- `reconciliationStatus`: `MATCHED`, `REQUIRED`, `UNKNOWN`
- 실패한 경우 `errorCode`, `retryable`

## 5. 공개 원 도메인 계약

각 공개 조회 Service는 scalar ID, 시각, 금액, 공개 enum·문자열로만 구성된 DTO를 반환한다. DTO 생성자나 필드 타입에 Entity·Repository·provider SDK 타입을 포함하지 않는다.

목록의 N+1 호출과 잘못된 전역 정렬을 피하기 위해 각 source 계약은 두 종류의 읽기를 제공한다.

1. 시간 범위와 seek 경계 안에서 변경된 사건 참조를 반환하는 page 조회
2. 최대 요청 page 크기로 제한된 사건 ID 묶음의 원장 cell을 반환하는 batch 조회

통합 Service는 네 source의 변경 참조를 같은 `asOf`로 병합하고 `caseType + caseId`로 중복을 제거한 뒤, bounded batch로 원장 cell을 채운다. 연결 원장 변경도 사건 후보를 만들기 때문에 Payment나 MenuHold 변경이 기존 Reservation 행의 `statusChangedAt`과 정렬에 반영된다.

### 5.1 Reservation

- 사건 목록을 `asOf`, 시간 범위, 상태, seek 경계로 조회한다.
- Reservation 사건 참조 page와 제한된 ID batch, 단일 상세를 제공한다.
- 사건 상세와 ReservationHold 상태, version, 전이 이력을 조회한다.
- 체크인과 노쇼는 실제 Reservation 상태·이력에서 제공한다.
- consumer authority를 요구하는 기존 조회 API를 플랫폼 운영자 용도로 재사용하지 않는다.

### 5.2 MenuHold

- 변경된 Reservation 사건 참조 page와 예약 ID batch로 MenuHold 존재 여부, 공개 상태, version, 상태 시각, 대사 상태를 조회한다.
- 메뉴 이름·원본 고객 정보보다 공개 메뉴 ID, 수량, 최소 표시 정보만 제공한다.
- 임시 홀드와 확정 홀드의 부재를 실패와 구분한다.

### 5.3 Payment

- 변경된 Reservation 사건 참조 page와 예약 ID batch로 저장된 결제·환불 상태, 금액, 통화, version, 대사 상태를 조회한다.
- provider를 재호출하거나 복구·환불 명령을 실행하지 않는다.
- PG 응답, transaction ID, 결제 키, 승인 토큰, 결제수단 원문은 공개하지 않는다.
- 열린 PR #459가 수정하는 `PaymentContracts`, `PaymentService`, `PaymentRepository`와의 충돌을 최소화하도록 새 `PaymentMonitoringContracts`와 `PaymentMonitoringQueryService`를 우선 사용한다. Repository 수정이 실제로 필요하면 #459 병합 상태와 최신 `dev`를 다시 대조한다.

### 5.4 Waiting

- Waiting 사건 참조 page와 제한된 ID batch, 단일 상세를 Reservation과 같은 `asOf`와 seek 의미로 조회한다.
- 상세과 Waiting 상태 전이 이력을 조회한다.
- 체크인·노쇼·예약 전환은 실제 Waiting 상태와 이력에서 제공한다.
- store-operator authority를 요구하는 기존 `WaitingTeamQueryService`를 우회하거나 플랫폼 권한으로 가장하지 않는다.

## 6. 필터, 정렬, cursor

### 6.1 필터

- `storeId`: 선택
- `caseTypes`: `RESERVATION`, `WAITING`
- `lifecycleStatuses`: 공통 lifecycle 상태
- `sourceStatuses`: `RESERVATION:CONFIRMED`처럼 원장-qualified 값
- `reconciliationStatuses`: `MATCHED`, `REQUIRED`, `UNKNOWN`
- `changedFrom`, `changedTo`: 필수 상태 변경 시각 범위
- `size`: 기본 20, 최대 100

`changedFrom`과 `changedTo`의 간격은 최대 31일이다. 역전 범위, 지원하지 않는 상태 조합, 범위를 벗어난 size는 `400 INVALID_MONITORING_FILTER`다.

### 6.2 정렬

통합 Service는 네 source의 후보를 bounded batch로 가져와 사건별 연결 원장 상태 변경 시각의 최댓값을 계산한다. `reconciliationStatuses`처럼 연결 cell이 필요한 필터도 이 batch 단계에서 평가한다. 완전한 batch만 다음 순서로 정렬한다.

1. `statusChangedAt DESC`
2. `caseType ASC`
3. `caseId DESC`

임의 정렬 파라미터는 제공하지 않는다. 원 도메인 Service도 같은 의미의 seek 경계를 받아 통합 계층이 각 소스 결과를 결정적으로 병합할 수 있어야 한다.

### 6.3 cursor

cursor는 내부 값을 직접 노출하지 않는 무결성 보호 문자열이다. payload 의미는 다음과 같다.

- `contractVersion`
- 서명 키 세대를 식별하는 `keyId`
- `asOf`
- 정규화된 필터의 `filterFingerprint`
- `seekAfterStatusChangedAt`
- `seekAfterCaseType`
- `seekAfterCaseId`

손상, 다른 필터와의 재사용, 알 수 없는 contract version은 `400 INVALID_CURSOR`다. 퇴역한 서명 키의 `keyId`이거나 원 도메인이 해당 `asOf`를 더 이상 제공할 수 없으면 `400 EXPIRED_CURSOR`다. #280만의 임의 wall-clock TTL은 추가하지 않는다.

cursor의 seek tuple은 마지막으로 반환한 행이 아니라 필터 평가를 끝낸 마지막 후보를 가리킨다. 그래야 연결 원장 필터에서 제외된 후보를 다음 페이지에서 반복 스캔하지 않는다.

부분 실패 페이지는 누락된 원장을 건너뛸 수 있으므로 `nextCursor=null`이다. 클라이언트는 같은 필터와 첫 요청 조건으로 재시도하고 완전한 페이지를 받은 뒤에만 다음 페이지로 진행한다.

## 7. 기준 시각과 부분 실패

### 7.1 동일 `asOf`

통합 Service는 첫 페이지 요청 시작 시 `Clock`으로 `asOf` 하나를 확정하고 모든 원 도메인 Service에 그대로 전달한다. 후속 페이지는 cursor의 같은 `asOf`를 사용한다. 각 원장은 `asOf` 이후의 상태를 현재 페이지에 섞지 않는다.

`dataThrough`는 원장이 확정적으로 설명할 수 있는 마지막 시각이다.

- `dataThrough == asOf`: `COMPLETE`
- `dataThrough < asOf`: `DELAYED`
- 일부 자료만 확정 가능: `PARTIAL`
- 호출 실패 또는 신뢰 가능한 상태 구성 불가: `UNAVAILABLE`

`dataThrough`가 없거나 `asOf`보다 뒤인 잘못된 계약 응답은 정상 상태로 사용하지 않는다.

### 7.2 독립 호출과 조합

Reservation, MenuHold, Payment, Waiting 목록 후보 호출은 서로 독립적이다. 한 호출의 예외를 다른 호출 결과나 빈 배열로 치환하지 않는다. 상세의 ReservationHold, MenuHold, Payment, Waiting 연결 조회도 원장 cell마다 독립적으로 실패를 포착한다.

### 7.3 HTTP와 응답 의미

| 상황 | HTTP | 응답 의미 |
|---|---:|---|
| 모든 필수 원장 정상 | 200 | `COMPLETE` 또는 실제 `DELAYED` |
| 목록의 Reservation·Waiting 중 하나 실패 | 200 | `PARTIAL`, 실패 source 명시, `nextCursor=null` |
| 목록의 Reservation·Waiting 모두 실패 | 503 | `MONITORING_SOURCES_UNAVAILABLE` |
| 상세 주 원장 호출 실패 | 503 | 주 사건 존재 여부를 추측하지 않음 |
| 상세 주 원장 정상 조회 후 부재 | 404 | `MONITORING_CASE_NOT_FOUND` |
| 상세 부가 원장 실패 | 200 | 해당 cell만 `UNAVAILABLE`, 전체 `PARTIAL` |
| 잘못된 필터 | 400 | `INVALID_MONITORING_FILTER` |
| 손상·필터 불일치 cursor | 400 | `INVALID_CURSOR` |
| 만료 cursor | 400 | `EXPIRED_CURSOR` |

빈 `items`는 모든 필수 원장이 정상 응답하고 실제 결과가 없을 때만 정상 0건이다.

## 8. 인증, 인가, 마스킹

### 8.1 인증과 권한

- `miriyum.platform-operator.enabled=false`: route가 등록되지 않아 404
- 유효한 platform-operator JWT 없음: 401
- 현재 MySQL authority version에서 `OPERATIONS_MONITOR_READ` 없음: 403
- 목록: 위 권한만 요구
- 상세: 위 권한과 활성 `OPERATIONS_MONITORING` 사건 배정을 함께 요구
- 배정 부재, 만료, case version 불일치: `403 MONITORING_CASE_ASSIGNMENT_REQUIRED`

JWT claim의 role 또는 permission을 그대로 신뢰하지 않고 `OperatorAuthorityReader`가 반환하는 현재 권한을 사용한다. 상세 사건 배정은 JWT의 현재 운영자 ID와 대상 case ID, 주 원장 `statusVersion`을 case version으로 삼아 `AdminCaseAssignmentVerifier`에 전달한다. 부가 Payment·MenuHold version 변경만으로 배정을 무효화하지 않으며 주 Reservation·Waiting version이 바뀌면 새 version 배정이 필요하다.

### 8.2 재인증과 감사

이 기능은 원장 상태를 변경하지 않는 최소 마스킹 조회다. 별도 비밀번호 재확인이나 일회 reauthentication approval을 요구하지 않는다. 조회 시 업무 감사 원장에 새 event를 쓰지 않으며 기존 HTTP·보안 접근 로그를 사용한다. 향후 민감 원문 공개 기능이 필요해지면 #280 범위에 덧붙이지 않고 재인증·감사 계약을 별도 승인한다.

### 8.3 데이터 최소화

목록에는 다음만 포함한다.

- 공개 case ID와 store ID
- 예약 예정·웨이팅 등록·상태 변경 시각
- 인원수
- 상태와 원장 메타데이터

목록에는 이름, 전화번호, 이메일, 결제수단을 포함하지 않는다.

배정된 운영자의 상세도 `maskingLevel=MINIMIZED`를 유지한다.

- 이름·전화번호·이메일은 원 도메인 공개 DTO가 제공하는 마스킹 값만 사용한다.
- 결제는 금액, 통화, 처리 상태만 제공한다.
- PG 원문, 승인 토큰, 결제 키, provider 식별자, 내부 consumer ID는 제공하지 않는다.
- 마스킹 해제 query, header, 권한을 만들지 않는다.

원 도메인 공개 DTO에 필요한 마스킹 필드가 없으면 통합 계층이 Entity나 Repository에서 보충하지 않는다.

## 9. API 표면

플랫폼 운영자 audience 아래 두 GET endpoint를 제공한다.

- `GET /api/v1/platform-operators/admin-monitoring/cases`
- `GET /api/v1/platform-operators/admin-monitoring/cases/{caseType}/{caseId}`

성공 응답은 공통 `ApiResponse<T>` envelope를 사용하며 공개 JSON ID는 문자열이다. feature OpenAPI가 path와 schema의 단일 원본이고 `docs/specs/platform-operator-openapi.yaml`은 path item을 단일 `$ref`로만 연결한다.

선행 계약 PR에서는 두 path에 다음을 표시한다.

- `x-miriyum-runtime-status: contract-only`
- `x-miriyum-owner-issue: 280`

#280 runtime PR에서 실제 Controller와 보안·오류 테스트가 준비될 때 두 확장 필드를 제거한다.

## 10. 충돌 관리

2026-08-19 확인 결과:

- #277과 #281은 열려 있으나 대응하는 열린 PR은 없다.
- #459는 Payment 공개 조회 계약과 관련된 `PaymentContracts`, `PaymentService`, `PaymentRepository`를 변경한다.
- #414는 Flyway V60을 변경한다.
- #448은 Flyway V61과 Waiting runtime 파일을 변경한다.

대응 원칙:

- Admin OpenAPI는 feature 파일이 소유하고 aggregate는 `$ref` 한 줄만 추가한다.
- Payment 모니터링 DTO와 Service는 별도 파일로 분리한다.
- Waiting 파일을 수정하기 전 #448의 병합 여부와 최신 `dev`를 다시 확인한다.
- 선행 PR push 전과 runtime worktree 생성 전에 열린 PR 파일과 migration 최고 번호를 다시 검사한다.
- 신규 migration이 없으므로 V60·V61 충돌은 만들지 않는다.

## 11. TDD와 검증

### 11.1 선행 공개 계약 PR

각 원 도메인에서 다음 순서로 진행한다.

1. 공개 DTO가 Entity·Repository·내부 enum 타입을 노출하지 않는 contract test를 먼저 실패시킨다.
2. `asOf`, seek, 상태 version, 대사·완전성, not-found와 unavailable 의미의 Service test를 먼저 실패시킨다.
3. 최소 공개 DTO와 Service를 구현한다.
4. 필요한 source-owned Repository 조회만 추가한다.
5. OpenAPI contract-only schema와 공개 DTO 의미를 대조한다.

### 11.2 #280 runtime PR

다음 테스트를 먼저 작성한다.

- 상태 매핑과 unmapped source status fail-closed
- cursor round-trip, 변조, 필터 불일치, 만료, 고정 정렬
- 모든 source에 동일 `asOf` 전달
- 한 source 실패가 다른 source 상태를 보존함
- 부분 실패 응답이 source 오류를 포함하고 cursor를 발급하지 않음
- 두 기본 source 실패의 503
- feature OFF 404, 미인증 401, 권한 없음 403
- 상세 사건 배정 없음·만료·version 불일치 403
- 목록 민감 필드 부재와 상세 최소 마스킹
- OpenAPI path, envelope, 오류 schema, contract-only 제거
- 플랫폼 운영자 패키지의 원 도메인 Entity·Repository import 금지

### 11.3 로컬 실행 범위

로컬에서는 공개 DTO contract, 관련 Service 단위 테스트, Controller 권한 HTTP, cursor, 부분 실패, OpenAPI와 영향 통합 테스트 클래스 또는 shard만 실행한다. 전체 backend `build`, `check`, 전체 `integrationTest`, integration shard A~D 조합은 실행하지 않는다. 전체 회귀는 GitHub CI의 `backend-ci`를 정본으로 사용하고 CI 결과가 나오기 전에는 전체 회귀를 `pending`으로 보고한다.

## 12. 범위 밖

- Waiting 호출·입장·취소 같은 상태 변경 명령
- 결제 복구, 환불, 보상, provider 재조회
- 관리자 전용 snapshot·projection과 migration
- 민감 원문 조회 또는 마스킹 해제
- 조회 전용 재인증과 업무 감사 event 쓰기
- 프론트엔드 모니터링 화면
- 사람 단위 사건 병합

## 13. 롤백과 운영 위험

선행 계약 PR은 기존 계약을 변경하지 않는 추가 공개 조회 계약이어야 한다. runtime PR은 신규 GET endpoint와 조합 Service만 추가한다. migration과 상태 변경 명령이 없으므로 각 PR을 revert하면 스키마 잔여물 없이 기능을 제거할 수 있다.

주요 남은 위험은 기존 원장 이력이 승인된 `asOf` 의미를 실제로 재구성할 수 있는지다. 구현 계획 전에 Repository와 append-only 이력의 정확한 조회 가능성을 각 도메인별로 검증한다. 불가능한 원장을 발견하면 현재 상태로 폴백하지 않는 것이 이 설계의 필수 안전 경계다.
