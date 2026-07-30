# MySQL 멱등 명령 기반 구현 계획 (#32)

> 추적 Issue: [#32](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/32)
> 설계: `docs/superpowers/specs/2026-07-29-mysql-idempotency-design.md` (승인)
> 브랜치: `feature/32-mysql-idempotency` (base `32b3f4e`, PR #40 병합 후 rebase)
> 방식: TDD, Testcontainers MySQL 검증

## 전제·선행 확인

- 계약 C-006/C-007/E-005/E-007 확정. 선행 PR #27·#30 병합 완료.
- 소유권: global 공통 기반 = @116Lv (팀 확인 완료).
- **Flyway 순서:** PR #40(#31) 병합과 최신 dev rebase 완료. `V4__create_idempotency_commands.sql` 사용, out-of-order 미사용.
- **Docker 필요:** IT는 Testcontainers MySQL(`mysql:8.0.40`). Docker 없으면 skip → 병합 증거는 Docker 환경 0 skipped.

## 산출물 구조 (com.miriyum.global.idempotency)

```
IdempotencyExecutor        # execute(command, supplier) — @Transactional(MANDATORY)
IdempotencyCommand         # principalNamespace, principalId(Long), commandType, idempotencyKey, requestFingerprint
IdempotencyKey             # UUID 검증·소문자 정규화 (COMMON_003/004), 트랜잭션 진입 전 사용
RequestFingerprint         # 정규 입력 → SHA-256 hex
BusinessResult<T>          # httpStatus, responseCode, resourceType, resourceId, data
IdempotentOutcome          # fresh/replay 공통 결과
IdempotencyStatus          # enum PROCESSING, SUCCEEDED
IdempotencyRecordRepository# JdbcTemplate upsert·FOR UPDATE·finalize
db/migration/V4__create_idempotency_commands.sql
```

## TDD 단계

1. **RED 단위** `IdempotencyKeyTest` — 유효 UUID 통과·소문자 정규화, 누락 `COMMON_003`, 비-UUID·중괄호·앞뒤 공백·길이≠36 `COMMON_004`. `RequestFingerprintTest` — 같은 입력 동일 해시, 리소스 ID 다르면 다른 해시, null·blank 정규 입력 거부.
2. **GREEN** — 위 값 타입 + `IdempotencyRecordRepository`(JdbcTemplate upsert/FOR UPDATE/update) + `IdempotencyExecutor`(MANDATORY, affected-rows 기반 신규 선점 판정, 재생·충돌·불변식 위반 분기).
3. **Migration** `V4__create_idempotency_commands.sql` — E-007 `idempotency_command_id` 대리 PK + 업무 복합 UNIQUE, `PROCESSING`/`SUCCEEDED` 상태와 성공 결과 CHECK, 식별 컬럼 `as_cs`, result_* nullable, `TEXT` payload.
4. **의존성** — production `spring-boot-starter-flyway`, test `testcontainers-junit-jupiter`/`testcontainers-mysql`은 #40의 공통 기반을 재사용한다.
5. **RED→GREEN 통합** `IdempotencyExecutorIT` (Testcontainers MySQL) — 테스트 전용 `@Transactional`(READ_COMMITTED, timeout=5) 래퍼 Service + 합성 명령으로:
   - Flyway가 `idempotency_commands`·유일 제약·상태/성공 결과 CHECK 재현
   - fresh: 콜백 1회, 행 `SUCCEEDED`+result 저장
   - replay(같은 키·지문): 콜백 미실행, 저장 결과 반환
   - 다른 지문·같은 키: `COMMON_007`(409), 기존 결과 미변경
   - 콜백 예외: 멱등 행 포함 전체 롤백(행 미존재)
   - `MANDATORY`: 트랜잭션 없이 호출 시 예외
   - **동시성:** 두 스레드 같은 키·지문 동시 호출(래치로 동시 시작) → 콜백 `AtomicInteger` **정확히 1**, 두 결과 동일
6. **리뷰 보강** — `IdempotencyCommand`와 `RequestFingerprint`가 입력 계약을 생성 시 검증하고, DB CHECK가 허용 상태와 `SUCCEEDED` 필수 성공 결과를 강제한다. 동시성 IT는 선점 트랜잭션을 열린 상태로 유지해 실제 유일키 경합을 검증하며, DB 시각은 `CURRENT_TIMESTAMP(6)`을 사용한다.

## 검증 명령

```powershell
cd backend
.\gradlew.bat clean build --offline
git diff --check
```

2026-07-30 rebase 후 로컬 Docker 환경에서 `clean build --offline`로 **124 tests, 0 failed, 0 errors,
0 skipped**를 수집했다. Testcontainers MySQL 8.0.40에서 Flyway `V1`→`V2`→`V3`→`V4`
clean-start를 확인했다.

## 완료 기준 (인수 조건 매핑)

- [x] 표준 UUID 검증·누락·형식 오류 `COMMON_003/004` → `IdempotencyKeyTest`.
- [x] `(namespace,id,command_type,key)` 유일 제약 선점 → migration + IT.
- [x] 같은 지문 재요청 재생 → IT replay.
- [x] 다른 지문 키 재사용 `COMMON_007`, 기존 미변경 → IT conflict.
- [x] 멱등 기록+업무 변경이 호출 도메인 같은 트랜잭션 참여 → executor `MANDATORY` + IT.
- [x] 자동 만료·TTL·Valkey 없음 → 코드·의존성 부재로 확인.

## 범위 밖·주의

- 업무 Entity·트랜잭션, 인증·JWT 없음. principal은 도메인이 전달.
- C-007 제한 재시도 루프는 executor 밖(명령 조정 계층) 소유. Spring Retry 미추가.
- `execute()`는 MANDATORY — 호출 도메인이 `@Transactional`을 소유해야 함(의도된 계약).
- #40 병합 후 rebase 완료, 적용된 Flyway 파일 수정 금지.
