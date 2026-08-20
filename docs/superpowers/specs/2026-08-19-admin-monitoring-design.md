# 예약·웨이팅 통합 모니터링 런타임 설계

- 소유 이슈: #280
- 기준 브랜치: `origin/dev` at `69d29b810dd94c5b9a2506d7e873d8740ca86a79`
- 선행 공개 계약: #472 / PR #475
- 상태: 사용자 승인 완료

## 목표와 경계

플랫폼 운영자가 하나의 기준 시각에서 예약과 웨이팅 사건을 조회하고, 연결된 ReservationHold, MenuHold, Payment 및 체크인·노쇼 이력을 원장별 진실성과 함께 확인한다. 목록 한 행은 동작이 아니라 `RESERVATION` 또는 `WAITING` 사건 하나다.

런타임은 `ReservationMonitoringQueryService`, `WaitingMonitoringQueryService`, `MenuHoldMonitoringQueryService`, `PaymentMonitoringQueryService`와 각 공개 DTO만 소비한다. 원 도메인 Entity·Repository를 import하지 않으며 관리자 snapshot, projection, migration을 만들지 않는다.

## 통합 모델과 상태 매핑

주 원장은 Reservation 또는 Waiting이다. ReservationHold를 거친 예약은 전환 후에도 `reservation-hold:{id}`를 유지하고 직접 예약은 `reservation:{id}`, 웨이팅은 `waiting:{id}`를 사용한다. `caseVersion`은 주 원장 원본 version에 1을 더해 중앙 사건 배정의 양수 version으로 사용한다.

상태 매핑은 다음과 같다.

| source | sourceStatus/event | lifecycleStatus |
|---|---|---|
| RESERVATION_HOLD | ACTIVE, RECONCILIATION_REQUIRED | PENDING |
| RESERVATION_HOLD | CONFIRMED | CONFIRMED |
| RESERVATION_HOLD | RELEASED, EXPIRED | CANCELLED |
| RESERVATION | CONFIRMED | CONFIRMED |
| RESERVATION event | CHECKED_IN | CHECKED_IN |
| RESERVATION | FULFILLED | COMPLETED |
| RESERVATION | CANCELLED | CANCELLED |
| RESERVATION | NO_SHOW | NO_SHOW |
| WAITING | WAITING, CALLED, RESERVATION_CONVERTING | PENDING |
| WAITING | ARRIVED | CONFIRMED |
| WAITING | CHECKED_IN, RESERVATION_CONVERTED | CHECKED_IN |
| WAITING | CLOSED_BY_STORE | COMPLETED |
| WAITING | CANCELLED | CANCELLED |
| WAITING | NO_SHOW | NO_SHOW |

Reservation 체크인은 batch snapshot에 별도 상태가 없으므로 공개 `findCase`의 event 이력에서 `CHECKED_IN`을 읽는다. 이는 공개 조회 계약만 사용하며 후보 수와 source detail 한도가 모두 100으로 제한된다. lifecycle과 reconciliation은 별도 축이며, `RECONCILIATION_REQUIRED`는 lifecycle을 덮어쓰지 않는다.

각 ledger cell은 source, nullable state, asOf, dataThrough, completeness, reconciliationStatus, historyAvailableFrom을 독립적으로 유지한다. `dataThrough < asOf`는 항상 DELAYED이며 COMPLETE로 승격하지 않는다. 호출 자체가 실패한 부가 원장은 state가 null인 UNAVAILABLE cell로 표현하고 failure를 함께 반환한다.

## 필터·정렬·cursor

필수 `changedFrom`, `changedTo`는 닫힌 구간이며 최대 31일이다. 선택 필터는 storeId, caseTypes, lifecycleStatuses, source-qualified sourceStatuses, reconciliationStatuses다. 같은 source의 여러 상태는 OR, 서로 다른 필터 축과 서로 다른 source 조건은 AND다. 존재하지 않는 source-qualified 상태나 case type/prefix 불일치는 `INVALID_MONITORING_FILTER`다.

정렬은 `statusChangedAt DESC`, `caseType ASC`, `caseId DESC`다. size는 기본 20, 범위 1..100이다.

cursor payload는 다음을 포함하고 HMAC-SHA256으로 보호한다.

- contractVersion, active key ID, issuedAt, 첫 페이지 asOf
- 정규화 필터와 size의 SHA-256 fingerprint
- 마지막으로 평가한 전역 `(statusChangedAt, caseType, caseId)` tuple
- Reservation과 Waiting 각각의 마지막 소비 seek checkpoint

