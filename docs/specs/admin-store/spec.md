# Admin Store 운영·제재 활성 정본

- 상태: `ACTIVE`
- 기준 이슈: `#279`
- 상위 정책: `ADMIN-007`, `OPER-009`, `OPER-010`
- 기준 브랜치: `origin/dev` (`#275`, `#276`, `#278`, `#282` 병합 후)
- 기준점: `f5c55abd` (PR `#353` 병합 commit)
- 계약: `docs/specs/admin-store/openapi.yaml`
- 확정일: 2026-08-16

## 1. 목적과 범위

플랫폼 운영자가 매장 운영자용 일상 관리 API를 재사용하지 않고 Store를 최소 조회하고, Store 단위 제재 사건을 생성·배정·조사·집행·해제한다. 제재 key는 항상 단일 `storeId`이며 같은 대표자의 다른 Store로 전파하지 않는다.

포함 범위는 다음과 같다.

1. 플랫폼 관점 Store 목록·상세 최소 조회
2. Store 제재 사건 생성·자기 배정·상세 조회
3. 사건에 결속된 거래 영향 미리보기
4. 경고·기능 제한·기간 운영 중지·영구 퇴점
5. 사건·제재·Store version 기반 동시 전이 제어
6. Store 단위 권한·cache·session 효과 무효화
7. `#282` 불변 감사 원장 연동

다음은 범위 밖이다.

- 기존 확정 예약·대기·픽업·결제를 자동 취소·환불하거나 상태를 직접 덮어쓰기
- 대표자 계정 전체 정지 또는 다른 Store 제재
- 매장 운영자용 controller/endpoint 호출·위임
- `membersupport/**` Entity·Repository·Service 수정 또는 재사용
- 프론트엔드 관리 화면

## 2. 현재 계약 대조와 소유 경계

현재 dev에는 다음 공통 계약이 준비되어 있다.

- 플랫폼 운영자 인증·중앙 session·RBAC: `#275`, `#276`
- 공통 사건 배정: `AdminCaseAssignmentManager`, `AdminCaseAssignmentVerifier`, `AdminCaseType.STORE_ENFORCEMENT`
- Store 권한: `STORE_READ_MINIMAL`, `STORE_SANCTION`, 역할 `ENFORCEMENT_OPERATOR`
- 고위험 명령: `HighRiskCommandGuard`, `AdminCommandPurpose.STORE_SANCTION`, `AdminTargetType.STORE`
- 불변 감사 원장: `#282`의 append-only `platform_operator_audit_events`
- 회원지원: `#278`의 `membersupport` bounded context

Store는 공개 운영 상태 `OperationStatus`, 거래 mode, 행 잠금 조회와 신규 Reservation/Menu/Pickup 적격성 검사를 갖지만 플랫폼 관리 projection과 Store 단위 enforcement version은 없었다. Reservation·Waiting·Pickup·Payment도 플랫폼용 영향 query port가 없었다.

누락 계약은 원 도메인 소유 DTO·Service port로만 제공한다.

- `platformoperator/adminstore`는 Store/Reservation/Waiting/Pickup/Payment Entity·Repository를 직접 참조하지 않는다.
- Store가 `StoreAdministrationService`, Store 관리 projection, 조건부 enforcement와 신규 거래 적격성 port를 소유한다.
- Reservation·Waiting·Pickup·Payment가 각각 Store 영향 DTO와 읽기 전용 query service를 소유한다.
- 다른 도메인 변경은 admin-store orchestration보다 먼저 독립 테스트되는 contract-first commit으로 분리한다.
- `#278`의 회원 사건 aggregate는 계정 지원 전용이다. Store 제재 사건은 `StoreSanctionCase`가 독립 소유하고 공통 assignment port만 재사용한다.

Waiting 기능 제한은 신규 `WaitingCreationService` 거래 생성 경계에서만 검사한다. 이미 확정된 Waiting의 조회·호출·도착·종결 경로는 계속 사용할 수 있어야 한다.

## 3. 사건과 제재 모델

### 3.1 Store 제재 사건

`StoreSanctionCase`는 다음 값을 보존한다.

- UUID public case ID와 단일 `storeId`
- 위반 유형, 증거 참조 목록, `policyVersion`
- `SUBMITTED`, `ASSIGNED`, `PENDING_APPROVAL`, `ACTIVE`, `RESOLVED`, `REJECTED` 상태
- 단조 증가 `caseVersion`, 생성자·담당자·생성/변경 시각

사건 생성은 `STORE_SANCTION` 권한과 멱등 key를 요구한다. 생성자는 자동 담당자가 아니다. 자기 배정은 사건을 잠그고 `SUBMITTED -> ASSIGNED`로 한 번만 전이한 다음 같은 트랜잭션에서 다음 공통 계약을 호출한다.

