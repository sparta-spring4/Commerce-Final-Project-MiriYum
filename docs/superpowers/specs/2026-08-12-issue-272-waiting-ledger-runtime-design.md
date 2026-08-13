# Issue #272 Waiting Ledger Runtime Design

## 목적과 범위

Issue #272는 Reservation 도메인 내부 `waiting` capability가 중앙 대기열 원장과 결정적 FIFO 순번을 소유하도록 구현한다. 한 PR에서 기능 명세, OpenAPI, 마이그레이션, backend runtime, 단위·HTTP·MySQL 통합 테스트를 함께 제공한다.

포함 범위는 중앙 대기 팀 생성 command와 중복·순번 원장, 매장 운영자의 목록·상세 snapshot 조회, 호출·도착 확인·입장 완료·취소, 활성 팀 판정, 비동기 매장 종결 job과 상태 조회, 상태 전이 감사 및 공개 상태 사건이다. 일반 사용자 HTTP 등록·자기 팀 조회·취소는 #271 설정 runtime의 접수 가능 판정과 함께 후속 연결한다. 알림 전달, SSE, 프론트엔드, 예약 전환, 결제, 좌석 조건, 순번 미루기, 매장별 반경 설정은 제외한다.

Issue #271 설정 runtime과의 경계는 다음과 같다. #272는 `WaitingLedgerService`의 활성 팀 판정과 `WaitingClosureService`의 종결 job 생성·조회 공개 계약/runtime을 제공한다. 이후 #271 설정 PUT이 `CLOSE_ACTIVE_TEAMS`를 받을 때 이 공개 서비스를 호출하고 HTTP `202 Accepted`로 변환한다. #272에서 아직 존재하지 않는 #271 설정 Controller를 선행 구현하지 않는다.

## 아키텍처

새 최상위 도메인은 만들지 않는다. 모든 소스는 `com.miriyum.domain.reservation.waiting` 아래에 두고 호출자별 HTTP 경계만 `controller.consumer`와 `controller.storeoperator`로 나눈다. Entity·Repository·Service는 호출자별로 복제하지 않는다.

구성 단위는 다음과 같다.

- `WaitingTeam`: 대표 사용자, 매장, 인원, source, queue sequence, 상태, 호출·도착 제한 시각, 낙관적 version을 소유하는 aggregate.
- `WaitingQueueSequence`: 매장·영업일별 다음 순번을 DB 행 잠금으로 할당한다.
- `WaitingTransitionAudit`: actor, 이전·다음 상태, 중앙 시각, 사유와 idempotency command를 기록한다.
- `WaitingClosureJob`: 매장 종료 대상 수, 완료·실패·대사 필요 수와 job 상태를 소유한다.
- `WaitingStatusEvent`: #250이 읽을 수 있는 최소 공개 상태 사건 원장. 알림 payload나 외부 채널 전송은 소유하지 않는다.
- `WaitingLedgerService`: 생성·조회·호출·도착·입장·취소와 활성 팀 판정을 조정한다.
- `WaitingClosureService`: closure job 생성, 팀별 멱등 종결, 실패 집계와 재대사를 조정한다.
- `WaitingStoreAuthorityPort`: Waiting이 요구하는 Store 공개 권한·영업 상태·기준 좌표 projection만 소비한다. 구현은 Store 공개 Service adapter이며 Store Entity·Repository를 Waiting이 직접 참조하지 않는다.

Controller는 Service만 호출한다. 명령은 기존 `IdempotencyExecutor`와 `RequestFingerprint`를 사용한다. DB 쓰기는 MySQL 조건부 갱신과 unique constraint가 최종 동시성 경계를 소유한다.

## 상태와 FIFO

고도화 공개 상태는 `WAITING`, `CALLED`, `ARRIVED`, `CHECKED_IN`, `CANCELLED`, `NO_SHOW`, `CLOSED_BY_STORE`, `RESERVATION_CONVERTING`으로 고정한다. #272에서 허용하는 전이는 다음뿐이다.

