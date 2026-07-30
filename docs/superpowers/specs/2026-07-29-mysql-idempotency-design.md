# MySQL 멱등 명령 기반 설계 (#32)

> 추적 Issue: [#32](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/32)
> 기준 브랜치: `dev` (기준 SHA `32b3f4e`, PR #40 병합 상태)
> 기준 계약: `docs/specs/mvp1-common/spec.md` **C-006**(상태 변경 명령 멱등성)·**C-007**(트랜잭션 경계·제한 재시도), 3단계 **E-005**(고정 잠금 순서)
> 아키텍처: ADR-001, `docs/specs/mvp1-common/ownership.md` (global 공통 기반; O-009 마이그레이션 공통 디렉터리)
> 소유자: Global 공통 기반 — @116Lv (매장·검색 담당이 공통 기반도 소유, 팀 확인 완료)
> 설계 상태: 승인 (2026-07-29 소유자 — wrapper API·MANDATORY·PROCESSING 불변식·JdbcTemplate upsert·E-007 PK·Flyway 순서 지침 반영)

## 목적

모든 1차 MVP 상태 변경 명령(#33 매장 등록·수정, #3 예약 생성·취소·상태 변경, #4 메뉴 홀드·픽업)이 **재사용**하는 MySQL 기반 멱등 명령 기록과 실행 wrapper를 `com.miriyum.global.idempotency`에 구현한다. 재전송으로 거래·자원이 중복 변경되는 것을 막는다.

이 산출물은 **읽기/실행 재사용 기반**이며 특정 업무 도메인의 Entity·트랜잭션을 만들지 않는다. 계약(C-006/C-007)은 이미 승인됐으므로 새 동작을 발명하지 않고 명시된 계약을 구현한다.

### 범위 밖

- 업무 Entity·트랜잭션(예약·매장·메뉴·픽업) — 각 도메인 소유.
- 인증·인가·JWT — 1번 소유. principal(namespace·id)은 호출 도메인이 인증 결과에서 전달한다.
- Valkey, TTL·만료 배치, `FAILED`·lease 등 장기 상태 머신, 범용 프레임워크, HTTP 원문·JWT·비밀번호 저장 (C-006 한계).
- C-007의 제한 재시도(최대 3회) 루프 자체 — 명령 조정 도메인이 `execute()` 밖(트랜잭션 밖)에서 소유한다. 이 모듈은 재시도가 안전하도록 멱등 재생을 보장한다. Spring Retry를 추가하지 않는다.

## 설계 원칙

- 계약 C-006/C-007을 그대로 구현하고 컬럼·의미를 발명하지 않는다.
- 실행 wrapper는 **호출 도메인의 주 Service가 소유한 트랜잭션에 참여**한다(`Propagation.MANDATORY`). 자체 트랜잭션을 열지 않는다.
- 멱등 기록과 업무 변경은 하나의 트랜잭션에서 원자적으로 확정된다. 실패 시 멱등 기록까지 전체 롤백한다.
- 유일키 경합은 **JPA flush 예외 복구가 아니라 JdbcTemplate 기반 MySQL upsert + 잠금 조회(`SELECT ... FOR UPDATE`)**로 결정적으로 처리한다.
- 공통 `ServiceException`·`CommonErrorCode`를 재사용한다(`COMMON_003/004/007`).
- DB 동작은 H2가 아니라 Testcontainers MySQL로 검증한다.

## 데이터 모델

### `idempotency_commands` (JdbcTemplate 접근, JPA 엔티티 없음)

| 컬럼 | 타입 | 제약 | 의미 |
|---|---|---|---|
| `idempotency_command_id` | `BIGINT` | PK, AUTO_INCREMENT | 테이블별 대리 PK(E-007) |
| `principal_namespace` | `VARCHAR(30)` `as_cs` | NOT NULL | 계정 namespace(예: `CONSUMER`, `STORE_OPERATOR`) |
| `principal_id` | `BIGINT` | NOT NULL | 인증 주체(행위자) 계정 PK. namespace와 함께 해석(E-007) |
| `command_type` | `VARCHAR(60)` `as_cs` | NOT NULL | 명령 유형 상수(예: `STORE_REGISTER`) |
| `idempotency_key` | `CHAR(36)` `as_cs` | NOT NULL | 정규화된 UUID |
| `request_fingerprint` | `CHAR(64)` `as_cs` | NOT NULL | 정규화 입력의 SHA-256 hex |
| `processing_status` | `VARCHAR(20)` | NOT NULL | `PROCESSING`(트랜잭션 내 일시)→`SUCCEEDED`(커밋). 커밋된 행은 항상 `SUCCEEDED` |
| `result_http_status` | `SMALLINT` | NULL | 최초 성공 HTTP 상태 |
| `result_response_code` | `VARCHAR(40)` | NULL | 최초 성공 응답 code(예: `SUCCESS`) |
| `result_resource_type` | `VARCHAR(40)` | NULL | 결과 리소스 유형 |
| `result_resource_id` | `VARCHAR(64)` | NULL | 결과 리소스 ID |
| `result_payload` | `TEXT` (utf8mb4) | NULL | 최초 성공 응답의 정규화된 `data` JSON 문자열 |
| `created_at` | `DATETIME(6)` | NOT NULL | 생성 시각 |
| `updated_at` | `DATETIME(6)` | NOT NULL | 갱신 시각 |

- **UNIQUE `uk_idempotency_commands` (principal_namespace, principal_id, command_type, idempotency_key)** — E-007 "명령 멱등 기록" 업무 유일성. 선점과 최종 방어선이며 애플리케이션 사전 조회로 대신하지 않는다(E-007).
- **CHECK `ck_idempotency_commands_status`** — 상태를 `PROCESSING`, `SUCCEEDED` 두 값으로 제한한다.
- **CHECK `ck_idempotency_commands_succeeded_result`** — `SUCCEEDED`이면 `result_http_status`와 `result_response_code`가 반드시 존재해야 한다.
- 식별 컬럼은 대소문자 구분(`utf8mb4_0900_as_cs`)이며 key는 validator에서 소문자 정규화한다(같은 UUID의 이중 표기 방지).
- `result_*`는 `PROCESSING` 삽입 시 NULL, `SUCCEEDED` 확정 시 채운다. 커밋 후에는 `FAILED` 행이 존재하지 않는다(실패는 롤백).
- HTTP 원문 바이트·인증 헤더·타임스탬프·원본 JSON 전체는 저장하지 않는다(C-006).

## 패키지 구조

```text
com.miriyum.global.idempotency
├─ IdempotencyExecutor        # execute(command, businessSupplier) — @Transactional(MANDATORY)
├─ IdempotencyCommand         # (principalNamespace, principalId, commandType, idempotencyKey, requestFingerprint)
├─ IdempotencyKey             # UUID 검증·정규화 값 타입 (COMMON_003/004)
├─ RequestFingerprint         # 정규화 입력 → SHA-256 hex 헬퍼
├─ BusinessResult<T>          # 업무 콜백 반환 (httpStatus, responseCode, resourceType, resourceId, data)
├─ IdempotentOutcome          # 실행 결과 (fresh/replay 공통: httpStatus, responseCode, resourceType, resourceId, payloadJson)
├─ IdempotencyRecordRepository# JdbcTemplate upsert·잠금조회·확정
└─ IdempotencyStatus          # enum PROCESSING, SUCCEEDED
```

- 전부 global 공통. 업무 도메인 참조 없음. 빈 패키지·미사용 추상화를 만들지 않는다.

## 핵심 컴포넌트

### `IdempotencyKey` (검증·정규화)

- 헤더 문자열을 받아 표준 UUID(하이픈 포함 36자)인지 검증한다.
- 누락 → `ServiceException(COMMON_003)`. 형식 오류(비-UUID·중괄호·앞뒤 공백·길이≠36) → `ServiceException(COMMON_004)`.
- 통과 시 소문자 정규화한 값으로 보관. **쓰기 트랜잭션 진입 전**(도메인 컨트롤러/전처리)에서 검증·정규화를 완료하며, executor는 이미 검증·정규화된 키를 받는다. 별도 필터는 없다.
- `IdempotencyCommand` 생성 시 DB 필수값·길이, 양수 principal ID, 소문자 UUID와 SHA-256 hex 형식을 다시 검증한다. `INSERT IGNORE`에 유일키 충돌 외 잘못된 입력이 도달하지 않게 하는 내부 경계다.

### `RequestFingerprint`

- HTTP method + 정규화 route + 실제 path parameter + 정렬·정규화한 승인 query + 승인 body field(예: 예약일·시간대·매장·인원·선택 메뉴·수량)를 **정규 문자열**로 결합.
- null·blank 정규 입력은 구성 누락으로 간주해 해시 생성 전에 거부한다.
- 같은 route template이라도 대상 리소스 ID가 다르면 다른 지문.
- 정규 문자열의 **SHA-256 hex(64자)**를 저장한다. 비밀번호·JWT·Refresh Token·원본 JSON 전체는 포함하지 않는다.
- 어떤 필드가 지문에 들어가는지는 각 명령의 기능별 계약이 정하며, 도메인이 정규 입력을 구성해 전달한다.

### `IdempotencyExecutor.execute(command, businessSupplier)`

`@Transactional(propagation = Propagation.MANDATORY)` — 호출 도메인 주 Service의 트랜잭션에 **참여만** 한다(없으면 예외). 격리 수준 `READ_COMMITTED`와 timeout=5초는 트랜잭션을 **시작·소유하는 각 도메인 주 Service**가 선언한다(참여 메서드의 isolation·timeout은 무시되므로 executor에 두지 않는다). 절차:

1. **선점(insert-or-ignore):** `INSERT IGNORE INTO idempotency_commands (... processing_status='PROCESSING' ...)`. 반환 행 수로 **이 트랜잭션이 신규 삽입(1)인지 기존 행 존재(0)인지** 판정한다(`INSERT IGNORE`는 UPDATE 매칭 경로가 없어 `CLIENT_FOUND_ROWS` 설정과 무관하게 1/0이 일정하다). 모든 컬럼을 서버가 유효값으로 채우므로 무시되는 오류는 유일키 충돌뿐이다. 동시 같은 키는 유일 인덱스 행 잠금으로 직렬화된다.
2. **잠금 조회:** `SELECT ... FOR UPDATE`로 해당 행을 잠근다.
3. **분기:**
   - **신규 삽입(affected=1)** → 이 트랜잭션이 선점한 `PROCESSING`. 업무 콜백을 **정확히 1회** 실행 → `SUCCEEDED`로 `UPDATE`(result_* 채움) → fresh `IdempotentOutcome`.
   - **기존 행(affected=0)**:
     - `SUCCEEDED` + 지문 일치 → **재생**: 콜백 미실행, 저장된 `result_*` 반환.
     - `SUCCEEDED` + 지문 불일치 → `ServiceException(COMMON_007)`(409), 기존 결과 미변경.
     - `SUCCEEDED`인데 필수 결과(`result_http_status`, `result_response_code`)가 누락됨 → 저장 불변식 위반으로 재생하지 않고 실패.
     - `PROCESSING`(커밋된 상태로 발견) → **불변식 위반**(커밋된 PROCESSING은 존재할 수 없음): 콜백을 실행하지 않고 오류로 처리한다.
4. 업무 콜백 예외는 전파되어 전체 트랜잭션(멱등 행 포함) 롤백 → 커밋된 `FAILED`·`PROCESSING` 행은 남지 않는다.

- 유일키 경합을 JPA flush 예외로 처리하지 않는다. upsert+`FOR UPDATE`로 잠금 순서를 명시한다(E-005 정렬을 이어 적용).
- 업무 콜백은 신규 선점(affected=1)에서만 정확히 1회 실행된다(재생·불변식 위반 경로는 콜백 미실행).
- 생성·갱신 시각은 애플리케이션 시스템 시각을 직접 호출하지 않고 MySQL `CURRENT_TIMESTAMP(6)`으로 기록한다.

### `IdempotentOutcome` 반환과 컨트롤러

- fresh·replay 모두 `{httpStatus, responseCode, resourceType, resourceId, payloadJson}`를 노출한다.
- 컨트롤러는 이 값으로 `ApiResponse`(code=responseCode, data=payload) 응답을 만든다. 최초 성공 message는 저장하지 않으므로 재생 시 표준 안내 문구를 사용한다(HTTP 원문 미저장 계약과 정합).
- `payloadJson` 직렬화는 global의 `JsonMapper` 설정을 재사용한다.

## 동시성·트랜잭션 (C-007 정합)

- 격리 수준 `READ_COMMITTED`와 트랜잭션 제한 5초는 트랜잭션을 시작·소유하는 **도메인 주 Service**가 선언한다. executor는 `MANDATORY`로 참여만 한다.
- 멱등 행을 업무 자원보다 먼저 선점하고 E-005의 자원 유형 순서·PK 오름차순 잠금을 이어 적용한다.
- 부분 성공 금지: 멱등 행과 업무 변경은 함께 커밋되거나 함께 롤백된다.
- 제한 재시도(C-007 최대 3회·백오프)는 `execute()` 밖에서 명령 조정 계층이 각 시도를 새 트랜잭션으로 수행한다. 이 모듈은 재시도가 같은 키·지문으로 재생되도록 보장할 뿐 재시도 루프를 구현하지 않는다.

## 의존성

`backend/build.gradle.kts`에 아래를 추가한다. 버전은 Spring Boot 의존성 관리가 소유(ADR-004).

```kotlin
implementation("org.springframework.boot:spring-boot-starter-flyway")   // production: 마이그레이션 자동 실행
testImplementation("org.springframework.boot:spring-boot-testcontainers")
testImplementation("org.testcontainers:testcontainers-junit-jupiter")
testImplementation("org.testcontainers:testcontainers-mysql")
```

> 주의(교차 조율): 이 세 의존성은 #31도 같은 이유로 추가한다. 두 PR이 각각 `dev` 기준이므로 나중에 병합되는 쪽에서 가산적 병합 충돌이 발생하나 사소하다.

## Flyway migration

- `backend/src/main/resources/db/migration/V4__create_idempotency_commands.sql` — 위 스키마·유일 제약.

> **적용된 규약(2026-07-30):** PR #40(#31)이 `dev`에 병합된 뒤 #32를 merge commit `32b3f4e` 위로 rebase하고 `V4`를 사용한다. `spring.flyway.out-of-order`는 활성화하지 않는다. 마이그레이션 적용 순서는 `V1`→`V2`→`V3`→`V4`다.

## 테스트 설계

업무 도메인이 없으므로 **테스트 전용 합성 명령**으로 검증한다(production에 예제 Service·엔드포인트를 만들지 않는다).

### 단위 테스트

- `IdempotencyKey`: 유효 UUID 통과·소문자 정규화, 누락 `COMMON_003`, 비-UUID·중괄호·공백·길이 오류 `COMMON_004`.
- `RequestFingerprint`: 같은 입력 → 같은 해시(결정적), 리소스 ID 다르면 다른 해시, null·blank 입력 거부.

### 통합 테스트 (Testcontainers MySQL, `mysql:8.0.40`)

테스트 전용 `@Transactional` 래퍼 Service(테스트 소스)에서 `execute()`를 호출한다.

- Flyway가 `idempotency_commands`와 유일 제약·상태/성공 결과 CHECK를 재현한다.
- 알 수 없는 상태와 필수 성공 결과가 누락된 `SUCCEEDED` 행은 DB가 거부한다.
- fresh: 업무 콜백 1회 실행, 행이 `SUCCEEDED`와 result 저장.
- replay(같은 키·지문): 콜백 미실행, 저장된 결과 반환.
- 다른 지문·같은 키: `COMMON_007`(409), 기존 결과 미변경.
- 업무 콜백 예외: 멱등 행 포함 전체 롤백(행 미존재).
- `MANDATORY`: 트랜잭션 없이 호출 시 예외.
- **동시성:** 두 스레드가 같은 키·지문으로 동시 호출 → 업무 콜백 **정확히 1회** 실행, 두 호출 모두 동일 성공 결과 반환.

### 회귀

```powershell
cd backend
.\gradlew.bat clean build --offline
git diff --check
```

2026-07-30 rebase 후 로컬 Docker 환경에서 `clean build --offline`로 **124 tests, 0 failed, 0 errors,
0 skipped**를 수집했다. Testcontainers MySQL 8.0.40에서 Flyway `V1`→`V2`→`V3`→`V4`
clean-start를 확인했다.

## 파일 허용 목록 (#32)

- `backend/build.gradle.kts` — Flyway autoconfig(production) + Testcontainers 테스트 의존성
- `backend/src/main/resources/db/migration/**` — `idempotency_commands`만
- `backend/src/main/java/com/miriyum/global/idempotency/**`
- `backend/src/test/java/com/miriyum/global/idempotency/**`
- `backend/src/test/resources/**` — 해당 통합 테스트 입력만
- `docs/superpowers/specs/*mysql-idempotency*-design.md`
- `docs/superpowers/plans/*mysql-idempotency*.md`

## 위험과 롤백

- **Flyway 번호 조율:** 적용 완료 — #40 병합 후 #32 rebase, `V4`, out-of-order 미사용.
- **build.gradle 중복:** #40 병합 후 rebase로 해소. #32 전용 diff에는 의존성 중복 변경이 없다.
- **동시성 정확성:** MySQL 행 잠금·유일 제약에 의존하므로 H2로 대체 검증하지 않고 Testcontainers로만 증명한다.
- **MANDATORY 계약:** 호출 도메인이 `@Transactional`을 소유하지 않으면 `execute()`가 실패한다. 이는 의도된 계약이며 문서로 명시한다.
- **재생 message:** 최초 message는 저장하지 않으므로 재생 응답은 표준 문구를 사용한다(원문 미저장 계약과 정합).
- 롤백: 문제 시 #32 PR 전체를 되돌릴 수 있다. 도메인별 임시 멱등 저장소는 대안이 아니다.
