# 기능 명세: 회원 조회·계정 복구·제재 사건 관리

> 문서 상태: 구현 승인 기준
> 적용 단계: 고도화
> 소유 도메인: platformoperator
> 관련 정책 ID: AUTH-006, AUTH-010, AUTH-011, ADMIN-007, ADMIN-012
> 소유 Issue: #278
> 선행 구현: #275, #276
> OpenAPI: `docs/specs/member-support/openapi.yaml`
> Audience: `docs/specs/platform-operator-openapi.yaml`
> 최종 승인일: 2026-08-14

## 결과와 경계

이 기능은 플랫폼 운영자가 소비자와 식당 운영자 계정을 최소 정보로 조회하고, 이메일 접근 불가 복구·계정 제재·이의 사건을 배정하고 결정하도록 한다. #275의 플랫폼 운영자 인증과 #276의 권한·사건 배정·현재 비밀번호 재인증·고위험 명령 guard를 그대로 사용한다.

`platformoperator`는 사건, 배정, 결정, 추가 승인과 감사 원장을 소유한다. `consumer`와 `storeoperator`는 자기 계정의 최소 조회 DTO와 이메일 교체·로그인 차단·세션 폐기·제재 적용 public service를 소유한다. 어떤 도메인도 다른 도메인의 Entity 또는 Repository를 직접 참조하지 않는다.

1차 구현은 외부 개인인증 사업자를 연결하지 않는다. 사용자가 확인 버튼을 누르면 서버 내부 mock verifier가 목적·계정 유형·계정·새 이메일에 결속된 성공 증거를 만든다. 브라우저에는 opaque 값을 `HttpOnly`, `Secure`, 기능 전용 `Path`, `SameSite=Strict` 쿠키로만 전달하고 DB에는 digest·결속값·만료·소비 시각만 저장한다. OTP, 비밀번호, 연락처 원문, 인증 비밀은 응답·로그·감사에 남기지 않는다.

## 활성화와 선행 조건

- #275와 #276이 `dev`에 병합된 경우에만 활성화한다.
- `miriyum.platform-operator.enabled=true`와 `miriyum.member-support.enabled=true`가 모두 참일 때 Controller bean을 생성한다. OFF에서는 실제 404를 반환한다.
- mock verifier는 `miriyum.identity-verification.dev-stub-enabled=true`일 때만 성공 증거를 발급한다. 운영 환경에서 이 값이 false이면 확인과 상태 변경을 실패 폐쇄한다.
- 시간은 주입한 `Clock`으로 판정하고 업무 기간은 Asia/Seoul 기준으로 계산한다.

## 권한과 존재 은닉

| 명령 | 권한 | 추가 조건 |
| --- | --- | --- |
| 회원 최소 목록·상세 | `MEMBER_READ_MINIMAL` | 권한을 계정 조회보다 먼저 검사 |
| 복구 사건 배정·결정 | `MEMBER_RECOVERY` | 현재 배정, 사건 version, `MEMBER_RECOVERY` 재인증 |
| 경고·기능 제한·기간 정지 제안·적용 | `ACCOUNT_SANCTION` | 현재 배정, 사건 version, `ACCOUNT_SANCTION` 재인증 |
| 영구 정지 추가 승인 | `ACCOUNT_PERMANENT_SANCTION_APPROVE` | 제안자와 다른 `SUPER_ADMIN`, 별도 재인증 |
| 이의 배정·결정 | `ACCOUNT_APPEAL_REVIEW` | 현재 배정, 사건 version, `ACCOUNT_APPEAL_DECISION` 재인증 |

운영자 API는 권한을 먼저 검사하고 그 뒤 계정 유형별 public lookup service를 호출한다. 존재하지 않는 ID와 요청한 계정 유형에 속하지 않는 ID는 모두 `404 AUTH_016`으로 정규화한다. 권한 없는 요청은 조회 자체를 수행하지 않고 `403 ADMIN_001`을 반환하므로 다른 계정 유형의 동일 ID 존재 여부, 이메일, 전화번호, 제재 상세를 추론할 수 없다.