- 생성: 없음 → `WAITING`
- 호출: `WAITING` → `CALLED`
- 도착 확인: `CALLED` → `ARRIVED`
- 입장 완료: `ARRIVED` → `CHECKED_IN`
- 사용자 취소: `WAITING|CALLED|ARRIVED` → `CANCELLED`
- 매장 취소: `WAITING|CALLED|ARRIVED` → `CANCELLED`
- 호출 후 10분 만료: `CALLED` → `NO_SHOW`
- 매장 일괄 종결: `WAITING|CALLED|ARRIVED` → `CLOSED_BY_STORE`

`RESERVATION_CONVERTING`은 공개 상태와 persistence enum에는 포함하지만 진입 명령은 후속 예약 전환 범위가 소유한다. 종결 상태에서는 어떤 명령도 성공하지 않는다.

FIFO sequence는 매장과 KST 영업일별로 단조 증가하며 재사용하지 않는다. 호출은 현재 `WAITING` 상태의 최소 sequence 팀만 허용한다. 취소·종결된 번호를 당기거나 재계산하지 않는다. 사용자에게 보이는 앞선 팀 수는 현재 자신보다 작은 sequence의 `WAITING` 팀 개수로 계산한다.

## HTTP 계약

매장 운영자 경로:

- `GET /api/v1/store-operators/stores/{storeId}/waiting-teams`: 상태·sequence 기준 snapshot 목록.
- `GET /api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}`: 팀 상세 snapshot.
- `POST .../{waitingTeamId}/call`: FIFO 선두 호출.
- `POST .../{waitingTeamId}/arrive`: 도착 확인.
- `POST .../{waitingTeamId}/check-in`: 입장 완료.
- `POST .../{waitingTeamId}/cancel`: 매장 취소.
- `GET /api/v1/store-operators/stores/{storeId}/waiting-close-jobs/{jobId}`: closure job 진행 상태.

모든 명령은 `Idempotency-Key`와 `expectedVersion`을 요구한다. GET에는 멱등 키를 요구하지 않는다. 목록은 cursor pagination을 사용하고 전화번호·위치 원문·다른 팀 사용자 식별자를 노출하지 않는다.

#272 내부 공개 Service 계약은 다음과 같다.

```java
WaitingActiveTeamImpact inspectActiveTeams(long operatorAccountId, long storeId);
WaitingClosureCommandResult startClosure(
        long operatorAccountId,
        long storeId,
        IdempotencyKey key,
        long expectedSettingsVersion);
WaitingClosureJobSnapshot getClosureJob(
        long operatorAccountId,
        long storeId,
        long jobId);
```

`startClosure`는 job이 영속되면 accepted 결과를 반환한다. 다음 #271 설정 runtime은 이 결과를 HTTP `202`로 투영한다.

## 데이터 모델과 마이그레이션

선행 작성 PR들의 병합 순서를 반영해 #272는 `V36__create_waiting_ledger.sql` 하나에서 다음 테이블과 제약을 만든다.

- `waiting_teams`: public id, store id, representative consumer id, business date, party size, source, sequence, status, version, call/arrival timestamps.
- `waiting_active_memberships`: store id와 consumer id 복합 unique key 및 team id. 팀 생성과 같은 트랜잭션에서 점유하고 종결 전이와 같은 트랜잭션에서 제거한다.
- `waiting_queue_sequences`: store id와 business date 복합 PK, next sequence.
- `waiting_transition_audits`: team id, actor type/id, from/to status, reason, occurred at, idempotency key.
- `waiting_closure_jobs`: store id, settings version, status, target/completed/failed/reconciliation counts, version.
- `waiting_closure_job_items`: job/team unique pair와 item 처리 상태.
- `waiting_status_events`: team id, event sequence, public status, occurred at, publication state.

