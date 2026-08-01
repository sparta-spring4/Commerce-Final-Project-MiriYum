# PR #67 Store Review Remediation Design

## Goal

PR #65가 병합되기 전에 발견됐지만 반영되지 못한 Store core 피드백 5건과
PR #67에 새로 등록된 미해결 리뷰 2건을 PR #67에서 함께 보완한다.

보완 후에는 일반 매장 수정 API로 폐점할 수 없고, 사업자등록번호는 폐점
이후에도 재사용할 수 없으며, 잘못된 매장 ID가 HTTP 경계에서 거절된다.
입점 신청은 신청자 자기확약과 필수 약관 동의 증거를 남기고, 일정 게시
권한과 멱등 재생 순서 및 일정 Entity 생성 방식은 중앙 계약과 팀
가드레일에 맞아야 한다.

## Scope

포함:

- 일반 매장 수정 API에서 `CLOSED` 전이 제거
- 사업자등록번호 영구 유일성 정본 문서 정렬
- Store core 및 Store schedule 경로의 양수 `storeId` 검증
- 입점 자기확약과 필수 약관 동의 요청·저장 계약
- 일정 게시용 중앙 권한 guard와 멱등 재생 순서 정리
- 일정 Entity 3종의 생성자·static factory 컨벤션 정렬
- 관련 단위·MockMvc·MySQL 통합 테스트와 전체 backend 검증

제외:

- OPER-009 폐점 명령 구현
- 폐점 전 영향 예약·결제·정산 조회
- 폐점 비가역 동의 및 폐점 감사 원장
- 사업자등록번호 귀속 이전·복구
- 외부 사업자 진위 확인과 플랫폼 운영자 심사
- GitHub 리뷰 댓글 작성 또는 리뷰 스레드 해결

OPER-009 폐점은 영향 거래 확인, 비가역 동의, 감사 기록의 소유 계약을
먼저 정의한 후 별도 Issue와 전용 명령으로 구현한다.

## Confirmed Decisions

### General update cannot close a store

`PATCH /api/v1/store-operator/stores/{storeId}`는 매장 정보와 가역적인 운영
가용성만 수정한다. `operationStatus`가 제공되면 `OPEN` 또는
`TEMPORARILY_CLOSED`만 허용한다. `CLOSED`는 구조적으로 유효한 enum
문자열이더라도 이 요청에서는 `COMMON_001` HTTP 400으로 거절한다.

DTO 검증만 믿지 않고 Store core Service와 aggregate 경계에서도 일반
수정 명령이 `CLOSED`를 만들 수 없게 한다. 이미 `CLOSED`인 매장은 일반
수정으로 다른 상태로 되돌릴 수 없다는 기존 비가역 규칙도 유지한다.

OpenAPI는 공용 응답용 `OperationStatus`와 수정 요청용 상태 스키마를
분리한다. 수정 요청용 enum에는 `OPEN`, `TEMPORARILY_CLOSED`만 둔다.

### Business registration number is permanently unique

사업자등록번호는 `stores.business_registration_number` 전체에 영구
유일하다. 매장이 `CLOSED`여도 일반 등록에서 재사용할 수 없다. 같은
번호의 재개, 귀속 이전 또는 복구는 일반 등록이 아니라 향후 플랫폼
운영자 복구 계약의 범위다.

다음 활성 정본을 같은 기준으로 수정한다.

- `docs/specs/store-search/spec.md`
- `docs/specs/store-search/openapi.yaml`
- `docs/service-policies/02-store-onboarding.md`
- `docs/specs/mvp1-common/domain-model.md`

문서의 “활성 매장만 중복 금지”, “활성 귀속”, “활성 유일 제약” 표현은
영구 유일성 및 폐점 후 재사용 금지로 바꾼다. 기존 V8의 단일-column
unique constraint와 제약 이름은 적용된 migration을 수정하지 않고
그대로 유지한다.

### Store IDs are positive at the HTTP boundary

다음 Controller의 모든 `storeId` path variable에 `@Positive`를 적용한다.