원장별 checkpoint가 있어 동일 statusChangedAt에서 Reservation과 Waiting의 상대 순서를 유지하면서도 어느 한 원장의 남은 후보를 건너뛰지 않는다. decode 시 서명, version, key ID, 30분 TTL, 미래 issuedAt/asOf, filter fingerprint를 검증한다. 손상·불일치는 INVALID_CURSOR, 알 수 없는/퇴역 key 또는 TTL 초과는 EXPIRED_CURSOR다.

각 HTTP page에서 source별 최대 100개를 읽어 k-way merge하고, batch hydration 후 필터를 평가한다. 필터 후 결과가 size에 미달해도 source page가 가득 찼다면 마지막 평가 checkpoint의 nextCursor를 반환해 남은 후보가 없다고 위장하지 않는다. 출력 size가 채워지면 아직 평가하지 않은 후보의 source checkpoint는 전진시키지 않는다.

## 동일 기준 시각과 부분 실패

첫 페이지는 주입된 Clock의 현재 Instant를 asOf로 확정한다. cursor 페이지는 payload의 동일 asOf를 재사용하고 모든 source ChangeQuery, BatchQuery, DetailQuery에 그대로 전달한다.

- Reservation 또는 Waiting 후보 source 하나만 실패하면 성공한 source의 항목을 200 PARTIAL로 반환하고 failure를 기록하며 nextCursor는 null이다.
- 두 후보 source가 모두 실패하면 503 MONITORING_SOURCES_UNAVAILABLE이다.
- 후보 조회 후 주 원장 batch/detail 조회가 실패한 경우에도 같은 base-source 규칙을 적용한다.
- 상세 주 원장 실패는 503, 성공 후 Optional.empty는 404다.
- MenuHold 또는 Payment 실패는 해당 cell만 UNAVAILABLE, 전체 PARTIAL이며 다른 원장의 상태는 보존한다.
- 실패한 ancillary source가 필터에 필요하면 그 후보는 일치한다고 추측하지 않고 제외하되 failure와 cursor null로 불완전성을 명시한다.

페이지 dataThrough는 성공적으로 읽은 후보·cell의 최소 dataThrough이며, 실패가 있으면 page completeness는 PARTIAL이다. 행의 lifecycle은 주 원장만 결정한다.

## 권한과 최소 공개

Controller와 모든 runtime bean은 `miriyum.platform-operator.enabled=true`와 `miriyum.admin-monitoring.enabled=true`일 때만 등록한다. Spring Security의 플랫폼 운영자 JWT principal을 사용한다.

목록과 상세 모두 `OperatorAuthorityReader.requireCurrentAuthority(accountId, authorityVersion)`로 현재 authority version을 검증하고 `OPERATIONS_MONITOR_READ`를 요구한다. 상세는 주 원장으로 caseVersion을 확인한 뒤 같은 transaction 안에서 `AdminCaseAssignmentVerifier.verify(OPERATIONS_MONITORING, caseId, caseVersion, operatorId)`를 호출한다. 별도 재인증과 업무 감사 write는 하지 않는다.

응답은 계정 ID, 이름, 전화번호, 이메일, 결제수단, paymentId, provider transaction ID, 결제 key·token·원문을 노출하지 않는다. 공개 source 계약에 최소 마스킹 subject가 없으므로 subject는 생략하며 `maskingLevel=MINIMIZED`만 반환한다. Payment는 금액·환불 금액·통화·처리 상태와 bounded ledger/refund 이력만 반환한다.

## 공유 파일과 migration 판단

#281 PR #476이 `docs/specs/platform-operator-openapi.yaml`을 수정하고 V66을 사용하며, 열린 #489가 V67을 사용한다. #280은 migration을 만들지 않는다. aggregate OpenAPI에는 admin-monitoring `$ref`가 이미 존재하므로 불필요한 재작성은 하지 않고, 최종 rebase에서 #476의 additive 변경을 보존한다.

## 검증

상태 매핑, cursor 서명·만료·필터 바인딩, 현재 권한·배정, source별 부분 실패, 동일 asOf, 고정 정렬, 민감 필드 부재, feature OFF 404, OpenAPI aggregate, 공개 계약 import 경계를 각각 targeted test로 검증한다. 전체 backend suite는 로컬에서 실행하지 않고 GitHub CI를 정본으로 사용한다.