```text
AdminCaseAssignmentManager.assign(
  STORE_ENFORCEMENT, publicCaseId, caseVersion, operatorId, expiresAt)
```

사건 상세·미리보기·제재 명령은 path의 `caseId`, `X-Admin-Case-Id`, `X-Admin-Case-Version`, 사건 현재 version과 공통 assignment verifier가 모두 일치해야 한다.

### 3.2 제재 유형과 정책

| 유형 | Store 정본 영향 | 고위험 | 추가 승인 |
|---|---|---|---|
| `WARNING` | 없음 | 아니오 | 없음 |
| `FEATURE_RESTRICTION` | 지정 기능만 차단 | 전체 기능 제한일 때 | 전체 기능이면 다른 `SUPER_ADMIN` |
| `TEMPORARY_SUSPENSION` | 기간 중 신규 거래·관리 중지 | 예 | 없음 |
| `PERMANENT_EXIT` | 조건부 폐점 | 예 | 다른 `SUPER_ADMIN` |

기능은 `RESERVATION`, `WAITING`, `MENU_HOLD`, `PICKUP`, `STORE_MANAGEMENT`다. 각 사건은 위반 유형·증거 참조·영향 기능·시작/종료 조건·`policyVersion`을 가진다. `StoreSanctionPolicyCatalog`가 허용 위반 유형, 증거 요건, 기본 단계, 가중·감경 조건과 기간을 version별로 검증하므로 담당자가 임의 기간을 만들 수 없다.

제재 상태는 `PENDING_APPROVAL`, `ACTIVE`, `RELEASED`, `EXPIRED`, `REJECTED`다. 임시 제재는 영구 퇴점으로 자동 승격하지 않는다.

### 3.3 Store별 enforcement

`StoreEnforcementState`는 Store당 하나이며 제재 전 운영 상태·mode snapshot, 합성된 현재 효과, `enforcementVersion`과 마지막 sanction ID를 보존한다. 모든 적용·복구는 `storeId`만으로 계산한다. 겹친 제재 해제 시 남은 활성 제재를 다시 합성한다.

Store 운영자 refresh token은 계정 단위이므로 계정 전체 revoke를 하지 않는다. 모든 Store scoped 관리 권한과 신규 거래는 Store 정본과 중앙 enforcement 상태를 요청마다 검사한다. 현재 Store application cache는 없으므로 새 API도 cache하지 않는다. 향후 cache key는 `storeId + enforcementVersion`이어야 한다.

### 3.4 동시성 순서

명령은 다음 잠금 순서를 사용한다.

1. 운영자 권한·담당 사건·재인증·멱등성을 검증한다.
2. Store port가 Store와 enforcement state를 잠근다.
3. 사건과 제재를 잠그고 `caseVersion`, `sanctionVersion`, `enforcementVersion`을 비교한다.
4. 고위험 명령은 거래 영향을 다시 조회해 preview digest를 비교한다.
5. 조건부 상태 전이와 Store 정본 변경을 수행한다.
6. 같은 트랜잭션에서 불변 감사 이벤트를 append한다.

동일 version의 동시 승인·해제·만료 중 하나만 성공하고 패자는 `409 ADMIN_STORE_005`를 받는다.

## 4. 거래 영향 미리보기

미리보기는 사건과 요청 제재 shape에 결속하여 다음을 저장한다.

- 미래 확정 Reservation 수와 ID 집합
- 활성 Waiting 팀 수
- 미래 확정 Pickup 수
- 연결된 미종결 Payment·환불 조정 수
- `caseVersion`, `enforcementVersion`
- 제재 유형·기능·기간, SHA-256 digest, 10분 만료 시각

`TEMPORARY_SUSPENSION`, `PERMANENT_EXIT`, 전체 기능 제한은 `previewId`, digest와 두 version을 요구한다. 만료·digest 불일치·version 변경·재조회 결과 변경이면 `409 ADMIN_STORE_004`로 거부한다.

제재 집행 서비스는 Reservation·Waiting·Pickup·Payment command service에 의존하지 않는다. 기존 확정 거래는 영향 자료로만 표시하고 별도 원 도메인 절차 없이 변경하지 않는다.

## 5. 권한과 감사

- Store 목록·상세: `STORE_READ_MINIMAL`
- 사건 생성·배정·미리보기·제재·해제: `STORE_SANCTION`
- 상세 이후 명령: 유효 `STORE_ENFORCEMENT` assignment 필수
- 고위험 명령: `HighRiskCommandGuard`와 `STORE_SANCTION` 목적 재인증
- 영구 퇴점·전체 기능 제한: 제안자와 다른 `SUPER_ADMIN`