중앙 제약은 같은 소비자·매장의 활성 팀 1건, 매장·영업일·sequence 유일성, transition idempotency, closure job/team 중복 방지를 담당한다. MySQL에서 부분 unique index에 의존하지 않고 `waiting_active_memberships` 별도 관계 테이블의 `(store_id, consumer_account_id)` unique key로 활성 중복을 차단한다.

## 권한과 데이터 흐름

#272의 중앙 생성 command는 이미 검증된 store id, consumer id, business date, party size, source를 받아 중복 관계와 sequence를 원자적으로 확정한다. 활성 계정, 공통 3km 위치, 현재 접수 가능 설정 검증은 일반 사용자 HTTP API 소유 service가 #271 설정 runtime과 함께 조정한다. #272에서는 이 선행 검증을 통과했다고 추측하는 공개 HTTP Controller나 production dummy adapter를 만들지 않는다.

매장 운영자 명령은 현재 계정, 대표 운영자 소유권, APPROVED 검증 상태, 허용 operation status를 Store 공개 계약으로 확인한다. Waiting은 Store Entity나 Repository를 참조하지 않는다. 조회 권한과 변경 권한은 #284 설정 계약과 같은 enumeration·오류 경계를 사용한다.

서비스 처리 순서는 권한 재검증 → 멱등 command claim → 필요한 queue/team/job 행 잠금 → expectedVersion과 상태 전이 검증 → aggregate 변경 → audit/event 저장 → 멱등 결과 저장이다. 재전송은 권한을 다시 검증한 뒤 최초 HTTP 의미와 payload를 재생한다.

## 오류 처리

기존 `COMMON_001`~`COMMON_004`, `COMMON_007`, `COMMON_008`, `COMMON_010`, `AUTH_001`, `AUTH_011`, `STORE_001`, `STORE_003`, `STORE_005`, `STORE_007`을 재사용한다.

Waiting 전용 오류는 기능 명세와 OpenAPI에서 다음 의미로 고정한다.

- `WAITING_003`: 대상 매장 범위의 웨이팅 팀을 찾을 수 없음.
- `WAITING_004`: 대상 매장 범위의 웨이팅 종결 작업을 찾을 수 없음.
- `WAITING_005`: expectedVersion 불일치.
- `WAITING_006`: 현재 상태에서 허용되지 않는 전이.
- `WAITING_007`: FIFO 선두가 아닌 팀 호출.
- `WAITING_008`: 활성 membership의 현재 상태와 요청 전제가 충돌함.
- `WAITING_009`: 종결 작업이 아직 완료되지 않았거나 대사가 필요함.
- `WAITING_010`: 종결 작업 대상 처리 중 실패가 발생함.

새 오류 코드는 구현 전에 canonical Waiting spec과 OpenAPI에 먼저 기록하고 계약 테스트를 RED로 만든다.

## 비동기 종결과 복구

closure job 생성 트랜잭션은 대상 활성 팀을 snapshot으로 item에 고정하고 즉시 반환한다. worker는 item을 작은 batch로 선점해 팀별 조건부 전이와 audit/event를 각각 커밋한다. 이미 종결된 팀은 성공적으로 건너뛰고, 일시 실패는 재시도하며, 영구 실패나 대상 누락은 `reconciliationRequiredCount`에 집계한다.

job은 `PENDING`, `RUNNING`, `COMPLETED`, `COMPLETED_WITH_RECONCILIATION` 상태를 사용한다. 재실행은 새 팀을 대상으로 추가하지 않고 최초 snapshot만 처리한다. 별도 대사 명령은 실패·누락 item만 다시 평가한다.

## 테스트 전략