사용자 복구·이의 접수 API는 계정 없음, 유형 불일치, 등록 정보 불일치, 증거 없음·만료·재사용을 모두 같은 `202 Accepted` envelope로 반환한다. 복구 확인 요청에는 유효 여부와 무관하게 같은 이름·속성·길이의 opaque 쿠키를 발급하고 유효한 요청의 digest만 저장한다. 유효한 요청에만 내부 사건을 생성하며 응답·헤더로 검증 성공, 생성 여부나 사건 ID를 공개하지 않는다.

## 회원 최소 조회

목록은 `accountType`, 계산된 `status`, `joinedFrom`, `joinedTo` 필터와 `joinedAt`·공개 ID 정렬만 지원한다. 목록·상세 응답은 다음 필드만 포함한다.

- `accountType`: `CONSUMER` 또는 `STORE_OPERATOR`
- `accountId`: 문자열 public ID
- `status`: `ACTIVE`, `PASSWORD_RESET_REQUIRED`, `FEATURE_RESTRICTED`, `TEMPORARILY_SUSPENDED`, `PERMANENTLY_SUSPENDED`
- `joinedAt`
- 활성 제재 요약: 단계, 제한 기능 묶음, 종료 시각

이메일, 휴대전화, 비밀번호 hash, OAuth 식별자, 매장 사업자 정보, 주문·결제·이용 이력은 반환하지 않는다.

## 이메일 접근 불가 수동 복구

1. 소비자 또는 식당 운영자는 자기 계정 유형 namespace에서 mock 확인을 요청한다. 소비자는 기존 등록 이메일, 등록 휴대전화, 새 이메일을 제출한다. 식당 운영자는 이에 더해 관리 중인 매장 한 곳의 사업자등록번호와 대표자명을 제출한다.
2. mock verifier는 원문을 응답·로그·감사에 남기지 않고 모든 요청에 같은 형태의 목적 결속 증거 쿠키를 발급한다. 조건이 맞을 때에만 서버가 digest와 암호화된 새 이메일을 저장한다.
3. 사용자가 사건 접수를 요청하면 유효한 증거를 한 번 소비하고 `SUBMITTED` 사건을 만든다. 겉으로는 항상 같은 202를 반환한다.
4. `MEMBER_RECOVERY` 운영자가 사건을 자기에게 배정하고 승인 또는 거절한다.
5. 승인은 한 MySQL transaction에서 새 이메일 교체, 모든 refresh state 폐기, `password_reset_required=true`, `support_version` 증가, 사건 종결, 고위험 감사 기록을 확정한다. 이후 카카오를 포함한 모든 로그인은 새 비밀번호 설정 전까지 차단한다.
6. 사용자는 복구 쿠키로 새 비밀번호를 설정한다. 성공 시 다시 모든 세션을 폐기하고 `password_reset_required=false`로 바꾸되 현재 제재는 제거하지 않는다. 자동 로그인하지 않는다.

복구 상태는 `SUBMITTED -> ASSIGNED -> APPROVED | REJECTED`만 허용한다. 종결 사건의 재결정과 stale version은 `409 AUTH_017`이다.

## 제재

제재 단계와 고정 기본값은 다음과 같다.

| 단계 | 적용 | 종료 |
| --- | --- | --- |
| `WARNING` | 경고 이력만 기록 | 없음 |
| `FEATURE_RESTRICTION` | 선택한 기능 묶음 차단 | 적용 시점부터 7일 |
| `TEMPORARY_SUSPENSION` | 모든 로그인 차단, 모든 세션 폐기 | 적용 시점부터 30일 |
| `PERMANENT_SUSPENSION` | 모든 로그인 차단, 모든 세션 폐기 | 없음 |

기능 묶음은 `RESERVATION`, `WAITING`, `PICKUP`, `STORE_OPERATION`, `MENU_OPERATION` 중 하나 이상이다. 소비자와 식당 운영자에게 의미 없는 기능을 요청하면 400으로 거절한다. 기능 제한 중에는 인증과 읽기, 복구와 이의 접수는 허용하고 해당 쓰기 명령만 post-auth 정책 filter에서 거절한다.