감사 action은 `STORE_CASE_CREATED`, `STORE_CASE_ASSIGNED`, `STORE_CASE_DETAIL_READ`, `STORE_IMPACT_PREVIEWED`, `STORE_SANCTION_PROPOSED`, `STORE_SANCTION_APPROVED`, `STORE_SANCTION_APPLIED`, `STORE_SANCTION_RELEASED`, `STORE_SANCTION_EXPIRED`, `STORE_SANCTION_REJECTED`다.

감사에는 actor, 공통 case, reason, Store/sanction ID, 멱등 key, 결과, 이전·이후 사건/제재/Store snapshot과 세 version을 기록한다. 상태 변경과 audit append는 같은 트랜잭션이다. `#282` update/delete 차단 trigger를 유지한다.

## 6. HTTP 계약

| Method | Path | 목적 |
|---|---|---|
| `GET` | `/api/v1/platform-operators/stores` | Store 최소 목록 |
| `GET` | `/api/v1/platform-operators/stores/{storeId}` | Store 최소 상세 |
| `POST` | `/api/v1/platform-operators/stores/{storeId}/sanction-cases` | 사건 생성 |
| `POST` | `/api/v1/platform-operators/stores/{storeId}/sanction-cases/{caseId}/assignments` | 사건 자기 배정 |
| `GET` | `/api/v1/platform-operators/stores/{storeId}/sanction-cases/{caseId}` | 사건·제재 상세 |
| `POST` | `/api/v1/platform-operators/stores/{storeId}/sanction-cases/{caseId}/impact-previews` | 영향 미리보기 |
| `POST` | `/api/v1/platform-operators/stores/{storeId}/sanction-cases/{caseId}/sanctions` | 제재 제안/적용 |
| `POST` | `/api/v1/platform-operators/stores/{storeId}/sanction-cases/{caseId}/sanctions/{sanctionId}/approvals` | 추가 승인/집행 |
| `POST` | `/api/v1/platform-operators/stores/{storeId}/sanction-cases/{caseId}/sanctions/{sanctionId}/releases` | 제재 해제 |

wire contract는 같은 디렉터리 OpenAPI가 정본이다. 오류 code는 `ADMIN_STORE_001` Store 없음, `002` 사건/제재/preview 없음, `003` 영향 확인 필요, `004` preview stale, `005` 상태/version 충돌, `006` 다른 승인자 필요, `007` 정책 shape 오류로 고정한다.

## 7. migration 선택 gate

현재 dev 최대 migration은 V46이지만 #279 번호를 미리 고정하지 않는다. migration 생성 직전에 최신 `origin/dev`를 fetch하고 모든 열린 PR의 migration 파일을 조회한다. 병합 dev와 열린 PR 어디에도 없는 다음 번호 하나를 선택해 `V{N}__create_store_sanctions.sql` 하나만 만든다.

## 8. 정확한 변경 파일 allowlist

아래 파일만 추가·수정한다. 새 경로가 필요하면 production code보다 먼저 이 정본을 수정한다.

### 계약

- `docs/specs/admin-store/spec.md`
- `docs/specs/admin-store/openapi.yaml`
- `docs/specs/README.md`
- `docs/specs/platform-operator-openapi.yaml`
- `redocly.yaml`

### admin-store

