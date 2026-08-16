# Admin Store 운영·제재 활성 정본

- 상태: `ACTIVE`
- 기준 이슈: `#279`
- 상위 정책: `ADMIN-007`, `OPER-009`, `OPER-010`
- 기준 브랜치: `origin/dev` (`#275`, `#276`, `#282` 병합 후)
- 연관 작업: `#278` / PR `#353` (미병합)
- 계약: `docs/specs/admin-store/openapi.yaml`
- 확정일: 2026-08-16

## 1. 목적과 범위

플랫폼 운영자가 매장 운영자용 API를 재사용하지 않고 매장을 최소 조회하고, 매장 단위 제재 사건을 조사·승인·집행·해제한다. 제재 대상은 항상 단일 `storeId`이며 같은 대표자의 다른 매장으로 전파하지 않는다.

구현 범위는 다음과 같다.

1. 플랫폼 관점 매장 목록·상세 최소 조회
2. 제재 사건과 거래 영향 미리보기
3. 경고, 기능 제한, 기간 운영 중지, 영구 퇴점
4. 상태 version 기반 동시 승인·집행·해제 제어
5. 매장 단위 권한·cache·session 효과의 즉시 무효화
6. `#282` 불변 감사 원장 연동

다음은 범위 밖이다.

- 기존 확정 예약·대기·픽업·결제를 자동 취소하거나 환불하는 행위
- 대표자 계정 전체 정지 또는 같은 대표자의 다른 매장 제재
- 매장 운영자용 관리 endpoint 호출·위임
- `membersupport/**` 또는 PR `#353` 전용 파일 수정

## 2. 현재 계약 점검과 경계

현재 Store는 공개 운영 상태(`StoreOperationStatus`)와 거래 모드, 행 잠금 조회(`findByIdForUpdate`), 신규 거래 적격성 검사(`StoreTransactionEligibilityService`)를 제공한다. 다만 플랫폼 운영자 조회·제재를 위한 안정된 공개 DTO/Service 계약과 거래 도메인별 영향 조회 계약은 없다.

누락 계약은 각 원 도메인이 소유하는 DTO와 Service port로 추가한다.

- `platformoperator`는 Store/Reservation/Waiting/Pickup/Payment의 Entity 또는 Repository를 직접 참조하지 않는다.
- Store가 `StoreAdministrationService`와 Store 관리 projection DTO를 소유한다.
- Reservation, Waiting, Pickup, Payment가 각각 store 단위 영향 DTO와 query service를 소유한다.
- 플랫폼 제재 aggregate와 미리보기, 승인 정보는 `platformoperator/adminstore`가 소유한다.
- 기존 검색·거래 차단 경로가 보는 Store 정본 운영 상태와 기능 mode를 Store port가 변경한다.

## 3. 제재 모델

### 3.1 제재 유형

| 유형 | 의미 | Store 정본 영향 | 고위험 |
|---|---|---|---|
| `WARNING` | 위반 기록과 경고 통지 | 없음 | 아니오 |
| `FEATURE_RESTRICTION` | 선택 기능 제한 | 지정 mode 비활성화 | 조건부 |
| `TEMPORARY_SUSPENSION` | 시작·종료 기간 운영 중지 | 운영 상태 중지 | 예 |
| `PERMANENT_EXIT` | 영구 퇴점 | 폐점 상태 | 예 |

기능 제한 대상은 `RESERVATION`, `WAITING`, `MENU_HOLD`, `PICKUP`, `STORE_MANAGEMENT`다. 모든 기능을 동시에 제한하는 기능 제한은 고위험으로 취급한다.

제재 상태는 `PENDING_APPROVAL`, `ACTIVE`, `RELEASED`, `EXPIRED`, `REJECTED`다. 경고는 승인 즉시 `ACTIVE`가 되며 Store 정본을 바꾸지 않는다. 기간 운영 중지는 종료 시각 이후 만료 작업이 Store 잠금과 version 조건을 다시 확인한 뒤 `EXPIRED`로 전이한다.

### 3.2 Store별 독립 상태