- `StoreController`의 GET 및 PATCH
- `StoreScheduleController`의 영업시간 PUT 및 예약 접수 시간대 PUT

`0`과 음수는 `HandlerMethodValidationException`을 통해 `COMMON_001`
HTTP 400과 `storeId` validation detail로 변환한다. 이 요청에서는
Service를 호출하지 않는다. OpenAPI `PublicId`의 양수 계약은 유지한다.

### Store onboarding owns consent evidence

현재 다른 도메인에는 입점 자기확약 또는 필수 약관 동의 계약과 저장
모델이 없다. 따라서 Store onboarding이 이 증거를 소유한다.

`StoreCreateRequest`에 다음 필수 boolean을 추가한다.

- `applicantSelfAttested`
- `requiredTermsAgreed`

두 값은 모두 `true`여야 하며, 누락·`false`는 `COMMON_001` HTTP 400으로
거절한다. 멱등 request fingerprint에도 두 값을 포함한다.

서버는 클라이언트가 약관 버전이나 동의 시각을 임의로 정하게 하지 않는다.
현재 Store onboarding 약관 버전은 서버 상수
`STORE_ONBOARDING_REQUIRED_TERMS_V1`으로 관리한다. 등록 성공 시 다음
증거를 Store 행에 기록한다.

- `applicant_self_attested_at`
- `required_terms_agreed_at`
- `required_terms_version`

시각은 명시적인 애플리케이션 `Clock`에서 `Asia/Seoul` 기준으로 한 번
계산하여 두 증거에 사용한다. 기존 V8은 수정하지 않고 PR #67의 V9 뒤에
새 migration을 추가한다. 세 column은 `NOT NULL`이며 약관 버전은 blank를
허용하지 않는 DB CHECK로 보호한다.

기존 설치 DB의 매장 행을 안전하게 이행하기 위해 migration은 기존 행에
대해 `created_at`을 두 동의 시각으로, 위 현재 약관 버전을 버전 값으로
backfill한 뒤 `NOT NULL`과 CHECK를 적용한다. 이 backfill은 기존 데이터의
법적 재동의를 주장하지 않으며, 1차 MVP 이전 등록 데이터의 기술적 이행
표시라는 한계를 정책 문서에 명시한다.

동의 증거는 관리 응답에 새로 노출하지 않는다. 현재 요구는 검증과 감사
가능한 저장이며, 공개 응답 확장은 별도 필요가 확인될 때 다룬다.

### Central guards own purpose-specific authority

`StoreService.requireManagementAuthority`가 상태 snapshot을 반환하고 각
호출자가 해석하는 현재 구조를 목적별 중앙 guard로 바꾼다.

- `requireManagementOwnership(operatorId, storeId)`: 존재와 대표 운영자
  소유권만 확인한다.
- `requireSchedulePublicationAuthority(operatorId, storeId)`: 존재,
  소유권, `verificationStatus == APPROVED`,
  `operationStatus != CLOSED`를 한곳에서 판정한다.

Store schedule은 Entity나 Repository를 직접 참조하지 않고 위 공개
Service 계약만 사용한다. 오류는 기존 `STORE_001`, `STORE_003`,
`STORE_005`, `STORE_007`을 유지한다.

### Idempotent replay precedes mutable publication checks

일정 게시의 처리 순서는 다음과 같다.

1. `requireManagementOwnership`으로 안정적인 소유권을 확인한다.
2. 전체 요청 fingerprint와 멱등 command를 만든다.
3. `IdempotencyExecutor.execute`가 기존 성공 결과 재생 여부를 판정한다.
4. 새 실행일 때만 callback 안에서
   `requireSchedulePublicationAuthority`로 현재 검증·운영 상태를 확인한다.
5. 일정 상태 row 잠금, 검증, 버전 저장과 활성화를 실행한다.

따라서 첫 게시가 성공한 뒤 매장이 `CLOSED`가 되어도 동일 주체·명령·키·
fingerprint 재시도는 저장된 성공 결과를 반환한다. 새 키의 게시 명령은
현재 상태를 검사하여 `STORE_005`로 거절한다.

### Schedule entities follow the construction convention