- `backend/src/main/java/com/miriyum/domain/platformoperator/controller/management/PlatformOperatorStoreController.java` (플랫폼 운영자 namespace와 management purpose HTTP boundary)
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/dto/AdminStoreRequests.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/dto/AdminStoreResponses.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/entity/StoreSanctionCase.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/entity/StoreSanction.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/entity/StoreSanctionApproval.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/entity/StoreSanctionImpactPreview.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/entity/StoreSanctionEnums.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/model/StoreSanctionPolicyCatalog.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/repository/StoreSanctionCaseRepository.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/repository/StoreSanctionRepository.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/repository/StoreSanctionApprovalRepository.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/repository/StoreSanctionImpactPreviewRepository.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/service/StoreSanctionCaseService.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/service/AdminStoreQueryService.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/service/StoreSanctionImpactService.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/service/StoreSanctionCommandService.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/service/StoreSanctionExpiryService.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/service/StoreSanctionFingerprint.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/exception/AdminStoreErrorCode.java`

### Store contract-first

- `backend/src/main/java/com/miriyum/domain/store/dto/administration/StoreAdministrationContracts.java`
- `backend/src/main/java/com/miriyum/domain/store/entity/Store.java`
- `backend/src/main/java/com/miriyum/domain/store/entity/StoreEnforcementState.java`
- `backend/src/main/java/com/miriyum/domain/store/repository/StoreRepository.java`
- `backend/src/main/java/com/miriyum/domain/store/repository/StoreEnforcementStateRepository.java`
- `backend/src/main/java/com/miriyum/domain/store/service/StoreAdministrationService.java`
- `backend/src/main/java/com/miriyum/domain/store/service/StoreService.java`
- `backend/src/main/java/com/miriyum/domain/store/service/StoreTransactionEligibilityService.java`
- `backend/src/main/java/com/miriyum/domain/store/error/StoreErrorCode.java`

### 거래 영향 contract-first

- `backend/src/main/java/com/miriyum/domain/reservation/dto/StoreReservationImpact.java`
- `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationRepository.java`
- `backend/src/main/java/com/miriyum/domain/reservation/service/StoreReservationImpactQueryService.java`
- `backend/src/main/java/com/miriyum/domain/reservation/waiting/dto/StoreWaitingImpact.java`
- `backend/src/main/java/com/miriyum/domain/reservation/waiting/service/StoreWaitingImpactQueryService.java`
- `backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingCreationService.java`
- `backend/src/main/java/com/miriyum/domain/pickup/dto/StorePickupImpact.java`
- `backend/src/main/java/com/miriyum/domain/pickup/repository/PickupReservationRepository.java`
- `backend/src/main/java/com/miriyum/domain/pickup/service/StorePickupImpactQueryService.java`
- `backend/src/main/java/com/miriyum/domain/payment/dto/StorePaymentImpact.java`
- `backend/src/main/java/com/miriyum/domain/payment/repository/PaymentRepository.java`
- `backend/src/main/java/com/miriyum/domain/payment/service/StorePaymentImpactQueryService.java`

### 감사와 DB

- `backend/src/main/java/com/miriyum/domain/platformoperator/entity/PlatformOperatorAuditEvent.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/enums/PlatformOperatorAuditAction.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/enums/PlatformOperatorAuditReason.java` (구조화된 `STORE_ENFORCEMENT` 사유)
- `backend/src/main/java/com/miriyum/domain/platformoperator/service/PlatformOperatorAuditWriter.java`
- `backend/src/main/resources/db/migration/V{implementation-gate-selected}__create_store_sanctions.sql` (정확히 1개)

### 테스트

- `backend/src/test/java/com/miriyum/domain/platformoperator/adminstore/controller/PlatformOperatorStoreControllerTest.java`
- `backend/src/test/java/com/miriyum/domain/platformoperator/adminstore/service/StoreSanctionCaseServiceTest.java`
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
- `backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingCreationServiceTest.java`
- `backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingCreationFailureClassifierTest.java` (생성자 fixture의 Store 거래 자격 port 주입)
- `backend/src/test/java/com/miriyum/domain/store/service/StoreServiceTest.java` (생성자 fixture의 Store 제재 판정 port 주입)
- `backend/src/test/java/com/miriyum/domain/pickup/service/StorePickupImpactQueryServiceTest.java`
- `backend/src/test/java/com/miriyum/domain/payment/service/StorePaymentImpactQueryServiceTest.java`
- `backend/src/test/java/com/miriyum/domain/platformoperator/service/PlatformOperatorAuditWriterTest.java`
- `backend/src/test/java/com/miriyum/domain/platformoperator/PlatformOperatorOpenApiContractTest.java`
- `backend/src/test/java/com/miriyum/architecture/DomainPackageArchitectureTest.java`

## 9. #278 병합 후 통합과 검증

PR `#353`은 dev에 병합됐고 기존 #279 세 커밋은 최신 dev 위로 충돌 없이 재배치됐다. 통합 OpenAPI에는 회원지원과 admin-store path를 모두 보존한다. 공통 enum은 필요한 Store 권한·role·purpose·case type이 이미 있어 수정하지 않는다. `membersupport/**`는 변경하지 않는다.

로컬에서는 서비스별 unit test, MySQL `StoreSanctionConcurrencyIT`·`StoreSanctionMigrationIT`, 영향받는 HTTP/integration class, `PlatformOperatorOpenApiContractTest`, Redocly admin-store/통합 lint만 실행한다. `build`, `check`, 전체 `integrationTest`, integration A~D 전체 조합은 실행하지 않는다. 전체 회귀는 Draft PR GitHub CI를 정본으로 확인한다.

완료 조건은 대상 기능·경합·권한·영향·HTTP·OpenAPI 검증, allowlist 준수, `membersupport/**` 무변경, feature branch push와 dev 대상 Draft PR 생성이다. dev에는 직접 push하지 않는다.