`StoreEnforcementState`는 한 Store에 하나만 존재하며 다음을 보존한다.

- 제재 전 운영 상태와 거래 mode snapshot
- 현재 유효 제재의 합성 결과
- 단조 증가하는 `enforcementVersion`
- 마지막 변경 시각

각 제재는 매장 ID, 위반 유형, 근거 참조 목록, 영향 기능, 시작·종료 조건, `policyVersion`을 보존한다. `StoreSanctionPolicyCatalog`는 위반 유형별 증거 요건, 기본 단계, 가중·감경 조건과 허용 기간을 version별 정본으로 제공한다. 요청자가 임의 기간을 만들 수 없고 요청 shape가 선택한 정책 version과 정확히 일치해야 한다.

모든 계산과 복구는 `storeId`만을 기준으로 한다. 대표자 ID는 조회 응답의 최소 식별 정보일 뿐 제재 적용 key로 사용하지 않는다. 겹친 제재가 있으면 가장 강한 유효 결과를 적용하고, 하나를 해제할 때 남은 제재를 다시 합성하므로 다른 유효 제재를 우회하지 않는다.

### 3.3 동시성

제재 aggregate의 `sanctionVersion`과 Store의 `enforcementVersion`을 모두 사용한다.

명령 트랜잭션의 순서는 다음으로 고정한다.

1. idempotency와 운영자 권한·담당 사건·재인증을 검증한다.
2. Store port가 Store와 enforcement state를 잠근다.
3. 제재와 승인 상태를 잠그고 요청 version을 비교한다.
4. 고위험 명령은 거래 영향을 다시 조회해 preview digest와 Store version을 비교한다.
5. 조건부 상태 전이와 Store 정본 변경을 수행한다.
6. 같은 트랜잭션에서 불변 감사 이벤트를 append한다.

동일 version에 대한 동시 승인·해제·만료 중 정확히 하나만 성공한다. 패자는 `409 ADMIN_STORE_STATE_CONFLICT`를 받는다.

## 4. 거래 영향 미리보기와 승인

미리보기는 다음 정보를 Store 단위로 집계한다.

- 미래 시점의 확정 예약 수
- 활성 대기 팀 수
- 확정 픽업 수
- 미종결 결제·환불 조정 수
- Store `enforcementVersion`
- 요청한 제재 유형·기능·기간
- 정규화된 영향 digest와 만료 시각(발급 후 10분)

고위험 제재 생성·승인·집행은 `previewId`, `previewDigest`, `storeEnforcementVersion`을 요구한다. 만료, digest 불일치, version 변경, 재조회 결과 변경 중 하나라도 있으면 `409 ADMIN_STORE_IMPACT_STALE`로 거부한다.

`PERMANENT_EXIT`와 전체 기능 제한은 제안자와 다른 `SUPER_ADMIN`의 승인을 요구한다. 기간 운영 중지는 `STORE_SANCTION` 권한, 사건 배정, `STORE_SANCTION` 목적 재인증과 유효 영향 확인을 요구한다. 승인자는 자신의 제안을 승인할 수 없다. 영구 퇴점 승인은 Store 원장을 직접 편집하지 않고 승인된 ADMIN-007 사건 ID·위반 유형·`policyVersion`을 Store 소유 조건부 폐점 명령에 불변으로 결속한다.

제재 집행은 기존 확정 거래를 변경하지 않는다. 영향 목록은 운영 후속 조치 판단 자료이며 취소·환불 API를 호출하지 않는다.

## 5. 권한, cache, session 효과

- 목록·상세·영향 미리보기: `STORE_READ_MINIMAL`
- 제재 생성·승인·해제: `STORE_SANCTION`
- 고위험 명령: 기존 `HighRiskCommandGuard`, `AdminCommandPurpose.STORE_SANCTION`, `AdminTargetType.STORE`
- 영구 퇴점·고위험 기능 제한 승인: 제안자와 다른 `SUPER_ADMIN`