다음 Entity는 JPA용 `protected` no-args constructor를 유지하고, 필수
영속 입력을 받는 `private` constructor를 추가한다.

- `OperatingScheduleVersion`
- `ReservationScheduleVersion`
- `StoreScheduleState`

static factory는 파생 값만 계산하고 private constructor를 호출한다.
factory에서 protected no-args constructor를 직접 호출한 뒤 모든 필드를
대입하는 방식은 사용하지 않는다. 공개 생성 계약과 저장 동작은 바꾸지
않는다.

## Persistence and Migration

PR #67의 기존 V9 일정 migration은 수정하지 않는다. 새 migration은
Store onboarding 증거 column 추가, 기존 행 backfill, `NOT NULL`, blank
version CHECK를 순서대로 수행한다.

Store Entity는 세 column을 immutable onboarding evidence로 매핑한다.
일반 update는 이 값을 변경하지 않는다. Store 생성 factory는 서버가
결정한 버전과 시각을 필수 인자로 받아 한 번만 설정한다.

Flyway clean-start와 기존 행이 있는 upgrade 경로를 Testcontainers
MySQL로 검증한다.

## Error Handling

- 일반 PATCH의 `CLOSED`: `COMMON_001`, HTTP 400
- 자기확약 또는 필수 약관 동의 누락·거짓: `COMMON_001`, HTTP 400
- `storeId <= 0`: `COMMON_001`, HTTP 400
- 일정 게시 대상 없음: `STORE_001`
- 다른 대표 운영자: `STORE_003`
- 폐점 상태의 새 일정 게시: `STORE_005`
- 승인되지 않은 상태의 새 일정 게시: `STORE_007`
- 동일 멱등 command의 성공 재생: 최초 성공 HTTP status와 body

검증 응답은 기존 `GlobalExceptionHandler`와 안전한 validation detail
형식을 재사용한다.

## Testing Strategy

TDD 순서는 각 행위별로 실패 테스트를 먼저 실행하고, 예상 원인으로
실패함을 확인한 뒤 최소 구현과 재검증을 수행한다.

Store core 단위·Service 테스트:

- 일반 update가 `CLOSED`를 거절하고 기존 필드를 부분 변경하지 않음
- `OPEN`과 `TEMPORARILY_CLOSED` 사이 전이는 유지됨
- 등록 시 동의 증거와 서버 약관 버전이 저장됨
- 멱등 fingerprint가 동의 boolean 차이를 구분함
- 목적별 ownership 및 schedule publication guard 오류

Store core MockMvc 테스트:

- PATCH `operationStatus=CLOSED`가 400이며 Service가 호출되지 않음
- 자기확약·필수 약관 동의 누락 또는 `false`가 400
- GET/PATCH의 `storeId` 0과 음수가 400이며 Service가 호출되지 않음

Store schedule Service 테스트:

- 동일 성공 command는 이후 `CLOSED` 상태에서도 저장 결과를 재생함
- 새 command는 callback 안의 중앙 guard로 `CLOSED`를 거절함
- Service가 ownership 확인과 publication guard를 올바른 순서로 사용함

Store schedule MockMvc 테스트:

- 두 PUT endpoint의 `storeId` 0과 음수가 400이며 Service가 호출되지 않음

MySQL 통합 테스트:

- 사업자등록번호는 `CLOSED` 이후에도 재사용할 수 없음
- 새 onboarding evidence column과 제약이 clean-start에서 적용됨
- 기존 매장 행 upgrade backfill이 완료됨
- 일정 버전·동시성·rollback 기존 검증이 유지됨

최종 검증:

1. Store core 집중 테스트
2. Store schedule 집중 테스트
3. 전체 backend `clean build`
4. `git diff --check`
5. #67 변경 파일과 승인 범위 대조

## GitHub Review Handling

현재 미해결 인라인 리뷰 2건은 코드와 테스트 검증이 끝난 뒤 처리 결과를
사용자에게 보고한다. GitHub 답글 작성과 스레드 해결은 별도 외부 write
action이므로 이번 구현 승인에 포함하지 않는다.
