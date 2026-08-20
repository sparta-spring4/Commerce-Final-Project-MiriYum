# 예약·웨이팅 통합 모니터링

- 소유 Issue: [#280](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/280)
- 단계: 고도화
- audience: Platform Operator
- 활성 OpenAPI: [openapi.yaml](openapi.yaml)
- 공개 조회 계약·설계 기록: [#472](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/472)

## 목적

플랫폼 운영자가 Reservation과 Waiting 사건을 한 목록에서 찾고 연결된 ReservationHold, MenuHold, Payment, 체크인, 노쇼 상태와 이력을 동일 기준 시각으로 확인한다. 목록의 한 행은 사람이나 동작이 아니라 하나의 예약 또는 웨이팅 사건이다.

## 사건 식별

- ReservationHold를 거친 예약: `reservation-hold:{reservationHoldId}`
- 직접 생성된 예약: `reservation:{reservationId}`
- 웨이팅: `waiting:{waitingTeamId}`

ReservationHold가 최종 Reservation으로 전환돼도 공개 case ID는 바뀌지 않는다. 같은 사람의 서로 다른 예약·웨이팅은 서로 다른 사건이다.

## 조회 계약

### 목록

`GET /api/v1/platform-operators/monitoring-cases`

- 필수 기간: `changedFrom`, `changedTo`; 최대 31일
- 선택 필터: `storeId`, `caseTypes`, `lifecycleStatuses`, 원장-qualified `sourceStatuses`, `reconciliationStatuses`
- 크기: 기본 20, 1~100
- 고정 정렬: `statusChangedAt DESC`, `caseType ASC`, `caseId DESC`
- 변경 후보는 Reservation·Waiting·MenuHold·Payment 네 공개 change stream을 병합하며, 연결 원장만 변경된 사건도 포함한다.
- 첫 페이지 `asOf`는 `changedTo`로 고정한다. 따라서 네 원장의 batch snapshot은 조회 구간 끝의 동일 상태를 사용하고, 사건의 조회 구간 내 최신 변경 tuple을 재구성할 수 있다.
- cursor: 계약 version, key ID, 첫 페이지 `asOf`, 정규화 필터 지문, 마지막 전역 tuple과 네 원장별 seek tuple만 HMAC으로 보호한다. 이전 페이지의 사건 ID 집합을 누적하지 않는다.
- source page가 현재 출력 경계와 같은 `statusChangedAt`에서 끝나면 다음 source page를 보충한 뒤 전역 `caseType ASC` 경계를 확정한다. 원장별 최대 10 page·1,000 reference까지만 읽으며 그 안에서 경계를 확정하지 못하면 해당 원장을 명시적 failure로 표시하고 cursor를 발급하지 않는다.

### 상세

`GET /api/v1/platform-operators/monitoring-cases/{caseType}/{caseId}`

주 사건과 연결 원장별 상태 및 상태 이력을 반환한다. 상세 조회는 목록 권한 외에 대상 case ID와 현재 case version에 대한 활성 `OPERATIONS_MONITORING` 배정이 필요하다.

## 상태와 기준 시각

- 통합 계층은 첫 페이지에서 `asOf=changedTo` 하나를 확정하고 모든 공개 원 도메인 Service에 그대로 전달한다.
- 후속 페이지는 cursor에 포함된 같은 `asOf`를 사용한다.
- 각 원장 cell은 `source`, 원본 `sourceStatus`, 원본 `statusVersion`, `statusChangedAt`, `asOf`, `dataThrough`, `completeness`, `reconciliationStatus`를 독립적으로 보존한다.
- `dataThrough < asOf`이면 `DELAYED`다. 이를 최신 확정 상태로 승격하지 않는다.
- 응답 `caseVersion`은 요청한 `asOf` 주 원장의 원본 version에 1을 더한 양수다. 상세 배정 검증은 이 snapshot version과 분리해 현재 시점 주 원장의 version을 사용한다.
- lifecycle과 reconciliation은 별도 축이다. 한 원장의 실패·대사 필요가 다른 원장의 lifecycle을 덮어쓰지 않는다.

## 부분 실패

| 상황 | HTTP | 표현 |
|---|---:|---|
| 모든 필수 원장 성공 | 200 | 실제 `COMPLETE` 또는 `DELAYED` |
| Reservation·Waiting 후보 중 하나 실패 | 200 | `PARTIAL`, 명시적 failure, `nextCursor=null` |
| Reservation·Waiting 후보 모두 실패 | 503 | `MONITORING_SOURCES_UNAVAILABLE` |
| 상세 주 원장 실패 | 503 | 존재 여부를 추측하지 않음 |
| 상세 주 원장 성공 후 부재 | 404 | `MONITORING_CASE_NOT_FOUND` |
| 부가 MenuHold·Payment 원장 실패 | 200 | 해당 cell만 `UNAVAILABLE`, 전체 `PARTIAL` |
| 손상·필터 불일치 cursor | 400 | `INVALID_CURSOR` |
| 퇴역 키·제공 불가능한 asOf | 400 | `EXPIRED_CURSOR` |

실패를 빈 목록이나 원장 부재로 치환하지 않는다. 순서를 완전히 확정할 수 없는 부분 응답에는 cursor를 발급하지 않는다.

## 권한과 최소 공개

- 기능 OFF: route 미등록 404
- 플랫폼 운영자 JWT 없음: 401
- 현재 authority에 `OPERATIONS_MONITOR_READ` 없음: 403
- 상세 사건 배정 없음·만료·version 불일치: 403
- 별도 비밀번호 재인증과 업무 감사 event 쓰기는 하지 않는다.
- 목록에는 이름, 전화번호, 이메일, 결제수단, 내부 사용자 ID가 없다.
- 상세도 `maskingLevel=MINIMIZED`이며 현재 공개 source 계약에 identity 표시값이 없으므로 subject를 반환하지 않는다.
- PG 원문, 승인 토큰, 결제 키, provider transaction ID와 마스킹 해제 옵션은 없다.

## 저장과 의존성 경계

- 관리자 snapshot·projection을 만들지 않고 원장 공개 조회 Service를 실시간 조합한다.
- 플랫폼 운영자 runtime은 원 도메인 공개 Service/DTO만 import하며 Entity·Repository를 직접 참조하지 않는다.
- MenuHold는 선행 #472의 V64에서 source-owned `status_version`과 append-only 전이 원장을 추가한다. 전이 원장은 부모 `MenuHold`가 삭제되어도 독립 조회에 필요한 hold·store·예약 연결·생성 시각·최소 item snapshot을 보존하며, 변경·batch·상세 조회는 부모 Entity 조인이나 현재 item으로 과거를 추측하지 않는다.
- V64 기존 행은 migration 시각의 `BASELINE` 하나만 기록한다. 그 이전 `asOf`는 `UNAVAILABLE`이며 과거 전이를 추측하지 않는다.
- Payment·Refund는 선행 #472의 V65에서 source-owned `store_id` snapshot과 append-only monitoring 원장을 추가한다.
- V65 baseline 이전 `asOf`는 현재 Payment·Refund 행으로 역추정하지 않으며, 확인된 상태가 없는 ledger cell의 `state`는 null이다.
- Payment detail의 ledger·refund 이력은 각각 최대 100건이며 초과 여부를 truncation metadata로 보존한다.

## 런타임 활성화

네 원 도메인의 공개 조회 계약이 `dev`에 병합되어 두 path는 runtime-active다. Controller와 조합 service는 플랫폼 운영자와 admin-monitoring feature flag가 모두 켜진 경우에만 등록된다.

## 인수 조건

- 한 사건의 여러 동작이 목록 행을 늘리지 않고 상세 이력에 나타난다.
- 모든 원장은 같은 `asOf`를 받는다.
- 원장별 version, data-through, completeness, reconciliation이 보존된다.
- 지연·실패 원장을 현재 정상 상태로 위장하지 않는다.
- 부분 실패를 0건으로 위장하거나 cursor로 건너뛰지 않는다.
- 목록 권한과 상세 사건 배정을 각각 검증한다.
- 응답에 민감 원문과 내부/provider 식별자가 없다.
- 플랫폼 runtime은 공개 원 도메인 계약만 소비한다.
