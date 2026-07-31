# PR #65 Review Remediation Design

> 대상 PR: [#65](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/pull/65)
>
> 대상 브랜치: `codex/33-store-core`
>
> 승인일: 2026-07-31

## 목표

PR #65의 미해결 리뷰 7건을 확정된 공통 계약과 서비스 정책에 맞게 수정한다. 공개 ID, 요청 검증, 운영 상태 전이, 멱등 결과 재생, 동시 수정, 사업자등록번호 재귀속, Entity 생성 규칙을 함께 바로잡고 Testcontainers MySQL에서 동시성 불변식을 검증한다.

## 범위

- `ManagedStoreResponse.storeId`를 공개 문자열 ID로 직렬화한다.
- `StoreModesRequest`의 세 입력 필드를 모두 필수로 검증한다.
- `CLOSED`를 매장 운영자가 되돌릴 수 없는 종단 상태로 보호한다.
- 가변 카탈로그 검증을 멱등 신규 실행 콜백 안으로 이동한다.
- 서로 다른 멱등 키의 동시 PATCH를 매장 행 잠금으로 직렬화한다.
- 폐점 뒤에도 사업자등록번호를 일반 등록 경로에서 재사용하지 못하게 한다.
- `Store` 생성은 private constructor와 의미 있는 static factory를 사용한다.

플랫폼 운영자의 매장 복구 API, 자동 재시도 루프, Redis·Valkey·분산 락, 새로운 외부 라이브러리는 이 변경에 포함하지 않는다.

## 계약 정합성

### 공개 ID와 요청 검증

C-008과 `PublicId` OpenAPI 계약에 따라 내부 `Long` ID는 응답 경계에서 `String.valueOf`로 변환한다. 경로 변수와 내부 저장 타입은 계속 `long`/`Long`을 사용한다.

`StoreModesRequest`는 `Boolean`과 `@NotNull`을 사용한다. 생성 요청의 `modes` 객체와 PATCH에 포함된 `modes` 객체는 각각 세 필드를 모두 가져야 한다. 누락은 `COMMON_001`의 `400 Bad Request`로 처리한다.

### 상태 전이와 사업자등록번호

허용 전이는 다음과 같다.

- `OPEN -> TEMPORARILY_CLOSED`
- `TEMPORARILY_CLOSED -> OPEN`
- `OPEN -> CLOSED`
- `TEMPORARILY_CLOSED -> CLOSED`
- 같은 상태로의 멱등 갱신

`CLOSED -> OPEN`과 `CLOSED -> TEMPORARILY_CLOSED`는 `STORE_005`로 거절한다. 실패한 전이는 다른 요청 필드도 변경하지 않는다.

OPER-009는 동일 사업자등록 단위의 재개를 플랫폼 운영자 복구로 한정한다. 해당 고도화 경로가 없는 1차 MVP에서는 `business_registration_number` 자체에 유일 제약을 유지해 폐점 후 일반 `POST /stores` 재등록을 차단한다. 기존 제약 이름 `uk_stores_active_business_number`는 서비스 오류 매핑 호환을 위해 유지한다.

### 멱등성과 잠금 순서

인증 계정 확인과 대상 매장의 존재·소유권 검증은 멱등 선점 전에도 수행한다. 카테고리·태그 활성 상태처럼 시간이 지나며 바뀌는 검증은 신규 명령의 business supplier 안에서만 수행한다. 따라서 같은 키·지문의 재시도는 최초 결과를 재생하고, 다른 지문의 키 재사용은 카탈로그 상태와 관계없이 `COMMON_007`을 우선 반환한다.

동시 PATCH는 저장소가 승인한 C-006/C-007, E-005, SCALE-008을 따른다.

1. 멱등 명령 기록을 확인·선점한다.
2. 신규 명령만 `stores` 행을 `SELECT ... FOR UPDATE`로 잠근다.
3. 잠긴 최신 Entity에서 소유권과 유효 카탈로그 조합을 다시 확인한다.
4. PATCH의 제공 필드만 반영하고 결과와 멱등 기록을 함께 커밋한다.

구현은 Spring Data JPA `@Lock(LockModeType.PESSIMISTIC_WRITE)`를 사용한다. 실제 정합성은 H2나 mock이 아니라 Testcontainers MySQL에서 서로 다른 필드의 병렬 PATCH 결과가 모두 보존되는지 검증한다.

## 테스트 전략

- Controller: `storeId`가 JSON 문자열인지, 생성·수정 `modes`의 각 필드 누락이 `COMMON_001`인지 검증한다.
- Entity: 허용 상태 전이와 `CLOSED` 역전이 거절, 실패 시 필드 불변을 검증한다.
- Service: 재생·키 충돌 경로에서 카탈로그 검증과 업무 저장이 실행되지 않는지 검증한다.
- Repository/통합: 폐점 뒤 사업자등록번호 재등록이 유일 제약으로 거절되는지 검증한다.
- Service 통합: 서로 다른 멱등 키로 이름과 주소를 동시에 PATCH해 둘 다 최종 상태에 남는지 검증한다.
- 회귀: store core 집중 테스트, 전체 `clean build`, `git diff --check origin/dev...HEAD`를 실행한다.

## 위험과 대응

- 비관적 락 대기: 트랜잭션은 기존 `READ_COMMITTED`, 5초 제한을 유지하고 락 이후 외부 호출이나 긴 계산을 추가하지 않는다.
- 잠금 순서 역전: 멱등 기록 선점 뒤 매장 단일 PK 행만 잠그며 다른 쓰기 경로도 같은 순서를 사용한다.
- V8 변경: PR이 아직 병합되지 않은 신규 마이그레이션이므로 V8을 직접 수정한다. 이미 적용된 운영 스키마의 역마이그레이션은 필요하지 않다.
- 응답 타입 변경: OpenAPI가 이미 문자열을 요구하므로 구현과 테스트만 계약에 맞추며 문서 스키마는 변경하지 않는다.

## 완료 기준

- 미해결 리뷰 7건에 대응하는 코드 또는 테스트 변경이 존재한다.
- 신규 회귀 테스트가 수정 전 구현에서 의도한 이유로 실패하고 수정 후 통과한다.
- Testcontainers MySQL에서 사업자번호 유일성과 동시 PATCH 보존이 검증된다.
- 전체 빌드와 whitespace 검사가 성공한다.
- 사용자 요청 없이 GitHub 리뷰 답글 작성이나 스레드 resolve는 수행하지 않는다.