경고·기능 제한·기간 정지는 한 명의 `ACCOUNT_SANCTION` 운영자가 적용한다. 영구 정지는 먼저 `PENDING_ADDITIONAL_APPROVAL`로 제안하고, 제안자와 다른 `SUPER_ADMIN`이 `ACCOUNT_PERMANENT_SANCTION_APPROVE` 권한과 별도 일회 재인증으로 승인해야 `APPLIED`가 된다. 같은 승인자, 중복 승인, 이미 종결된 제안은 `409 AUTH_018`이다. 승인 원장에는 제안자·추가 승인자 public operator ID, 사건·정책 version, 결정 코드와 시각만 저장한다.

만료 작업은 기능 제한·기간 정지를 여러 인스턴스에서 멱등하게 종료한다. 제재 원장은 삭제하거나 덮어쓰지 않는다.

## 이의 접수·배정·결정

사용자는 등록 휴대전화 또는 등록 이메일 중 하나에 대한 mock 확인 증거로 활성 제재 하나에 이의를 접수한다. 동일 사건에는 동시 이의 하나만 허용하고 접수 여부는 항상 같은 202로 감춘다. 이의 처리 중 기존 제재는 유지한다.

`ACCOUNT_APPEAL_REVIEW` 운영자가 사건을 자기에게 배정한 뒤 `UPHOLD`, `REDUCE`, `CANCEL` 중 하나를 결정한다. 감경은 허용된 더 낮은 단계와 그 단계의 고정 기간·기능 묶음만 선택한다. 취소·감경은 새 불변 제재 revision을 추가하고 현재 projection을 갱신한다. 영구 정지 결과를 유지하거나 변경하는 최종 결정은 `SUPER_ADMIN`만 할 수 있다.

이의 상태는 `SUBMITTED -> ASSIGNED -> UPHELD | REDUCED | CANCELLED`만 허용한다.

## 동시성과 단일 유효 전이

소비자·식당 운영자 계정은 각각 `support_version`을 가진다. 복구 승인, 제재 적용, 영구 정지 추가 승인과 이의 결정은 대상 계정의 현재 version을 비교·증가시키는 CAS와 사건 row lock을 같은 transaction에서 수행한다.

같은 계정에 복구 승인과 제재 적용이 동시에 도착하면 먼저 `support_version`을 증가시킨 명령 하나만 성공한다. 패자는 `409 AUTH_017`로 전체 rollback하며 사건 상태, 일회 재인증 소비, mock 증거 소비와 감사 행도 남기지 않는다. 클라이언트는 최신 계정·사건 version을 다시 읽은 뒤 명시적으로 재시도해야 한다.

## 감사와 데이터 최소화

고위험 명령은 #276의 `HighRiskCommandGuard.authorize()`가 반환한 `AdminAuditContext`와 업무 결과를 같은 transaction에서 append-only 감사 행으로 기록한다. 감사에는 다음만 저장한다.

- correlation ID, 사건·대상 유형과 public ID
- 명령 목적, 권한·역할 snapshot, 권한 version
- 사건 version, 결정 코드, 정책 version
- 제안자·승인자 public operator ID와 처리 시각

이메일·전화번호·사업자번호·대표자명·비밀번호·OTP·JWT·cookie·재인증 원문·mock 증거 원문은 애플리케이션 로그와 감사에 저장하지 않는다. 복구 감사는 사건 종결 후 3년 보존 대상으로 표시한다.

## HTTP와 오류 계약

정확한 path·request·response는 `openapi.yaml`이 소유한다. 모든 운영자 고위험 POST는 `X-Admin-Reauthentication`, `If-Match` 사건 또는 계정 version, `Idempotency-Key`를 요구한다.

| 상황 | HTTP | code |
| --- | --- | --- |
| 권한·배정·재인증 전제 불충족 | 403 | `ADMIN_001` |
| 운영자 세션·권한 version 무효 | 401 | `AUTH_015` |
| 계정 없음 또는 요청 유형과 불일치 | 404 | `AUTH_016` |
| stale version·종결 상태·동시 전이 패배 | 409 | `AUTH_017` |
| 영구 정지 자기 승인·중복 승인 | 409 | `AUTH_018` |
| 중앙 상태 저장소 장애 | 503 | `COMMON_012` |