Store 운영자 refresh token은 계정 단위이므로 제재 시 계정 전체 revoke를 하지 않는다. 계정 전체 revoke는 같은 대표자의 다른 Store session까지 끊어 제재 전파 금지 조건을 위반한다. 대신 모든 Store scoped 관리 권한과 신규 거래 적격성은 Store 정본 상태와 `enforcementVersion`을 확인한다. 현재 코드에는 Store scoped application cache가 없으므로 새 API도 Store 결과를 cache하지 않는다. 이후 cache가 추가되더라도 key에 `storeId`와 `enforcementVersion`을 포함해야 한다. 중앙 `StoreEnforcementState`의 영속 version과 변경 감사가 권한 회수·session/cache 무효화 의도를 같은 트랜잭션에 기록하며, 모든 인스턴스가 요청마다 이를 검사한다. 이 동작이 Store 단위 session 권한 무효화 계약이다.

## 6. 감사 원장

`#282`의 `platform_operator_audit_events` append-only 원장과 DB update/delete 차단 trigger를 그대로 사용한다. 제재 동작은 다음 action을 추가한다.

- `STORE_IMPACT_PREVIEWED`
- `STORE_SANCTION_PROPOSED`
- `STORE_SANCTION_APPROVED`
- `STORE_SANCTION_APPLIED`
- `STORE_SANCTION_RELEASED`
- `STORE_SANCTION_EXPIRED`
- `STORE_SANCTION_REJECTED`

감사 이벤트에는 actor, case, reason, target Store, sanction ID, idempotency key, 결과, 이전·이후 제재 상태, 이전·이후 Store 운영 snapshot, 두 version을 기록한다. 변경 이벤트와 감사 append는 같은 트랜잭션에 속한다.

## 7. HTTP 계약

| Method | Path | 목적 |
|---|---|---|
| `GET` | `/api/v1/platform-operators/stores` | 최소 목록 조회 |
| `GET` | `/api/v1/platform-operators/stores/{storeId}` | 최소 상세와 활성 제재 조회 |
| `POST` | `/api/v1/platform-operators/stores/{storeId}/sanction-impact-previews` | 거래 영향 미리보기 생성 |
| `POST` | `/api/v1/platform-operators/stores/{storeId}/sanctions` | 제재 제안 또는 저위험 즉시 적용 |
| `POST` | `/api/v1/platform-operators/stores/{storeId}/sanctions/{sanctionId}/approvals` | 고위험 제재 승인·집행 |
| `POST` | `/api/v1/platform-operators/stores/{storeId}/sanctions/{sanctionId}/releases` | 활성 제재 해제 |

명령은 `Idempotency-Key`, `X-Admin-Case-Id`, `X-Admin-Case-Version`을 요구한다. 고위험 명령은 기존 재인증 header 계약을 추가로 요구한다. 상세 wire contract는 같은 디렉터리의 OpenAPI가 정본이다.

신규 오류 code는 `AdminStoreErrorCode`에 격리한다.

- `ADMIN_STORE_001`: Store 없음
- `ADMIN_STORE_002`: 제재/미리보기 없음
- `ADMIN_STORE_003`: 영향 확인 필요
- `ADMIN_STORE_004`: 영향 미리보기 만료 또는 불일치
- `ADMIN_STORE_005`: 제재 상태/version 충돌
- `ADMIN_STORE_006`: 다른 2차 승인자 필요
- `ADMIN_STORE_007`: 허용되지 않은 기능·기간·상태 조합

## 8. migration 선택 규칙

PR `#353`이 `V44__create_member_support.sql`을 사용하므로 이 작업의 migration 번호를 지금 고정하지 않는다. 구현 migration을 만들기 직전에 다음을 수행한다.

1. 최신 `origin/dev`와 PR `#353` 병합 여부를 다시 조회한다.
2. 병합된 dev의 최대 번호와 열린 PR에서 이미 예약한 번호를 비교한다.
3. 충돌하지 않는 다음 번호 하나를 선택해 `V{N}__create_store_sanctions.sql`로 만든다.

따라서 migration은 아래 allowlist의 동적 단일 항목이며, 위 gate를 통과하기 전에는 파일을 생성하지 않는다.