- Entity 단위 테스트: 모든 허용·거부 전이, version 증가, 10분 경계, 종결 상태 불변성.
- Service 단위 테스트: 권한 검증 순서, 멱등 fingerprint, FIFO 선두, privacy-safe not-found, closure 집계.
- Controller MockMvc: 매장 운영자 principal, header/body 검증, HTTP/error code, 타 매장 접근, 민감 필드 부재.
- Migration 테스트: V36 테이블·FK·unique/index·check constraint.
- MySQL 통합 테스트: 병렬 sequence 할당, 중복 생성, 동일 version 명령 경합, 호출/취소 경합, closure 재시도와 부분 실패 대사.
- OpenAPI 계약 테스트: 정확한 path·verb·response set, schema enum/range, audience aggregate, 향후 기능 필드·경로 부재.
- 전체 gate: `test`, `integrationTest`, `build`, OpenAPI lint/bundle, `git diff --check`.

## 구현 순서와 완료 조건

1. Canonical Waiting spec·OpenAPI와 Issue #272 allowlist를 확정한다.
2. V36 migration과 persistence model을 TDD로 구현한다.
3. 상태기계·FIFO·권한 port·원장 service를 TDD로 구현한다.
4. 운영자 조회·명령 HTTP를 계약대로 구현한다.
5. closure job runtime과 공개 Service 계약을 구현한다.
6. 중앙 생성 command의 MySQL 중복·순번 경합을 검증하되 일반 사용자 HTTP adapter는 만들지 않는다.
7. MySQL 동시성·전체 회귀·OpenAPI 검증을 통과한다.

완료 시 #272는 #271 설정 runtime이 활성 팀 영향 조회와 일괄 종결 job을 실제로 호출할 수 있는 공개 Service 계약/runtime을 제공해야 한다. #272 단독으로 #271 설정 PUT의 `202` HTTP 응답이나 프론트/SSE를 완료했다고 주장하지 않는다.

## 2026-08-13 리뷰 보완 결정

### Closure lease 소비

worker poll 하나는 최대 100개 항목을 처리할 수 있지만, lease는 처리할 항목 1개에만 직전에 부여한다. runner는 `1건 claim → 즉시 process/failure 기록`을 최대 100회 반복하고 claim 결과가 없으면 현재 poll을 종료한다. 처리 대기 중인 뒤쪽 항목에 미리 같은 만료 시각을 부여하지 않으므로 앞 항목 처리 시간이 30초를 넘겨도 아직 시작하지 않은 항목의 attempt와 reconciliation 상태에 영향을 주지 않는다.

lease duration은 30초를 유지한다. 장기 단일 항목 처리의 heartbeat 갱신은 이번 보완 범위에 추가하지 않으며, 소유권은 기존 owner·token·`leaseUntil > now` fencing을 그대로 사용한다.

### WAIT-011 호출 직렬화

호출 command는 대상 팀 잠금 뒤 같은 매장·영업일의 `waiting_queue_sequences` 행을 비관적으로 잠근다. 이 행이 해당 호출 흐름의 직렬화 mutex다. 잠금 안에서 미종결 `CALLED` 팀 존재 여부와 현재 `WAITING` FIFO 선두를 확인하고, 기존 `CALLED`가 있으면 다음 팀을 `WAITING_NOT_FIFO_HEAD`로 거부한다.

새 공개 상태나 오류 코드를 만들지 않는다. 도착·입장·취소·미응답 처리로 기존 `CALLED` 흐름이 끝난 뒤에만 다음 호출이 가능하다. 별도 advisory lock이나 새로운 migration/guard table은 도입하지 않는다.

### 보완 검증

- Runner 단위 회귀: claim limit이 항상 1이고 각 claim 직후 처리한 뒤 다음 claim으로 이동하며 poll당 최대 100건을 넘지 않는다.
- MySQL closure 회귀: 첫 항목 처리 시각이 lease duration을 넘어도 두 번째 항목은 처리 직전 새 lease를 받아 정상 처리된다.
- Service 단위 회귀: queue sequence 잠금 뒤 기존 `CALLED` 흐름이 있으면 다음 `WAITING` 팀 호출을 거부한다.
- MySQL 동시성 회귀: 같은 매장·영업일의 두 `WAITING` 팀을 동시에 호출해 정확히 한 팀만 `CALLED`가 되고 다른 팀은 `WAITING`으로 남는다.