## migration

신규 migration은 반드시 `backend/src/main/resources/db/migration/V42__create_member_support.sql` 하나다. 기존 migration을 수정하지 않는다. 복구·제재·이의·추가 승인·감사·mock 확인 session·대상 guard 원장을 만들고 `consumer_accounts`, `store_operator_accounts`에 `password_reset_required`, `support_version`을 추가한다. 연락처·인증 비밀 원문은 신규 테이블에 저장하지 않는다.

## 변경 파일 allowlist

구현 PR은 아래 경로만 변경할 수 있다. `/**`는 해당 신규 기능 전용 package 또는 테스트 package 전체를 뜻하며, 기존 도메인의 다른 파일은 포함하지 않는다.

- `backend/src/main/java/com/miriyum/domain/auth/exception/AuthErrorCode.java`
- `backend/src/main/java/com/miriyum/domain/auth/membersupport/**`
- `backend/src/main/java/com/miriyum/domain/consumer/entity/ConsumerAccount.java`
- `backend/src/main/java/com/miriyum/domain/consumer/service/ConsumerAccountService.java`
- `backend/src/main/java/com/miriyum/domain/consumer/service/ConsumerAuthService.java`
- `backend/src/main/java/com/miriyum/domain/consumer/service/ConsumerKakaoAuthService.java`
- `backend/src/main/java/com/miriyum/domain/consumer/membersupport/**`
- `backend/src/main/java/com/miriyum/domain/storeoperator/entity/StoreOperatorAccount.java`
- `backend/src/main/java/com/miriyum/domain/storeoperator/service/StoreOperatorAccountService.java`
- `backend/src/main/java/com/miriyum/domain/storeoperator/service/StoreOperatorAuthService.java`
- `backend/src/main/java/com/miriyum/domain/storeoperator/service/StoreOperatorKakaoAuthService.java`
- `backend/src/main/java/com/miriyum/domain/storeoperator/membersupport/**`
- `backend/src/main/java/com/miriyum/domain/platformoperator/controller/membersupport/**`
- `backend/src/main/java/com/miriyum/domain/platformoperator/config/membersupport/**`
- `backend/src/main/java/com/miriyum/domain/platformoperator/dto/membersupport/**`
- `backend/src/main/java/com/miriyum/domain/platformoperator/entity/membersupport/**`
- `backend/src/main/java/com/miriyum/domain/platformoperator/enums/AdminCommandPurpose.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/enums/PlatformOperatorPermission.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/enums/PlatformOperatorRole.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/exception/AdminAuthorizationErrorCode.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/repository/membersupport/**`
- `backend/src/main/java/com/miriyum/domain/platformoperator/service/membersupport/**`
- `backend/src/main/java/com/miriyum/global/security/SecurityConfig.java`
- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/db/migration/V42__create_member_support.sql`
- `backend/src/test/java/com/miriyum/domain/auth/membersupport/**`
- `backend/src/test/java/com/miriyum/domain/consumer/membersupport/**`
- `backend/src/test/java/com/miriyum/domain/storeoperator/membersupport/**`
- `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/**`
- `backend/src/test/java/com/miriyum/domain/platformoperator/config/membersupport/**`
- `backend/src/test/java/com/miriyum/domain/platformoperator/enums/PlatformOperatorRoleTest.java`
- `backend/src/test/java/com/miriyum/domain/platformoperator/PlatformOperatorAuthorizationOpenApiContractTest.java`
- `backend/src/test/java/com/miriyum/domain/platformoperator/PlatformOperatorOpenApiContractTest.java`
- `backend/src/test/java/com/miriyum/architecture/AudienceOpenApiContractTest.java`
- `backend/src/test/java/com/miriyum/architecture/ApiUrlConvention.java`
- `backend/src/test/java/com/miriyum/architecture/ApiUrlConventionTest.java`
- `backend/src/test/java/com/miriyum/architecture/ControllerOpenApiContractTest.java`
- `backend/src/test/java/com/miriyum/architecture/DomainPackageArchitectureTest.java`
- `backend/src/test/java/com/miriyum/architecture/HttpApiNamespaceContractTest.java`
- `backend/src/test/java/com/miriyum/architecture/OpenApiRouteInventory.java`
- `backend/src/test/java/com/miriyum/architecture/OpenApiRouteInventoryTest.java`
- `backend/src/test/java/com/miriyum/global/config/MemberSupportConfigurationTest.java`
- `backend/src/test/resources/application-test.yml`
- `docs/02-users-and-permissions.md`
- `docs/05-functional-requirements.md`
- `docs/07-data-and-api-contracts.md`
- `docs/09-quality-operations-and-rules.md`
- `docs/service-policies/01-member-auth.md`
- `docs/service-policies/15-admin-operation.md`
- `docs/specs/README.md`
- `docs/specs/consumer-openapi.yaml`
- `docs/specs/platform-operator-openapi.yaml`
- `docs/specs/store-operator-openapi.yaml`
- `docs/specs/platform-operator-authorization/openapi.yaml`
- `docs/specs/platform-operator-authorization/spec.md`
- `docs/specs/member-support/spec.md`
- `docs/specs/member-support/openapi.yaml`
- `docs/specs/member-support/implementation-plan.md`
- `redocly.yaml`

`docs/superpowers/**`, 기존 migration, 다른 도메인의 Entity·Repository 직접 참조, frontend, 배포 파일, dev 직접 push는 allowlist 밖이다. 구현 중 새 파일 필요가 발견되면 먼저 이 명세의 allowlist와 계획을 갱신하고 별도 검토를 받는다.

## 구현 계획

1. 계약·정책 테스트에서 OpenAPI path, 오류, 권한 catalog와 migration V42를 먼저 실패시킨다.
2. mock 확인 증거와 계정 유형별 public 최소 조회·복구·제재 port를 테스트 주도로 만든다.
3. MySQL migration과 복구·제재·이의 사건 원장, version CAS, 감사 writer를 구현한다.
4. 운영자 최소 조회·사건 배정·결정·영구 정지 추가 승인 HTTP를 순서대로 red-green-refactor 한다.
5. 사용자 복구·비밀번호 설정·이의 접수와 기능 제한 filter를 red-green-refactor 한다.
6. 권한 선검사, 존재 은닉, 일회 증거, 다른 승인자, 복구·제재 경합을 실제 MySQL·HTTP 통합 테스트로 검증한다.
7. OpenAPI lint, 단위·통합·전체 회귀, `git diff --check`를 통과시킨 후 커밋·push하고 `dev` 대상 Draft PR을 생성한다.

## 테스트와 인수 조건

- 계정 유형·계산 상태·가입 기간 필터가 최소 필드만 반환한다.
- 권한이 없으면 계정 lookup이 호출되지 않고, 존재하지 않는 ID와 다른 계정 유형 ID가 같은 외부 결과를 낸다.
- mock 확인 성공 응답·cookie·DB·로그·감사에 인증 비밀과 불필요한 개인정보가 없다.
- 복구 승인은 이메일 교체·전 세션 폐기·비밀번호 재설정 전 로그인 차단을 원자적으로 확정한다.
- 경고·기능 제한 7일·기간 정지 30일·영구 정지와 기능별 차단을 검증한다.
- 영구 정지는 제안자와 다른 슈퍼관리자의 추가 승인 없이는 적용되지 않는다.
- 이의 접수·배정·유지·감경·취소와 영구 정지 최종 슈퍼관리자 조건을 검증한다.
- 실제 MySQL에서 복구·제재 동시 요청 중 한 전이만 성공하고 패자의 재인증·감사·상태가 rollback된다.
- runtime HTTP path·status·error envelope가 OpenAPI와 일치하고 feature flag OFF에서 실제 404다.
- backend 전체 회귀와 OpenAPI strict lint가 통과한다.