## 9. 정확한 변경 파일 allowlist

아래 파일만 추가·수정한다. 구현 중 새로운 경로가 필요하면 코드를 건드리기 전에 이 활성 정본을 먼저 수정하고 재승인받는다.

### 계약과 통합 OpenAPI

- `docs/specs/admin-store/spec.md`
- `docs/specs/admin-store/openapi.yaml`
- `docs/specs/README.md`
- `docs/specs/platform-operator-openapi.yaml`
- `redocly.yaml`

### Platform operator admin-store

- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/controller/PlatformOperatorStoreController.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/dto/AdminStoreRequests.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/dto/AdminStoreResponses.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/entity/StoreSanction.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/entity/StoreSanctionApproval.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/entity/StoreSanctionImpactPreview.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/entity/StoreSanctionEnums.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/model/StoreSanctionPolicyCatalog.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/repository/StoreSanctionRepository.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/repository/StoreSanctionApprovalRepository.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/repository/StoreSanctionImpactPreviewRepository.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/service/AdminStoreQueryService.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/service/StoreSanctionImpactService.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/service/StoreSanctionCommandService.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/service/StoreSanctionExpiryService.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/service/StoreSanctionFingerprint.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/exception/AdminStoreErrorCode.java`

### Store-owned public port와 enforcement

- `backend/src/main/java/com/miriyum/domain/store/dto/administration/StoreAdministrationContracts.java`
- `backend/src/main/java/com/miriyum/domain/store/entity/Store.java`
- `backend/src/main/java/com/miriyum/domain/store/entity/StoreEnforcementState.java`
- `backend/src/main/java/com/miriyum/domain/store/repository/StoreRepository.java`
- `backend/src/main/java/com/miriyum/domain/store/repository/StoreEnforcementStateRepository.java`
- `backend/src/main/java/com/miriyum/domain/store/service/StoreAdministrationService.java`
- `backend/src/main/java/com/miriyum/domain/store/service/StoreService.java`
- `backend/src/main/java/com/miriyum/domain/store/service/StoreTransactionEligibilityService.java`
- `backend/src/main/java/com/miriyum/domain/store/error/StoreErrorCode.java`

### 거래 도메인 영향 port

- `backend/src/main/java/com/miriyum/domain/reservation/dto/StoreReservationImpact.java`
- `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationRepository.java`
- `backend/src/main/java/com/miriyum/domain/reservation/service/StoreReservationImpactQueryService.java`
- `backend/src/main/java/com/miriyum/domain/reservation/waiting/dto/StoreWaitingImpact.java`
- `backend/src/main/java/com/miriyum/domain/reservation/waiting/service/StoreServiceWaitingAuthorityAdapter.java`
- `backend/src/main/java/com/miriyum/domain/reservation/waiting/service/StoreWaitingImpactQueryService.java`
- `backend/src/main/java/com/miriyum/domain/pickup/dto/StorePickupImpact.java`
- `backend/src/main/java/com/miriyum/domain/pickup/repository/PickupReservationRepository.java`
- `backend/src/main/java/com/miriyum/domain/pickup/service/StorePickupImpactQueryService.java`
- `backend/src/main/java/com/miriyum/domain/payment/dto/StorePaymentImpact.java`
- `backend/src/main/java/com/miriyum/domain/payment/repository/PaymentRepository.java`
- `backend/src/main/java/com/miriyum/domain/payment/service/StorePaymentImpactQueryService.java`

### 불변 감사 원장 확장

- `backend/src/main/java/com/miriyum/domain/platformoperator/entity/PlatformOperatorAuditEvent.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/enums/PlatformOperatorAuditAction.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/enums/PlatformOperatorAuditReason.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/service/PlatformOperatorAuditWriter.java`

### DB migration

- `backend/src/main/resources/db/migration/V{implementation-gate-selected}__create_store_sanctions.sql` (정확히 1개)

### 테스트

- `backend/src/test/java/com/miriyum/domain/platformoperator/adminstore/controller/PlatformOperatorStoreControllerTest.java`
- `backend/src/test/java/com/miriyum/domain/platformoperator/adminstore/service/AdminStoreQueryServiceTest.java`
- `backend/src/test/java/com/miriyum/domain/platformoperator/adminstore/service/StoreSanctionImpactServiceTest.java`
- `backend/src/test/java/com/miriyum/domain/platformoperator/adminstore/service/StoreSanctionCommandServiceTest.java`
- `backend/src/test/java/com/miriyum/domain/platformoperator/adminstore/model/StoreSanctionPolicyCatalogTest.java`
- `backend/src/test/java/com/miriyum/domain/platformoperator/adminstore/service/StoreSanctionConcurrencyIT.java`
- `backend/src/test/java/com/miriyum/domain/platformoperator/adminstore/service/AdminStoreHttpIT.java`
- `backend/src/test/java/com/miriyum/domain/platformoperator/adminstore/repository/StoreSanctionMigrationIT.java`
- `backend/src/test/java/com/miriyum/domain/store/service/StoreAdministrationServiceIT.java`
- `backend/src/test/java/com/miriyum/domain/store/service/StoreTransactionEligibilityServiceTest.java`
- `backend/src/test/java/com/miriyum/domain/reservation/service/StoreReservationImpactQueryServiceTest.java`
- `backend/src/test/java/com/miriyum/domain/reservation/waiting/service/StoreWaitingImpactQueryServiceTest.java`
- `backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingLedgerServiceTest.java`
- `backend/src/test/java/com/miriyum/domain/pickup/service/StorePickupImpactQueryServiceTest.java`
- `backend/src/test/java/com/miriyum/domain/payment/service/StorePaymentImpactQueryServiceTest.java`
- `backend/src/test/java/com/miriyum/domain/platformoperator/service/PlatformOperatorAuditWriterTest.java`
- `backend/src/test/java/com/miriyum/domain/platformoperator/PlatformOperatorOpenApiContractTest.java`
- `backend/src/test/java/com/miriyum/architecture/DomainPackageArchitectureTest.java`

## 10. PR #353 예상 충돌

`membersupport/**`와 그 전용 파일은 수정하지 않는다. 현재 diff 기준으로 양쪽이 수정할 가능성이 있는 파일은 다음 네 개다.

- `docs/specs/README.md`
- `docs/specs/platform-operator-openapi.yaml`
- `redocly.yaml`
- `backend/src/test/java/com/miriyum/architecture/PlatformOperatorOpenApiContractTest.java`

공통 enum은 dev에 필요한 `STORE_READ_MINIMAL`, `STORE_SANCTION`, `ENFORCEMENT_OPERATOR`, `STORE_SANCTION` purpose가 이미 있으므로 변경하지 않는다. #353 병합 후 최신 dev를 반영할 때 위 네 파일은 양쪽 feature ref와 path assertion을 모두 보존해 수동 정리한다. migration 번호는 8절의 gate에서 재선택한다.

## 11. 테스트와 완료 기준

구현은 테스트 우선으로 진행한다. 로컬에서는 다음 최소 범위만 실행한다.

- 변경 서비스별 unit test class
- MySQL 기반 `StoreSanctionConcurrencyIT`, `StoreSanctionMigrationIT`
- 권한·영향·HTTP 관련 `AdminStoreHttpIT` 및 필요한 영향 query test class
- `PlatformOperatorOpenApiContractTest`, Redocly admin-store lint
- 변경으로 직접 영향을 받는 integration class 또는 단일 shard만 추가 실행

로컬 `build`, `check`, 전체 `integrationTest`, integration A~D 전체 조합은 실행하지 않는다. 전체 회귀는 Draft PR의 GitHub CI를 정본으로 확인하며 아직 끝나지 않았다면 완료가 아니라 pending으로 보고한다.

완료 조건은 기능·경합·권한·영향·HTTP·OpenAPI의 대상 테스트 통과, allowlist 준수, `membersupport/**` 무변경, dev 직접 push 없음, feature branch push와 dev 대상 Draft PR 생성이다.
