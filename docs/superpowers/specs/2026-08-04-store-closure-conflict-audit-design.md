# Store Closure Conflict Audit Design

## Goal

PR #106의 휴무 명령 감사 원본이 실제 영향 거래 조회를 수행하지 않았다는 사실을 명시적으로 보존한다. Issue #69의 공개 조회 계약이 연결되기 전에는 모든 휴무 감사 사건을 `conflictCheckStatus=NOT_EVALUATED`, `conflictCount=null`로 기록하며 이를 충돌 없음이나 0건으로 해석하지 않는다.

## Scope

- `StoreClosureAuditEvent`에 충돌 확인 상태와 nullable 충돌 수를 추가한다.
- PR #106에서 새로 도입된 `V18__create_store_closures.sql`의 감사 테이블에 같은 컬럼과 조합 무결성 제약을 추가한다.
- 정기 휴무의 초안, 즉시·예약 게시, 게시 취소, 자동 활성화, 활성화 실패 감사와 임시 휴점의 생성, 종료 변경, 취소 감사가 미평가 상태를 남기는지 검증한다.
- 공개 HTTP API, DTO, OpenAPI 및 정본 정책의 의미는 변경하지 않는다.
- Reservation 등 다른 도메인의 Entity·Repository를 조회하지 않으며 실제 충돌 계산은 Issue #69 이후 범위로 남긴다.

## Data Model

`StoreClosureAuditEvent`는 Store 일정 감사가 이미 사용하는 `ConflictCheckStatus` enum을 재사용한다. 별도 closure 전용 enum은 같은 Store 도메인 안에서 중복된 상태 어휘를 만들기 때문에 추가하지 않는다.

`record(...)`는 호출자에게 상태나 건수를 받지 않고 다음 값을 강제한다.

- `conflictCheckStatus = ConflictCheckStatus.NOT_EVALUATED`
- `conflictCount = null`

이 방식은 Issue #69 연결 전에 호출자가 실수로 `EVALUATED` 또는 `0`을 기록하는 것을 막는다. 실제 조회 계약이 연결될 때 명시적인 새 factory 또는 audit record 모델을 별도 변경으로 도입한다.

V18의 `store_closure_audit_events`에는 다음을 추가한다.

- `conflict_check_status VARCHAR(20) NOT NULL`
- `conflict_count INT NULL`
- 상태 허용값 `NOT_EVALUATED | EVALUATED` 제약
- `NOT_EVALUATED`이면 count가 null이고, `EVALUATED`이면 count가 0 이상인 조합 제약

V18은 아직 병합·배포되지 않은 PR #106 신규 migration이므로 별도 V19를 만들지 않고 clean-start 정의를 보완한다.

## Command Flow

기존 `StoreClosureService`와 `TemporaryClosureService`의 audit 호출 구조는 유지한다. 모든 호출이 단일 `StoreClosureAuditEvent.record(...)` factory를 통과하므로 factory의 기본값이 초안·게시·취소·자동 활성화·실패를 포함한 전체 감사 경로에 동일하게 적용된다.

실패 감사는 명령 자체의 트랜잭션 실패를 새로 보존한다는 의미가 아니라, 현재 존재하는 예약 활성화의 `ACTIVATION_FAILED` 감사 사건을 뜻한다. 기존 예외·rollback 계약은 변경하지 않는다.

## Verification

- Entity 단위 테스트로 factory가 `NOT_EVALUATED`와 null count를 강제하는지 확인한다.
- Service 단위 테스트에서 정기 휴무의 각 lifecycle 감사와 임시 휴점 감사가 같은 값을 보존하는지 확인한다.
- Migration contract 테스트에서 컬럼과 두 CHECK 제약을 확인한다.
- Store closure 집중 테스트, backend 전체 테스트, build, migration 관련 Testcontainers 검증, `git diff --check`를 실행한다.

## Risks

- JPA enum과 DB 허용값이 어긋날 위험은 동일한 문자열 이름과 migration contract 테스트로 차단한다.
- `NOT_EVALUATED`와 null count 조합이 깨질 위험은 entity factory와 DB CHECK 제약으로 이중 방어한다.
- 후속 #69 구현이 현재 factory를 우회할 위험이 있으므로, 평가 결과 저장은 별도 명시적 API로만 확장한다.
